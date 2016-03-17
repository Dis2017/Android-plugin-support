package com.ximsfei.dynamicapk;

import android.app.Application;
import android.content.Context;

import com.ximsfei.dynamic.DynamicApkManager;

/**
 * Created by pengfenx on 2/25/2016.
 */
public class DynamicApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        DynamicApkManager.init(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
