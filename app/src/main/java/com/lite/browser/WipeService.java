package com.lite.browser;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.io.File;

/**
 * Wipes browsing data when the app is swiped away from recent apps
 * (onDestroy is not guaranteed to run in that case), then ends the process.
 */
public class WipeService extends Service {

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        wipe(this, null);
        deleteContents(getCacheDir());
        deleteContents(getExternalCacheDir());
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!MainActivity.alive) Process.killProcess(Process.myPid());
        }, 1200);
    }

    /** Cookies, DOM storage, HTTP cache, history, form data, permissions. Must run on the main thread. */
    static void wipe(Context c, WebView wv) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            GeolocationPermissions.getInstance().clearAll();
            WebView w = wv != null ? wv : new WebView(c.getApplicationContext());
            w.clearCache(true);
            w.clearHistory();
            w.clearFormData();
        } catch (Throwable ignored) { }
    }

    static void deleteContents(File dir) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) deleteContents(f);
            f.delete();
        }
    }
}
