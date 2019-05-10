package com.dji.Detector;

/*
* Elisa Tengan
* LSI-TEC
* 2018
*/
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import dji.common.airlink.LightbridgeDataRate;
import dji.common.airlink.LightbridgeTransmissionMode;
import dji.common.error.DJIError;

import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;

import dji.common.util.CommonCallbacks;
import dji.keysdk.DJIKey;
import dji.keysdk.FlightControllerKey;
import dji.keysdk.KeyManager;
import dji.sdk.airlink.AirLink;
import dji.sdk.airlink.LightbridgeLink;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;

import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;
import dji.sdk.sdkmanager.DJISDKManager;

import com.dji.Detector.Insulator_DetectorApplication;



public class ConnectionActivity extends Activity implements View.OnClickListener {

    private static final String TAG = ConnectionActivity.class.getName();

    private FlightController mFlightController;
    protected TextView mConnectStatusTextView;

Boolean debug=false;
    private ToggleButton mBtnFlightControl;
    private Button mBtnOpenCamera;
    private Button mBtnTakeOff;
    private Button mBtnLand;
    private Button mBtnMediaManager;
    private Boolean isVirtualStickEnabled = false;
    private LightbridgeLink lightbridgeLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.e(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_connection);

        // Initialize Buttons
        initUI();

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Insulator_DetectorApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        // Update Title Bar indicating connection status
        updateTitleBar();

        // Initialize Flight Controller
        initFlightController();

        // Set FlightControl Button
        setButton();

        // Setting isVirtualStickEnabled variable
        if(mFlightController!=null){
        DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);
        isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);
        showToast("isVirtualStickEnabled = " + isVirtualStickEnabled);
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");

        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");

        super.onStop();
    }

    public void onReturn(View view) {
        Log.e(TAG, "onReturn");

        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }

        super.onDestroy();
    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            updateTitleBar();

            BaseProduct product = Insulator_DetectorApplication.getProductInstance();
            if (product != null) {
                Log.e(TAG, "PRODUCT NOT NULL");
            }
            initFlightController();
            mBtnFlightControl.setChecked(false);
        }

    };

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateTitleBar() {
        // Code took from DJI SDK Tutorial Samples

        if (mConnectStatusTextView == null) return;

        boolean ret = false;

        BaseProduct product = Insulator_DetectorApplication.getProductInstance();

        if (product != null) {
            if (product.isConnected()) {
                //The product is connected
                mConnectStatusTextView.setText(product.getModel() + " Connected");
                ret = true;
            } else {
                if (product instanceof Aircraft) {
                    Aircraft aircraft = (Aircraft) product;
                    RemoteController controller = aircraft.getRemoteController();
                    if (controller != null && controller.isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        mConnectStatusTextView.setText("only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if (!ret) {
            // The product or the remote controller are not connected.
            mConnectStatusTextView.setText("Disconnected");
        }
        product = null; // here I was trying to remove memory leak problems but I guess it is useless...
    }

    private void setButton() {
        if (isVirtualStickEnabled) {
            Log.e(TAG, "virtual stick enabled 2= " + isVirtualStickEnabled);
            mBtnFlightControl.setChecked(true);
        } else {
            mBtnFlightControl.setChecked(false);
        }

    }

    private void initFlightController() {
        // Initialize Flight Controller from aircraft
        Aircraft aircraft = Insulator_DetectorApplication.getAircraftInstance();

        if (aircraft == null || !aircraft.isConnected()) {
            // showToast("Disconnected");
            // No aircraft found
            mFlightController = null;
            return;
        } else {
            // Get Flight Controller
            mFlightController = aircraft.getFlightController();
            if (mFlightController != null) {
                // Set control mode
                mFlightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                mFlightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                mFlightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
                mFlightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

            }

            if (mFlightController != null) {
                // Check if Virtual Stick Mode is already enabled
                DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);
                isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);

                try {
                    // Try to change configs in Lightbridge Link (in case there is one) in order to lower video quality and lower video latency
                    BaseProduct product = Insulator_DetectorApplication.getProductInstance();
                    if (product != null) {
                        AirLink link = product.getAirLink();
                        if (link != null) {
                            lightbridgeLink = link.getLightbridgeLink();
                            if (lightbridgeLink != null) {
                                lightbridgeLink.setDataRate(LightbridgeDataRate.BANDWIDTH_4_MBPS, new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            Log.e(TAG, djiError.getDescription());
                                        }
                                    }
                                });
                                lightbridgeLink.setTransmissionMode(LightbridgeTransmissionMode.LOW_LATENCY, new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            Log.e(TAG, djiError.getDescription());
                                        }
                                    }
                                });
                            } else {
                                Log.e(TAG, "NO LIGHTBRIDGE LINK");
                            }
                        }

                    }

                } catch (Exception e) {
                    if (e != null) {
                        Log.e(TAG, e.toString());
                    }
                }

            }


        }

    }

    private void initUI() {

        // Initialize Buttons
        mBtnOpenCamera = (Button) findViewById(R.id.btn_open_camera);
        mBtnTakeOff = (Button) findViewById(R.id.btn_take_off);
        mBtnLand = (Button) findViewById(R.id.btn_land);
        mBtnFlightControl = (ToggleButton) findViewById(R.id.btn_flight_control);
        mBtnMediaManager = (Button) findViewById(R.id.btn_media_manager);
        mConnectStatusTextView = (TextView) findViewById(R.id.ConnectStatusTextView);


        // Set Buttons listeners
        mBtnOpenCamera.setOnClickListener(this);
        mBtnTakeOff.setOnClickListener(this);
        mBtnLand.setOnClickListener(this);
        mBtnMediaManager.setOnClickListener(this);


        // Set special button listener for Toggle Button mBtnFlightControl
        mBtnFlightControl.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { /////////////////////
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    //Button was pressed to enable Virtual Stick Mode and Button goes from "Turn Flight Control ON" to "Turn Flight Control OFF"

                    if (mFlightController != null) {
                        if (!isVirtualStickEnabled) {
                            // Virtual Stick Mode getting enabled
                            mFlightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError != null) {
                                        showToast("Failed to turn Flight Control ON. Error: " + djiError.getDescription());

                                    } else {
                                        showToast("FLIGHT CONTROL TURNED ***ON***");
                                        //   isVirtualStickEnabled = true;
                                    }
                                }
                            });
                        }

                    } else {
                        mBtnFlightControl.setChecked(false);
                        // isVirtualStickEnabled = false;
                        showToast("FlightController NULL");
                    }

                } else {
                    //Button was pressed to disable Virtual Stick Mode and Button goes from "Turn Flight Control OFF" to "Turn Flight Control ON"

                    if (mFlightController != null) {
                        if (isVirtualStickEnabled) {
                            // Virtual Stick Mode getting disabled
                            mFlightController.setVirtualStickModeEnabled(false, new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError != null) {
                                        showToast("Failed to turn Flight Control OFF. Error: "  + djiError.getDescription());

                                    } else {
                                        showToast("FLIGHT CONTROL TURNED ...OFF...");
                                        //  isVirtualStickEnabled = false;
                                    }
                                }
                            });
                        }
                    } else {
                        // isVirtualStickEnabled= false;
                        showToast("FlightController NULL");
                    }
                }
            }
        });

    }


    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_open_camera:
                // Button to open camera, starting Detector Activity
                if (!debug) {
                    if (mFlightController != null) {

                        Intent intent = new Intent(this, DetectorActivity.class);
                        Log.e(TAG, "created intent");
                        startActivity(intent);
                        Log.e(TAG, "started detector activity");

                    } else {
                        showToast("FlightController NULL");
                    }
                } else {
                    Intent intent = new Intent(this, DetectActivity.class);
                    Log.e(TAG, "created intent");
                    startActivity(intent);
                    Log.e(TAG, "started detector activity");
                }

                break;


            case R.id.btn_take_off:
                // Button to take off. Take off instruction does not really need Virtual Stick Control Mode to be enabled but it is left as a safety measure for now
                if (mFlightController != null) {
                    DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);

                    isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);
                    if (isVirtualStickEnabled) {
                        mFlightController.startTakeoff(
                                new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            showToast("take off btn Error: " + djiError.getDescription());
                                        } else {
                                            showToast("Take Off Success");
                                        }
                                    }
                                }
                        );
                    } else {
                        showToast("FlightControl is OFF!!!!");
                    }
                } else {
                    showToast("FlightController NULL");
                }
                break;

            case R.id.btn_land:
                // Button to land. Land instruction does not really need Virtual Stick Control Mode to be enabled but it is left as a safety measure for now
                if (mFlightController != null) {
                    DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);

                    isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);
                    if (isVirtualStickEnabled) {
                        mFlightController.startLanding(
                                new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            showToast("land btn Error: " + djiError.getDescription());
                                        } else {
                                            showToast("Start Landing");
                                        }
                                    }
                                }
                        );
                    } else {
                        showToast("FlightControl is OFF!!!!");
                    }

                } else {
                    showToast("FlightController NULL");
                }
                break;
            case R.id.btn_media_manager:
                // Button to open media manager. Product needs to be connected in order to open it
                BaseProduct product = Insulator_DetectorApplication.getProductInstance();

                if (product != null && product.isConnected()) {


                    Intent intent = new Intent(this, MediaActivity.class);
                    Log.e(TAG, "created intent");
                    startActivity(intent);
                    Log.e(TAG, "started Media activity");
                } else {
                    showToast("No product connected! :(");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            debug = !debug;
            showToast("Debug:" + debug.toString());
            return true;
        }
        return super.onKeyDown(keyCode, event);
        //  return false;
    }
}
