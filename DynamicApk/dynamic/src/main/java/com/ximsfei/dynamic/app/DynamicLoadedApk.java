package com.ximsfei.dynamic.app;

import android.app.Application;
import android.content.Context;

import com.ximsfei.dynamic.pm.DynamicApkInfo;
import com.ximsfei.dynamic.reflect.Reflect;

/**
 * Created by pengfenx on 3/18/2016.
 */
public class DynamicLoadedApk {

    public static Application makeApplication(Context applicationContext, ClassLoader cl,
                                              DynamicApkInfo apkInfo) {
        Application app = null;

        String appClass = apkInfo.applicationInfo.className;
        if (appClass == null) {
            appClass = "android.app.Application";
        }

        try {
            DynamicContextImpl appContext = DynamicContextImpl.createApplicationContext(
                    applicationContext, apkInfo);
            app = (Application) cl.loadClass(appClass).newInstance();
            Reflect.create(Application.class).setMethod("attach",
                    Context.class).invoke(app, appContext);
            DynamicActivityThread.getInstance().getInstrumentation().callApplicationOnCreate(app);
        } catch (Exception e) {
        }

        return app;
    }
}
