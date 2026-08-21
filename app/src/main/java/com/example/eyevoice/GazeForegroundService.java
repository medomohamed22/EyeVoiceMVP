package com.example.eyevoice;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GazeForegroundService extends LifecycleService {

    public static final String ACTION_CALIBRATE_POINT =
            "com.example.eyevoice.CALIBRATE_POINT";

    private static final String CHANNEL_ID = "eye_voice_control";
    private static final int NOTIFICATION_ID = 10;
    private static final String MODEL_NAME = "face_landmarker.task";

    private WindowManager windowManager;
    private TextView cursor;
    private WindowManager.LayoutParams cursorParams;
    private int screenWidth, screenHeight;

    private float cursorNX = 0.5f, cursorNY = 0.5f;
    private final float smoothing = 0.26f;

    private ExecutorService cameraExecutor;
    private FaceLandmarker faceLandmarker;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean speechActive = false;
    private SharedPreferences prefs;

    private int calibrationIndex = -1;
    private float calibrationSX, calibrationSY;
    private long calibrationUntil = 0;
    private double calibrationSumX = 0, calibrationSumY = 0;
    private int calibrationFrames = 0;

    private boolean eyesClosed = false;
    private int blinkSequence = 0;
    private long firstBlinkAt = 0;
    private long lastBlinkActionAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Eye Voice MVP")
                .setContentText("تتبع البؤبؤ والتحكم يعملان الآن")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA |
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupOverlay();
        setupFaceLandmarker();
        startCamera();
        startSpeech();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CALIBRATE_POINT.equals(intent.getAction())) {
            calibrationIndex = intent.getIntExtra("index", -1);
            calibrationSX = intent.getFloatExtra("sx", 0.5f);
            calibrationSY = intent.getFloatExtra("sy", 0.5f);
            calibrationSumX = calibrationSumY = 0;
            calibrationFrames = 0;
            calibrationUntil = SystemClock.uptimeMillis() + 1100;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void setupFaceLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_NAME)
                    .build();

            FaceLandmarker.FaceLandmarkerOptions options =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumFaces(1)
                            .setMinFaceDetectionConfidence(0.5f)
                            .setMinFacePresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setResultListener(this::onFaceResult)
                            .setErrorListener(error -> error.printStackTrace())
                            .build();

            faceLandmarker = FaceLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupOverlay() {
        if (!Settings.canDrawOverlays(this)) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;

        cursor = new TextView(this);
        cursor.setText("◎");
        cursor.setTextSize(34);
        cursor.setTextColor(Color.CYAN);
        cursor.setGravity(Gravity.CENTER);
        cursor.setBackgroundColor(Color.argb(40, 0, 0, 0));

        int overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        cursorParams = new WindowManager.LayoutParams(
                74, 74, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        cursorParams.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(cursor, cursorParams);
    }

    private void startCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED || faceLandmarker == null) return;

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, analysis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, getMainExecutor());
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        try {
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            Bitmap buffer = Bitmap.createBitmap(
                    imageProxy.getWidth(), imageProxy.getHeight(), Bitmap.Config.ARGB_8888);
            buffer.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());

            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            matrix.postScale(-1f, 1f, buffer.getWidth(), buffer.getHeight());

            Bitmap rotated = Bitmap.createBitmap(
                    buffer, 0, 0, buffer.getWidth(), buffer.getHeight(), matrix, true);

            MPImage mpImage = new BitmapImageBuilder(rotated).build();
            long timestamp = SystemClock.uptimeMillis();
            faceLandmarker.detectAsync(mpImage, timestamp);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            imageProxy.close();
        }
    }

    private void onFaceResult(FaceLandmarkerResult result, MPImage input) {
        if (result.faceLandmarks().isEmpty()) return;
        List<NormalizedLandmark> lm = result.faceLandmarks().get(0);
        if (lm.size() < 478) return;

        double[] raw = calculateIrisGaze(lm);
        double gx = raw[0], gy = raw[1];
        handleCalibrationSample(gx, gy);

        float[] mapped = CalibrationModel.map(gx, gy, prefs);

        if (prefs.getBoolean("calibrated", false)) {
            cursorNX += smoothing * (mapped[0] - cursorNX);
            cursorNY += smoothing * (mapped[1] - cursorNY);
            prefs.edit()
                    .putFloat("last_cursor_x", cursorNX)
                    .putFloat("last_cursor_y", cursorNY)
                    .apply();
            updateCursor();
        }

        detectDoubleBlink(lm);
    }

    private double[] calculateIrisGaze(List<NormalizedLandmark> lm) {
        // Iris centers: 468..472 and 473..477 in MediaPipe's 478-point mesh.
        double irisAX = avgX(lm, 468,469,470,471,472);
        double irisAY = avgY(lm, 468,469,470,471,472);
        double irisBX = avgX(lm, 473,474,475,476,477);
        double irisBY = avgY(lm, 473,474,475,476,477);

        // Normalize iris position inside each eye so head distance matters less.
        double ax = ratio(irisAX, lm.get(33).x(), lm.get(133).x());
        double bx = ratio(irisBX, lm.get(362).x(), lm.get(263).x());

        double ay = ratio(irisAY, lm.get(159).y(), lm.get(145).y());
        double by = ratio(irisBY, lm.get(386).y(), lm.get(374).y());

        double gx = (ax + bx) / 2.0;
        double gy = (ay + by) / 2.0;
        return new double[]{gx, gy};
    }

    private void detectDoubleBlink(List<NormalizedLandmark> lm) {
        double eyeA = eyeOpenRatio(lm, 33, 133, 159, 145);
        double eyeB = eyeOpenRatio(lm, 362, 263, 386, 374);
        double openness = (eyeA + eyeB) / 2.0;

        boolean nowClosed = openness < 0.13;
        long now = SystemClock.uptimeMillis();

        if (nowClosed && !eyesClosed) {
            eyesClosed = true;
        } else if (!nowClosed && eyesClosed) {
            eyesClosed = false; // a complete blink occurred

            if (blinkSequence == 0 || now - firstBlinkAt > 900) {
                blinkSequence = 1;
                firstBlinkAt = now;
            } else {
                blinkSequence++;
            }

            if (blinkSequence >= 2 && now - firstBlinkAt <= 900) {
                blinkSequence = 0;
                if (now - lastBlinkActionAt > 1000) {
                    lastBlinkActionAt = now;
                    if (MainActivity.MODE_BLINK.equals(
                            prefs.getString("control_mode", MainActivity.MODE_VOICE))) {
                        performCursorClick(false);
                    }
                }
            }
        }

        if (blinkSequence > 0 && now - firstBlinkAt > 900) blinkSequence = 0;
    }

    private double eyeOpenRatio(List<NormalizedLandmark> lm,
                                int left, int right, int upper, int lower) {
        double width = distance(lm.get(left), lm.get(right));
        double height = distance(lm.get(upper), lm.get(lower));
        return width < 1e-6 ? 1.0 : height / width;
    }

    private void handleCalibrationSample(double gx, double gy) {
        if (calibrationIndex < 0) return;
        long now = SystemClock.uptimeMillis();

        // Ignore the first 250 ms so the eye has time to settle on the new target.
        if (now < calibrationUntil - 850) return;

        if (now <= calibrationUntil) {
            calibrationSumX += gx;
            calibrationSumY += gy;
            calibrationFrames++;
            return;
        }

        if (calibrationFrames >= 5) {
            double avgX = calibrationSumX / calibrationFrames;
            double avgY = calibrationSumY / calibrationFrames;

            int count = prefs.getInt("cal_sample_count", 0);
            prefs.edit()
                    .putFloat("cal_" + count + "_gx", (float) avgX)
                    .putFloat("cal_" + count + "_gy", (float) avgY)
                    .putFloat("cal_" + count + "_sx", calibrationSX)
                    .putFloat("cal_" + count + "_sy", calibrationSY)
                    .putInt("cal_sample_count", count + 1)
                    .putInt("calibration_completed_index", calibrationIndex)
                    .apply();

            if (calibrationIndex == 8) {
                CalibrationModel.fitAndSave(CalibrationModel.loadSamples(prefs), prefs);
            }
        }

        calibrationIndex = -1;
    }

    private void updateCursor() {
        if (cursor == null || windowManager == null || cursorParams == null) return;
        cursor.post(() -> {
            cursorParams.x = Math.round(cursorNX * screenWidth - 37);
            cursorParams.y = Math.round(cursorNY * screenHeight - 37);
            try { windowManager.updateViewLayout(cursor, cursorParams); }
            catch (Exception ignored) {}
        });
    }

    private void startSpeech() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                speechActive = false; restartSpeechSoon();
            }
            @Override public void onResults(android.os.Bundle results) {
                handleSpeechResults(results);
                speechActive = false; restartSpeechSoon();
            }
            @Override public void onPartialResults(android.os.Bundle partialResults) {
                handleSpeechResults(partialResults);
            }
            @Override public void onEvent(int eventType, android.os.Bundle params) {}
        });

        beginListening();
    }

    private void beginListening() {
        if (speechRecognizer == null || speechActive) return;
        try {
            speechActive = true;
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            speechActive = false;
        }
    }

    private void restartSpeechSoon() {
        if (cursor != null) cursor.postDelayed(this::beginListening, 450);
    }

    private void handleSpeechResults(android.os.Bundle bundle) {
        ArrayList<String> results =
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (results == null || results.isEmpty()) return;
        executeCommand(results.get(0).toLowerCase(new Locale("ar", "EG")));
    }

    private void executeCommand(String text) {
        EyeAccessibilityService a11y = EyeAccessibilityService.instance;
        if (a11y == null) return;

        if (containsAny(text, "افتح", "اضغط", "اختار", "دوس")) {
            if (MainActivity.MODE_VOICE.equals(
                    prefs.getString("control_mode", MainActivity.MODE_VOICE))) {
                performCursorClick(true);
            }
        } else if (containsAny(text, "ارجع", "رجوع", "ورا")) {
            a11y.goBack();
        } else if (containsAny(text, "الرئيسية", "هوم", "الشاشة الرئيسية")) {
            a11y.goHome();
        } else if (containsAny(text, "انزل", "لتحت", "تحت")) {
            a11y.scrollDown();
        } else if (containsAny(text, "اطلع", "لفوق", "فوق")) {
            a11y.scrollUp();
        }
    }

    private void performCursorClick(boolean voice) {
        EyeAccessibilityService a11y = EyeAccessibilityService.instance;
        if (a11y == null) return;
        a11y.tap(cursorNX * screenWidth, cursorNY * screenHeight);
        String key = voice ? "voice_click_count" : "blink_click_count";
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private double avgX(List<NormalizedLandmark> lm, int... ids) {
        double sum = 0;
        for (int id : ids) sum += lm.get(id).x();
        return sum / ids.length;
    }

    private double avgY(List<NormalizedLandmark> lm, int... ids) {
        double sum = 0;
        for (int id : ids) sum += lm.get(id).y();
        return sum / ids.length;
    }

    private double ratio(double value, double a, double b) {
        double min = Math.min(a,b), max = Math.max(a,b);
        if (max-min < 1e-6) return 0.5;
        return (value-min)/(max-min);
    }

    private double distance(NormalizedLandmark a, NormalizedLandmark b) {
        double dx=a.x()-b.x(), dy=a.y()-b.y();
        return Math.sqrt(dx*dx+dy*dy);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Eye Voice Control", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }
        if (faceLandmarker != null) faceLandmarker.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();

        if (windowManager != null && cursor != null) {
            try { windowManager.removeView(cursor); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
