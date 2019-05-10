package com.dji.Detector;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.app.Activity;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Surface;
import android.widget.ImageView;
import android.widget.Toast;

import com.dji.Detector.env.BorderedText;
import com.dji.Detector.env.ImageUtils;
import com.dji.Detector.env.Logger;
import com.dji.Detector.tracking.MultiBoxTracker;

import org.bouncycastle.util.Arrays;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.jcodec.api.android.AndroidSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Rational;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import dji.common.error.DJIError;
import dji.common.gimbal.GimbalMode;
import dji.common.util.CommonCallbacks;
import dji.keysdk.DJIKey;
import dji.keysdk.FlightControllerKey;
import dji.sdk.gimbal.Gimbal;

/// *****************************************************************   CAMERA DO DRONE ****************************************************************
public class DetectorActivity extends CameraActivity {

    private static final int MB_INPUT_SIZE = 224;
    private static final int MB_IMAGE_MEAN = 128;
    private static final float MB_IMAGE_STD = 128;
    private static final String MB_INPUT_NAME = "ResizeBilinear";
    private static final String MB_OUTPUT_LOCATIONS_NAME = "output_locations/Reshape";
    private static final String MB_OUTPUT_SCORES_NAME = "output_scores/Reshape";
    private static final String MB_MODEL_FILE = "file:///android_asset/multibox_model.pb";
    private static final String MB_LOCATION_FILE =
            "file:///android_asset/multibox_location_priors.txt";

    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;//false;//
    private static final String TF_OD_API_MODEL_FILE =
            "ssd_mobilenet_v1_quantized_300x300_coco14_sync_2018_07_18.tflite";
           //"ssdlite_mobilenet_v2-Tower5labels-pbtxt.tflite";
           //"file:///android_asset/frozen_inference_graph_mobilenet_archanjo.pb";
           // "file:///android_asset/frozen_inference_graph_non_optimized_pikachu.pb";
           //"file:///android_asset/frozen_inference_graph_noopt.pb";

    // Uncomment the label file you want to use
    private static final String TF_OD_API_LABELS_FILE =
            "file:///android_asset/labels_map_ssd_mobilenet_v1_quantized_300x300_coco14_sync_2018_07_18.txt";
            //"file:///android_asset/ssdlite_mobilenet_v2-Tower5labels.txt";
            //"file:///android_asset/labelmap_archanjo.txt";
            //"file:///android_asset/pikachu.txt";
            // "file:///android_asset/labelmap_isolator.txt";

    // Configuration values for tiny-yolo-voc. Note that the graph is not included with TensorFlow and
    // must be manually placed in the assets/ directory by the user.
    // Graphs and models downloaded from http://pjreddie.com/darknet/yolo/ may be converted e.g. via
    // DarkFlow (https://github.com/thtrieu/darkflow). Sample command:
    // ./flow --model cfg/tiny-yolo-voc.cfg --load bin/tiny-yolo-voc.weights --savepb --verbalise
    private static final String YOLO_MODEL_FILE = "file:///android_asset/graph-tiny-yolo-voc.pb";
    private static final int YOLO_INPUT_SIZE = 416;
    private static final String YOLO_INPUT_NAME = "input";
    private static final String YOLO_OUTPUT_NAMES = "output";
    private static final int YOLO_BLOCK_SIZE = 32;


    private enum DetectorMode {
        TF_OD_API, MULTIBOX, YOLO;
    }

    private static final DetectorMode MODE = DetectorMode.TF_OD_API;

    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;///////////////////////////////////7f;
    private static final float MINIMUM_CONFIDENCE_MULTIBOX = 0.1f;
    private static final float MINIMUM_CONFIDENCE_YOLO = 0.25f;

    private static final boolean MAINTAIN_ASPECT = MODE == DetectorMode.YOLO;
    private static final boolean SAVE_PREVIEW_BITMAP = true;
    private static final float TEXT_SIZE_DIP = 10;
    private boolean debug = false;
    private boolean toast = false;

    private static final String TAG = DetectorActivity.class.getName();
    private Integer sensorOrientation;

    private com.dji.Detector.tflite.Classifier detector;

    private long lastProcessingTimeMs;
    //private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;
   // private Bitmap rgbViewBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;
    private OverlayView trackingOverlay;

    private Matrix cropToFrameTransform;
    private Matrix frametoViewTransform;

    //private Matrix cropToViewTransform;
    //private Matrix frameToCropTransform;

    private MultiBoxTracker tracker;
    private static final Logger LOGGER = new Logger();
    int viewwidth = 0;
    int viewheight = 0;
    private byte[] luminanceCopy;

    private BorderedText borderedText;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(1280, 720);

    AndroidSequenceEncoder encoder;
    SeekableByteChannel out = null;


@Override
public synchronized void onPause(){
    Log.e(TAG, "onPause");
    tracker.resetVirtualStick();
    try {
        // Finalize the encoding, i.e. clear the buffers, write the header, etc.
        encoder.finish();
        NIOUtils.closeQuietly(out);
    }
    catch(Exception e){
        LOGGER.e(e.getMessage());
    }

    super.onPause();
}
@Override
public synchronized void onDestroy(){
    Log.e(TAG, "onDestroy");
    tracker.resetVirtualStick();
    super.onDestroy();
}

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        //LOGGER.e("CameraConnectionFragment.onPreviewSizeChosen) 0");

        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;
        if (MODE == DetectorMode.YOLO) {
            LOGGER.i("YOLO");
            /*detector =
                    TensorFlowYoloDetector.create(
                            getAssets(),
                            YOLO_MODEL_FILE,
                            YOLO_INPUT_SIZE,
                            YOLO_INPUT_NAME,
                            YOLO_OUTPUT_NAMES,
                            YOLO_BLOCK_SIZE);
            cropSize = YOLO_INPUT_SIZE;*/
        } else if (MODE == DetectorMode.MULTIBOX) {
            LOGGER.i("MULTIBOX");
            /*detector =
                    TensorFlowMultiBoxDetector.create(
                            getAssets(),
                            MB_MODEL_FILE,
                            MB_LOCATION_FILE,
                            MB_IMAGE_MEAN,
                            MB_IMAGE_STD,
                            MB_INPUT_NAME,
                            MB_OUTPUT_LOCATIONS_NAME,
                            MB_OUTPUT_SCORES_NAME);
            cropSize = MB_INPUT_SIZE;*/
        } else {
            try {
                /*detector = TensorFlowObjectDetectionAPIModel.create(
                        getAssets(),
                        TF_OD_API_MODEL_FILE,
                        TF_OD_API_LABELS_FILE,
                        TF_OD_API_INPUT_SIZE);*/
                LOGGER.i("TensorFlow");
                detector =  com.dji.Detector.tflite.TFLiteObjectDetectionAPIModel.create(
                        getAssets(),
                        TF_OD_API_MODEL_FILE,
                        TF_OD_API_LABELS_FILE,
                        TF_OD_API_INPUT_SIZE,
                        TF_OD_API_IS_QUANTIZED);
                cropSize = TF_OD_API_INPUT_SIZE;
            } catch (final IOException e) {
                LOGGER.e("Exception initializing classifier!", e);
                Toast toast =
                        Toast.makeText(
                                getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
                toast.show();
                finish();
            }
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();
        textureView =  (AutoFitTextureView) findViewById(R.id.texture);

        if(textureView != null){
            LOGGER.e("TEXTUREVIEW NOT NULL");
        }
        viewwidth = textureView.getWidth();
        viewheight = textureView.getHeight();

        if (viewwidth < viewheight * previewWidth / previewHeight) {
            framewidth = viewwidth;
            frameheight = viewwidth *  previewWidth / previewHeight;
        } else {
            frameheight = viewheight;
            framewidth = viewheight * previewWidth / previewHeight;
        }

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);
        LOGGER.i("frame size: " + framewidth + " x " + frameheight);
        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);

        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888);

        Log.d("Detector Activity", "croppedBitmap ok");

        rgbViewBitmap = Bitmap.createBitmap(framewidth,frameheight,Bitmap.Config.ARGB_8888);
        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        final int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            trackingOverlay.setAspectRatio(previewWidth, previewHeight);
        } else {
            trackingOverlay.setAspectRatio(previewHeight, previewWidth);
        }

        // This next SetAspect ratio is for making the imageView show by reducing the texture view that is in front of it (because I am lazy,
        // otherwise I could just figure out how to put ImageView to the front)

         /* if (orientation == Configuration.ORIENTATION_LANDSCAPE) { //////////////////////////  this was for making the imageView show by reducing the trackingOverlay
            trackingOverlay.setAspectRatio(1, 15);
        } else {
            trackingOverlay.setAspectRatio(15, 1);
        }  */ /////////////////////////////


        trackingOverlay.addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {

                        tracker.draw(canvas);
                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }
                    }
                });

        addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        if (!isDebug()) {
                            return;
                        }
                        final Bitmap copy = cropCopyBitmap;
                        if (copy == null) {
                            return;
                        }

                        final int backgroundColor = Color.argb(100, 0, 0, 0);
                        canvas.drawColor(backgroundColor);

                        final Matrix matrix = new Matrix();
                        final float scaleFactor = 2;
                        matrix.postScale(scaleFactor, scaleFactor);
                        matrix.postTranslate(
                                canvas.getWidth() - copy.getWidth() * scaleFactor,
                                canvas.getHeight() - copy.getHeight() * scaleFactor);
                        canvas.drawBitmap(copy, matrix, new Paint());

                        final Vector<String> lines = new Vector<String>();
                        if (detector != null) {
                            final String statString = detector.getStatString();
                            final String[] statLines = statString.split("\n");
                            for (final String line : statLines) {
                                lines.add(line);
                            }
                        }
                        lines.add("");

                        lines.add("Frame: " + previewWidth + "x" + previewHeight);
                        lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
                        lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
                        lines.add("Rotation: " + sensorOrientation);
                        lines.add("Inference time: " + lastProcessingTimeMs + "ms");

                        borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
                    }
                });

           // showToast("Texture view Dimension: " + textureView.getWidth() + " x " + textureView.getHeight());
           // showToast("Tracking Overlay view Dimension: " + trackingOverlay.getWidth() + " x " + trackingOverlay.getHeight());

    }

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(null);
        try{
            out = NIOUtils.writableFileChannel(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "/tensorflow/output2.mp4");
            encoder = new AndroidSequenceEncoder(out, Rational.R(25, 1));}
            catch(Exception e){
            LOGGER.e(e.getMessage());
            }
        }
    @Override
    protected void processImage() {
        toast = true;

        ++timestamp;
        final long currTimestamp = timestamp;


        byte[] originalLuminance = getLuminance();
        int cropSize = TF_OD_API_INPUT_SIZE;
        Matrix frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        // framewidth, frameheight,
                        rgbFrameBitmap.getWidth(),rgbFrameBitmap.getHeight(),
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
        // cropToViewTransform = ImageUtils.getTransformationMatrix(cropSize,cropSize,framewidth,frameheight,sensorOrientation,MAINTAIN_ASPECT);
        frametoViewTransform = ImageUtils.getTransformationMatrix(previewWidth,previewHeight,framewidth,frameheight,sensorOrientation,MAINTAIN_ASPECT);
       /* tracker.onFrame(
                rgbFrameBitmap.getWidth(),rgbFrameBitmap.getHeight(),
                getLuminanceStride(),
                sensorOrientation,
                originalLuminance,
                timestamp);*/
        trackingOverlay.postInvalidate();





        computingDetection = true;

        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");


        // rgbFrameBitmap.setPixels(getRgbBytes(), 0, rgbFrameBitmap.getWidth(), 0, 0, rgbFrameBitmap.getWidth(), rgbFrameBitmap.getHeight());

        //
        if (luminanceCopy == null) {
             LOGGER.i("Preparing image " + currTimestamp + " done-2");
            luminanceCopy = new byte[originalLuminance.length];
            LOGGER.i("Preparing image " + currTimestamp + " done-1");
        }
        LOGGER.i("Preparing image " + currTimestamp + " done0");
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
        LOGGER.i("Preparing image " + currTimestamp + " done");


        LOGGER.i("Preparing image " + currTimestamp + " afterreadyfornxtimg");


        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        final Canvas bigCanvas = new Canvas(rgbViewBitmap);
        bigCanvas.drawBitmap(rgbFrameBitmap,frametoViewTransform,null);

        // computingDetection = false; //DELETE THIS LATER
        int i = 0; // test for COMMIT
        int j = 0; // second test for COMMIT
        // For examining the actual TF input.
        /*   if (SAVE_PREVIEW_BITMAP) {
               try{
               encoder.encodeImage(rgbFrameBitmap);}
               catch(Exception e){
                   LOGGER.e(e.getMessage());
               }
          //  ImageUtils.saveBitmap(croppedBitmap,"croppedframe.png");
        }*/
        LOGGER.i("Preparing image " + currTimestamp + " before runinBackground");
         runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        LOGGER.i("Preparing image " + currTimestamp + " inside Runnable");
                        LOGGER.i("Running detection on image " + currTimestamp);
                        final long startTime = SystemClock.uptimeMillis();
                        final List<com.dji.Detector.tflite.Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
                        LOGGER.i("PROCESSING TIME TENSORFLOW - DRONE CAMERA : " + lastProcessingTimeMs);
                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                        switch (MODE) {
                            case TF_OD_API:
                                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                break;
                            case MULTIBOX:
                                minimumConfidence = MINIMUM_CONFIDENCE_MULTIBOX;
                                break;
                            case YOLO:
                                minimumConfidence = MINIMUM_CONFIDENCE_YOLO;
                                break;
                        }

                        final List<com.dji.Detector.tflite.Classifier.Recognition> mappedRecognitions =
                                new LinkedList<com.dji.Detector.tflite.Classifier.Recognition>();
                        Log.d("Draw Rectangle","total of results " + results.size());
                        for (final com.dji.Detector.tflite.Classifier.Recognition result : results) {
                            final RectF location = result.getLocation();
                            if (location != null && result.getConfidence() >= minimumConfidence) {
                                canvas.drawRect(location, paint);
                                Log.d("Draw Rectangle",location.toString());

                               cropToFrameTransform.mapRect(location);
                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            }
                        }

                        tracker.trackResults(mappedRecognitions, luminanceCopy, currTimestamp);//The drone control happen in the MultiBoxTracker.java
                        trackingOverlay.postInvalidate();
                        requestRender();
                        computingDetection = false;
                    }
                });


    }

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    @Override
    public void onSetDebug(final boolean debug) {
        detector.enableStatLogging(debug);
    }





}
