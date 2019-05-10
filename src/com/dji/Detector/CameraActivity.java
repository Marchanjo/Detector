package com.dji.Detector;

/// *****************************************************************   CAMERA DO DRONE ****************************************************************

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Bundle;
import android.app.Activity;
import android.app.Fragment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ToggleButton;
import com.dji.Detector.env.ImageUtils;
import com.dji.Detector.env.Logger;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import dji.common.error.DJIError;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.util.CommonCallbacks;
import dji.keysdk.DJIKey;
import dji.keysdk.FlightControllerKey;
import dji.keysdk.KeyManager;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;


public abstract class CameraActivity extends Activity  {
    private static final Logger LOGGER = new Logger(); //Debug tool
    private Handler handler; //Handler used to post runnables into a specific thread
    private HandlerThread handlerThread; //Generates a thread to process inferences
    private ToggleButton mBtnFlightControlCamera; //Button to activate the camera control
    private FlightController mFlightController; //Dji SDK Flight Controller
    protected Boolean isVirtualStickEnabled = false; //Controls Button State
    protected ImageView imageView;  //used for debug purposes
    int previewWidth = 0;   //preview width
    int previewHeight = 0;  //preview Height
    int framewidth = 0;     //textureview width
    int frameheight = 0;    //textureview height
    private byte[][] yuvBytes = new byte[3][];  //yuv data
    private int yRowStride;  //size of bitmap in the y axis
    private boolean debug = false;  //enable/disable debug mode (activated by button press)
    protected Fragment fragment;    //fragment reference
    protected AutoFitTextureView textureView = null;    //texture view reference
    protected Bitmap rgbFrameBitmap = null; //frame in RGB format
    protected Bitmap rgbViewBitmap = null;//view in RGB


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LOGGER.d("onCreate " + this);
        super.onCreate(null);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Insulator_DetectorApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);

        Aircraft aircraft = Insulator_DetectorApplication.getAircraftInstance();
        mFlightController = aircraft.getFlightController();
        mFlightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        mFlightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        mFlightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
        mFlightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

        DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);
        isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);

        setFragment();
    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            // stopThread();
            Intent intent1 = new Intent(getApplicationContext(), ConnectionActivity.class);
            intent1.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivityIfNeeded(intent1, 0);
        }
    };

    private void setButton() {
        if (isVirtualStickEnabled) {
            LOGGER.e("virtual stick enabled 2= " + isVirtualStickEnabled);
            mBtnFlightControlCamera.setChecked(true);
        } else {
            mBtnFlightControlCamera.setChecked(false);
        }

    }

    @Override
    public synchronized void onStart() {
        LOGGER.d("onStart " + this);
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public synchronized void onResume() {
        LOGGER.d("onResume " + this);
        super.onResume();

        handlerThread = new HandlerThread("inference");
        LOGGER.e("created inference thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());


        Aircraft aircraft = Insulator_DetectorApplication.getAircraftInstance();
        mFlightController = aircraft.getFlightController();


        // Setting isVirtualStickEnabled variable
        DJIKey temp = FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED);
        isVirtualStickEnabled = (Boolean) KeyManager.getInstance().getValue(temp);
        LOGGER.e("isVirtualStickEnabled = " + isVirtualStickEnabled);
        showToast("isVirtualStickEnabled = " + isVirtualStickEnabled);

        // Set FlightControl Button
        setButton();
    }

    public void stopThread() {
        LOGGER.e("STOP THREAD");
        try {
            handler.removeCallbacksAndMessages(null);
            boolean quit = handlerThread.quitSafely();

            handlerThread.interrupt();
            if (quit) {
                LOGGER.e("destroyed inference thread");
            } else {

                LOGGER.e("DID NOT DESTROY inference thread");

            }
            try {
                handlerThread.join();
                handlerThread = null;
                handler = null;
            } catch (final Exception e) {
                LOGGER.e(e, "Exception!");
            }
        } catch (Exception e) {
            LOGGER.e(e.getMessage());
        }
        LOGGER.e("END OF STOP THREAD");
    }

    @Override
    public synchronized void onPause() {
        LOGGER.d("onPause " + this);
        try {
            stopThread();
        } catch (Exception e) {
            LOGGER.e(e.getMessage());
        }
        if (!isFinishing()) {
            LOGGER.d("Requesting finish");
            finish();
        }
        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        LOGGER.d("onStop " + this);
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        LOGGER.d("onDestroy " + this);
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        LOGGER.e("onBackPressed()");
        stopThread();

        Intent intent1 = new Intent(getApplicationContext(), ConnectionActivity.class);
        intent1.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivityIfNeeded(intent1, 0);
    }

    protected int getLuminanceStride() {
        return yRowStride;
    }

    protected byte[] getLuminance() {
        return yuvBytes[0];
    }

    public void requestRender() {
        //refresh view
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }


    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    protected void setFragment() {
        CameraConnectionFragment camera2Fragment =
                CameraConnectionFragment.newInstance(
                        new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight();
                                previewWidth = size.getWidth();
                                CameraActivity.this.onPreviewSizeChosen(size, rotation);
                            }
                        },
                        new DJICodecManager.YuvDataCallback() {
                            @Override
                            public void onYuvDataReceived(ByteBuffer byteBuffer, int i, int i1, int i2) {

                            }
                        },
                        getLayoutId(),
                        getDesiredPreviewFrameSize());


        fragment = camera2Fragment;


        getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit();


        mBtnFlightControlCamera = (ToggleButton) findViewById(R.id.btn_flight_control_camera);
        imageView = (ImageView) findViewById(R.id.imageView);
        imageView.bringToFront();
        mBtnFlightControlCamera.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { /////////////////////
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
                                        //  isVirtualStickEnabled = true;
                                    }
                                }
                            });
                        }

                    } else {
                        mBtnFlightControlCamera.setChecked(false);
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
                                        showToast("Failed to turn Flight Control OFF. Error: " + djiError.getDescription());

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



    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    public boolean isDebug() {
        return debug;
    }

    public void addCallback(final OverlayView.DrawCallback callback) {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.addCallback(callback);
        }
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            debug = !debug;
            requestRender();
            onSetDebug(debug);
            return true;
        }
        return super.onKeyDown(keyCode, event);
        //  return false;
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void onSetDebug(final boolean debug) {
    }


    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract void processImage();

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();
    @Subscribe
    public void getBitmap(Bitmap bitmap) {
        //((ImageView) findViewById(R.id.imageView)).setImageBitmap(bitmap);

        Log.d("getBitmap", "Bitmap is comming");
        rgbFrameBitmap=bitmap;

        rgbViewBitmap=bitmap;


        int[] rgbValues = new int[rgbViewBitmap.getWidth() * rgbViewBitmap.getHeight()];
        byte[] yuvValues = new byte[3 * rgbViewBitmap.getWidth() * rgbViewBitmap.getHeight()];

       // bitmap.getPixels(rgbValues, 0, rgbViewBitmap.getWidth(), 0, 0, rgbViewBitmap.getWidth(), rgbViewBitmap.getHeight());
       // ImageUtils.convertARGB8888ToYUV420SP(rgbValues,yuvValues,rgbViewBitmap.getWidth(), rgbViewBitmap.getHeight());

        byte[] slice = Arrays.copyOfRange(yuvValues, 0, rgbViewBitmap.getWidth() * rgbViewBitmap.getHeight());
        yuvBytes[0] = slice;
        yRowStride = rgbViewBitmap.getWidth();

//        if (!alreadyCreated) {
//            //newhandler.postDelayed(runnable, 1000);  ////for debug purpose
//            alreadyCreated = true;
//        }



        EventBus.getDefault().post(true);
        processImage();

    }

}
