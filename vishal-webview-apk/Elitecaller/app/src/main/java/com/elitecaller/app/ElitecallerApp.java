package com.elitecaller.app;

import android.app.Application;
import android.webkit.WebView;

/**
 * Application entry point for Elitecaller.
 *
 * Enables WebView remote debugging in debug builds only, which is useful
 * when diagnosing issues on the hosted website via chrome://inspect.
 */
public class ElitecallerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }
}
