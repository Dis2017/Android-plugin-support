package com.ximsfei.dynamic.pm;

import static com.ximsfei.dynamic.DynamicApkManager.INSTALL_SUCCEEDED;
import static com.ximsfei.dynamic.DynamicApkManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static com.ximsfei.dynamic.DynamicApkManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
import static com.ximsfei.dynamic.DynamicApkManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
import static com.ximsfei.dynamic.DynamicApkManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;

import com.ximsfei.dynamic.DynamicApkManager;
import com.ximsfei.dynamic.app.DynamicLoadedApk;
import com.ximsfei.dynamic.util.DynamicConstants;
import com.ximsfei.dynamic.reflect.Hooks;
import com.ximsfei.dynamic.util.ArrayUtils;
import com.ximsfei.dynamic.util.FileUtils;
import com.ximsfei.dynamic.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Set;

import dalvik.system.DexClassLoader;

/**
 * Created by pengfenx on 2/26/2016.
 */
public class DynamicApkParser {
    /** File name in an APK for the Android manifest. */
    private static final String ANDROID_MANIFEST_FILENAME = "AndroidManifest.xml";

    public static class NewPermissionInfo {
        public final String name;
        public final int sdkVersion;
        public final int fileVersion;

        public NewPermissionInfo(String name, int sdkVersion, int fileVersion) {
            this.name = name;
            this.sdkVersion = sdkVersion;
            this.fileVersion = fileVersion;
        }
    }

    /**
     * List of new permissions that have been added since 1.0.
     * NOTE: These must be declared in SDK version order, with permissions
     * added to older SDKs appearing before those added to newer SDKs.
     * If sdkVersion is 0, then this is not a permission that we want to
     * automatically add to older apps, but we do want to allow it to be
     * granted during a platform update.
     */
    public static final DynamicApkParser.NewPermissionInfo NEW_PERMISSIONS[] =
            new DynamicApkParser.NewPermissionInfo[] {
                    new DynamicApkParser.NewPermissionInfo(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.os.Build.VERSION_CODES.DONUT, 0),
                    new DynamicApkParser.NewPermissionInfo(android.Manifest.permission.READ_PHONE_STATE,
                            android.os.Build.VERSION_CODES.DONUT, 0)
            };

    /**
     * @deprecated callers should move to explicitly passing around source path.
     */
    @Deprecated
    private String mArchiveSourcePath;

    private int mParseError = INSTALL_SUCCEEDED;

    static class ParsePackageItemArgs {
        final DynamicApkInfo owner;
        final String[] outError;
        final int nameRes;
        final int labelRes;
        final int iconRes;
        final int logoRes;
        final int bannerRes;

        String tag;
        TypedArray sa;

        ParsePackageItemArgs(DynamicApkInfo _owner, String[] _outError,
                             int _nameRes, int _labelRes, int _iconRes, int _logoRes, int _bannerRes) {
            owner = _owner;
            outError = _outError;
            nameRes = _nameRes;
            labelRes = _labelRes;
            iconRes = _iconRes;
            logoRes = _logoRes;
            bannerRes = _bannerRes;
        }
    }

    static class ParseComponentArgs extends ParsePackageItemArgs {
        final String[] sepProcesses;
        final int processRes;
        final int descriptionRes;
        final int enabledRes;

        ParseComponentArgs(DynamicApkInfo _owner, String[] _outError,
                           int _nameRes, int _labelRes, int _iconRes, int _logoRes, int _bannerRes,
                           String[] _sepProcesses, int _processRes,
                           int _descriptionRes, int _enabledRes) {
            super(_owner, _outError, _nameRes, _labelRes, _iconRes, _logoRes, _bannerRes);
            sepProcesses = _sepProcesses;
            processRes = _processRes;
            descriptionRes = _descriptionRes;
            enabledRes = _enabledRes;
        }
    }

    private ParsePackageItemArgs mParseInstrumentationArgs;
    private ParseComponentArgs mParseActivityArgs;
    private ParseComponentArgs mParseActivityAliasArgs;
    private ParseComponentArgs mParseServiceArgs;
    private ParseComponentArgs mParseProviderArgs;

    /** If set to true, we will only allow package files that exactly match
     *  the DTD.  Otherwise, we try to get as much from the package as we
     *  can without failing.  This should normally be set to false, to
     *  support extensions to the DTD in future versions. */
    private static final boolean RIGID_PARSER = false;

    private static final String TAG = "DynamicApkParser";

    public static final boolean isApkFile(File file) {
        return isApkPath(file.getName());
    }

    private static boolean isApkPath(String path) {
        return path.endsWith(".apk");
    }

    /**
     * Parse the given APK file, treating it as as a single monolithic package.
     * <p>
     * @deprecated external callers should move to
     *             {@link #parsePackage(DynamicApkManager, Context, File)}.
     *             Eventually this method will be marked private.
     */
    public void parsePackage(DynamicApkManager dpkg, Context context, File apkFile) {
        if (isApkFile(apkFile)) {
            parseBasePackage(dpkg, context, apkFile);
        } else if (apkFile.isDirectory()) {
            File[] apkFiles = apkFile.listFiles();
            if (apkFiles != null && apkFiles.length > 0) {
                for (File file : apkFiles) {
                    parsePackage(dpkg, context, file);
                }
            }
        }
    }

    private void parseBasePackage(DynamicApkManager dpkg, Context context, File apkFile) {
        try {
            dpkg.addPackage(parseBaseApk(context, apkFile));
        } catch (DynamicApkParserException e) {
            e.printStackTrace();
        }
    }

    private DynamicApkInfo parseBaseApk(Context context, File apkFile)
            throws DynamicApkParserException {
        final String apkPath = apkFile.getAbsolutePath();

        mParseError = INSTALL_SUCCEEDED;
        mArchiveSourcePath = apkFile.getAbsolutePath();

        Log.d(TAG, "Scanning base APK: " + apkPath);

        Resources res;
        XmlResourceParser parser = null;
        try {
            AssetManager assets = AssetManager.class.newInstance();
            Hooks.addAssetPath(assets, apkFile.getAbsolutePath());
            res = new Resources(assets, context.getResources().getDisplayMetrics(),
                    context.getResources().getConfiguration());

            parser = assets.openXmlResourceParser(ANDROID_MANIFEST_FILENAME);

            final String[] outError = new String[1];
            final DynamicApkInfo pkg = parseBaseApk(res, parser, outError);
            if (pkg == null) {
                throw new DynamicApkParserException(mParseError,
                        apkPath + " (at " + parser.getPositionDescription() + "): " + outError[0]);
            }

            pkg.baseCodePath = apkPath;
            String optimizedDexDir = context.getDir(
                    pkg.packageName + DynamicConstants.PRIVATE_OPTIMIZED_DEX_DIR,
                    Context.MODE_PRIVATE).getAbsolutePath();
            String optimizedLibDir = context.getDir(
                    pkg.packageName + DynamicConstants.PRIVATE_OPTIMIZED_LIB_DIR,
                    Context.MODE_PRIVATE).getAbsolutePath();
            DexClassLoader classLoader = new DexClassLoader(apkFile.getAbsolutePath(),
                    optimizedDexDir, optimizedLibDir, ClassLoader.getSystemClassLoader().getParent());
            pkg.classLoader = classLoader;
            pkg.assets = assets;
            pkg.resources = res;
            pkg.application = DynamicLoadedApk.makeDynamicApplication(context, classLoader, pkg);
            return pkg;
        } catch (DynamicApkParserException e) {
            throw e;
        } catch (Exception e) {
            throw new DynamicApkParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to read manifest from " + apkPath, e);
        } finally {
            closeQuietly(parser);
        }
    }

    private static String validateName(String name, boolean requireSeparator,
                                       boolean requireFilename) {
        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i=0; i<N; i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                front = false;
                continue;
            }
            if (!front) {
                if ((c >= '0' && c <= '9') || c == '_') {
                    continue;
                }
            }
            if (c == '.') {
                hasSep = true;
                front = true;
                continue;
            }
            return "bad character '" + c + "'";
        }
        if (requireFilename && !FileUtils.isValidExtFilename(name)) {
            return "Invalid filename";
        }
        return hasSep || !requireSeparator
                ? null : "must have at least one '.' separator";
    }

    private static String parsePackageNames(XmlPullParser parser, AttributeSet attrs)
            throws IOException, XmlPullParserException, DynamicApkParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }

        if (type != XmlPullParser.START_TAG) {
            throw new DynamicApkParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No start tag found");
        }
        if (!parser.getName().equals("manifest")) {
            throw new DynamicApkParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No <manifest> tag");
        }

        final String packageName = attrs.getAttributeValue(null, "package");
        if (!"android".equals(packageName)) {
            final String error = validateName(packageName, true, true);
            if (error != null) {
                throw new DynamicApkParserException(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                        "Invalid manifest package: " + error);
            }
        }

        return packageName.intern();
    }

    /**
     * Parse the manifest of a <em>base APK</em>.
     * <p>
     * When adding new features, carefully consider if they should also be
     * supported by split APKs.
     */
    private DynamicApkInfo parseBaseApk(Resources res, XmlResourceParser parser,
                                 String[] outError) throws XmlPullParserException, IOException {
        AttributeSet attrs = parser;

        mParseInstrumentationArgs = null;
        mParseActivityArgs = null;
        mParseServiceArgs = null;
        mParseProviderArgs = null;

        final String pkgName;
        try {
            String packageName = parsePackageNames(parser, attrs);
            pkgName = packageName;
        } catch (DynamicApkParserException e) {
            mParseError = INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
            return null;
        }

        int type;

        final DynamicApkInfo pkg = new DynamicApkInfo(pkgName);
        boolean foundApp = false;

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifest"));
        pkg.coreApp = attrs.getAttributeBooleanValue(null, "coreApp", false);
        sa.recycle();

        // Resource boolean are -1, so 1 means we don't know the value.
        int supportsSmallScreens = 1;
        int supportsNormalScreens = 1;
        int supportsLargeScreens = 1;
        int supportsXLargeScreens = 1;
        int resizeable = 1;
        int anyDensity = 1;

        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("application")) {
                if (foundApp) {
                    if (RIGID_PARSER) {
                        outError[0] = "<manifest> has more than one <application>";
                        mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        return null;
                    } else {
                        Log.w(TAG, "<manifest> has more than one <application>");
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                }

                foundApp = true;
                if (!parseBaseApplication(pkg, res, parser, attrs, outError)) {
                    return null;
                }
            } else if (tagName.equals("overlay")) {
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestResourceOverlay"));
                pkg.mOverlayTarget = sa.getString(
                        Hooks.getStyleable("AndroidManifestResourceOverlay_targetPackage"));
                pkg.mOverlayPriority = sa.getInt(
                        Hooks.getStyleable("AndroidManifestResourceOverlay_priority"),
                        -1);
                sa.recycle();

                if (pkg.mOverlayTarget == null) {
                    outError[0] = "<overlay> does not specify a target package";
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return null;
                }
                if (pkg.mOverlayPriority < 0 || pkg.mOverlayPriority > 9999) {
                    outError[0] = "<overlay> priority must be between 0 and 9999";
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return null;
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("key-sets")) {
                if (!parseKeySets(pkg, res, parser, attrs, outError)) {
                    return null;
                }
            } else if (tagName.equals("permission-group")) {
                if (parsePermissionGroup(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }
            } else if (tagName.equals("permission")) {
                if (parsePermission(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }
            } else if (tagName.equals("permission-tree")) {
                if (parsePermissionTree(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }
            } else if (tagName.equals("uses-permission")) {
                if (!parseUsesPermission(pkg, res, parser, attrs)) {
                    return null;
                }
            } else if (tagName.equals("uses-permission-sdk-m")
                    || tagName.equals("uses-permission-sdk-23")) {
                if (!parseUsesPermission(pkg, res, parser, attrs)) {
                    return null;
                }
            } else if (tagName.equals("uses-configuration")) {
                ConfigurationInfo cPref = new ConfigurationInfo();
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestUsesConfiguration"));
                cPref.reqTouchScreen = sa.getInt(
                        Hooks.getStyleable("AndroidManifestUsesConfiguration_reqTouchScreen"),
                        Configuration.TOUCHSCREEN_UNDEFINED);
                cPref.reqKeyboardType = sa.getInt(
                        Hooks.getStyleable("AndroidManifestUsesConfiguration_reqKeyboardType"),
                        Configuration.KEYBOARD_UNDEFINED);
                if (sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestUsesConfiguration_reqHardKeyboard"),
                        false)) {
                    cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
                }
                cPref.reqNavigation = sa.getInt(
                        Hooks.getStyleable("AndroidManifestUsesConfiguration_reqNavigation"),
                        Configuration.NAVIGATION_UNDEFINED);
                if (sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestUsesConfiguration_reqFiveWayNav"),
                        false)) {
                    cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
                }
                sa.recycle();
                pkg.configPreferences = ArrayUtils.add(pkg.configPreferences, cPref);

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-feature")) {
                FeatureInfo fi = parseUsesFeature(res, attrs);
                pkg.reqFeatures = ArrayUtils.add(pkg.reqFeatures, fi);

                if (fi.name == null) {
                    ConfigurationInfo cPref = new ConfigurationInfo();
                    cPref.reqGlEsVersion = fi.reqGlEsVersion;
                    pkg.configPreferences = ArrayUtils.add(pkg.configPreferences, cPref);
                }

                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("uses-sdk")) {
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("supports-screens")) {
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestSupportsScreens"));

                pkg.applicationInfo.requiresSmallestWidthDp = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_requiresSmallestWidthDp"),
                        0);
                pkg.applicationInfo.compatibleWidthLimitDp = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_compatibleWidthLimitDp"),
                        0);
                pkg.applicationInfo.largestWidthLimitDp = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_largestWidthLimitDp"),
                        0);

                // This is a trick to get a boolean and still able to detect
                // if a value was actually set.
                supportsSmallScreens = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_smallScreens"),
                        supportsSmallScreens);
                supportsNormalScreens = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_normalScreens"),
                        supportsNormalScreens);
                supportsLargeScreens = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_largeScreens"),
                        supportsLargeScreens);
                supportsXLargeScreens = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_xlargeScreens"),
                        supportsXLargeScreens);
                resizeable = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_resizeable"),
                        resizeable);
                anyDensity = sa.getInteger(
                        Hooks.getStyleable("AndroidManifestSupportsScreens_anyDensity"),
                        anyDensity);

                sa.recycle();

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("protected-broadcast")) {
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestProtectedBroadcast"));

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String name = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestProtectedBroadcast_name"));

                sa.recycle();

                if (name != null) {
                    if (pkg.protectedBroadcasts == null) {
                        pkg.protectedBroadcasts = new ArrayList<String>();
                    }
                    if (!pkg.protectedBroadcasts.contains(name)) {
                        pkg.protectedBroadcasts.add(name.intern());
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("instrumentation")) {
                if (parseInstrumentation(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }

            } else if (tagName.equals("original-package")) {
                XmlUtils.skipCurrentTag(parser);
                continue;
            } else if (tagName.equals("adopt-permissions")) {
                XmlUtils.skipCurrentTag(parser);
                continue;
            } else if (tagName.equals("uses-gl-texture")) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;

            } else if (tagName.equals("compatible-screens")) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;
            } else if (tagName.equals("supports-input")) {
                XmlUtils.skipCurrentTag(parser);
                continue;

            } else if (tagName.equals("eat-comment")) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;

            } else if (RIGID_PARSER) {
                outError[0] = "Bad element under <manifest>: "
                        + parser.getName();
                mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return null;

            } else {
                Log.w(TAG, "Unknown element under <manifest>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }

        if (!foundApp && pkg.instrumentation.size() == 0) {
            outError[0] = "<manifest> does not contain an <application> or <instrumentation>";
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_EMPTY;
        }

        final int NP = DynamicApkParser.NEW_PERMISSIONS.length;
        StringBuilder implicitPerms = null;
        for (int ip=0; ip<NP; ip++) {
            final DynamicApkParser.NewPermissionInfo npi
                    = DynamicApkParser.NEW_PERMISSIONS[ip];
            if (pkg.applicationInfo.targetSdkVersion >= npi.sdkVersion) {
                break;
            }
            if (!pkg.requestedPermissions.contains(npi.name)) {
                if (implicitPerms == null) {
                    implicitPerms = new StringBuilder(128);
                    implicitPerms.append(pkg.packageName);
                    implicitPerms.append(": compat added ");
                } else {
                    implicitPerms.append(' ');
                }
                implicitPerms.append(npi.name);
                pkg.requestedPermissions.add(npi.name);
            }
        }
        if (implicitPerms != null) {
            Log.i(TAG, implicitPerms.toString());
        }

        if (supportsSmallScreens < 0 || (supportsSmallScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
        }
        if (supportsNormalScreens != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
        }
        if (supportsLargeScreens < 0 || (supportsLargeScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
        }
        if (supportsXLargeScreens < 0 || (supportsXLargeScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                >= android.os.Build.VERSION_CODES.GINGERBREAD)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;
        }
        if (resizeable < 0 || (resizeable > 0
                && pkg.applicationInfo.targetSdkVersion
                >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
        }
        if (anyDensity < 0 || (anyDensity > 0
                && pkg.applicationInfo.targetSdkVersion
                >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
        }

        return pkg;
    }

    private FeatureInfo parseUsesFeature(Resources res, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        FeatureInfo fi = new FeatureInfo();
        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestUsesFeature"));
        // Note: don't allow this value to be a reference to a resource
        // that may change.
        fi.name = sa.getNonResourceString(
                Hooks.getStyleable("AndroidManifestUsesFeature_name"));
        if (fi.name == null) {
            fi.reqGlEsVersion = sa.getInt(
                    Hooks.getStyleable("AndroidManifestUsesFeature_glEsVersion"),
                    FeatureInfo.GL_ES_VERSION_UNDEFINED);
        }
        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestUsesFeature_required"), true)) {
            fi.flags |= FeatureInfo.FLAG_REQUIRED;
        }
        sa.recycle();
        return fi;
    }

    private boolean parseUsesPermission(DynamicApkInfo pkg, Resources res, XmlResourceParser parser,
                                        AttributeSet attrs) throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestUsesPermission"));

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        String name = sa.getNonResourceString(
                Hooks.getStyleable("AndroidManifestUsesPermission_name"));

        int maxSdkVersion = 0;
        TypedValue val = sa.peekValue(
                Hooks.getStyleable("AndroidManifestUsesPermission_maxSdkVersion"));
        if (val != null) {
            if (val.type >= TypedValue.TYPE_FIRST_INT && val.type <= TypedValue.TYPE_LAST_INT) {
                maxSdkVersion = val.data;
            }
        }

        sa.recycle();

        if ((maxSdkVersion == 0) || (maxSdkVersion >= Build.VERSION.SDK_INT)) {
            if (name != null) {
                int index = pkg.requestedPermissions.indexOf(name);
                if (index == -1) {
                    pkg.requestedPermissions.add(name.intern());
                } else {
                    Log.w(TAG, "Ignoring duplicate uses-permissions/uses-permissions-sdk-m: "
                            + name + " in package: " + pkg.packageName + " at: "
                            + parser.getPositionDescription());
                }
            }
        }

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static String buildClassName(String pkg, CharSequence clsSeq,
                                         String[] outError) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            outError[0] = "Empty class name in package " + pkg;
            return null;
        }
        String cls = clsSeq.toString();
        char c = cls.charAt(0);
        if (c == '.') {
            return (pkg + cls).intern();
        }
        if (cls.indexOf('.') < 0) {
            StringBuilder b = new StringBuilder(pkg);
            b.append('.');
            b.append(cls);
            return b.toString().intern();
        }
        if (c >= 'a' && c <= 'z') {
            return cls.intern();
        }
        outError[0] = "Bad class name " + cls + " in package " + pkg;
        return null;
    }

    private static String buildCompoundName(String pkg,
                                            CharSequence procSeq, String type, String[] outError) {
        String proc = procSeq.toString();
        char c = proc.charAt(0);
        if (pkg != null && c == ':') {
            if (proc.length() < 2) {
                outError[0] = "Bad " + type + " name " + proc + " in package " + pkg
                        + ": must be at least two characters";
                return null;
            }
            String subName = proc.substring(1);
            String nameError = validateName(subName, false, false);
            if (nameError != null) {
                outError[0] = "Invalid " + type + " name " + proc + " in package "
                        + pkg + ": " + nameError;
                return null;
            }
            return (pkg + proc).intern();
        }
        String nameError = validateName(proc, true, false);
        if (nameError != null && !"system".equals(proc)) {
            outError[0] = "Invalid " + type + " name " + proc + " in package "
                    + pkg + ": " + nameError;
            return null;
        }
        return proc.intern();
    }

    private static String buildProcessName(String pkg, String defProc,
                                           CharSequence procSeq, String[] separateProcesses,
                                           String[] outError) {
        if (separateProcesses != null) {
            for (int i=separateProcesses.length-1; i>=0; i--) {
                String sp = separateProcesses[i];
                if (sp.equals(pkg) || sp.equals(defProc) || sp.equals(procSeq)) {
                    return pkg;
                }
            }
        }
        if (procSeq == null || procSeq.length() <= 0) {
            return defProc;
        }
        return buildCompoundName(pkg, procSeq, "process", outError);
    }

    private static String buildTaskAffinityName(String pkg, String defProc,
                                                CharSequence procSeq, String[] outError) {
        if (procSeq == null) {
            return defProc;
        }
        if (procSeq.length() <= 0) {
            return null;
        }
        return buildCompoundName(pkg, procSeq, "taskAffinity", outError);
    }

    private boolean parseKeySets(DynamicApkInfo owner, Resources res,
                                 XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        // we've encountered the 'key-sets' tag
        // all the keys and keysets that we want must be defined here
        // so we're going to iterate over the parser and pull out the things we want
        int outerDepth = parser.getDepth();
        int currentKeySetDepth = -1;
        int type;
        String currentKeySet = null;
        ArrayMap<String, PublicKey> publicKeys = new ArrayMap<String, PublicKey>();
        ArraySet<String> upgradeKeySets = new ArraySet<String>();
        ArrayMap<String, ArraySet<String>> definedKeySets = new ArrayMap<String, ArraySet<String>>();
        ArraySet<String> improperKeySets = new ArraySet<String>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG) {
                if (parser.getDepth() == currentKeySetDepth) {
                    currentKeySet = null;
                    currentKeySetDepth = -1;
                }
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals("key-set")) {
                if (currentKeySet != null) {
                    outError[0] = "Improperly nested 'key-set' tag at "
                            + parser.getPositionDescription();
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
                final TypedArray sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestKeySet"));
                final String keysetName = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestKeySet_name"));
                definedKeySets.put(keysetName, new ArraySet<String>());
                currentKeySet = keysetName;
                currentKeySetDepth = parser.getDepth();
                sa.recycle();
            } else if (tagName.equals("public-key")) {
                if (currentKeySet == null) {
                    outError[0] = "Improperly nested 'key-set' tag at "
                            + parser.getPositionDescription();
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
                final TypedArray sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestPublicKey"));
                final String publicKeyName = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestPublicKey_name"));
                final String encodedKey = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestPublicKey_value"));
                if (encodedKey == null && publicKeys.get(publicKeyName) == null) {
                    outError[0] = "'public-key' " + publicKeyName + " must define a public-key value"
                            + " on first use at " + parser.getPositionDescription();
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    sa.recycle();
                    return false;
                } else if (encodedKey != null) {
                    PublicKey currentKey = parsePublicKey(encodedKey);
                    if (currentKey == null) {
                        Log.w(TAG, "No recognized valid key in 'public-key' tag at "
                                + parser.getPositionDescription() + " key-set " + currentKeySet
                                + " will not be added to the package's defined key-sets.");
                        sa.recycle();
                        improperKeySets.add(currentKeySet);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    if (publicKeys.get(publicKeyName) == null
                            || publicKeys.get(publicKeyName).equals(currentKey)) {

                        /* public-key first definition, or matches old definition */
                        publicKeys.put(publicKeyName, currentKey);
                    } else {
                        outError[0] = "Value of 'public-key' " + publicKeyName
                                + " conflicts with previously defined value at "
                                + parser.getPositionDescription();
                        mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        sa.recycle();
                        return false;
                    }
                }
                definedKeySets.get(currentKeySet).add(publicKeyName);
                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("upgrade-key-set")) {
                final TypedArray sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestUpgradeKeySet"));
                String name = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestUpgradeKeySet_name"));
                upgradeKeySets.add(name);
                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (RIGID_PARSER) {
                outError[0] = "Bad element under <key-sets>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription();
                mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return false;
            } else {
                Log.w(TAG, "Unknown element under <key-sets>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }
        Set<String> publicKeyNames = publicKeys.keySet();
        if (publicKeyNames.removeAll(definedKeySets.keySet())) {
            outError[0] = "PackageInfo" + owner.packageName + " AndroidManifext.xml "
                    + "'key-set' and 'public-key' names must be distinct.";
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }
        owner.mKeySetMapping = new ArrayMap<String, ArraySet<PublicKey>>();
        for (ArrayMap.Entry<String, ArraySet<String>> e: definedKeySets.entrySet()) {
            final String keySetName = e.getKey();
            if (e.getValue().size() == 0) {
                Log.w(TAG, "PackageInfo" + owner.packageName + " AndroidManifext.xml "
                        + "'key-set' " + keySetName + " has no valid associated 'public-key'."
                        + " Not including in package's defined key-sets.");
                continue;
            } else if (improperKeySets.contains(keySetName)) {
                Log.w(TAG, "PackageInfo" + owner.packageName + " AndroidManifext.xml "
                        + "'key-set' " + keySetName + " contained improper 'public-key'"
                        + " tags. Not including in package's defined key-sets.");
                continue;
            }
            owner.mKeySetMapping.put(keySetName, new ArraySet<PublicKey>());
            for (String s : e.getValue()) {
                owner.mKeySetMapping.get(keySetName).add(publicKeys.get(s));
            }
        }
        if (owner.mKeySetMapping.keySet().containsAll(upgradeKeySets)) {
            owner.mUpgradeKeySets = upgradeKeySets;
        } else {
            outError[0] ="PackageInfo" + owner.packageName + " AndroidManifext.xml "
                    + "does not define all 'upgrade-key-set's .";
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }
        return true;
    }

    private PermissionGroup parsePermissionGroup(DynamicApkInfo owner, Resources res,
                                                 XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        PermissionGroup perm = new PermissionGroup(owner);

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestPermissionGroup"));

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission-group>", sa,
                Hooks.getStyleable("AndroidManifestPermissionGroup_name"),
                Hooks.getStyleable("AndroidManifestPermissionGroup_label"),
                Hooks.getStyleable("AndroidManifestPermissionGroup_icon"),
                Hooks.getStyleable("AndroidManifestPermissionGroup_logo"),
                Hooks.getStyleable("AndroidManifestPermissionGroup_banner"))) {
            sa.recycle();
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        perm.info.descriptionRes = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestPermissionGroup_description"),
                0);
        perm.info.flags = sa.getInt(
                Hooks.getStyleable("AndroidManifestPermissionGroup_permissionGroupFlags"), 0);
        perm.info.priority = sa.getInt(
                Hooks.getStyleable("AndroidManifestPermissionGroup_priority"), 0);

        sa.recycle();

        if (!parseAllMetaData(res, parser, attrs, "<permission-group>", perm,
                outError)) {
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.permissionGroups.add(perm);

        return perm;
    }

    private Permission parsePermission(DynamicApkInfo owner, Resources res,
                                       XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        Permission perm = new Permission(owner);

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestPermission"));

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission>", sa,
                Hooks.getStyleable("AndroidManifestPermission_name"),
                Hooks.getStyleable("AndroidManifestPermission_label"),
                Hooks.getStyleable("AndroidManifestPermission_icon"),
                Hooks.getStyleable("AndroidManifestPermission_logo"),
                Hooks.getStyleable("AndroidManifestPermission_banner"))) {
            sa.recycle();
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        perm.info.group = sa.getNonResourceString(
                Hooks.getStyleable("AndroidManifestPermission_permissionGroup"));
        if (perm.info.group != null) {
            perm.info.group = perm.info.group.intern();
        }

        perm.info.descriptionRes = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestPermission_description"),
                0);

        perm.info.protectionLevel = sa.getInt(
                Hooks.getStyleable("AndroidManifestPermission_protectionLevel"),
                PermissionInfo.PROTECTION_NORMAL);

        perm.info.flags = sa.getInt(
                Hooks.getStyleable("AndroidManifestPermission_permissionFlags"), 0);

        sa.recycle();

        if (perm.info.protectionLevel == -1) {
            outError[0] = "<permission> does not specify protectionLevel";
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

//        perm.info.protectionLevel = PermissionInfo.fixProtectionLevel(perm.info.protectionLevel);

        if ((perm.info.protectionLevel&PermissionInfo.PROTECTION_MASK_FLAGS) != 0) {
            if ((perm.info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE) !=
                    PermissionInfo.PROTECTION_SIGNATURE) {
                outError[0] = "<permission>  protectionLevel specifies a flag but is "
                        + "not based on signature type";
                mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return null;
            }
        }

        if (!parseAllMetaData(res, parser, attrs, "<permission>", perm,
                outError)) {
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.permissions.add(perm);

        return perm;
    }

    private Permission parsePermissionTree(DynamicApkInfo owner, Resources res,
                                           XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        Permission perm = new Permission(owner);

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestPermissionTree"));

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission-tree>", sa,
                Hooks.getStyleable("AndroidManifestPermissionTree_name"),
                Hooks.getStyleable("AndroidManifestPermissionTree_label"),
                Hooks.getStyleable("AndroidManifestPermissionTree_icon"),
                Hooks.getStyleable("AndroidManifestPermissionTree_logo"),
                Hooks.getStyleable("AndroidManifestPermissionTree_banner"))) {
            sa.recycle();
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        sa.recycle();

        int index = perm.info.name.indexOf('.');
        if (index > 0) {
            index = perm.info.name.indexOf('.', index+1);
        }
        if (index < 0) {
            outError[0] = "<permission-tree> name has less than three segments: "
                    + perm.info.name;
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        perm.info.descriptionRes = 0;
        perm.info.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
        perm.tree = true;

        if (!parseAllMetaData(res, parser, attrs, "<permission-tree>", perm,
                outError)) {
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.permissions.add(perm);

        return perm;
    }

    private Instrumentation parseInstrumentation(DynamicApkInfo owner, Resources res,
                                                 XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestInstrumentation"));

        if (mParseInstrumentationArgs == null) {
            mParseInstrumentationArgs = new ParsePackageItemArgs(owner, outError,
                    Hooks.getStyleable("AndroidManifestInstrumentation_name"),
                    Hooks.getStyleable("AndroidManifestInstrumentation_label"),
                    Hooks.getStyleable("AndroidManifestInstrumentation_icon"),
                    Hooks.getStyleable("AndroidManifestInstrumentation_logo"),
                    Hooks.getStyleable("AndroidManifestInstrumentation_banner"));
            mParseInstrumentationArgs.tag = "<instrumentation>";
        }

        mParseInstrumentationArgs.sa = sa;

        Instrumentation a = new Instrumentation(mParseInstrumentationArgs,
                new InstrumentationInfo());
        if (outError[0] != null) {
            sa.recycle();
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        String str;
        // Note: don't allow this value to be a reference to a resource
        // that may change.
        str = sa.getNonResourceString(
                Hooks.getStyleable("AndroidManifestInstrumentation_targetPackage"));
        a.info.targetPackage = str != null ? str.intern() : null;

        a.info.handleProfiling = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestInstrumentation_handleProfiling"),
                false);

        a.info.functionalTest = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestInstrumentation_functionalTest"),
                false);

        sa.recycle();

        if (a.info.targetPackage == null) {
            outError[0] = "<instrumentation> does not specify targetPackage";
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        if (!parseAllMetaData(res, parser, attrs, "<instrumentation>", a,
                outError)) {
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.instrumentation.add(a);

        return a;
    }

    /**
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>base APK</em> manifest.
     * <p>
     * When adding new features, carefully consider if they should also be
     * supported by split APKs.
     */
    private boolean parseBaseApplication(DynamicApkInfo owner, Resources res,
                                         XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        final ApplicationInfo ai = owner.applicationInfo;
        final String pkgName = owner.applicationInfo.packageName;

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestApplication"));

        String name = sa.getString(
                Hooks.getStyleable("AndroidManifestApplication_name"));
        if (name != null) {
            ai.className = buildClassName(pkgName, name, outError);
            if (ai.className == null) {
                sa.recycle();
                mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return false;
            }
        }

        String manageSpaceActivity = sa.getString(
                Hooks.getStyleable("AndroidManifestApplication_manageSpaceActivity"));
        if (manageSpaceActivity != null) {
            ai.manageSpaceActivityName = buildClassName(pkgName, manageSpaceActivity,
                    outError);
        }

        boolean allowBackup = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_allowBackup"), true);
        if (allowBackup) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;

            //backupAgent, killAfterRestore, fullBackupContent and restoreAnyVersion are only
            //relevant if backup is possible for the given application.
            String backupAgent = sa.getString(
                    Hooks.getStyleable("AndroidManifestApplication_backupAgent"));
            if (backupAgent != null) {
                ai.backupAgentName = buildClassName(pkgName, backupAgent, outError);

                if (sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestApplication_killAfterRestore"),
                        true)) {
                    ai.flags |= ApplicationInfo.FLAG_KILL_AFTER_RESTORE;
                }
                if (sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestApplication_restoreAnyVersion"),
                        false)) {
                    ai.flags |= ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
                }
                if (sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestApplication_fullBackupOnly"),
                        false)) {
                    ai.flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;
                }
            }
        }

        TypedValue v = sa.peekValue(
                Hooks.getStyleable("AndroidManifestApplication_label"));
        if (v != null && (ai.labelRes=v.resourceId) == 0) {
            ai.nonLocalizedLabel = v.coerceToString();
        }

        ai.icon = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestApplication_icon"), 0);
        ai.logo = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestApplication_logo"), 0);
//        ai.banner = sa.getResourceId(
//                Hooks.getStyleable("AndroidManifestApplication_banner"), 0);
        ai.theme = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestApplication_theme"), 0);
        ai.descriptionRes = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestApplication_description"), 0);

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_requiredForAllUsers"),
                false)) {
            owner.mRequiredForAllUsers = true;
        }

//        String restrictedAccountType = sa.getString(R.styleable
//                .AndroidManifestApplication_restrictedAccountType);
//        if (restrictedAccountType != null && restrictedAccountType.length() > 0) {
//            owner.mRestrictedAccountType = restrictedAccountType;
//        }
//
//        String requiredAccountType = sa.getString(R.styleable
//                .AndroidManifestApplication_requiredAccountType);
//        if (requiredAccountType != null && requiredAccountType.length() > 0) {
//            owner.mRequiredAccountType = requiredAccountType;
//        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_debuggable"),
                false)) {
            ai.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_vmSafeMode"),
                false)) {
            ai.flags |= ApplicationInfo.FLAG_VM_SAFE_MODE;
        }

        owner.baseHardwareAccelerated = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_hardwareAccelerated"),
                owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.ICE_CREAM_SANDWICH);
        if (owner.baseHardwareAccelerated) {
            ai.flags |= ApplicationInfo.FLAG_HARDWARE_ACCELERATED;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_hasCode"),
                true)) {
            ai.flags |= ApplicationInfo.FLAG_HAS_CODE;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_allowTaskReparenting"),
                false)) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_allowClearUserData"),
                true)) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_testOnly"),
                false)) {
            ai.flags |= ApplicationInfo.FLAG_TEST_ONLY;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_largeHeap"),
                false)) {
            ai.flags |= ApplicationInfo.FLAG_LARGE_HEAP;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_usesCleartextTraffic"),
                true)) {
            ai.flags |= ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_supportsRtl"),
                false /* default is no RTL support*/)) {
            ai.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_multiArch"),
                false)) {
            ai.flags |= ApplicationInfo.FLAG_MULTIARCH;
        }

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestApplication_extractNativeLibs"),
                true)) {
            ai.flags |= ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS;
        }

        String str;
        str = sa.getString(
                Hooks.getStyleable("AndroidManifestApplication_permission"));
        ai.permission = (str != null && str.length() > 0) ? str.intern() : null;

        if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
            str = sa.getString(
                    Hooks.getStyleable("AndroidManifestApplication_taskAffinity"));
        } else {
            // Some older apps have been seen to use a resource reference
            // here that on older builds was ignored (with a warning).  We
            // need to continue to do this for them so they don't break.
            str = sa.getNonResourceString(
                    Hooks.getStyleable("AndroidManifestApplication_taskAffinity"));
        }
        ai.taskAffinity = buildTaskAffinityName(ai.packageName, ai.packageName,
                str, outError);

        if (outError[0] == null) {
            CharSequence pname;
            if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
                pname = sa.getString(
                        Hooks.getStyleable("AndroidManifestApplication_process"));
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestApplication_process"));
            }
            ai.processName = buildProcessName(ai.packageName, null, pname,
                    null, outError);

            ai.enabled = sa.getBoolean(
                    Hooks.getStyleable("AndroidManifestApplication_enabled"), true);

            if (sa.getBoolean(
                    Hooks.getStyleable("AndroidManifestApplication_isGame"), false)) {
                ai.flags |= ApplicationInfo.FLAG_IS_GAME;
            }

            if (false) {
                if (sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestApplication_cantSaveState"),
                        false)) {
                    // A heavy-weight application can not be in a custom process.
                    // We can do direct compare because we intern all strings.
                    if (ai.processName != null && ai.processName != ai.packageName) {
                        outError[0] = "cantSaveState applications can not use custom processes";
                    }
                }
            }
        }

        ai.uiOptions = sa.getInt(
                Hooks.getStyleable("AndroidManifestApplication_uiOptions"), 0);

        sa.recycle();

        if (outError[0] != null) {
            mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        final int innerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("activity")) {
                Activity a = parseActivity(owner, res, parser, attrs, outError, false,
                        owner.baseHardwareAccelerated);
                if (a == null) {
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.activities.add(a);

            } else if (tagName.equals("receiver")) {
                Activity a = parseActivity(owner, res, parser, attrs, outError, true, false);
                if (a == null) {
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.receivers.add(a);

            } else if (tagName.equals("service")) {
                Service s = parseService(owner, res, parser, attrs, outError);
                if (s == null) {
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.services.add(s);

            } else if (tagName.equals("provider")) {
                Provider p = parseProvider(owner, res, parser, attrs, outError);
                if (p == null) {
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.providers.add(p);

            } else if (tagName.equals("activity-alias")) {
                Activity a = parseActivityAlias(owner, res, parser, attrs, outError);
                if (a == null) {
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.activities.add(a);

            } else if (parser.getName().equals("meta-data")) {
                // note: application meta-data is stored off to the side, so it can
                // remain null in the primary copy (we like to avoid extra copies because
                // it can be large)
                if ((owner.mAppMetaData = parseMetaData(res, parser, attrs, owner.mAppMetaData,
                        outError)) == null) {
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

            } else if (tagName.equals("library")) {
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestLibrary"));

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String lname = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestLibrary_name"));

                sa.recycle();

                if (lname != null) {
                    lname = lname.intern();
                    if (!ArrayUtils.contains(owner.libraryNames, lname)) {
                        owner.libraryNames = ArrayUtils.add(owner.libraryNames, lname);
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-library")) {
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestUsesLibrary"));

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String lname = sa.getNonResourceString(
                        Hooks.getStyleable("AndroidManifestUsesLibrary_name"));
                boolean req = sa.getBoolean(
                        Hooks.getStyleable("AndroidManifestUsesLibrary_required"),
                        true);

                sa.recycle();

                if (lname != null) {
                    lname = lname.intern();
                    if (req) {
                        owner.usesLibraries = ArrayUtils.add(owner.usesLibraries, lname);
                    } else {
                        owner.usesOptionalLibraries = ArrayUtils.add(
                                owner.usesOptionalLibraries, lname);
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-package")) {
                // Dependencies for app installers; we don't currently try to
                // enforce this.
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!RIGID_PARSER) {
                    Log.w(TAG, "Unknown element under <application>: " + tagName
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <application>: " + tagName;
                    mParseError = INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
            }
        }

        modifySharedLibrariesForBackwardCompatibility(owner);
        return true;
    }

    private static void modifySharedLibrariesForBackwardCompatibility(DynamicApkInfo owner) {
        // "org.apache.http.legacy" is now a part of the boot classpath so it doesn't need
        // to be an explicit dependency.
        //
        // A future change will remove this library from the boot classpath, at which point
        // all apps that target SDK 21 and earlier will have it automatically added to their
        // dependency lists.
        owner.usesLibraries = ArrayUtils.remove(owner.usesLibraries, "org.apache.http.legacy");
        owner.usesOptionalLibraries = ArrayUtils.remove(owner.usesOptionalLibraries,
                "org.apache.http.legacy");
    }

    private boolean parsePackageItemInfo(DynamicApkInfo owner, PackageItemInfo outInfo,
                                         String[] outError, String tag, TypedArray sa,
                                         int nameRes, int labelRes, int iconRes, int logoRes, int bannerRes) {
        String name = sa.getString(nameRes);
        if (name == null) {
            outError[0] = tag + " does not specify android:name";
            return false;
        }

        outInfo.name
                = buildClassName(owner.applicationInfo.packageName, name, outError);
        if (outInfo.name == null) {
            return false;
        }

        int iconVal = sa.getResourceId(iconRes, 0);
        if (iconVal != 0) {
            outInfo.icon = iconVal;
            outInfo.nonLocalizedLabel = null;
        }

        int logoVal = sa.getResourceId(logoRes, 0);
        if (logoVal != 0) {
            outInfo.logo = logoVal;
        }

        int bannerVal = sa.getResourceId(bannerRes, 0);
        if (bannerVal != 0) {
            outInfo.banner = bannerVal;
        }

        TypedValue v = sa.peekValue(labelRes);
        if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        outInfo.packageName = owner.packageName;

        return true;
    }

    private Activity parseActivity(DynamicApkInfo owner, Resources res,
                                   XmlPullParser parser, AttributeSet attrs, String[] outError,
                                   boolean receiver, boolean hardwareAccelerated)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs, Hooks.getStyleableArray("AndroidManifestActivity"));

        if (mParseActivityArgs == null) {
            mParseActivityArgs = new ParseComponentArgs(owner, outError,
                    Hooks.getStyleable("AndroidManifestActivity_name"),
                    Hooks.getStyleable("AndroidManifestActivity_label"),
                    Hooks.getStyleable("AndroidManifestActivity_icon"),
                    Hooks.getStyleable("AndroidManifestActivity_logo"),
                    Hooks.getStyleable("AndroidManifestActivity_banner"),
                    null,
                    Hooks.getStyleable("AndroidManifestActivity_process"),
                    Hooks.getStyleable("AndroidManifestActivity_description"),
                    Hooks.getStyleable("AndroidManifestActivity_enabled"));
        }

        mParseActivityArgs.tag = receiver ? "<receiver>" : "<activity>";
        mParseActivityArgs.sa = sa;

        Activity a = new Activity(mParseActivityArgs, new ActivityInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean setExported = sa.hasValue(Hooks.getStyleable("AndroidManifestActivity_exported"));
        if (setExported) {
            a.info.exported = sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_exported"), false);
        }

        a.info.theme = sa.getResourceId(Hooks.getStyleable("AndroidManifestActivity_theme"), 0);

        a.info.uiOptions = sa.getInt(Hooks.getStyleable("AndroidManifestActivity_uiOptions"),
                a.info.applicationInfo.uiOptions);

        String parentName = sa.getString(
                Hooks.getStyleable("AndroidManifestActivity_parentActivityName"));
        if (parentName != null) {
            String parentClassName = buildClassName(a.info.packageName, parentName, outError);
            if (outError[0] == null) {
                a.info.parentActivityName = parentClassName;
            } else {
                Log.e(TAG, "Activity " + a.info.name + " specified invalid parentActivityName " +
                        parentName);
                outError[0] = null;
            }
        }

        String str;
        str = sa.getString(Hooks.getStyleable("AndroidManifestActivity_permission"));
        if (str == null) {
            a.info.permission = owner.applicationInfo.permission;
        } else {
            a.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        str = sa.getString(
                Hooks.getStyleable("AndroidManifestActivity_taskAffinity"));
        a.info.taskAffinity = buildTaskAffinityName(owner.applicationInfo.packageName,
                owner.applicationInfo.taskAffinity, str, outError);

        a.info.flags = 0;
        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestActivity_multiprocess"), false)) {
            a.info.flags |= ActivityInfo.FLAG_MULTIPROCESS;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_finishOnTaskLaunch"), false)) {
            a.info.flags |= ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_clearTaskOnLaunch"), false)) {
            a.info.flags |= ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_noHistory"), false)) {
            a.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_alwaysRetainTaskState"), false)) {
            a.info.flags |= ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_stateNotNeeded"), false)) {
            a.info.flags |= ActivityInfo.FLAG_STATE_NOT_NEEDED;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_excludeFromRecents"), false)) {
            a.info.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_allowTaskReparenting"),
                (owner.applicationInfo.flags&ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING) != 0)) {
            a.info.flags |= ActivityInfo.FLAG_ALLOW_TASK_REPARENTING;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_finishOnCloseSystemDialogs"), false)) {
            a.info.flags |= ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_showOnLockScreen"), false)
                || sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_showForAllUsers"), false)) {
//            a.info.flags |= ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_immersive"), false)) {
            a.info.flags |= ActivityInfo.FLAG_IMMERSIVE;
        }

        if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_primaryUserOnly"), false)) {
//            a.info.flags |= ActivityInfo.FLAG_PRIMARY_USER_ONLY;
        }

        if (!receiver) {
            if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_hardwareAccelerated"),
                    hardwareAccelerated)) {
                a.info.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
            }

            a.info.launchMode = sa.getInt(
                    Hooks.getStyleable("AndroidManifestActivity_launchMode"), ActivityInfo.LAUNCH_MULTIPLE);
//            a.info.documentLaunchMode = sa.getInt(
//                    Hooks.getStyleable("AndroidManifestActivity_documentLaunchMode"),
//                    ActivityInfo.DOCUMENT_LAUNCH_NONE);
//            a.info.maxRecents = sa.getInt(
//                    Hooks.getStyleable("AndroidManifestActivity_maxRecents"),
//                    ActivityManager.getDefaultAppRecentsLimitStatic());
            a.info.configChanges = sa.getInt(Hooks.getStyleable("AndroidManifestActivity_configChanges"), 0);
            a.info.softInputMode = sa.getInt(
                    Hooks.getStyleable("AndroidManifestActivity_windowSoftInputMode"), 0);

//            a.info.persistableMode = sa.getInteger(
//                    Hooks.getStyleable("AndroidManifestActivity_persistableMode"),
//                    ActivityInfo.PERSIST_ROOT_ONLY);

            if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_allowEmbedded"), false)) {
//                a.info.flags |= ActivityInfo.FLAG_ALLOW_EMBEDDED;
            }

            if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_autoRemoveFromRecents"), false)) {
                a.info.flags |= ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS;
            }

            if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_relinquishTaskIdentity"), false)) {
                a.info.flags |= ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
            }

            if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_resumeWhilePausing"), false)) {
                a.info.flags |= ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
            }

            boolean resizeable = sa.getBoolean(
                    Hooks.getStyleable("AndroidManifestActivity_resizeableActivity"), false);
            if (resizeable) {
                // Fixed screen orientation isn't supported with resizeable activities.
                a.info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            } else {
//                a.info.screenOrientation = sa.getInt(
//                        Hooks.getStyleable("AndroidManifestActivity_screenOrientation"),
//                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }

        } else {
            a.info.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            a.info.configChanges = 0;

            if (sa.getBoolean(Hooks.getStyleable("AndroidManifestActivity_singleUser"), false)) {
                a.info.flags |= ActivityInfo.FLAG_SINGLE_USER;
            }
        }

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, attrs, true, true, intent, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Log.w(TAG, "No actions in intent filter at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    a.intents.add(intent);
                }
            } else if (!receiver && parser.getName().equals("preferred")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, attrs, false, false, intent, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Log.w(TAG, "No actions in preferred at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    if (owner.preferredActivityFilters == null) {
                        owner.preferredActivityFilters = new ArrayList<ActivityIntentInfo>();
                    }
                    owner.preferredActivityFilters.add(intent);
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((a.metaData=parseMetaData(res, parser, attrs, a.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Log.w(TAG, "Problem in package " + mArchiveSourcePath + ":");
                    if (receiver) {
                        Log.w(TAG, "Unknown element under <receiver>: " + parser.getName()
                                + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    } else {
                        Log.w(TAG, "Unknown element under <activity>: " + parser.getName()
                                + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    if (receiver) {
                        outError[0] = "Bad element under <receiver>: " + parser.getName();
                    } else {
                        outError[0] = "Bad element under <activity>: " + parser.getName();
                    }
                    return null;
                }
            }
        }

        if (!setExported) {
            a.info.exported = a.intents.size() > 0;
        }

        return a;
    }

    private Activity parseActivityAlias(DynamicApkInfo owner, Resources res,
                                        XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestActivityAlias"));

        String targetActivity = sa.getString(
                Hooks.getStyleable("AndroidManifestActivityAlias_targetActivity"));
        if (targetActivity == null) {
            outError[0] = "<activity-alias> does not specify android:targetActivity";
            sa.recycle();
            return null;
        }

        targetActivity = buildClassName(owner.applicationInfo.packageName,
                targetActivity, outError);
        if (targetActivity == null) {
            sa.recycle();
            return null;
        }

        if (mParseActivityAliasArgs == null) {
            mParseActivityAliasArgs = new ParseComponentArgs(owner, outError,
                    Hooks.getStyleable("AndroidManifestActivityAlias_name"),
                    Hooks.getStyleable("AndroidManifestActivityAlias_label"),
                    Hooks.getStyleable("AndroidManifestActivityAlias_icon"),
                    Hooks.getStyleable("AndroidManifestActivityAlias_logo"),
                    Hooks.getStyleable("AndroidManifestActivityAlias_banner"),
                    null,
                    0,
                    Hooks.getStyleable("AndroidManifestActivityAlias_description"),
                    Hooks.getStyleable("AndroidManifestActivityAlias_enabled"));
            mParseActivityAliasArgs.tag = "<activity-alias>";
        }

        mParseActivityAliasArgs.sa = sa;

        Activity target = null;

        final int NA = owner.activities.size();
        for (int i=0; i<NA; i++) {
            Activity t = owner.activities.get(i);
            if (targetActivity.equals(t.info.name)) {
                target = t;
                break;
            }
        }

        if (target == null) {
            outError[0] = "<activity-alias> target activity " + targetActivity
                    + " not found in manifest";
            sa.recycle();
            return null;
        }

        ActivityInfo info = new ActivityInfo();
        info.targetActivity = targetActivity;
        info.configChanges = target.info.configChanges;
        info.flags = target.info.flags;
        info.icon = target.info.icon;
        info.logo = target.info.logo;
        info.banner = target.info.banner;
        info.labelRes = target.info.labelRes;
        info.nonLocalizedLabel = target.info.nonLocalizedLabel;
        info.launchMode = target.info.launchMode;
//        info.lockTaskLaunchMode = target.info.lockTaskLaunchMode;
        info.processName = target.info.processName;
        if (info.descriptionRes == 0) {
            info.descriptionRes = target.info.descriptionRes;
        }
        info.screenOrientation = target.info.screenOrientation;
        info.taskAffinity = target.info.taskAffinity;
        info.theme = target.info.theme;
        info.softInputMode = target.info.softInputMode;
        info.uiOptions = target.info.uiOptions;
        info.parentActivityName = target.info.parentActivityName;
        info.maxRecents = target.info.maxRecents;

        Activity a = new Activity(mParseActivityAliasArgs, info);
        if (outError[0] != null) {
//            sa.recycle();
            return null;
        }

        final boolean setExported = sa.hasValue(
                Hooks.getStyleable("AndroidManifestActivityAlias_exported"));
        if (setExported) {
            a.info.exported = sa.getBoolean(
                    Hooks.getStyleable("AndroidManifestActivityAlias_exported"), false);
        }

        String str;
        str = sa.getString(
                Hooks.getStyleable("AndroidManifestActivityAlias_permission"));
        if (str != null) {
            a.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        String parentName = sa.getString(
                Hooks.getStyleable("AndroidManifestActivityAlias_parentActivityName"));
        if (parentName != null) {
            String parentClassName = buildClassName(a.info.packageName, parentName, outError);
            if (outError[0] == null) {
                a.info.parentActivityName = parentClassName;
            } else {
                Log.e(TAG, "Activity alias " + a.info.name +
                        " specified invalid parentActivityName " + parentName);
                outError[0] = null;
            }
        }

        sa.recycle();

        if (outError[0] != null) {
            return null;
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, attrs, true, true, intent, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Log.w(TAG, "No actions in intent filter at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    a.intents.add(intent);
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((a.metaData=parseMetaData(res, parser, attrs, a.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Log.w(TAG, "Unknown element under <activity-alias>: " + parser.getName()
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <activity-alias>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            a.info.exported = a.intents.size() > 0;
        }

        return a;
    }

    private Provider parseProvider(DynamicApkInfo owner, Resources res,
                                   XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestProvider"));

        if (mParseProviderArgs == null) {
            mParseProviderArgs = new ParseComponentArgs(owner, outError,
                    Hooks.getStyleable("AndroidManifestProvider_name"),
                    Hooks.getStyleable("AndroidManifestProvider_label"),
                    Hooks.getStyleable("AndroidManifestProvider_icon"),
                    Hooks.getStyleable("AndroidManifestProvider_logo"),
                    Hooks.getStyleable("AndroidManifestProvider_banner"),
                    null,
                    Hooks.getStyleable("AndroidManifestProvider_process"),
                    Hooks.getStyleable("AndroidManifestProvider_description"),
                    Hooks.getStyleable("AndroidManifestProvider_enabled"));
            mParseProviderArgs.tag = "<provider>";
        }

        mParseProviderArgs.sa = sa;

        Provider p = new Provider(mParseProviderArgs, new ProviderInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean providerExportedDefault = false;

        if (owner.applicationInfo.targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // For compatibility, applications targeting API level 16 or lower
            // should have their content providers exported by default, unless they
            // specify otherwise.
            providerExportedDefault = true;
        }

        p.info.exported = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestProvider_exported"),
                providerExportedDefault);

        String cpname = sa.getString(
                Hooks.getStyleable("AndroidManifestProvider_authorities"));

        p.info.isSyncable = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestProvider_syncable"),
                false);

        String permission = sa.getString(
                Hooks.getStyleable("AndroidManifestProvider_permission"));
        String str = sa.getString(
                Hooks.getStyleable("AndroidManifestProvider_readPermission"));
        if (str == null) {
            str = permission;
        }
        if (str == null) {
            p.info.readPermission = owner.applicationInfo.permission;
        } else {
            p.info.readPermission =
                    str.length() > 0 ? str.toString().intern() : null;
        }
        str = sa.getString(
                Hooks.getStyleable("AndroidManifestProvider_writePermission"));
        if (str == null) {
            str = permission;
        }
        if (str == null) {
            p.info.writePermission = owner.applicationInfo.permission;
        } else {
            p.info.writePermission =
                    str.length() > 0 ? str.toString().intern() : null;
        }

        p.info.grantUriPermissions = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestProvider_grantUriPermissions"),
                false);

        p.info.multiprocess = sa.getBoolean(
                Hooks.getStyleable("AndroidManifestProvider_multiprocess"),
                false);

        p.info.initOrder = sa.getInt(
                Hooks.getStyleable("AndroidManifestProvider_initOrder"),
                0);

        p.info.flags = 0;

        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestProvider_singleUser"),
                false)) {
            p.info.flags |= ProviderInfo.FLAG_SINGLE_USER;
        }

        sa.recycle();

//        if ((owner.applicationInfo.privateFlags&ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)
//                != 0) {
//            // A heavy-weight application can not have providers in its main process
//            // We can do direct compare because we intern all strings.
//            if (p.info.processName == owner.packageName) {
//                outError[0] = "Heavy-weight applications can not have providers in main process";
//                return null;
//            }
//        }

        if (cpname == null) {
            outError[0] = "<provider> does not include authorities attribute";
            return null;
        }
        if (cpname.length() <= 0) {
            outError[0] = "<provider> has empty authorities attribute";
            return null;
        }
        p.info.authority = cpname.intern();

        if (!parseProviderTags(res, parser, attrs, p, outError)) {
            return null;
        }

        return p;
    }

    private boolean parseProviderTags(Resources res,
                                      XmlPullParser parser, AttributeSet attrs,
                                      Provider outInfo, String[] outError)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ProviderIntentInfo intent = new ProviderIntentInfo(outInfo);
                if (!parseIntent(res, parser, attrs, true, false, intent, outError)) {
                    return false;
                }
                outInfo.intents.add(intent);

            } else if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData=parseMetaData(res, parser, attrs,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }

            } else if (parser.getName().equals("grant-uri-permission")) {
                TypedArray sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestGrantUriPermission"));

                PatternMatcher pa = null;

                String str = sa.getString(
                        Hooks.getStyleable("AndroidManifestGrantUriPermission_path"));
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestGrantUriPermission_pathPrefix"));
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestGrantUriPermission_pathPattern"));
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.info.uriPermissionPatterns == null) {
                        outInfo.info.uriPermissionPatterns = new PatternMatcher[1];
                        outInfo.info.uriPermissionPatterns[0] = pa;
                    } else {
                        final int N = outInfo.info.uriPermissionPatterns.length;
                        PatternMatcher[] newp = new PatternMatcher[N+1];
                        System.arraycopy(outInfo.info.uriPermissionPatterns, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.info.uriPermissionPatterns = newp;
                    }
                    outInfo.info.grantUriPermissions = true;
                } else {
                    if (!RIGID_PARSER) {
                        Log.w(TAG, "Unknown element under <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                        return false;
                    }
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (parser.getName().equals("path-permission")) {
                TypedArray sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestPathPermission"));

                PathPermission pa = null;

                String permission = sa.getString(
                        Hooks.getStyleable("AndroidManifestPathPermission_permission"));
                String readPermission = sa.getString(
                        Hooks.getStyleable("AndroidManifestPathPermission_readPermission"));
                if (readPermission == null) {
                    readPermission = permission;
                }
                String writePermission = sa.getString(
                        Hooks.getStyleable("AndroidManifestPathPermission_writePermission"));
                if (writePermission == null) {
                    writePermission = permission;
                }

                boolean havePerm = false;
                if (readPermission != null) {
                    readPermission = readPermission.intern();
                    havePerm = true;
                }
                if (writePermission != null) {
                    writePermission = writePermission.intern();
                    havePerm = true;
                }

                if (!havePerm) {
                    if (!RIGID_PARSER) {
                        Log.w(TAG, "No readPermission or writePermssion for <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No readPermission or writePermssion for <path-permission>";
                        return false;
                    }
                }

                String path = sa.getString(
                        Hooks.getStyleable("AndroidManifestPathPermission_path"));
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_LITERAL, readPermission, writePermission);
                }

                path = sa.getString(
                        Hooks.getStyleable("AndroidManifestPathPermission_pathPrefix"));
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_PREFIX, readPermission, writePermission);
                }

                path = sa.getString(
                        Hooks.getStyleable("AndroidManifestPathPermission_pathPattern"));
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_SIMPLE_GLOB, readPermission, writePermission);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.info.pathPermissions == null) {
                        outInfo.info.pathPermissions = new PathPermission[1];
                        outInfo.info.pathPermissions[0] = pa;
                    } else {
                        final int N = outInfo.info.pathPermissions.length;
                        PathPermission[] newp = new PathPermission[N+1];
                        System.arraycopy(outInfo.info.pathPermissions, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.info.pathPermissions = newp;
                    }
                } else {
                    if (!RIGID_PARSER) {
                        Log.w(TAG, "No path, pathPrefix, or pathPattern for <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!RIGID_PARSER) {
                    Log.w(TAG, "Unknown element under <provider>: "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <provider>: " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    private Service parseService(DynamicApkInfo owner, Resources res,
                                 XmlPullParser parser, AttributeSet attrs, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestService"));

        if (mParseServiceArgs == null) {
            mParseServiceArgs = new ParseComponentArgs(owner, outError,
                    Hooks.getStyleable("AndroidManifestService_name"),
                    Hooks.getStyleable("AndroidManifestService_label"),
                    Hooks.getStyleable("AndroidManifestService_icon"),
                    Hooks.getStyleable("AndroidManifestService_logo"),
                    Hooks.getStyleable("AndroidManifestService_banner"),
                    null,
                    Hooks.getStyleable("AndroidManifestService_process"),
                    Hooks.getStyleable("AndroidManifestService_description"),
                    Hooks.getStyleable("AndroidManifestService_enabled"));
            mParseServiceArgs.tag = "<service>";
        }

        mParseServiceArgs.sa = sa;

        Service s = new Service(mParseServiceArgs, new ServiceInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean setExported = sa.hasValue(
                Hooks.getStyleable("AndroidManifestService_exported"));
        if (setExported) {
            s.info.exported = sa.getBoolean(
                    Hooks.getStyleable("AndroidManifestService_exported"), false);
        }

        String str = sa.getString(
                Hooks.getStyleable("AndroidManifestService_permission"));
        if (str == null) {
            s.info.permission = owner.applicationInfo.permission;
        } else {
            s.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        s.info.flags = 0;
        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestService_stopWithTask"),
                false)) {
            s.info.flags |= ServiceInfo.FLAG_STOP_WITH_TASK;
        }
        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestService_isolatedProcess"),
                false)) {
            s.info.flags |= ServiceInfo.FLAG_ISOLATED_PROCESS;
        }
        if (sa.getBoolean(
                Hooks.getStyleable("AndroidManifestService_singleUser"),
                false)) {
            s.info.flags |= ServiceInfo.FLAG_SINGLE_USER;
        }

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ServiceIntentInfo intent = new ServiceIntentInfo(s);
                if (!parseIntent(res, parser, attrs, true, false, intent, outError)) {
                    return null;
                }

                s.intents.add(intent);
            } else if (parser.getName().equals("meta-data")) {
                if ((s.metaData=parseMetaData(res, parser, attrs, s.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Log.w(TAG, "Unknown element under <service>: "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <service>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            s.info.exported = s.intents.size() > 0;
        }

        return s;
    }

    private boolean parseAllMetaData(Resources res,
                                     XmlPullParser parser, AttributeSet attrs, String tag,
                                     Component outInfo, String[] outError)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData=parseMetaData(res, parser, attrs,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }
            } else {
                if (!RIGID_PARSER) {
                    Log.w(TAG, "Unknown element under " + tag + ": "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under " + tag + ": " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    private Bundle parseMetaData(Resources res,
                                 XmlPullParser parser, AttributeSet attrs,
                                 Bundle data, String[] outError)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestMetaData"));

        if (data == null) {
            data = new Bundle();
        }

        String name = sa.getString(
                Hooks.getStyleable("AndroidManifestMetaData_name"));
        if (name == null) {
            outError[0] = "<meta-data> requires an android:name attribute";
            sa.recycle();
            return null;
        }

        name = name.intern();

        TypedValue v = sa.peekValue(
                Hooks.getStyleable("AndroidManifestMetaData_resource"));
        if (v != null && v.resourceId != 0) {
            Log.i(TAG, "Meta data ref " + name + ": " + v);
            data.putInt(name, v.resourceId);
        } else {
            v = sa.peekValue(
                    Hooks.getStyleable("AndroidManifestMetaData_value"));
            Log.i(TAG, "Meta data " + name + ": " + v);
            if (v != null) {
                if (v.type == TypedValue.TYPE_STRING) {
                    CharSequence cs = v.coerceToString();
                    data.putString(name, cs != null ? cs.toString().intern() : null);
                } else if (v.type == TypedValue.TYPE_INT_BOOLEAN) {
                    data.putBoolean(name, v.data != 0);
                } else if (v.type >= TypedValue.TYPE_FIRST_INT
                        && v.type <= TypedValue.TYPE_LAST_INT) {
                    data.putInt(name, v.data);
                } else if (v.type == TypedValue.TYPE_FLOAT) {
                    data.putFloat(name, v.getFloat());
                } else {
                    if (!RIGID_PARSER) {
                        Log.w(TAG, "<meta-data> only supports string, integer, float, color, boolean, and resource reference types: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    } else {
                        outError[0] = "<meta-data> only supports string, integer, float, color, boolean, and resource reference types";
                        data = null;
                    }
                }
            } else {
                outError[0] = "<meta-data> requires an android:value or android:resource attribute";
                data = null;
            }
        }

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);

        return data;
    }

    public static final PublicKey parsePublicKey(final String encodedPublicKey) {
        if (encodedPublicKey == null) {
            Log.w(TAG, "Could not parse null public key");
            return null;
        }

        EncodedKeySpec keySpec;
        try {
            final byte[] encoded = Base64.decode(encodedPublicKey, Base64.DEFAULT);
            keySpec = new X509EncodedKeySpec(encoded);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Could not parse verifier public key; invalid Base64");
            return null;
        }

        /* First try the key as an RSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Could not parse public key: RSA KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a RSA public key.
        }

        /* Now try it as a ECDSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Could not parse public key: EC KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a ECDSA public key.
        }

        /* Now try it as a DSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Could not parse public key: DSA KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a DSA public key.
        }

        /* Not a supported key type */
        return null;
    }

    private static final String ANDROID_RESOURCES
            = "http://schemas.android.com/apk/res/android";

    private boolean parseIntent(Resources res, XmlPullParser parser, AttributeSet attrs,
                                boolean allowGlobs, boolean allowAutoVerify, IntentInfo outInfo, String[] outError)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(attrs,
                Hooks.getStyleableArray("AndroidManifestIntentFilter"));

        int priority = sa.getInt(
                Hooks.getStyleable("AndroidManifestIntentFilter_priority"), 0);
        outInfo.setPriority(priority);

        TypedValue v = sa.peekValue(
                Hooks.getStyleable("AndroidManifestIntentFilter_label"));
        if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        outInfo.icon = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestIntentFilter_icon"), 0);

        outInfo.logo = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestIntentFilter_logo"), 0);

        outInfo.banner = sa.getResourceId(
                Hooks.getStyleable("AndroidManifestIntentFilter_banner"), 0);

        if (allowAutoVerify) {
//            outInfo.setAutoVerify(sa.getBoolean(
//                    Hooks.getStyleable("AndroidManifestIntentFilter_autoVerify"),
//                    false));
        }

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String nodeName = parser.getName();
            if (nodeName.equals("action")) {
                String value = attrs.getAttributeValue(
                        ANDROID_RESOURCES, "name");
                if (value == null || value == "") {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                outInfo.addAction(value);
            } else if (nodeName.equals("category")) {
                String value = attrs.getAttributeValue(
                        ANDROID_RESOURCES, "name");
                if (value == null || value == "") {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                outInfo.addCategory(value);

            } else if (nodeName.equals("data")) {
                sa = res.obtainAttributes(attrs,
                        Hooks.getStyleableArray("AndroidManifestData"));

                String str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_mimeType"));
                if (str != null) {
                    try {
                        outInfo.addDataType(str);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        outError[0] = e.toString();
                        sa.recycle();
                        return false;
                    }
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_scheme"));
                if (str != null) {
                    outInfo.addDataScheme(str);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_ssp"));
                if (str != null) {
                    outInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_sspPrefix"));
                if (str != null) {
                    outInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_sspPattern"));
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "sspPattern not allowed here; ssp must be literal";
                        return false;
                    }
                    outInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                String host = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_host"));
                String port = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_port"));
                if (host != null) {
                    outInfo.addDataAuthority(host, port);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_path"));
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_pathPrefix"));
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getString(
                        Hooks.getStyleable("AndroidManifestData_pathPattern"));
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "pathPattern not allowed here; path must be literal";
                        return false;
                    }
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (!RIGID_PARSER) {
                Log.w(TAG, "Unknown element under <intent-filter>: "
                        + parser.getName() + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
            } else {
                outError[0] = "Bad element under <intent-filter>: " + parser.getName();
                return false;
            }
        }

        outInfo.hasDefault = outInfo.hasCategory(Intent.CATEGORY_DEFAULT);

//            final StringBuilder cats = new StringBuilder("Intent d=");
//            cats.append(outInfo.hasDefault);
//            cats.append(", cat=");
//
//            final Iterator<String> it = outInfo.categoriesIterator();
//            if (it != null) {
//                while (it.hasNext()) {
//                    cats.append(' ');
//                    cats.append(it.next());
//                }
//            }
//            Log.d(TAG, cats.toString());

        return true;
    }


    public static class Component<II extends IntentInfo> {
        public final DynamicApkInfo owner;
        public final ArrayList<II> intents;
        public final String className;
        public Bundle metaData;

        ComponentName componentName;
        String componentShortName;

        public Component(DynamicApkInfo _owner) {
            owner = _owner;
            intents = null;
            className = null;
        }

        public Component(final ParsePackageItemArgs args, final PackageItemInfo outInfo) {
            owner = args.owner;
            intents = new ArrayList<II>(0);

            String name = args.sa.getString(args.nameRes);
            if (name == null) {
                className = null;
                args.outError[0] = args.tag + " does not specify android:name";
                return;
            }

            outInfo.name
                    = buildClassName(owner.applicationInfo.packageName, name, args.outError);
            if (outInfo.name == null) {
                className = null;
                args.outError[0] = args.tag + " does not have valid android:name";
                return;
            }

            className = outInfo.name;

            int iconVal = args.sa.getResourceId(args.iconRes, 0);
            if (iconVal != 0) {
                outInfo.icon = iconVal;
                outInfo.nonLocalizedLabel = null;
            }

            int logoVal = args.sa.getResourceId(args.logoRes, 0);
            if (logoVal != 0) {
                outInfo.logo = logoVal;
            }

//            int bannerVal = args.sa.getResourceId(args.bannerRes, 0);
//            if (bannerVal != 0) {
//                outInfo.banner = bannerVal;
//            }

            TypedValue v = args.sa.peekValue(args.labelRes);
            if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
                outInfo.nonLocalizedLabel = v.coerceToString();
            }

            outInfo.packageName = owner.packageName;
        }

        public Component(final ParseComponentArgs args, final ComponentInfo outInfo) {
            this(args, (PackageItemInfo)outInfo);
            if (args.outError[0] != null) {
                return;
            }

            if (args.processRes != 0) {
                CharSequence pname;
                if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
//                    pname = args.sa.getNonConfigurationString(args.processRes,
//                            Configuration.NATIVE_CONFIG_VERSION);
                    pname = args.sa.getNonResourceString(args.processRes);
                } else {
                    // Some older apps have been seen to use a resource reference
                    // here that on older builds was ignored (with a warning).  We
                    // need to continue to do this for them so they don't break.
                    pname = args.sa.getNonResourceString(args.processRes);
                }
                outInfo.processName = buildProcessName(owner.applicationInfo.packageName,
                        owner.applicationInfo.processName, pname,
                        args.sepProcesses, args.outError);
            }

            if (args.descriptionRes != 0) {
                outInfo.descriptionRes = args.sa.getResourceId(args.descriptionRes, 0);
            }

            outInfo.enabled = args.sa.getBoolean(args.enabledRes, true);
        }

        public Component(Component<II> clone) {
            owner = clone.owner;
            intents = clone.intents;
            className = clone.className;
            componentName = clone.componentName;
            componentShortName = clone.componentShortName;
        }

        public ComponentName getComponentName() {
            if (componentName != null) {
                return componentName;
            }
            if (className != null) {
                componentName = new ComponentName(owner.applicationInfo.packageName,
                        className);
            }
            return componentName;
        }

        public void appendComponentShortName(StringBuilder sb) {
            appendShortString(sb, owner.applicationInfo.packageName, className);
        }

        public void printComponentShortName(PrintWriter pw) {
            printShortString(pw, owner.applicationInfo.packageName, className);
        }
        public static void appendShortString(StringBuilder sb, String packageName, String className) {
            sb.append(packageName).append('/');
            appendShortClassName(sb, packageName, className);
        }

        private static void appendShortClassName(StringBuilder sb, String packageName,
                                                 String className) {
            if (className.startsWith(packageName)) {
                int PN = packageName.length();
                int CN = className.length();
                if (CN > PN && className.charAt(PN) == '.') {
                    sb.append(className, PN, CN);
                    return;
                }
            }
            sb.append(className);
        }

        public static void printShortString(PrintWriter pw, String packageName, String className) {
            pw.print(packageName);
            pw.print('/');
            printShortClassName(pw, packageName, className);
        }

        private static void printShortClassName(PrintWriter pw, String packageName,
                                                String className) {
            if (className.startsWith(packageName)) {
                int PN = packageName.length();
                int CN = className.length();
                if (CN > PN && className.charAt(PN) == '.') {
                    pw.write(className, PN, CN-PN);
                    return;
                }
            }
            pw.print(className);
        }

        public void setPackageName(String packageName) {
            componentName = null;
            componentShortName = null;
        }
    }

    public final static class Permission extends Component<IntentInfo> {
        public final PermissionInfo info;
        public boolean tree;
        public PermissionGroup group;

        public Permission(DynamicApkInfo _owner) {
            super(_owner);
            info = new PermissionInfo();
        }

        public Permission(DynamicApkInfo _owner, PermissionInfo _info) {
            super(_owner);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Permission{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " " + info.name + "}";
        }
    }

    public final static class PermissionGroup extends Component<IntentInfo> {
        public final PermissionGroupInfo info;

        public PermissionGroup(DynamicApkInfo _owner) {
            super(_owner);
            info = new PermissionGroupInfo();
        }

        public PermissionGroup(DynamicApkInfo _owner, PermissionGroupInfo _info) {
            super(_owner);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "PermissionGroup{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " " + info.name + "}";
        }
    }

    public final static class Activity extends Component<ActivityIntentInfo> {
        public final ActivityInfo info;

        public Activity(final ParseComponentArgs args, final ActivityInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Activity{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public final static class Service extends Component<ServiceIntentInfo> {
        public final ServiceInfo info;

        public Service(final ParseComponentArgs args, final ServiceInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Service{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public final static class Provider extends Component<ProviderIntentInfo> {
        public final ProviderInfo info;
        public boolean syncable;

        public Provider(final ParseComponentArgs args, final ProviderInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
            syncable = false;
        }

        public Provider(Provider existingProvider) {
            super(existingProvider);
            this.info = existingProvider.info;
            this.syncable = existingProvider.syncable;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Provider{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public final static class Instrumentation extends Component {
        public final InstrumentationInfo info;

        public Instrumentation(final ParsePackageItemArgs args, final InstrumentationInfo _info) {
            super(args, _info);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Instrumentation{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public static final InstrumentationInfo generateInstrumentationInfo(
            Instrumentation i, int flags) {
        if (i == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static class IntentInfo extends IntentFilter {
        public boolean hasDefault;
        public int labelRes;
        public CharSequence nonLocalizedLabel;
        public int icon;
        public int logo;
        public int banner;
        public int preferred;
    }

    public final static class ActivityIntentInfo extends IntentInfo {
        public final Activity activity;

        public ActivityIntentInfo(Activity _activity) {
            activity = _activity;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ActivityIntentInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            activity.appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public final static class ServiceIntentInfo extends IntentInfo {
        public final Service service;

        public ServiceIntentInfo(Service _service) {
            service = _service;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ServiceIntentInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            service.appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public static final class ProviderIntentInfo extends IntentInfo {
        public final Provider provider;

        public ProviderIntentInfo(Provider provider) {
            this.provider = provider;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ProviderIntentInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            provider.appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }
    }

    public static void closeQuietly(XmlResourceParser parser) {
        if (parser != null) {
            parser.close();
        }
    }

    public static class DynamicApkParserException extends Exception {
        public final int error;

        public DynamicApkParserException(int error, String detailMessage) {
            super(detailMessage);
            this.error = error;
        }

        public DynamicApkParserException(int error, String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
            this.error = error;
        }
    }
}

