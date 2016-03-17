package com.ximsfei.dynamic.app;

import java.util.ArrayList;

/**
 * Created by pengfenx on 3/15/2016.
 */
public class DynamicClassLoaderWrapper extends ClassLoader {
    private final ClassLoader mBase;
    private final ArrayList<ClassLoader> mDynamicLoaders = new ArrayList<>();

    protected DynamicClassLoaderWrapper(ClassLoader base) {
        super();
        mBase = base;
    }

    public void addClassLoader(ClassLoader cl) {
        if (!mDynamicLoaders.contains(cl)) {
            mDynamicLoaders.add(cl);
        }
    }

    @Override
    protected Class<?> findClass(String className) throws ClassNotFoundException {
        try {
            return mBase.loadClass(className);
        } catch (ClassNotFoundException e) {
        }
        int N = mDynamicLoaders.size();
        for (int i=0; i<N; i++) {
            try {
                return mDynamicLoaders.get(i).loadClass(className);
            } catch (ClassNotFoundException e) {
            }
        }
        throw new ClassNotFoundException(className);
    }

}
