/* Copyright 2016 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.dji.Detector.tracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.widget.Toast;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import com.dji.Detector.Classifier.Recognition;
import com.dji.Detector.env.BorderedText;
import com.dji.Detector.env.ImageUtils;
import com.dji.Detector.env.Logger;
import com.dji.Detector.SendVirtualStickDataTask;
import static java.lang.StrictMath.abs;

/**
 * A tracker wrapping ObjectTracker that also handles non-max suppression and matching existing
 * objects to new detections.
 */
public class MultiBoxTracker {

    boolean debug = false;

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    private final Logger logger = new Logger();

    private static final float TEXT_SIZE_DIP = 18;

    // Maximum percentage of a box that can be overlapped by another box at detection time. Otherwise
    // the lower scored box (new or old) will be removed.
    private static final float MAX_OVERLAP = 0.2f;

    private static final float MIN_SIZE = 16.0f;

    // Allow replacement of the tracked box with new results if
    // correlation has dropped below this level.
    private static final float MARGINAL_CORRELATION = 0.4f;//0.8f;

    // Consider object to be lost if correlation falls below this threshold.
    private static final float MIN_CORRELATION = 0.3f;//0.6f;

    private boolean photo = false;

    private Timer mSendVirtualStickDataTimer;
    public SendVirtualStickDataTask mSendVirtualStickDataTask;

    private static final int[] COLORS = {
            Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE,
            Color.parseColor("#55FF55"), Color.parseColor("#FFA500"), Color.parseColor("#FF8888"),
            Color.parseColor("#AAAAFF"), Color.parseColor("#FFFFAA"), Color.parseColor("#55AAAA"),
            Color.parseColor("#AA33AA"), Color.parseColor("#0D0068")
    };

    private final Queue<Integer> availableColors = new LinkedList<Integer>();

    public ObjectTracker objectTracker;

    final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();
    float mYaw = 0;
    float mPitch = 0;
    float mRoll = 0;
    float mThrottle = 0;


    private static class TrackedRecognition {
        ObjectTracker.TrackedObject trackedObject;
        RectF location;
        float detectionConfidence;
        int color;
        String title;
    }

    private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();

    private final Paint boxPaint = new Paint();

    private final float textSizePx;
    private final BorderedText borderedText;

    private Matrix frameToCanvasMatrix;

    private int frameWidth;
    private int frameHeight;

    private int sensorOrientation;
    private Context context;

    private int counter = 0;
   // public static DJIKey flightControl = FlightControllerKey.create(FlightControllerKey.SEND_VIRTUAL_STICK_FLIGHT_CONTROL_DATA);
   // public static GimbalKey gimbalKey = GimbalKey.create(GimbalKey.ATTITUDE_IN_DEGREES);

    public MultiBoxTracker(final Context context) {
        this.context = context;
        for (final int color : COLORS) {
            availableColors.add(color);
        }

        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Style.STROKE);
        boxPaint.setStrokeWidth(12.0f);
        boxPaint.setStrokeCap(Cap.ROUND);
        boxPaint.setStrokeJoin(Join.ROUND);
        boxPaint.setStrokeMiter(100);

        textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);


    }

    private Matrix getFrameToCanvasMatrix() {
        return frameToCanvasMatrix;
    }

    public synchronized void drawDebug(final Canvas canvas) {
        final Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(60.0f);

        final Paint boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setAlpha(200);
        boxPaint.setStyle(Style.STROKE);

        for (final Pair<Float, RectF> detection : screenRects) {
            final RectF rect = detection.second;
            canvas.drawRect(rect, boxPaint);
            canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
            borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
        }

        if (objectTracker == null) {
            return;
        }

        // Draw correlations.
        for (final TrackedRecognition recognition : trackedObjects) {
            final ObjectTracker.TrackedObject trackedObject = recognition.trackedObject;

            final RectF trackedPos = trackedObject.getTrackedPositionInPreviewFrame();

            if (getFrameToCanvasMatrix().mapRect(trackedPos)) {
                final String labelString = String.format("%.2f", trackedObject.getCurrentCorrelation());
                borderedText.drawText(canvas, trackedPos.right, trackedPos.bottom, labelString);
            }
        }

        final Matrix matrix = getFrameToCanvasMatrix();
        objectTracker.drawDebug(canvas, matrix);
    }
    public void resetVirtualStick(){
        if (null != mSendVirtualStickDataTimer) {
            mSendVirtualStickDataTask.cancel();
            mSendVirtualStickDataTask = null;
            mSendVirtualStickDataTimer.cancel();
            mSendVirtualStickDataTimer.purge();
            mSendVirtualStickDataTimer = null;
        }
    }

    public synchronized void trackResults(
            final List<com.dji.Detector.tflite.Classifier.Recognition> results, final byte[] frame, final long timestamp) {
        logger.i("Processing %d results from %d", results.size(), timestamp);
        processResults(timestamp, results, frame);
    }

    public synchronized void draw(final Canvas canvas) {
        final boolean rotated = sensorOrientation % 180 == 90;
        final float multiplier =
                Math.min(canvas.getHeight() / (float) (rotated ? frameWidth : frameHeight),
                        canvas.getWidth() / (float) (rotated ? frameHeight : frameWidth));
        frameToCanvasMatrix =
                ImageUtils.getTransformationMatrix(
                        frameWidth,
                        frameHeight,
                        (int) (multiplier * (rotated ? frameHeight : frameWidth)),
                        (int) (multiplier * (rotated ? frameWidth : frameHeight)),
                        sensorOrientation,
                        true);
        for (final TrackedRecognition recognition : trackedObjects) {
            final RectF trackedPos =
                    (objectTracker != null)
                            ? recognition.trackedObject.getTrackedPositionInPreviewFrame()
                            : new RectF(recognition.location);

            getFrameToCanvasMatrix().mapRect(trackedPos);
            boxPaint.setColor(recognition.color);

            final float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
            canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);

            final String labelString =
                    !TextUtils.isEmpty(recognition.title)
                            ? String.format("%s %.2f", recognition.title, recognition.detectionConfidence)
                            : String.format("%.2f", recognition.detectionConfidence);
            borderedText.drawText(canvas, trackedPos.left + cornerSize, trackedPos.bottom, labelString);
        }
    }

    private boolean initialized = false;

    public synchronized void onFrame(
            final int w,
            final int h,
            final int rowStride,
            final int sensorOrientation,
            final byte[] frame,
            final long timestamp) {
        if (objectTracker == null && !initialized) {
            ObjectTracker.clearInstance();

            logger.i("Initializing ObjectTracker: %dx%d", w, h);
            objectTracker = ObjectTracker.getInstance(w, h, rowStride, true);
            frameWidth = w;
            frameHeight = h;
            this.sensorOrientation = sensorOrientation;
            initialized = true;

            if (objectTracker == null) {
                String message =
                        "Object tracking support not found. "
                                + "See tensorflow/examples/android/README.md for details.";
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                logger.e(message);
            }
        }

        if (objectTracker == null) {
            return;
        }

        objectTracker.nextFrame(frame, null, timestamp, null, true);

        // Clean up any objects not worth tracking any more.
        final LinkedList<TrackedRecognition> copyList =
                new LinkedList<TrackedRecognition>(trackedObjects);
        for (final TrackedRecognition recognition : copyList) {
            final ObjectTracker.TrackedObject trackedObject = recognition.trackedObject;
            final float correlation = trackedObject.getCurrentCorrelation();
            if (correlation < MIN_CORRELATION) {
                logger.v("Removing tracked object %s because NCC is %.2f", trackedObject, correlation);
                trackedObject.stopTracking();
                trackedObjects.remove(recognition);

                availableColors.add(recognition.color);
            }
        }
    }

    private void processResults(
            final long timestamp, final List<com.dji.Detector.tflite.Classifier.Recognition> results, final byte[] originalFrame) {
        logger.e("got in PROCESSRESULTS size results = " + results.size());
        final List<Pair<Float, com.dji.Detector.tflite.Classifier.Recognition>> rectsToTrack = new LinkedList<Pair<Float, com.dji.Detector.tflite.Classifier.Recognition>>();
        if (rectsToTrack.isEmpty() == true) {
            logger.e("rectstoTrack empty");
        }
        screenRects.clear();
        final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());
        logger.e("cleared screen rects and created rgbFrametoScreeen");
        for (final com.dji.Detector.tflite.Classifier.Recognition result : results) {
            if (result.getLocation() == null) {
                logger.e("LOCATION NULL");
                continue;
            }
            final RectF detectionFrameRect = new RectF(result.getLocation());

            final RectF detectionScreenRect = new RectF();
            rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

            logger.v(
                    "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

            screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                logger.w("Degenerate rectangle! " + detectionFrameRect);
                continue;
            }

            rectsToTrack.add(new Pair<Float, com.dji.Detector.tflite.Classifier.Recognition>(result.getConfidence(), result));
        }

        if (rectsToTrack.isEmpty()) {
            logger.v("Nothing to track, aborting.");
            if (!debug) {
                if (null != mSendVirtualStickDataTimer) {
                    mSendVirtualStickDataTask.setmYaw(0);
                    mSendVirtualStickDataTask.setmThrottle(0);
                   /* mSendVirtualStickDataTask.cancel();
                    mSendVirtualStickDataTask = null;
                    mSendVirtualStickDataTimer.cancel();
                    mSendVirtualStickDataTimer.purge();
                    mSendVirtualStickDataTimer = null;*/
                }
            }
            return;
        }

        if (objectTracker == null) {
            trackedObjects.clear();
            for (final Pair<Float, com.dji.Detector.tflite.Classifier.Recognition> potential : rectsToTrack) {
                final TrackedRecognition trackedRecognition = new TrackedRecognition();
                trackedRecognition.detectionConfidence = potential.first;
                trackedRecognition.location = new RectF(potential.second.getLocation());
                trackedRecognition.trackedObject = null;
                trackedRecognition.title = potential.second.getTitle();
                trackedRecognition.color = COLORS[trackedObjects.size()];
                trackedObjects.add(trackedRecognition);

                if (trackedObjects.size() >= COLORS.length) {
                    break;
                }
            }
            return;
        }

        logger.i("%d rects to track", rectsToTrack.size());
        for (final Pair<Float, com.dji.Detector.tflite.Classifier.Recognition> potential : rectsToTrack) {
            handleDetection(originalFrame, timestamp, potential);
        }
    }

    private void handleDetection(
            final byte[] frameCopy, final long timestamp, final Pair<Float, com.dji.Detector.tflite.Classifier.Recognition> potential) {
         logger.e("COUNTER = " + counter);
        if (counter >= 2) { //weakened condition to send flight instruction


            // Get label from detected thing
            final String title = potential.second.getTitle();
            logger.e("string title is : " + title);


            if(title.equals("Found")){
                // Pikachu detected. Rotate drone in order to center it on screen
                logger.e("got inside command sending");


                final ObjectTracker.TrackedObject potentialObject =
                    objectTracker.trackObject(potential.second.getLocation(), timestamp, frameCopy);

                // Center of Pikachu bounding box (horizontal only)
                float centerXo = potentialObject.getTrackedPositionInPreviewFrame().centerX();
                // Center of screen (horizontal only)
                float centerXf = frameWidth / 2;

                //Angular velocity for rotation (Yaw control) is proportional to the distance between bounding box center and screen center (the closer it is, the slower it rotates)
                float veloc = ((centerXo - centerXf) / (centerXf)) * 40;
                mYaw = veloc;
                if (!debug) {
                    if (null == mSendVirtualStickDataTimer) { //////////////////////////////////////////////////////////////////////////////////
                        logger.e("creating task");
                        mSendVirtualStickDataTask = new SendVirtualStickDataTask();
                        mSendVirtualStickDataTimer = new Timer();
                        mSendVirtualStickDataTask.setmYaw(mYaw);
                        mSendVirtualStickDataTask.setmThrottle(0);
                        mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                    } else {
                        logger.e("task already created, just setting Yaw");
                        if (abs(mYaw) < 0.25f) { // In case angular velocity is too low, do not rotate at all
                            // The commented part below corresponds to the photo shooting instruction when object is centered well enough
                                        /* if(!photo) {
                                            logger.e("SHOOT PHOTO");

                                            logger.e("setting SHOOT_PHOTO mode");
                                            final Camera camera = Insulator_DetectorApplication.getCameraInstance();

                                            if (camera != null) {
                                                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                                                    @Override
                                                    public void onResult(DJIError error) {

                                                        if (error == null) {
                                                            logger.e("Switch Camera Mode Succeeded");
                                                        } else {
                                                            logger.e(error.getDescription());

                                                        }
                                                    }
                                                });
                                                final Handler handler = new Handler();
                                                SettingsDefinitions.ShootPhotoMode photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE; // Set the camera capture mode as Single mode
                                                camera.setShootPhotoMode(photoMode, new CommonCallbacks.CompletionCallback() {
                                                    @Override
                                                    public void onResult(DJIError djiError) {
                                                        if (null == djiError) {
                                                            handler.postDelayed(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    camera.startShootPhoto(new CommonCallbacks.CompletionCallback() {
                                                                        @Override
                                                                        public void onResult(DJIError djiError) {
                                                                            if (djiError == null) {
                                                                                logger.e("take photo: success");
                                                                            } else {
                                                                                logger.e(djiError.getDescription());
                                                                            }
                                                                        }
                                                                    });
                                                                }
                                                            }, 2000);
                                                        }
                                                    }
                                                });

                                                photo = true;
                                            }


                                        } */
                            // In case angular velocity is too low, do not rotate at all
                            mSendVirtualStickDataTask.setmYaw(0);
                            mSendVirtualStickDataTask.setmThrottle(0);
                        } else {
                            mSendVirtualStickDataTask.setmYaw(mYaw);
                            mSendVirtualStickDataTask.setmThrottle(0);
                        }
                    }
                }////////////////////////////////////////////////////////////////////////////
                     //   return; // commented because i want to see if the bounding box keeps getting drawn
            }

            if(title.equals("Tower Body") || title.equals("Tower Top")){
            // The commented part below corresponds to flight instructions for going up when tower parts were detected. Not used anymore

               /* logger.e("got inside command sending TOWER TOWER TOWER");
                final ObjectTracker.TrackedObject potentialObject =
                    objectTracker.trackObject(potential.second.getLocation(), timestamp, frameCopy);

                //float centerYo = potentialObject.getTrackedPositionInPreviewFrame().centerY();
                //float centerYf = frameHeight / 2;
               // float updown = -((centerYo - centerYf) / (centerYf)) * (1/4);
                float updown = 0.1f;
                mThrottle = updown;

                if (null == mSendVirtualStickDataTimer) { //////////////////////////////////////////////////////////////////////////////////
                    logger.e("creating task");
                    mSendVirtualStickDataTask = new SendVirtualStickDataTask();
                    mSendVirtualStickDataTimer = new Timer();
                    mSendVirtualStickDataTask.setmYaw(0);
                    mSendVirtualStickDataTask.setmThrottle(mThrottle);
                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                }
                else {
                    logger.e("SENDING SHIT");
                mSendVirtualStickDataTask.setmYaw(0);
                mSendVirtualStickDataTask.setmThrottle(mThrottle);
                } */ ////////////////////////////////////////////////////////////////////////////
            //   return; // commented because i want to see if the bounding box keeps getting drawn
            }

        }


        final ObjectTracker.TrackedObject potentialObject =
                objectTracker.trackObject(potential.second.getLocation(), timestamp, frameCopy);

        final float potentialCorrelation = potentialObject.getCurrentCorrelation();
        logger.v(
                "Tracked object went from %s to %s with correlation %.2f",
                potential.second, potentialObject.getTrackedPositionInPreviewFrame(), potentialCorrelation);

        if (potentialCorrelation < MARGINAL_CORRELATION) {
            logger.v("Correlation too low to begin tracking %s.", potentialObject);
            potentialObject.stopTracking();
            counter = 0;
            if (!debug) {
                if (null != mSendVirtualStickDataTimer) {
                    mSendVirtualStickDataTask.setmYaw(0);
                    mSendVirtualStickDataTask.setmThrottle(0);
                /*mSendVirtualStickDataTask.cancel();
                mSendVirtualStickDataTask = null;
                mSendVirtualStickDataTimer.cancel();
                mSendVirtualStickDataTimer.purge();
                mSendVirtualStickDataTimer = null;*/
                }
            }
            return;
        }

        final List<TrackedRecognition> removeList = new LinkedList<TrackedRecognition>();

        float maxIntersect = 0.0f;

        // This is the current tracked object whose color we will take. If left null we'll take the
        // first one from the color queue.
        TrackedRecognition recogToReplace = null;

        // Look for intersections that will be overridden by this object or an intersection that would
        // prevent this one from being placed.
        for (final TrackedRecognition trackedRecognition : trackedObjects) {
            final RectF a = trackedRecognition.trackedObject.getTrackedPositionInPreviewFrame();
            final RectF b = potentialObject.getTrackedPositionInPreviewFrame();
            final RectF intersection = new RectF();
            final boolean intersects = intersection.setIntersect(a, b);

            final float intersectArea = intersection.width() * intersection.height();
            final float totalArea = a.width() * a.height() + b.width() * b.height() - intersectArea;
            final float intersectOverUnion = intersectArea / totalArea;

            // If there is an intersection with this currently tracked box above the maximum overlap
            // percentage allowed, either the new recognition needs to be dismissed or the old
            // recognition needs to be removed and possibly replaced with the new one.
            if (intersects && intersectOverUnion > MAX_OVERLAP) {
                if (potential.first < trackedRecognition.detectionConfidence
                        && trackedRecognition.trackedObject.getCurrentCorrelation() > MARGINAL_CORRELATION) {
                    // If track for the existing object is still going strong and the detection score was
                    // good, reject this new object.
                    potentialObject.stopTracking();
                    counter++;
                    return;
                } else {
                    removeList.add(trackedRecognition);

                    // Let the previously tracked object with max intersection amount donate its color to
                    // the new object.
                    if (intersectOverUnion > maxIntersect) {
                        maxIntersect = intersectOverUnion;
                        recogToReplace = trackedRecognition;
                    }
                }
            }
        }

        // If we're already tracking the max object and no intersections were found to bump off,
        // pick the worst current tracked object to remove, if it's also worse than this candidate
        // object.
        if (availableColors.isEmpty() && removeList.isEmpty()) {
            for (final TrackedRecognition candidate : trackedObjects) {
                if (candidate.detectionConfidence < potential.first) {
                    if (recogToReplace == null
                            || candidate.detectionConfidence < recogToReplace.detectionConfidence) {
                        // Save it so that we use this color for the new object.
                        recogToReplace = candidate;
                    }
                }
            }
            if (recogToReplace != null) {
                logger.v("Found non-intersecting object to remove.");
                removeList.add(recogToReplace);
            } else {
                logger.v("No non-intersecting object found to remove");
            }
        }

        // Remove everything that got intersected.
        for (final TrackedRecognition trackedRecognition : removeList) {
            logger.v(
                    "Removing tracked object %s with detection confidence %.2f, correlation %.2f",
                    trackedRecognition.trackedObject,
                    trackedRecognition.detectionConfidence,
                    trackedRecognition.trackedObject.getCurrentCorrelation());
            trackedRecognition.trackedObject.stopTracking();
            trackedObjects.remove(trackedRecognition);
            if (trackedRecognition != recogToReplace) {
                availableColors.add(trackedRecognition.color);
            }
        }

        if (recogToReplace == null && availableColors.isEmpty()) {
            logger.e("No room to track this object, aborting.");
            potentialObject.stopTracking();
            counter = 0;
            if (!debug) {
                if (null != mSendVirtualStickDataTimer) {
                    mSendVirtualStickDataTask.setmYaw(0);
                    mSendVirtualStickDataTask.setmThrottle(0);
               /* mSendVirtualStickDataTask.cancel();
                mSendVirtualStickDataTask = null;
                mSendVirtualStickDataTimer.cancel();
                mSendVirtualStickDataTimer.purge();
                mSendVirtualStickDataTimer = null;*/
                }
            }
            return;
        }

        // Finally safe to say we can track this object.
        logger.v(
                "Tracking object %s (%s) with detection confidence %.2f at position %s",
                potentialObject,
                potential.second.getTitle(),
                potential.first,
                potential.second.getLocation());
        counter++;
        final TrackedRecognition trackedRecognition = new TrackedRecognition();
        trackedRecognition.detectionConfidence = potential.first;
        trackedRecognition.trackedObject = potentialObject;
        trackedRecognition.title = potential.second.getTitle();

        // Use the color from a replaced object before taking one from the color queue.
        trackedRecognition.color =
                recogToReplace != null ? recogToReplace.color : availableColors.poll();
        trackedObjects.add(trackedRecognition);
    }
}
