package com.ximsfei.dynamic.app;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ProviderInfo;

import com.ximsfei.dynamic.pm.DynamicApkParser;
import com.ximsfei.dynamic.reflect.Reflect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by pengfenx on 3/2/2016.
 */
public class DynamicActivityThread {

    private static DynamicActivityThread sDynamicActivityThread;
    private Reflect mActivityThreadReflect;
    private Object mActivityThread;
    private Application mInitialApplication;
    private Instrumentation mInstrumentation;
    private Map mPackages;

    private DynamicActivityThread() {
        mActivityThreadReflect = Reflect.create("android.app.ActivityThread");
    }

    public synchronized static DynamicActivityThread getInstance() {
        if (sDynamicActivityThread == null) {
            sDynamicActivityThread = new DynamicActivityThread();
        }
        return sDynamicActivityThread;
    }

    public synchronized ClassLoader getClassLoader() {
        Object loadedApk = ((WeakReference) getPackages().get(getHostPackageName())).get();
        try {
            return (ClassLoader) Reflect.create(loadedApk.getClass())
                    .setMethod("getClassLoader").invoke(loadedApk);
        } catch (Exception e) {
        }
        return null;
    }

    public synchronized void installClassLoader(ClassLoader classLoader) {
        Object loadedApk = ((WeakReference) getPackages().get(getHostPackageName())).get();
        try {
            ClassLoader cl = (ClassLoader) Reflect.create(loadedApk.getClass())
                    .setMethod("getClassLoader").invoke(loadedApk);
            if (!(cl instanceof DynamicClassLoaderWrapper)) {
                DynamicClassLoaderWrapper dclw = new DynamicClassLoaderWrapper(cl);
                dclw.addClassLoader(classLoader);
                Reflect.create(loadedApk.getClass()).setField("mClassLoader")
                        .set(loadedApk, dclw);
                updateLoadedApk(loadedApk);
            } else {
                ((DynamicClassLoaderWrapper) cl).addClassLoader(classLoader);
            }
        } catch (Exception e) {
        }
    }

    private void updateLoadedApk(Object loadedApk) {
        getPackages().put(getInitialApplication().getPackageName(),
                new WeakReference<>(loadedApk));
    }

    public synchronized String getHostPackageName() {
        return getInitialApplication().getPackageName();
    }

    private synchronized Map getPackages() {
        if (mPackages == null) {
            try {
                mPackages = mActivityThreadReflect.setField("mPackages").get(currentActivityThread());
            } catch (Exception e) {
            }
        }
        return mPackages;
    }

    public synchronized void installContentProviders(List<DynamicApkParser.Provider> providers) {
        try {
            mActivityThreadReflect.setMethod("installContentProviders", Context.class, List.class)
                    .invoke(currentActivityThread(), getInitialApplication(),
                            generateProviderInfos(providers));
        } catch (Exception e) {
        }
    }

    private List<ProviderInfo> generateProviderInfos(List<DynamicApkParser.Provider> providers) {
        List<ProviderInfo> providerInfos = new ArrayList<>();
        for (DynamicApkParser.Provider p : providers) {
            p.info.packageName = getHostPackageName();
            p.info.applicationInfo.packageName = getHostPackageName();
            providerInfos.add(p.info);
        }
        return providerInfos;
    }

    public synchronized Object currentActivityThread() {
        if (mActivityThread == null) {
            try {
                mActivityThread = mActivityThreadReflect.setMethod("currentActivityThread").invoke(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return mActivityThread;
    }

    public synchronized Application getInitialApplication() {
        if (mInitialApplication == null) {
            try {
                mInitialApplication = mActivityThreadReflect.setField("mInitialApplication")
                        .get(currentActivityThread());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return mInitialApplication;
    }

    public synchronized Instrumentation getInstrumentation() {
        if (mInstrumentation == null) {
            try {
                mInstrumentation = mActivityThreadReflect.setField("mInstrumentation")
                        .get(currentActivityThread());
            } catch (Exception e) {
            }
        }
        return mInstrumentation;
    }

    public synchronized void setInstrumentation(Instrumentation instrumentation) {
        mActivityThreadReflect.setField("mInstrumentation").set(
                currentActivityThread(), instrumentation);
        mInstrumentation = instrumentation;
    }
}
