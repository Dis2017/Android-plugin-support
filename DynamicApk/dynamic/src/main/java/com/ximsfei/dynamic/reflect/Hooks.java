package com.ximsfei.dynamic.reflect;

import android.content.res.AssetManager;

/**
 * Created by pengfenx on 3/3/2016.
 */
public class Hooks {
    public static int[] getStyleableArray(String name) {
        try {
            return (int[]) Reflect.create("com.android.internal.R$styleable")
                    .setField(name).get(null);
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
        }
        return new int[0];
    }

    public static int getStyleable(String name) {
        try {
            return (int) Reflect.create("com.android.internal.R$styleable")
                    .setField(name).get(null);
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static int addAssetPath(AssetManager assets, String apkPath) {
        try {
            return (int) Reflect.create(AssetManager.class)
                    .setMethod("addAssetPath", String.class).invoke(assets, apkPath);
        } catch (Reflect.ReflectException e) {
            e.printStackTrace();
        }
        return 0;
    }
}
