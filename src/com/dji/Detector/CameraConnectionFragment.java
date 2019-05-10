/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
* Elisa Tengan
* LSI-TEC
* 2018
*
*
*
*
*/

package com.dji.Detector;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import com.dji.Detector.env.Logger;
import dji.common.camera.ResolutionAndFrameRate;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import com.dji.Detector.media.DJIVideoStreamDecoder;
import com.dji.Detector.media.NativeHelper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static dji.common.camera.SettingsDefinitions.PhotoAspectRatio.RATIO_16_9;
import static dji.common.camera.SettingsDefinitions.VideoFrameRate.FRAME_RATE_24_FPS;
import static dji.common.camera.SettingsDefinitions.VideoFrameRate.FRAME_RATE_30_FPS;
import static dji.common.camera.SettingsDefinitions.VideoResolution.RESOLUTION_1280x720;



public class CameraConnectionFragment extends Fragment {


    /**
     * A {@link OnImageAvailableListener} to receive frames as they are available.
     */
    private final OnImageAvailableListener imageListener;

    private static final int MINIMUM_PREVIEW_SIZE = 320;

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String cameraId;


    // Logger for debugging
    private static final Logger LOGGER = new Logger();

    // Set boolean to true if you want debug toasts to be shown
    private boolean showMyToast = false;

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();


    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    // Callback for video live view
    protected VideoFeeder.VideoDataCallback mReceivedVideoDataCallBack = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;

    // Callback for camera connection
    private final ConnectionCallback cameraConnectionCallback;

    // DJI's sample video stream decoder
    protected DJIVideoStreamDecoder streamDecoder;

    // DJI's sample native helper
    protected NativeHelper nativeHelper;

    // Fragment constructor
    private CameraConnectionFragment(
            final ConnectionCallback connectionCallback,
            final DJICodecManager.YuvDataCallback YUVListener,
            //final DJIVideoStreamDecoder.CustomYuvDataCallback YUVListener,
            final int layout,
            final Size inputSize) {
        this.cameraConnectionCallback = connectionCallback;
        this.imageListener = null;
        this.YuvListener = YUVListener;
        this.layout = layout;
        this.inputSize = inputSize;

    }

    private CameraConnectionFragment(
            final ConnectionCallback connectionCallback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        this.cameraConnectionCallback = connectionCallback;
        this.imageListener = imageListener;
        this.YuvListener = null;
        this.layout = layout;
        this.inputSize = inputSize;
    }


    public void setCamera(String cameraId) {
        this.cameraId = cameraId;
    }

    // Fragment instance creator (used in CameraActivity)
    public static CameraConnectionFragment newInstance(
            final ConnectionCallback callback,
            final DJICodecManager.YuvDataCallback YUVListener,
            // final DJIVideoStreamDecoder.CustomYuvDataCallback YUVListener,
            final int layout,
            final Size inputSize) {
        // Old version 1
        // return new CameraConnectionFragment(callback, imageListener, layout, inputSize);
        // Old version 2
        // return new CameraConnectionFragment(imageListener, layout, inputSize);

        // Latest version
        return new CameraConnectionFragment(callback, YUVListener, layout, inputSize);

    }

    public static CameraConnectionFragment newInstance(
            final ConnectionCallback callback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        return new CameraConnectionFragment(callback, imageListener, layout, inputSize);
    }

    //SurfaceTexture Listener: necessary for setting up Codec Manager and opening camera
    /**
     * {@link android.view.TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener1 =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    LOGGER.e("onSurfaceTextureAvailable CameraConnectionFragment 0");
                    if (textureView == null) {
                        LOGGER.e("textureView null in onsurfaceTextureAvailable");
                    } else {
                        LOGGER.e("textureView NOT null in onsurfaceTextureAvailable");
                    }
                    openCamera(width, height);
                    if (mCodecManager == null) {
                        SurfaceTexture nullTexture = null;
                        mCodecManager = new DJICodecManager(getActivity(), texture, width, height); // Here the SurfaceTexture is not null and the stream is directly shown on textureView
                        mCodecManager.enabledYuvData(false);
                        mCodecManager.setYuvDataCallback(null);
                        captureFramesFromSurface();
                        //mCodecManager = new DJICodecManager(getActivity(), nullTexture, width, height); // SurfaceTexture needs to be set as null so we can enable YUV Data
                        //mCodecManager.enabledYuvData(true);
                        //mCodecManager.setYuvDataCallback(YuvListener); // YuvListener comes from CameraActivity
                    }
                    LOGGER.e("onSurfaceTextureAvailable CameraConnectionFragment 1");
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    LOGGER.e("onSurfaceTextureSizeChanged CameraConnectionFragment 0");
                    configureTransform(width, height);
                    LOGGER.e("onSurfaceTextureSizeChanged CameraConnectionFragment 1");
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    LOGGER.e("onSurfaceDestroyed 0");

                    LOGGER.e("onSurfaceDestroyed 1");
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {

                }
            };

    //inicia a apresentação de 1 frame a cada 50ms
    private void captureFramesFromSurface() {
        callRunnable();
    }

    private void callRunnable (){
        surfaceHandler.postDelayed(refresh,50);
    }

    Handler surfaceHandler = new Handler(Looper.getMainLooper());

    private Runnable refresh = new Runnable() {//async para não segurar a thread
        @Override
        public void run() {
            Bitmap temp = textureView.getBitmap();

            EventBus.getDefault().post(temp);
            callRunnable();
        }
    };




    @Override
    public void onStart(){
       EventBus.getDefault().register(this);
        super.onStart();
    }
    @Override
    public void onStop(){

           EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        LOGGER.e("onCreateView CameraConnectionFragment");

        LOGGER.e("Layout ID in CameraConnectionFragment= " + layout);

        return inflater.inflate(layout, container, false);
    }
  @Subscribe
    public void unlock(Boolean event) {
        LOGGER.e ("UNLOCK CALLED");
        synchronized (mLock) {
            locked = false;
            mLock.notify();

        }
    }

    private Object mLock = new Object();
    private boolean locked = false;
    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        LOGGER.e("onViewCreated CameraConnectionFragment 0");

        textureView = (AutoFitTextureView) view.findViewById(R.id.texture); // redundant because of updated SDK version

        rectangles = (OverlayView) view.findViewById(R.id.tracking_overlay);


        LOGGER.e("onViewCreated CameraConnectionFragment 1");
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        LOGGER.e("onActivityCreated CameraConnectionFragment 0");

        super.onActivityCreated(savedInstanceState);

        LOGGER.e("onActivityCreated CameraConnectionFragment 1");
    }

    @Override
    public void onResume() {
        LOGGER.e("onResume CameraConnectionFragment 0");

        super.onResume();

       // startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            LOGGER.e("textureview ia available onResume");

            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            LOGGER.e("set textureview listener onResume");

            textureView.setSurfaceTextureListener(surfaceTextureListener1);
        }
        LOGGER.e("onResume CameraConnectionFragment 1");
    }

    @Override
    public void onPause() {
        LOGGER.e("onPause CameraConnectionFragment 0");

        // To be honest I don't remember why I put this here and why I commented it later

       /* Camera camera =Insulator_DetectorApplication.getCameraInstance();
        if(camera!=null){
            if(camera.isMediaDownloadModeSupported()){
                camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if(djiError !=null){
                            LOGGER.e("SETTING MODE DOWNLOAD ERROR : " + djiError.getDescription());
                        }
                    }
                });

                MediaManager mediaManager = camera.getMediaManager();
                if(mediaManager!=null){


                }

            }

        }*/

        if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
            VideoFeeder.getInstance().getPrimaryVideoFeed().setCallback(null);
        }
        if(streamDecoder !=null)
        {
            streamDecoder.stop();
        }

        if(nativeHelper !=null){
            nativeHelper.release();
        }

        closeCamera();
       // stopBackgroundThread();


        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager.destroyCodec();
        }
        super.onPause();
        LOGGER.e("onPause CameraConnectionFragment 1");
    }

    @Override
    public void onDestroy(){
        LOGGER.e("CameraConnectionFragment onDestroy 0");
        synchronized (mLock) {
            mLock.notify();
            locked = false;
        }
        super.onDestroy();
        LOGGER.e("CameraConnectionFragment onDestroy 1");
    }
int counter = 0;

    /**
     * Opens the camera specified
     */
    private void openCamera(final int width, final int height) {
        LOGGER.e("CameraConnectionFragment.openCamera() 0");

        setUpCameraOutputs();

        configureTransform(width, height);

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataCallBack = new VideoFeeder.VideoDataCallback() {

            @Override
            public void onReceive(final byte[] videoBuffer, final int size) {

                    //LOGGER.e("ONRECEIVE");
                    if (mCodecManager != null) {
                      //  if (!locked) {
                           // LOGGER.e("UNLOCKED");
                          //  synchronized (mLock) {
                             //   LOGGER.e("INSIDE SYNCHRONIZED");
                            //    if (!locked) {
                                   // locked = true;
                               //     LOGGER.e("LOCKED");

                                    //  streamDecoder.parse(videoBuffer,size); // Uncomment this if using DJI's Sample Video Stream Decoder to get YUV frames


                                    // If YUV Data enabled, the function bellow calls onYUVDataReceived callback function.
                                    // If YUV Data is disabled and texture is not null, will show video stream on TextureView
                                   // LOGGER.e("SEND DATA TO DECODER");
                                    mCodecManager.sendDataToDecoder(videoBuffer, size);


                                    /* try {
                                        LOGGER.e("TRY WAIT");
                                        mLock.wait();
                                    } catch (InterruptedException ie) {
                                        LOGGER.e("SHIT");
                                        showToast("SHIT");
                                        throw new RuntimeException(ie);     // not expected
                                    }*/
                              //  }
                          //  }
                       // }

                    } else {
                        LOGGER.e("CODEC MANAGER STILL NULL");
                    }
                }

        };


        createCameraPreviewSession();
        if(showMyToast){
            showToast("Init Previewer OK");
        }
        LOGGER.e("CameraConnectionFragment.openCamera() 1");
    }



    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        LOGGER.e("CameraConnectionFragment.closeCamera() 0");

        Camera camera =Insulator_DetectorApplication.getCameraInstance();
        if (camera != null) {
            // Reset the callback
            VideoFeeder.getInstance().getPrimaryVideoFeed().setCallback(null);
        }
        LOGGER.e("CameraConnectionFragment.closeCamera() 1");
    }



    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
   public AutoFitTextureView textureView;

public OverlayView rectangles;

    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    /**
     * The rotation in degrees of the camera sensor from the display.
     */
    private Integer sensorOrientation;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size previewSize;


    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    public HandlerThread backgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    public Handler backgroundHandler;


    /**
     * A {@link OnImageAvailableListener} to receive frames as they are available.
     */

  //  private final DJIVideoStreamDecoder.CustomYuvDataCallback YuvListener;
    private final DJICodecManager.YuvDataCallback YuvListener;
    /**
     * The input size in pixels desired by TensorFlow (width and height of a square bitmap).
     */
    private final Size inputSize;

    /**
     * The layout identifier to inflate for this Fragment.
     */
    private final int layout;




    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }



    /**
     * Sets up member variables related to camera.
     */
    private void setUpCameraOutputs() {
        LOGGER.e("CameraConnectionFragment.setUpCameraOutputs() 0");
        if(showMyToast){
            showToast("setupcameraaoutputs 0");}
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        sensorOrientation = 90;

        // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        // garbage capture data.


        // I deleted the function that chose the previewSize trying to fit the inputSize chosen by the user.
        // I assume the user put a known screen size in DetectorActivity (1280x720)
        previewSize = new Size(inputSize.getWidth(), inputSize.getHeight());

        LOGGER.e("texture view size: " + textureView.getWidth() + " x " + textureView.getHeight());

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        final int orientation = getResources().getConfiguration().orientation;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }

        // This next SetAspect ratio is for making the imageView show by reducing the texture view that is in front of it (because I am lazy,
        // otherwise I could just figure out how to put ImageView to the front)

//         if (orientation == Configuration.ORIENTATION_LANDSCAPE) { ///////////////////////////////////////
//            textureView.setAspectRatio(1, 15);
//        } else {
//            textureView.setAspectRatio(15, 1);
//        }  ////////////////////////////////////////////////////////


        LOGGER.e("texture view size: " + textureView.getWidth() + " x " + textureView.getHeight());

        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);

        if(showMyToast){
            showToast("setupcameraaoutputs 1");
        }
        LOGGER.e("CameraConnectionFragment.setUpCameraOutputs() 1");
    }


    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        LOGGER.e("startBackgroundThread 0");

        backgroundThread = new HandlerThread("ImageListener");

        backgroundThread.start();

        backgroundHandler = new Handler(backgroundThread.getLooper());

        LOGGER.e("startBackgroundThread 1");
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        LOGGER.e("CameraConnectionFragment.stopBackgroundThread");

       boolean quit =  backgroundThread.quitSafely();

       if(quit)
       {
           LOGGER.e("destroyed BackgroundThread");
       }
       else{
           LOGGER.e("DID NOT DESTROY BackgroundThread");
       }
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
            LOGGER.e(e, "Exception!");
        }
    }


    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {

        LOGGER.e("CameraConnectionFragment.createCameraPreviewSession() 0");

        if(showMyToast){
            showToast("createcamerapreviewsession 0");}

        final SurfaceTexture texture = textureView.getSurfaceTexture();

        assert texture != null;

        // We configure the size of default buffer to be the size of camera preview we want.
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());


        LOGGER.e("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());



        BaseProduct product =Insulator_DetectorApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != textureView) {
                textureView.setSurfaceTextureListener(surfaceTextureListener1);
            } else {
                LOGGER.e("textureview is null createCameraPreviewSession");
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                Camera camera = product.getCamera();

                // I tried changing the frame rate to improve the frame decoding but it is still shitty
              /*   camera.setVideoResolutionAndFrameRate(new ResolutionAndFrameRate(RESOLUTION_1280x720, FRAME_RATE_30_FPS), new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError!=null){
                            LOGGER.e("Error in setting video res and frame rate: " + djiError.getDescription());
                        }
                    }
                });*/
               /* camera.setPhotoAspectRatio(RATIO_16_9,new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError error) {
                                if (error!=null)
                                    LOGGER.e(error.getDescription());
                            }
                        }
                );
                ResolutionAndFrameRate x = new ResolutionAndFrameRate(RESOLUTION_1280x720, SettingsDefinitions.VideoFrameRate.UNKNOWN);

                camera.setVideoResolutionAndFrameRate(x,new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError error) {
                                LOGGER.e(error.getDescription());
                            }
                        }
                        );
                */
                // Setting up Native Helper and Stream Decoder, although they are not really used if
                // YUV frames are being recovered from CodecManager (sendDataToDecoder)
                nativeHelper = new NativeHelper();
                nativeHelper.init();
                streamDecoder = new DJIVideoStreamDecoder();
                streamDecoder.init(getActivity().getApplicationContext(), null, textureView.getSurfaceTexture());
              //  streamDecoder.setCustomYuvDataListener(YuvListener);
                streamDecoder.resume();

                // Setting the callback for when video stream is received
                VideoFeeder.getInstance().getPrimaryVideoFeed().setCallback(mReceivedVideoDataCallBack);

                LOGGER.e("called the listeners");
                if(showMyToast){
                    showToast("VideoFeeder Callback");}
            }
        }

        if(showMyToast){
            showToast("createcamerapreviewsession 1");
        }

        LOGGER.e("CameraConnectionFragment.createCameraPreviewSession() 1");
    }

    protected synchronized void runInBackgroundFrag(final Runnable r) {
        if (backgroundHandler != null) {
            backgroundHandler.post(r);
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(final int viewWidth, final int viewHeight) {
        LOGGER.e("CameraConnectionFragment.configureTransform 0");
        final Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();

        // I removed the Surface.ROTATION_90 == rotation option because the screen rotation would not work with it
        //if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            if (Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
        if (textureView == null) {
            LOGGER.e("textureView null in configureTransform");
        } else {
            LOGGER.e("textureView NOT null in configureTransform");
        }
        LOGGER.e("CameraConnectionFragment.configureTransform 1");
    }


    protected static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
        final Size desiredSize = new Size(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        LOGGER.i("Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize);
        LOGGER.i("Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]");
        LOGGER.i("Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]");

        if (exactSizeFound) {
            LOGGER.i("Exact size match found.");
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            LOGGER.e("Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }


    /**
     * Callback for Activities to use to initialize their data once the
     * selected preview size is known.
     */
    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }



}
