package com.ximsfei.dynamic.pm;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.Signature;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.ximsfei.dynamic.pm.DynamicApkParser.Activity;
import com.ximsfei.dynamic.pm.DynamicApkParser.ActivityIntentInfo;
import com.ximsfei.dynamic.pm.DynamicApkParser.Instrumentation;
import com.ximsfei.dynamic.pm.DynamicApkParser.Permission;
import com.ximsfei.dynamic.pm.DynamicApkParser.PermissionGroup;
import com.ximsfei.dynamic.pm.DynamicApkParser.Provider;
import com.ximsfei.dynamic.pm.DynamicApkParser.Service;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;


/**
 * Created by pengfenx on 3/7/2016.
 */
public final class DynamicApkInfo {

    public AssetManager assets;
    public Resources resources;
    public ClassLoader classLoader;
    public Application application;

    public String packageName;

    /** Path of base APK */
    public String baseCodePath;

    public boolean baseHardwareAccelerated;

    public final ApplicationInfo applicationInfo = new ApplicationInfo();

    public final ArrayList<Permission> permissions = new ArrayList<>(0);
    public final ArrayList<PermissionGroup> permissionGroups = new ArrayList<>(0);
    public final ArrayList<Activity> activities = new ArrayList<>(0);
    public final ArrayList<Activity> receivers = new ArrayList<>(0);
    public final ArrayList<Provider> providers = new ArrayList<>(0);
    public final ArrayList<Service> services = new ArrayList<>(0);
    public final ArrayList<Instrumentation> instrumentation = new ArrayList<>(0);

    public final ArrayList<String> requestedPermissions = new ArrayList<>();

    public ArrayList<String> protectedBroadcasts;

    public ArrayList<String> libraryNames = null;
    public ArrayList<String> usesLibraries = null;
    public ArrayList<String> usesOptionalLibraries = null;

    public ArrayList<ActivityIntentInfo> preferredActivityFilters = null;

    // We store the application meta-data independently to avoid multiple unwanted references
    public Bundle mAppMetaData = null;

    // The version code declared for this package.
    public int mVersionCode;

    // The version name declared for this package.
    public String mVersionName;

    // The shared user id that this package wants to use.
    public String mSharedUserId;

    // The shared user label that this package wants to use.
    public int mSharedUserLabel;

    // Signatures that were read from the package.
    public Signature[] mSignatures;

    // Applications hardware preferences
    public ArrayList<ConfigurationInfo> configPreferences = null;

    // Applications requested features
    public ArrayList<FeatureInfo> reqFeatures = null;

    // Applications requested feature groups
    public ArrayList<FeatureGroupInfo> featureGroups = null;

    public int installLocation;

    public boolean coreApp;

    /* An app that's required for all users and cannot be uninstalled for a user */
    public boolean mRequiredForAllUsers;

    /* The restricted account authenticator type that is used by this application */
    public String mRestrictedAccountType;

    /* The required account type without which this application will not function */
    public String mRequiredAccountType;

    public String mOverlayTarget;
    public int mOverlayPriority;

    /**
     * Data used to feed the KeySetManagerService
     */
    public ArraySet<String> mUpgradeKeySets;
    public ArrayMap<String, ArraySet<PublicKey>> mKeySetMapping;

    public DynamicApkInfo(String packageName) {
        this.packageName = packageName;
        applicationInfo.packageName = packageName;
        applicationInfo.uid = -1;
    }

    public List<String> getAllCodePaths() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add(baseCodePath);
        return paths;
    }

    /**
     * Filtered set of {@link #getAllCodePaths()} that excludes
     * resource-only APKs.
     */
    public List<String> getAllCodePathsExcludingResourceOnly() {
        ArrayList<String> paths = new ArrayList<>();
        if ((applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            paths.add(baseCodePath);
        }
        return paths;
    }

    public void setPackageName(String newName) {
        packageName = newName;
        applicationInfo.packageName = newName;
        for (int i=permissions.size()-1; i>=0; i--) {
            permissions.get(i).setPackageName(newName);
        }
        for (int i=permissionGroups.size()-1; i>=0; i--) {
            permissionGroups.get(i).setPackageName(newName);
        }
        for (int i=activities.size()-1; i>=0; i--) {
            activities.get(i).setPackageName(newName);
        }
        for (int i=receivers.size()-1; i>=0; i--) {
            receivers.get(i).setPackageName(newName);
        }
        for (int i=providers.size()-1; i>=0; i--) {
            providers.get(i).setPackageName(newName);
        }
        for (int i=services.size()-1; i>=0; i--) {
            services.get(i).setPackageName(newName);
        }
        for (int i=instrumentation.size()-1; i>=0; i--) {
            instrumentation.get(i).setPackageName(newName);
        }
    }

    public boolean hasComponentClassName(String name) {
        for (int i=activities.size()-1; i>=0; i--) {
            if (name.equals(activities.get(i).className)) {
                return true;
            }
        }
        for (int i=receivers.size()-1; i>=0; i--) {
            if (name.equals(receivers.get(i).className)) {
                return true;
            }
        }
        for (int i=providers.size()-1; i>=0; i--) {
            if (name.equals(providers.get(i).className)) {
                return true;
            }
        }
        for (int i=services.size()-1; i>=0; i--) {
            if (name.equals(services.get(i).className)) {
                return true;
            }
        }
        for (int i=instrumentation.size()-1; i>=0; i--) {
            if (name.equals(instrumentation.get(i).className)) {
                return true;
            }
        }
        return false;
    }

    public String toString() {
        return "PackageInfo{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
    }
}
