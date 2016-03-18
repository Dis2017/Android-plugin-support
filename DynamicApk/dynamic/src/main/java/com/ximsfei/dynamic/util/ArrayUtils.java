package com.ximsfei.dynamic.util;

import java.util.ArrayList;

/**
 * Created by pengfenx on 3/2/2016.
 */
public class ArrayUtils {

    private ArrayUtils() { /* cannot be instantiated */ }

    public static <T> ArrayList<T> add(ArrayList<T> cur, T val) {
        if (cur == null) {
            cur = new ArrayList<>();
        }
        cur.add(val);
        return cur;
    }

    public static <T> ArrayList<T> remove(ArrayList<T> cur, T val) {
        if (cur == null) {
            return null;
        }
        cur.remove(val);
        if (cur.isEmpty()) {
            return null;
        } else {
            return cur;
        }
    }

    public static <T> boolean contains(ArrayList<T> cur, T val) {
        return (cur != null) ? cur.contains(val) : false;
    }
}
