package com.dji.Detector;

import android.util.Log;

import java.util.TimerTask;

import dji.common.error.DJIError;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.util.CommonCallbacks;
import dji.keysdk.DJIKey;
import dji.keysdk.FlightControllerKey;
import dji.keysdk.KeyManager;
import dji.sdk.flightcontroller.FlightController;

import static dji.thirdparty.v3.eventbus.EventBus.TAG;

public class SendVirtualStickDataTask extends TimerTask {

    private FlightController mFlightController;

    private float mPitch;
    private float mRoll;
    private float mYaw;
    private float mThrottle;
private CommonCallbacks.CompletionCallbackWith<Boolean> mCallback;
private boolean isVirtualStickEnabled;
    public SendVirtualStickDataTask() {
        mPitch = 0;
        mRoll = 0;
        mThrottle = 0;
        mYaw = 0;
        mFlightController = Insulator_DetectorApplication.getAircraftInstance().getFlightController();
        mFlightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        mFlightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        mFlightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
        mFlightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
        mCallback = new CommonCallbacks.CompletionCallbackWith<Boolean>() {
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
        };
    }
        public void setmYaw(float YAW){mYaw = YAW;}
        public void setmThrottle(float THROTTLE){mThrottle = THROTTLE;}

        public float getmYaw(){return mYaw;}
        public float  getmThrottle(){return mThrottle;}
    @Override
    public void run() {

        if (mFlightController != null) {
         //   mFlightController.getVirtualStickModeEnabled(mCallback);
            DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);
            if(KeyManager.getInstance().getValue(temp)!=null) {
                isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);
                if (isVirtualStickEnabled) {
                    mFlightController.sendVirtualStickFlightControlData(
                            new FlightControlData(
                                    mPitch, mRoll, mYaw, mThrottle
                            ), new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError != null) {
                                        Log.e("DJI VIRTUAL STICK", "djiError");
                                        Log.e("DJI VIRTUAL STICK", djiError.getDescription());
                                    }
                                }
                            }
                    );

                }
            }
        }
        else{
            Log.e("VIRTUAL STICK ERROR","mFlightController NULL");
        }
    }

}
