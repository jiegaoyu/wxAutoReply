package com.example.autoreply;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;

public class AppLifecycleLogger implements Application.ActivityLifecycleCallbacks {
    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new AppLifecycleLogger());
        XposedBridge.log(MainHook.TAG + " ActivityLifecycleLogger registered");
    }

    @Override public void onActivityCreated(Activity a, Bundle b)  { XposedBridge.log(MainHook.TAG + " ACT created  " + a.getClass().getName()); }
    @Override public void onActivityStarted(Activity a)            { XposedBridge.log(MainHook.TAG + " ACT started  " + a.getClass().getName()); }
    @Override public void onActivityResumed(Activity a)            { XposedBridge.log(MainHook.TAG + " ACT resumed  " + a.getClass().getName()); }
    @Override public void onActivityPaused(Activity a)             { }
    @Override public void onActivityStopped(Activity a)            { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a)          { }
}

