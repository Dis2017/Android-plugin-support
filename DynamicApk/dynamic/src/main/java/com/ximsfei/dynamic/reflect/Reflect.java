package com.ximsfei.dynamic.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Created by pengfenx on 3/1/2016.
 */
public class Reflect {

    private final Class mClass;
    private Field mField;
    private Method mMethod;

    private Reflect(Class c) {
        mClass = c;
    }

    private Reflect(String name) {
        try {
            mClass = Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Reflect create(Class c) {
        return new Reflect(c);
    }

    public static Reflect create(String name) {
        return new Reflect(name);
    }

    public Reflect setField(String name) {
        Class c = mClass;
        do {
            try {
                mField = c.getDeclaredField(name);
                mField.setAccessible(true);
                break;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        } while (c != null);

        if (mField == null) throw new RuntimeException("No Such Field!");
        return this;
    }

    public Reflect setMethod(String name, Class... parameterTypes) {
        Class c = mClass;
        do {
            try {
                mMethod = c.getDeclaredMethod(name, parameterTypes);
                mMethod.setAccessible(true);
                break;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        } while (c != null);

        if (mMethod == null) throw new RuntimeException("No Such Method!");
        return this;
    }

    public <T> T get(Object o) {
        try {
            return (T) mField.get(o);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void set(Object o, Object o1) {
        try {
            mField.set(o, o1);
        } catch (IllegalAccessException e) {
        }
    }

    public <T> T invoke(Object o, Object... o2) {
        try {
            return (T) mMethod.invoke(o, o2);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public Constructor getConstructor(Class... parameterTypes) {
        try {
            return mClass.getConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("No Such Constructor!");
        }
    }
}
