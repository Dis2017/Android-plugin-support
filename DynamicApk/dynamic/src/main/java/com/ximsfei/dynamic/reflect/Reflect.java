package com.ximsfei.dynamic.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Created by pengfenx on 3/1/2016.
 */
public class Reflect {

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
            throw new RuntimeException("Class Not Found!");
        }
    }

    public Reflect setField(Field f) {
        if (mClass == null) {
            throw new RuntimeException("mClass can not be null !");
        }

        mField = f;
        return this;
    }

    public Reflect setField(String name) {
        Class c = mClass;
        if (c == null) {
            throw new RuntimeException("mClass can not be null !");
        }

        do {
            try {
                mField = c.getDeclaredField(name);
                mField.setAccessible(true);
                break;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        } while (c != null);
        return this;
    }

    public Reflect setMethod(Method m) {
        if (mClass == null) {
            throw new RuntimeException("mClass can not be null !");
        }

        mMethod = m;
        return this;
    }

    public Reflect setMethod(String name, Class... parameterTypes) {
        Class c = mClass;
        if (c == null) {
            throw new RuntimeException("mClass can not be null !");
        }

        do {
            try {
                mMethod = c.getDeclaredMethod(name, parameterTypes);
                mMethod.setAccessible(true);
                break;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        } while (c != null);
        return this;
    }

    public <T> T get(Object o) throws ReflectException {
        if (mField == null) {
            throw new RuntimeException("You must invoke setMethod() first!");
        }
        try {
            return (T) mField.get(o);
        } catch (IllegalAccessException e) {
        }
        throw new ReflectException("Illegal Access Exception!");
    }

    public void set(Object o, Object o1) {
        if (mField == null) {
            throw new RuntimeException("You must invoke setField() first!");
        }
        try {
            mField.set(o, o1);
        } catch (IllegalAccessException e) {
        }
    }

    public Object invoke(Object o, Object... o2) throws ReflectException {
        if (mMethod == null) {
            throw new RuntimeException("You must invoke setMethod() first!");
        }
        try {
            return mMethod.invoke(o, o2);
        } catch (IllegalAccessException e) {
            throw new ReflectException("Illegal Access Exception!");
        } catch (InvocationTargetException e) {
            throw new ReflectException("Invocation Target Exception!");
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
