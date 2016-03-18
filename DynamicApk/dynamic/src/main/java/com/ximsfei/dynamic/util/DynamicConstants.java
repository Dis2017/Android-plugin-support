package com.ximsfei.dynamic.util;

/**
 * Created by pengfenx on 3/2/2016.
 */
public interface DynamicConstants {
    String PRIVATE_OPTIMIZED_DEX_DIR = "dynamic_dex";
    String PRIVATE_OPTIMIZED_LIB_DIR = "dynamic_lib";

    String DYNAMIC_CURRENT_PACKAGE = "dynamic_current_package";
    String DYNAMIC_ACTIVITY_FLAG = "dynamic_activity_flag";

    String STUB_DYNAMIC_ACTIVITY = "com.ximsfei.dynamic.DynamicActivity";
    int RESOLVE_ACTIVITY = 1;
    int RESOLVE_RECEIVER = 2;
    int RESOLVE_SERVICE = 3;
    int RESOLVE_PROVIDER = 4;
}
