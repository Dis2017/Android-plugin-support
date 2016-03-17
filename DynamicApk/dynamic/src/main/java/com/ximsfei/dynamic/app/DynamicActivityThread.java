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
    private Reflect mReflect;
    private Application mInitialApplication;
    private Map mPackages;
    private Object mActivityThread;
    private Instrumentation mInstrumentation;
    private Map mServices;

    private DynamicActivityThread() {
        mReflect = Reflect.create("android.app.ActivityThread");
    }

    public synchronized static DynamicActivityThread getInstance() {
        if (sDynamicActivityThread == null) {
            sDynamicActivityThread = new DynamicActivityThread();
        }
        return sDynamicActivityThread;
    }

    public synchronized void installClassLoader(ClassLoader classLoader) {
        Object loadedApk = ((WeakReference)
                getPackages().get(getInitialApplication().getPackageName())).get();
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
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
        }
    }

    public synchronized ClassLoader getClassLoader() {
        Object loadedApk = ((WeakReference)
                getPackages().get(getInitialApplication().getPackageName())).get();
        try {
            return (ClassLoader) Reflect.create(loadedApk.getClass())
                    .setMethod("getClassLoader").invoke(loadedApk);
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void updateLoadedApk(Object loadedApk) {
        getPackages().put(getInitialApplication().getPackageName(),
                new WeakReference<>(loadedApk));
    }

    public synchronized Object currentActivityThread() {
        if (mActivityThread == null) {
            try {
                mActivityThread = mReflect.setMethod("currentActivityThread").invoke(null);
            } catch (Reflect.ReflectException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        return mActivityThread;
    }

    public synchronized Application getInitialApplication() {
        if (mInitialApplication == null) {
            try {
                mInitialApplication = mReflect.setField("mInitialApplication")
                        .get(currentActivityThread());
            } catch (Reflect.ReflectException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        return mInitialApplication;
    }

    public synchronized String getHostPackageName() {
        return getInitialApplication().getPackageName();
    }

    private synchronized Map getPackages() {
        if (mPackages == null) {
            try {
                mPackages = mReflect.setField("mPackages").get(currentActivityThread());
            } catch (Reflect.ReflectException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        return mPackages;
    }

    private synchronized Map getServices() {
        if (mServices == null) {
            try {
                mServices = mReflect.setField("mServices").get(currentActivityThread());
            } catch (Reflect.ReflectException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        return mServices;
    }

    public synchronized void installContentProviders(List<DynamicApkParser.Provider> providers) {
        try {
            mReflect.setMethod("installContentProviders", Context.class, List.class)
                    .invoke(currentActivityThread(), getInitialApplication(),
                            generateProviderInfos(providers));
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
        }
    }

    private List<ProviderInfo> generateProviderInfos(List<DynamicApkParser.Provider> providers) {
        List<ProviderInfo> providerInfos = new ArrayList<>();
        for (DynamicApkParser.Provider p : providers) {
            p.info.packageName = getInitialApplication().getPackageName();
            p.info.applicationInfo.packageName = getInitialApplication().getPackageName();
            providerInfos.add(p.info);
        }
        return providerInfos;
    }

    public synchronized Instrumentation getInstrumentation() {
        if (mInstrumentation == null) {
            try {
                mInstrumentation = mReflect.setField("mInstrumentation").get(currentActivityThread());
            } catch (Reflect.ReflectException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        return mInstrumentation;
    }

    public synchronized void setInstrumentation(Instrumentation instrumentation) {
        try {
            mReflect.setField("mInstrumentation").set(currentActivityThread(), instrumentation);
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }
}
