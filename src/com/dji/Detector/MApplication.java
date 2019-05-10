package com.dji.Detector;

import android.app.Application;
import android.content.Context;

import com.secneo.sdk.Helper;
import com.squareup.leakcanary.LeakCanary;

public class MApplication extends Application {
    private Insulator_DetectorApplication insulator_detectorApplication;

    @Override
    protected void attachBaseContext(Context paramContext) {
        super.attachBaseContext(paramContext);
        Helper.install(MApplication.this);
        if (insulator_detectorApplication == null) {
            insulator_detectorApplication = new Insulator_DetectorApplication();
            insulator_detectorApplication.setContext(this);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (LeakCanary.isInAnalyzerProcess(this)) {
            // This process is dedicated to LeakCanary for heap analysis.
            // You should not init your app in this process.
            return;
        }
        LeakCanary.install(this);
        insulator_detectorApplication.onCreate();
    }
}