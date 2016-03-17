package com.ximsfei.dynamic;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LogPrinter;

import com.ximsfei.dynamic.app.DynamicActivityThread;
import com.ximsfei.dynamic.app.DynamicInstrumentation;
import com.ximsfei.dynamic.pm.DynamicApkInfo;
import com.ximsfei.dynamic.pm.DynamicApkParser;
import com.ximsfei.dynamic.util.DynamicConstants;
import com.ximsfei.dynamic.util.FastImmutableArraySet;

import java.io.File;
import java.lang.IllegalStateException;
import java.lang.IllegalThreadStateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class DynamicApkManager {
    private static final String TAG = "DynamicApkManager";
    private static final boolean DEBUG_PACKAGE_SCANNING = true;
    private static final boolean DEBUG_SHOW_INFO = true;

    public static DynamicApkManager sDynamicApk;
    private Context mApplicationContext;
    public final HashMap<String, DynamicApkInfo> mDynamicPackages;
    public final HashMap<String, ActivityInfo> mDynamicMainActivities;

    // All available activities, for your resolving pleasure.
    final ActivityIntentResolver mActivities = new ActivityIntentResolver();

    // All available receivers, for your resolving pleasure.
    final ActivityIntentResolver mReceivers = new ActivityIntentResolver();

    // All available services, for your resolving pleasure.
    final ServiceIntentResolver mServices = new ServiceIntentResolver();

    // All available providers, for your resolving pleasure.
    final ProviderIntentResolver mProviders = new ProviderIntentResolver();

    // Mapping from provider base names (first directory in content URI codePath)
    // to the provider information.
    final ArrayMap<String, DynamicApkParser.Provider> mProvidersByAuthority =
            new ArrayMap<String, DynamicApkParser.Provider>();

    // Mapping from instrumentation class names to info about them.
    final ArrayMap<ComponentName, DynamicApkParser.Instrumentation> mInstrumentation =
            new ArrayMap<ComponentName, DynamicApkParser.Instrumentation>();

    // Mapping from permission names to info about them.
    final ArrayMap<String, DynamicApkParser.PermissionGroup> mPermissionGroups =
            new ArrayMap<String, DynamicApkParser.PermissionGroup>();

    // Broadcast actions that are only available to the system.
    final ArraySet<String> mProtectedBroadcasts = new ArraySet<String>();

    private DynamicApkManager(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalThreadStateException(
                    "DynamicApk must be instantiated in UI Thread!");
        }
        mApplicationContext = context;
        mDynamicPackages = new HashMap();
        mDynamicMainActivities = new HashMap();

        Instrumentation instrumentation = DynamicActivityThread.getInstance().getInstrumentation();
        if (!(instrumentation instanceof DynamicInstrumentation)) {
            DynamicInstrumentation dynamicInstrumentation = new DynamicInstrumentation(instrumentation);
            DynamicActivityThread.getInstance().setInstrumentation(dynamicInstrumentation);
        }
    }

    public static void init(Context context) {
        if (sDynamicApk == null) {
            sDynamicApk = new DynamicApkManager(context);
        }
    }

    public static DynamicApkManager getInstance() {
        if (sDynamicApk == null) {
            throw new IllegalStateException("Please invoke newInstance(context) firstly!");
        }
        return sDynamicApk;
    }

    public void install(File apkFile) {
        new DynamicApkParser().parsePackage(getInstance(), mApplicationContext, apkFile);
    }

    public void addPackage(DynamicApkInfo info) {
        DynamicActivityThread.getInstance().installClassLoader(info.classLoader);
        DynamicActivityThread.getInstance().installContentProviders(info.providers);
        if (mDynamicPackages.get(info.packageName) == null) {
            try {
                scanApkInfo(info);
            } catch (DynamicApkManagerException e) {
                e.printStackTrace();
            }
        }
        updateMainActivity();
        registerStaticBroadcastReceiver(info);
        mDynamicPackages.put(info.packageName, info);
    }

    public DynamicApkInfo getPackage(String pkgName) {
        return mDynamicPackages.get(pkgName);
    }

    public void registerStaticBroadcastReceiver(DynamicApkInfo info) {
        int N = info.receivers.size();
        for (int i = 0; i < N; i++) {
            int M = info.receivers.get(i).intents.size();
            for (int j = 0; j < M; j++) {
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(info.receivers.get(i).intents.get(j).getAction(0));
                try {
                    mApplicationContext.registerReceiver((BroadcastReceiver) info.classLoader
                            .loadClass(info.receivers.get(i).info.name).newInstance(), intentFilter);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void updateMainActivity() {
        Intent main = new Intent();
        main.setAction(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = resolveIntent(main, null, DynamicConstants.RESOLVE_ACTIVITY);
        int N = resolveInfos.size();
        for (int i=0; i<N; i++) {
            ActivityInfo aInfo = resolveInfos.get(i).activityInfo;
            if (!mDynamicMainActivities.containsValue(aInfo)) {
                mDynamicMainActivities.put(aInfo.packageName, aInfo);
            }
        }
    }

    public HashMap getMainActivities() {
        return mDynamicMainActivities;
    }

    public ActivityInfo getMainActivity(String pkgName) {
        return mDynamicMainActivities.get(pkgName);
    }

    public DynamicApkParser.Component queryClassName(String name, int type) {
        switch (type) {
            case DynamicConstants.RESOLVE_ACTIVITY:
                return mActivities.mActivities.get(name);
            case DynamicConstants.RESOLVE_RECEIVER:
                return mReceivers.mActivities.get(name);
            case DynamicConstants.RESOLVE_SERVICE:
                return mServices.mServices.get(name);
            case DynamicConstants.RESOLVE_PROVIDER:
                return mProviders.mProviders.get(name);
            default:
                throw new IllegalStateException("Error resolved flag !");
        }
    }

    public List<ResolveInfo> resolveIntent(Intent intent, String resolvedType, int type) {
        switch (type) {
            case DynamicConstants.RESOLVE_ACTIVITY:
                return mActivities.queryIntent(intent, resolvedType);
            case DynamicConstants.RESOLVE_RECEIVER:
                return mReceivers.queryIntent(intent, resolvedType);
            case DynamicConstants.RESOLVE_SERVICE:
                return mServices.queryIntent(intent, resolvedType);
            case DynamicConstants.RESOLVE_PROVIDER:
                return mProviders.queryIntent(intent, resolvedType);
            default:
                throw new IllegalStateException("Error resolved flag !");
        }
    }

    private void scanApkInfo(DynamicApkInfo info) throws DynamicApkManagerException {
        if (mDynamicPackages.containsKey(info.packageName)) {
            throw new DynamicApkManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                    "Application package " + info.packageName
                            + " already installed.  Skipping duplicate.");
        }

        synchronized (mDynamicPackages) {
            mDynamicPackages.put(info.applicationInfo.packageName, info);
            int N = info.providers.size();
            StringBuilder r = null;
            int i;
            for (i=0; i<N; i++) {
                DynamicApkParser.Provider p = info.providers.get(i);
                p.info.processName = fixProcessName(info.applicationInfo.processName,
                        p.info.processName, info.applicationInfo.uid);
                mProviders.addProvider(p);
                p.syncable = p.info.isSyncable;
                if (p.info.authority != null) {
                    String names[] = p.info.authority.split(";");
                    p.info.authority = null;
                    for (int j = 0; j < names.length; j++) {
                        if (j == 1 && p.syncable) {
                            // We only want the first authority for a provider to possibly be
                            // syncable, so if we already added this provider using a different
                            // authority clear the syncable flag. We copy the provider before
                            // changing it because the mProviders object contains a reference
                            // to a provider that we don't want to change.
                            // Only do this for the second authority since the resulting provider
                            // object can be the same for all future authorities for this provider.
                            p = new DynamicApkParser.Provider(p);
                            p.syncable = false;
                        }
                        if (!mProvidersByAuthority.containsKey(names[j])) {
                            mProvidersByAuthority.put(names[j], p);
                            if (p.info.authority == null) {
                                p.info.authority = names[j];
                            } else {
                                p.info.authority = p.info.authority + ";" + names[j];
                            }
                        } else {
                            DynamicApkParser.Provider other = mProvidersByAuthority.get(names[j]);
                            Log.w(TAG, "Skipping provider name " + names[j] +
                                    " (in package " + info.applicationInfo.packageName +
                                    "): name already used by "
                                    + ((other != null && other.getComponentName() != null)
                                    ? other.getComponentName().getPackageName() : "?"));
                        }
                    }
                }
                if (DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Providers: " + r);
            }

            N = info.services.size();
            r = null;
            for (i=0; i<N; i++) {
                DynamicApkParser.Service s = info.services.get(i);
                s.info.processName = fixProcessName(info.applicationInfo.processName,
                        s.info.processName, info.applicationInfo.uid);
                mServices.addService(s);
                if (DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(s.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Services: " + r);
            }

            N = info.receivers.size();
            r = null;
            for (i=0; i<N; i++) {
                DynamicApkParser.Activity a = info.receivers.get(i);
                a.info.processName = fixProcessName(info.applicationInfo.processName,
                        a.info.processName, info.applicationInfo.uid);
                mReceivers.addActivity(a, "receiver");
                if (DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Receivers: " + r);
            }

            N = info.activities.size();
            r = null;
            for (i=0; i<N; i++) {
                DynamicApkParser.Activity a = info.activities.get(i);
                a.info.processName = fixProcessName(info.applicationInfo.processName,
                        a.info.processName, info.applicationInfo.uid);
                mActivities.addActivity(a, "activity");
                if (DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Activities: " + r);
            }

            N = info.permissionGroups.size();
            r = null;
            for (i=0; i<N; i++) {
                DynamicApkParser.PermissionGroup pg = info.permissionGroups.get(i);
                DynamicApkParser.PermissionGroup cur = mPermissionGroups.get(pg.info.name);
                if (cur == null) {
                    mPermissionGroups.put(pg.info.name, pg);
                    if (DEBUG_PACKAGE_SCANNING) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(pg.info.name);
                    }
                } else {
                    Log.w(TAG, "Permission group " + pg.info.name + " from package "
                            + pg.info.packageName + " ignored: original from "
                            + cur.info.packageName);
                    if (DEBUG_PACKAGE_SCANNING) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append("DUP:");
                        r.append(pg.info.name);
                    }
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Permission Groups: " + r);
            }

            N = info.instrumentation.size();
            r = null;
            for (i=0; i<N; i++) {
                DynamicApkParser.Instrumentation a = info.instrumentation.get(i);
                a.info.packageName = info.applicationInfo.packageName;
                a.info.sourceDir = info.applicationInfo.sourceDir;
                a.info.publicSourceDir = info.applicationInfo.publicSourceDir;
                a.info.splitSourceDirs = info.applicationInfo.splitSourceDirs;
                a.info.splitPublicSourceDirs = info.applicationInfo.splitPublicSourceDirs;
                a.info.dataDir = info.applicationInfo.dataDir;

                // TODO: Update instrumentation.nativeLibraryDir as well ? Does it
                // need other information about the application, like the ABI and what not ?
                mInstrumentation.put(a.getComponentName(), a);
                if (DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            if (info.protectedBroadcasts != null) {
                N = info.protectedBroadcasts.size();
                for (i=0; i<N; i++) {
                    mProtectedBroadcasts.add(info.protectedBroadcasts.get(i));
                }
            }
        }
    }

    private static FastImmutableArraySet<String> getFastIntentCategories(Intent intent) {
        final Set<String> categories = intent.getCategories();
        if (categories == null) {
            return null;
        }
        return new FastImmutableArraySet<String>(categories.toArray(new String[categories.size()]));
    }

    private static final Comparator mResolvePrioritySorter = new Comparator() {
        public int compare(Object o1, Object o2) {
            final int q1 = ((ResolveInfo) o1).filter.getPriority();
            final int q2 = ((ResolveInfo) o2).filter.getPriority();
            return (q1 > q2) ? -1 : ((q1 < q2) ? 1 : 0);
        }
    };


    final class ActivityIntentResolver extends IntentResolver<DynamicApkParser.ActivityIntentInfo>  {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType) {
            if (intent.getComponent() != null &&
                    mActivities.get(intent.getComponent().getClassName()) != null) {
                final List<ResolveInfo> resultList = new ArrayList<>();
                final ResolveInfo res = new ResolveInfo();
                res.activityInfo = mActivities.get(intent.getComponent().getClassName()).info;
                resultList.add(res);
                return resultList;
            } else {
                return queryIntentForFilter(intent, resolvedType);
            }
        }

        public List<ResolveInfo> queryIntentForFilter(Intent intent, String resolvedType) {
            int N = mActivities.size();
            ArrayList<DynamicApkParser.ActivityIntentInfo[]> listCut =
                    new ArrayList<>(N);
            List<DynamicApkParser.ActivityIntentInfo> intentFilters;
            for (DynamicApkParser.Activity activity : mActivities.values()) {
                intentFilters = activity.intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    DynamicApkParser.ActivityIntentInfo[] array =
                            new DynamicApkParser.ActivityIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }

            return queryIntentFromList(intent, resolvedType, listCut);
        }

        public List<ResolveInfo> queryIntentFromList(Intent intent, String resolvedType,
                                               ArrayList<DynamicApkParser.ActivityIntentInfo[]> listCut) {
            ArrayList<ResolveInfo> resultList = new ArrayList();

            FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
            final String scheme = intent.getScheme();
            int N = listCut.size();
            for (int i = 0; i < N; ++i) {
                buildResolveList(intent, categories,
                        resolvedType, scheme, listCut.get(i), resultList);
            }

            sortResults(resultList);
            return resultList;
        }

        public final void addActivity(DynamicApkParser.Activity a, String type) {
            mActivities.put(a.getComponentName().getClassName(), a);
            if (DEBUG_SHOW_INFO)
                Log.v(
                        TAG, "  " + type + " " +
                                (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name) + ":");
            if (DEBUG_SHOW_INFO)
                Log.v(TAG, "    Class=" + a.info.name);
            final int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                DynamicApkParser.ActivityIntentInfo intent = a.intents.get(j);
                if (intent.getPriority() > 0 && "activity".equals(type)) {
                    intent.setPriority(0);
                    Log.w(TAG, "Package " + a.info.applicationInfo.packageName + " has activity "
                            + a.className + " with priority > 0, forcing to 0");
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
            }
        }

        public final void removeActivity(DynamicApkParser.Activity a, String type) {
            mActivities.remove(a.getComponentName().getClassName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + " "
                        + (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel
                        : a.info.name) + ":");
                Log.v(TAG, "    Class=" + a.info.name);
            }
            final int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                DynamicApkParser.ActivityIntentInfo intent = a.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
            }
        }

        @Override
        protected ResolveInfo newResult(DynamicApkParser.ActivityIntentInfo info, int match) {
            final ResolveInfo res = new ResolveInfo();
            res.activityInfo = info.activity.info;

            res.priority = info.getPriority();
            res.match = match;
            res.filter = info;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            return res;
        }

        private final ArrayMap<String, DynamicApkParser.Activity> mActivities = new ArrayMap<>();
    }

    private final class ServiceIntentResolver extends IntentResolver<DynamicApkParser.ServiceIntentInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType) {
            if (intent.getComponent() != null) {
                final List<ResolveInfo> resultList = new ArrayList<>();
                final ResolveInfo res = new ResolveInfo();
                res.serviceInfo = mServices.get(intent.getComponent().getClassName()).info;
                resultList.add(res);
                return resultList;
            } else {
                return queryIntentForFilter(intent, resolvedType);
            }
        }

        public List<ResolveInfo> queryIntentForFilter(Intent intent, String resolvedType) {
            int N = mServices.size();
            ArrayList<DynamicApkParser.ServiceIntentInfo[]> listCut =
                    new ArrayList<>(N);
            List<DynamicApkParser.ServiceIntentInfo> intentFilters;
            for (DynamicApkParser.Service service : mServices.values()) {
                intentFilters = service.intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    DynamicApkParser.ServiceIntentInfo[] array =
                            new DynamicApkParser.ServiceIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }

            return queryIntentFromList(intent, resolvedType, listCut);
        }

        public List<ResolveInfo> queryIntentFromList(Intent intent, String resolvedType,
                                               ArrayList<DynamicApkParser.ServiceIntentInfo[]> listCut) {
            ArrayList<ResolveInfo> resultList = new ArrayList();

            FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
            final String scheme = intent.getScheme();
            int N = listCut.size();
            for (int i = 0; i < N; ++i) {
                buildResolveList(intent, categories,
                        resolvedType, scheme, listCut.get(i), resultList);
            }

            sortResults(resultList);
            return resultList;
        }

        public final void addService(DynamicApkParser.Service s) {
            mServices.put(s.getComponentName().getClassName(), s);
            if (DEBUG_SHOW_INFO)
                Log.v(
                        TAG, "  " +
                                (s.info.nonLocalizedLabel != null ? s.info.nonLocalizedLabel : s.info.name) + ":");
            if (DEBUG_SHOW_INFO)
                Log.v(TAG, "    Class=" + s.info.name);
            final int NI = s.intents.size();
            for (int j=0; j<NI; j++) {
                DynamicApkParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
            }
        }

        public final void removeService(DynamicApkParser.Service s, String type) {
            mServices.remove(s.getComponentName().getClassName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + " "
                        + (s.info.nonLocalizedLabel != null ? s.info.nonLocalizedLabel
                        : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int NI = s.intents.size();
            for (int j=0; j<NI; j++) {
                DynamicApkParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
            }
        }

        protected ResolveInfo newResult(DynamicApkParser.ServiceIntentInfo info, int match) {
            final ResolveInfo res = new ResolveInfo();
            res.serviceInfo = info.service.info;

            res.priority = info.getPriority();
            res.match = match;
            res.filter = info;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            return res;
        }

        private final ArrayMap<String, DynamicApkParser.Service> mServices
                = new ArrayMap<>();
    };

    private final class ProviderIntentResolver extends IntentResolver<DynamicApkParser.ProviderIntentInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType) {
            if (intent.getComponent() != null) {
                final List<ResolveInfo> resultList = new ArrayList<>();
                final ResolveInfo res = new ResolveInfo();
                res.providerInfo = mProviders.get(intent.getComponent().getClassName()).info;
                resultList.add(res);
                return resultList;
            } else {
                return queryIntentForFilter(intent, resolvedType);
            }
        }

        public List<ResolveInfo> queryIntentForFilter(Intent intent, String resolvedType) {
            int N = mProviders.size();
            ArrayList<DynamicApkParser.ProviderIntentInfo[]> listCut =
                    new ArrayList<>(N);
            List<DynamicApkParser.ProviderIntentInfo> intentFilters;
            for (DynamicApkParser.Provider provider : mProviders.values()) {
                intentFilters = provider.intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    DynamicApkParser.ProviderIntentInfo[] array =
                            new DynamicApkParser.ProviderIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }

            return queryIntentFromList(intent, resolvedType, listCut);
        }

        public List<ResolveInfo> queryIntentFromList(Intent intent, String resolvedType,
                                               ArrayList<DynamicApkParser.ProviderIntentInfo[]> listCut) {
            ArrayList<ResolveInfo> resultList = new ArrayList();

            FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
            final String scheme = intent.getScheme();
            int N = listCut.size();
            for (int i = 0; i < N; ++i) {
                buildResolveList(intent, categories,
                        resolvedType, scheme, listCut.get(i), resultList);
            }

            sortResults(resultList);
            return resultList;
        }

        public final void addProvider(DynamicApkParser.Provider p) {

            mProviders.put(p.getComponentName().getClassName(), p);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (p.info.nonLocalizedLabel != null
                        ? p.info.nonLocalizedLabel : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int NI = p.intents.size();
            int j;
            for (j = 0; j < NI; j++) {
                DynamicApkParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
            }
        }

        public final void removeProvider(DynamicApkParser.Provider p) {
            mProviders.remove(p.getComponentName().getClassName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (p.info.nonLocalizedLabel != null
                        ? p.info.nonLocalizedLabel : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int NI = p.intents.size();
            int j;
            for (j = 0; j < NI; j++) {
                DynamicApkParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
            }
        }

        protected ResolveInfo newResult(DynamicApkParser.ProviderIntentInfo info,
                                        int match) {
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = info.provider.info;
            res.filter = info;
            res.priority = info.getPriority();
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            return res;
        }

        private final ArrayMap<String, DynamicApkParser.Provider> mProviders
                = new ArrayMap<>();
    };

    private static String fixProcessName(String defProcessName,
                                         String processName, int uid) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    public static final int INSTALL_SUCCEEDED = 1;
    public static final int INSTALL_FAILED_DUPLICATE_PACKAGE = -10;
    public static final int INSTALL_PARSE_FAILED_NOT_APK = -100;
    public static final int INSTALL_PARSE_FAILED_BAD_MANIFEST = -101;
    public static final int INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION = -102;
    public static final int INSTALL_PARSE_FAILED_NO_CERTIFICATES = -103;
    public static final int INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES = -104;
    public static final int INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING = -105;
    public static final int INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME = -106;
    public static final int INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID = -107;
    public static final int INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108;
    public static final int INSTALL_PARSE_FAILED_MANIFEST_EMPTY = -109;

    public static class DynamicApkManagerException extends Exception {
        public final int error;

        public DynamicApkManagerException(int error, String detailMessage) {
            super(detailMessage);
            this.error = error;
        }

        public DynamicApkManagerException(int error, String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
            this.error = error;
        }
    }

    private abstract class IntentResolver <F extends IntentFilter> {
        protected void buildResolveList(Intent intent, FastImmutableArraySet<String> categories,
                         String resolvedType, String scheme, F[] src, List<ResolveInfo> dest) {
            final String action = intent.getAction();
            final Uri data = intent.getData();

            final int N = src != null ? src.length : 0;
            int i;
            F filter;
            for (i=0; i<N && (filter=src[i]) != null; i++) {
                int match = filter.match(action, resolvedType, scheme, data, categories, TAG);
                if (match >= 0) {
                    final ResolveInfo oneResult = newResult(filter, match);
                    if (oneResult != null) {
                        dest.add(oneResult);
                    }
                }
            }
        }

        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        protected abstract ResolveInfo newResult(F info, int match);
    }
}
