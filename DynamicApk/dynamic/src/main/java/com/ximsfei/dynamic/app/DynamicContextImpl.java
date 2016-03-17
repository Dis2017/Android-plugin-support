package com.ximsfei.dynamic.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.LayoutInflater;

import com.ximsfei.dynamic.pm.DynamicApkInfo;

/**
 * Created by pengfenx on 3/2/2016.
 */
public class DynamicContextImpl extends ContextWrapper {
    private final Resources mResources;
    private final AssetManager mAssets;
    private final ClassLoader mClassLoader;
    private final String mPackageName;

    public DynamicContextImpl(Context hostContext, DynamicApkInfo apkInfo) {
        super(hostContext);
        mResources = apkInfo.resources;
        mAssets = apkInfo.assets;
        mClassLoader = apkInfo.classLoader;
        mPackageName = apkInfo.packageName;
    }

    private LayoutInflater mInflater;

    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (mInflater == null) {
                mInflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
            }
            return mInflater;
        }
        return super.getSystemService(name);
    }

    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public AssetManager getAssets() {
        return mAssets;
    }

    @Override
    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    @Override
    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public ComponentName startService(Intent service) {
        checkServiceIntent(service);
        return super.startService(service);
    }

    @Override
    public boolean stopService(Intent service) {
        checkServiceIntent(service);
        return super.stopService(service);
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        checkServiceIntent(service);
        return super.bindService(service, conn, flags);
    }

    private void checkServiceIntent(Intent service) {
        if (!service.getComponent().getPackageName().equals(
                DynamicActivityThread.getInstance().getHostPackageName())) {
            service.setClassName(DynamicActivityThread.getInstance().getHostPackageName(),
                    service.getComponent().getClassName());
        }
    }
}
