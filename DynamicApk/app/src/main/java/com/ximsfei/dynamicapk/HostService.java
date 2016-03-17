package com.ximsfei.dynamicapk;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class HostService extends Service {
    public HostService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("host-plugin", "HostService = " + HostService.class.getSimpleName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("host-plugin", "HostService onStartCommand = " + HostService.class.getSimpleName());
        return super.onStartCommand(intent, flags, startId);
    }
}
