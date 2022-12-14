/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
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

package org.tensorflow.lite.examples.yolo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.tensorflow.lite.examples.yolo.customview.OverlayView;
import org.tensorflow.lite.examples.yolo.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.yolo.env.BorderedText;
import org.tensorflow.lite.examples.yolo.env.ImageUtils;
import org.tensorflow.lite.examples.yolo.env.Logger;
import org.tensorflow.lite.examples.yolo.tflite.Classifier;
import org.tensorflow.lite.examples.yolo.tflite.YoloV4Classifier;
import org.tensorflow.lite.examples.yolo.tracking.MultiBoxTracker;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
    private static final Logger LOGGER = new Logger();

    public static int TF_OD_API_INPUT_SIZE = 320;
    private static final boolean TF_OD_API_IS_QUANTIZED = false;
    private static String TF_OD_API_MODEL_FILE = "yolov4-fp16.tflite";

    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labels.txt";

    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
    private static final boolean MAINTAIN_ASPECT = false;
    //private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final Size DESIRED_PREVIEW_SIZE = new Size(1920, 1080);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    OverlayView trackingOverlay;
    private Integer sensorOrientation;

    private static Classifier detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private BorderedText borderedText;

    private ArrayList<Integer> seriesOfNumbers = new ArrayList<Integer>();
    private int inference_counter = 0;

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                    YoloV4Classifier.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_IS_QUANTIZED);
//            detector = TFLiteObjectDetectionAPIModel.create(
//                    getAssets(),
//                    TF_OD_API_MODEL_FILE,
//                    TF_OD_API_LABELS_FILE,
//                    TF_OD_API_INPUT_SIZE,
//                    TF_OD_API_IS_QUANTIZED);
            cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        tracker.draw(canvas);
                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }
                    }
                });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }

    public void save(String text, String FILE_NAME) {
        FileOutputStream fos = null;

        seriesOfNumbers.add(Integer.valueOf(text));
        text = text + "\n";

        try {
            fos = openFileOutput(FILE_NAME, MODE_APPEND);
            fos.write(text.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void clearObjectDetector(String model, int size) {
        TF_OD_API_MODEL_FILE = model;
        TF_OD_API_INPUT_SIZE = size;
        detector = null;
    }

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        if (detector != null) {
                            LOGGER.i("Running detection on image " + currTimestamp);
                            //LOGGER.i("Details: " + TF_OD_API_MODEL_FILE + " " + TF_OD_API_INPUT_SIZE);
                            final long startTime = SystemClock.uptimeMillis();
                            final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        if (inference_counter < 51) {
                            inference_counter++;
                            save(String.valueOf(lastProcessingTimeMs), "inference-yolo-fp16.txt");
                        }

                        /*if (inference_counter == 50) {
                            Integer sum = 0;
                            for (Integer inference : seriesOfNumbers) {
                                sum += inference;
                            }
                            sum /= inference_counter++;
                            save(String.valueOf(sum), "inference-yolo-tiny.txt");
                        }*/

                            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                            final Canvas canvas = new Canvas(cropCopyBitmap);
                            final Paint paint = new Paint();
                            paint.setColor(Color.RED);
                            paint.setStyle(Style.STROKE);
                            paint.setStrokeWidth(2.0f);

                            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                            switch (MODE) {
                                case TF_OD_API:
                                    minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                    break;
                            }

                            final List<Classifier.Recognition> mappedRecognitions =
                                    new LinkedList<Classifier.Recognition>();

                            for (final Classifier.Recognition result : results) {
                                final RectF location = result.getLocation();
                                if (location != null && result.getConfidence() >= minimumConfidence) {
                                    canvas.drawRect(location, paint);

                                    float left_loc = (location.right + location.left) / 2;

                                    if (left_loc <= 96) {
                                        LOGGER.i("Location: Left at " + left_loc);
                                    } else if (left_loc >= 224) {
                                        LOGGER.i("Location: Right at " + left_loc);
                                    } else {
                                        LOGGER.i("Location: Center at " + left_loc);
                                    }


                                    LOGGER.i("Bottom: " + location.bottom +
                                            " Left: " + location.left +
                                            " Right: " + location.right +
                                            " Top: " + location.top);

                                    cropToFrameTransform.mapRect(location);

                                    result.setLocation(location);
                                    mappedRecognitions.add(result);
                                }
                            }

                            tracker.trackResults(mappedRecognitions, currTimestamp);
                            trackingOverlay.postInvalidate();

                            computingDetection = false;

                            runOnUiThread(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            showFrameInfo(previewWidth + "x" + previewHeight);
                                            showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                                            showInference(lastProcessingTimeMs + "ms");
                                        }
                                    });
                        }
                    }
                });
    }

    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(() -> detector.setUseNNAPI(isChecked));
    }

    @Override
    protected void setNumThreads(final int numThreads) {
        runInBackground(() -> detector.setNumThreads(numThreads));
    }
}
