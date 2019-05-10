package com.dji.Detector;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;



import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.products.HandHeld;
import dji.sdk.sdkmanager.DJISDKManager;

import static dji.thirdparty.v3.eventbus.EventBus.TAG;

public class Insulator_DetectorApplication extends Application {

    public static final String FLAG_CONNECTION_CHANGE = "connection_change";

    private DJISDKManager.SDKManagerCallback mDJISDKManagerCallback;
    //private BaseProduct.BaseProductListener mDJIBaseProductListener;
    private BaseComponent.ComponentListener mDJIComponentListener;
    private static BaseProduct mProduct;
    public Handler mHandler;

    private Application instance;
    private boolean flightControlON = false;
    static boolean isVirtualStickEnabled = false;


    public void setContext(Application application) {
        instance = application;
    }

    @Override
    public Context getApplicationContext() {
        return instance;
    }

    public Insulator_DetectorApplication() {

    }



    /**
     * This function is used to get the instance of DJIBaseProduct.
     * If no product is connected, it returns null.
     */
    public static synchronized BaseProduct getProductInstance() {
        if (null == mProduct) {
            mProduct = DJISDKManager.getInstance().getProduct();
        }
        return mProduct;
    }

    public static synchronized Camera getCameraInstance() {

        if (getProductInstance() == null) return null;

        Camera camera = null;

        if (getProductInstance() instanceof Aircraft) {
            camera = ((Aircraft) getProductInstance()).getCamera();

        } else if (getProductInstance() instanceof HandHeld) {
            camera = ((HandHeld) getProductInstance()).getCamera();
        }

        return camera;
    }
public static synchronized Boolean getVirtualStickState(){
    if (getProductInstance() == null) return null;

    FlightController FC = getAircraftInstance().getFlightController();

FC.getVirtualStickModeEnabled(new CommonCallbacks.CompletionCallbackWith<Boolean>() {
    @Override
    public void onSuccess(Boolean aBoolean) {
        Log.e(TAG, "virtual stick enabled = " + aBoolean);
        isVirtualStickEnabled = aBoolean;
    }

    @Override
    public void onFailure(DJIError djiError) {
        Log.e(TAG, "VIRTUAL STICK ENABLED ERROR");
        Log.e(TAG, djiError.getDescription());
    }
}
);
return isVirtualStickEnabled;
}
    @Override
    public void onCreate() {
        super.onCreate();

        mHandler = new Handler(Looper.getMainLooper());
        mDJIComponentListener = new BaseComponent.ComponentListener() {

            @Override
            public void onConnectivityChange(boolean isConnected) {
                notifyStatusChange();
            }

        };

        /*mDJIBaseProductListener = new BaseProduct.BaseProductListener() {


            @Override
            public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {

                if(newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                }
                notifyStatusChange();
            }

            @Override
            public void onConnectivityChange(boolean isConnected) {

                notifyStatusChange();
            }

        };*/

        /**
         * When starting SDK services, an instance of interface DJISDKManager.DJISDKManagerCallback will be used to listen to
         * the SDK Registration result and the product changing.
         */
        mDJISDKManagerCallback = new DJISDKManager.SDKManagerCallback() {

            //Listens to the SDK registration result
            @Override
            public void onRegister(DJIError error) {

                if (error == DJISDKError.REGISTRATION_SUCCESS) {

                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "Register Success", Toast.LENGTH_LONG).show();
                        }
                    });

                    DJISDKManager.getInstance().startConnectionToProduct();

                } else {

                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {

                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "Register sdk fails, check network is available", Toast.LENGTH_LONG).show();
                        }
                    });

                }
                Log.e("TAG", error.toString());
            }

            @Override
            public void onProductConnect(BaseProduct baseProduct) {
                Log.d(TAG, String.format("onProductConnect newProduct:%s", baseProduct));
                Toast.makeText(getApplicationContext(), "Product Connected", Toast.LENGTH_LONG).show();
                notifyStatusChange();
            }

            @Override
            public void onProductDisconnect() {
                Log.d(TAG, "onProductDisconnect");
                Toast.makeText(getApplicationContext(), "Product Disconnected", Toast.LENGTH_LONG).show();
                notifyStatusChange();
               /* Intent intent = new Intent(getApplicationContext(), ConnectionActivity.class);
                Log.e(TAG, "created intent");
                startActivity(intent);*/
            }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey componentKey,
                                          BaseComponent oldComponent,
                                          BaseComponent newComponent) {
                if (newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                }
                Log.d(TAG,
                        String.format("onComponentChange key:%s, oldComponent:%s, newComponent:%s",
                                componentKey,
                                oldComponent,
                                newComponent));

                notifyStatusChange();
            }

            //Listens to the connected product changing, including two parts, component changing or product connection changing.
           /* @Override
            public void onProductChange(BaseProduct oldProduct, BaseProduct newProduct) {

                mProduct = newProduct;
                if(mProduct != null) {
                    mProduct.setBaseProductListener(mDJIBaseProductListener);
                }

                notifyStatusChange();
            }*/
        };
        //Check the permissions before registering the application for android system 6.0 above.
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int permissionCheck2 = ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (permissionCheck == 0 && permissionCheck2 == 0)) {
            //This is used to start SDK services and initiate SDK.
            DJISDKManager.getInstance().registerApp(getApplicationContext(), mDJISDKManagerCallback);
            Toast.makeText(getApplicationContext(), "registering, pls wait...", Toast.LENGTH_LONG).show();

        } else {
            Toast.makeText(getApplicationContext(), "Please check if the permission is granted.", Toast.LENGTH_LONG).show();
        }
    }

    public static boolean isAircraftConnected() {
        return getProductInstance() != null && getProductInstance() instanceof Aircraft;
    }
    public static synchronized Aircraft getAircraftInstance() {
        if (!isAircraftConnected()) return null;
        return (Aircraft) getProductInstance();
    }
    private void notifyStatusChange() {
        mHandler.removeCallbacks(updateRunnable);
        mHandler.postDelayed(updateRunnable, 500);
    }

    private Runnable updateRunnable = new Runnable() {

        @Override
        public void run() {
            Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
            getApplicationContext().sendBroadcast(intent);
        }
    };

}