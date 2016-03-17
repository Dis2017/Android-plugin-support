package com.ximsfei.dynamic.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Created by pengfenx on 3/1/2016.
 */
public class Reflect {

    private static final String TAG = "Reflect";

    public static class ReflectException extends Exception {
        ReflectException(String info) {
            super(info);
        }
    }

    private Class mClass;
    private Field mField;
    private Method mMethod;

    public static Reflect create(Class c) {
        Reflect reflect = new Reflect();
        reflect.setClass(c);
        return reflect;
    }

    public static Reflect create(String name) {
        Reflect reflect = new Reflect();
        reflect.setClass(name);
        return reflect;
    }

    private Reflect() {
    }

    private void setClass(Class c) {
        mClass = c;
    }

    private Reflect setClass(String name) {
        try {
            mClass = Class.forName(name);
            return this;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new IllegalStateException("Class Not Found!");
        }
    }

    public Reflect setField(Field f) throws ReflectException {
        Class c = mClass;
        if (c == null) {
            throw new ReflectException("You must invoke setClass() first!");
        }

        mField = f;
        return this;
    }

    public Reflect setField(String name) throws ReflectException {
        Class c = mClass;
        if (c == null) {
            throw new ReflectException("You must invoke setClass() first!");
        }

        do {
            try {
                mField = c.getDeclaredField(name);
                mField.setAccessible(true);
                break;
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
                c = c.getSuperclass();
            }
        } while (c != null);
        return this;
    }

    public Reflect setMethod(Method m) throws ReflectException {
        Class c = mClass;
        if (c == null) {
            throw new ReflectException("You must invoke setClass() first!");
        }

        mMethod = m;
        return this;
    }

    public Reflect setMethod(String name, Class... parameterTypes) throws ReflectException {
        Class c = mClass;
        if (c == null) {
            throw new ReflectException("You must invoke setClass() first!");
        }

        do {
            try {
                mMethod = c.getDeclaredMethod(name, parameterTypes);
                mMethod.setAccessible(true);
                break;
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
                c = c.getSuperclass();
            }
        } while (c != null);
        return this;
    }

    public <T> T get(Object o) throws ReflectException {
        if (mField == null) {
            throw new ReflectException("You must invoke setMethod() first!");
        }
        try {
            return (T) mField.get(o);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        throw new ReflectException("Illegal Access Exception!");
    }

    public void set(Object o, Object o1) throws ReflectException {
        if (mField == null) {
            throw new ReflectException("You must invoke setField() first!");
        }
        try {
            mField.set(o, o1);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public Object invoke(Object o, Object... o2) throws ReflectException {
        if (mMethod == null) {
            throw new ReflectException("You must invoke setMethod() first!");
        }
        try {
            return mMethod.invoke(o, o2);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            throw new ReflectException("Illegal Access Exception!");
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            throw new ReflectException("Invocation Target Exception!");
        }
    }

    public Constructor getConstructor(Class... parameterTypes) {
        try {
            return mClass.getConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
            throw new IllegalStateException("No Such Constructor!");
        }
    }
}
