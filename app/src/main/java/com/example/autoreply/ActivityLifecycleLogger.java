package com.example.autoreply;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;

public class ActivityLifecycleLogger {

    public static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle bundle) {
                XposedBridge.log(MainHook.TAG + " ACT created  " + activity.getClass().getName());
            }
            @Override public void onActivityStarted(Activity activity) {
                XposedBridge.log(MainHook.TAG + " ACT started  " + activity.getClass().getName());
            }
            @Override public void onActivityResumed(Activity activity) {
                XposedBridge.log(MainHook.TAG + " ACT resumed  " + activity.getClass().getName());
            }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle bundle) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
}

