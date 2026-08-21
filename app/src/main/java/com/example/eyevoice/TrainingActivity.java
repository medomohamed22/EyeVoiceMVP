package com.example.eyevoice;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class TrainingActivity extends AppCompatActivity {

    private static final float[][] CAL_POINTS = {
            {0.12f,0.18f},{0.50f,0.18f},{0.88f,0.18f},
            {0.12f,0.50f},{0.50f,0.50f},{0.88f,0.50f},
            {0.12f,0.82f},{0.50f,0.82f},{0.88f,0.82f}
    };

    private static final float[][] TEST_POINTS = {
            {0.20f,0.32f},{0.78f,0.35f},{0.25f,0.72f},{0.76f,0.74f},{0.50f,0.55f}
    };

    private CalibrationView view;
    private TextView title, info;
    private ProgressBar progress;
    private Button action;
    private SharedPreferences prefs;
    private Handler handler = new Handler(Looper.getMainLooper());

    private int calIndex = -1;
    private int testIndex = -1;
    private int testHits = 0;
    private int baselineClickCount = 0;
    private boolean waitingCalibration = false;
    private boolean testing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training);

        view = findViewById(R.id.calibrationView);
        title = findViewById(R.id.trainingTitle);
        info = findViewById(R.id.trainingInfo);
        progress = findViewById(R.id.trainingProgress);
        action = findViewById(R.id.trainingAction);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        info.setText("ثبّت الهاتف على مسافة مريحة. أثناء المعايرة حرّك عينيك فقط قدر الإمكان، وانظر إلى مركز الدائرة الصفراء.");
        view.setShowCursor(false);

        action.setOnClickListener(v -> startCalibration());
        handler.post(poll);
    }

    private void startCalibration() {
        prefs.edit()
                .putBoolean("calibrated", false)
                .putInt("cal_sample_count", 0)
                .putInt("calibration_completed_index", -1)
                .apply();

        calIndex = 0;
        testing = false;
        action.setEnabled(false);
        title.setText("معايرة البؤبؤ — 1 / 9");
        showCalibrationPoint();
    }

    private void showCalibrationPoint() {
        if (calIndex >= CAL_POINTS.length) {
            beginTest();
            return;
        }
        float[] p = CAL_POINTS[calIndex];
        view.setTarget(p[0], p[1]);
        view.setShowCursor(false);
        info.setText("انظر فقط إلى النقطة الصفراء وثبّت نظرك لحظة...");
        progress.setProgress(Math.round((calIndex / 9f) * 60f));

        Intent i = new Intent(this, GazeForegroundService.class);
        i.setAction(GazeForegroundService.ACTION_CALIBRATE_POINT);
        i.putExtra("index", calIndex);
        i.putExtra("sx", p[0]);
        i.putExtra("sy", p[1]);
        ContextCompat.startForegroundService(this, i);
        waitingCalibration = true;
    }

    private void beginTest() {
        if (!prefs.getBoolean("calibrated", false)) {
            title.setText("المعايرة لم تنجح");
            info.setText("لم تصل بيانات كافية من البؤبؤ. تأكد من الإضاءة، وأن الوجه كامل أمام الكاميرا، ثم أعد المحاولة.");
            action.setText("إعادة المحاولة");
            action.setEnabled(true);
            action.setOnClickListener(v -> startCalibration());
            return;
        }

        testing = true;
        testIndex = 0;
        testHits = 0;
        view.setShowCursor(true);
        baselineClickCount = totalClicks();
        title.setText("اختبار عملي — 1 / 5");
        showTestPoint();
    }

    private void showTestPoint() {
        if (testIndex >= TEST_POINTS.length) {
            finishTest();
            return;
        }
        float[] p = TEST_POINTS[testIndex];
        view.setTarget(p[0], p[1]);
        baselineClickCount = totalClicks();
        String mode = prefs.getString("control_mode", MainActivity.MODE_VOICE);
        if (MainActivity.MODE_BLINK.equals(mode)) {
            info.setText("حرّك المؤشر بعينيك إلى الهدف، ثم ارمش مرتين بسرعة وأنت ثابت عليه.");
        } else {
            info.setText("حرّك المؤشر بعينيك إلى الهدف، ثم قل «افتح» أو «اضغط».");
        }
        title.setText("اختبار عملي — " + (testIndex + 1) + " / 5");
        progress.setProgress(60 + Math.round((testIndex / 5f) * 40f));
    }

    private int totalClicks() {
        return prefs.getInt("voice_click_count", 0) + prefs.getInt("blink_click_count", 0);
    }

    private void evaluateTest() {
        if (!testing || testIndex < 0 || testIndex >= TEST_POINTS.length) return;

        float cx = prefs.getFloat("last_cursor_x", 0.5f);
        float cy = prefs.getFloat("last_cursor_y", 0.5f);
        view.setCursor(cx, cy);

        int clicks = totalClicks();
        if (clicks <= baselineClickCount) return;

        float[] t = TEST_POINTS[testIndex];
        double d = Math.sqrt((cx-t[0])*(cx-t[0]) + (cy-t[1])*(cy-t[1]));

        if (d < 0.16) testHits++;
        testIndex++;
        if (testIndex >= TEST_POINTS.length) finishTest();
        else showTestPoint();
    }

    private void finishTest() {
        testing = false;
        float score = 100f * testHits / TEST_POINTS.length;
        prefs.edit().putFloat("training_score", score).apply();
        progress.setProgress(100);
        title.setText("اكتمل التدريب — " + Math.round(score) + "%");

        if (score >= 80f) {
            info.setText("ممتاز. المؤشر وطريقة الاختيار يعملان بشكل جيد. يمكنك الآن الرجوع وتشغيل التحكم.");
        } else {
            info.setText("النظام يعمل لكن الدقة تحتاج تحسين. أعد المعايرة مع إضاءة أفضل وثبات أكبر للهاتف.");
        }

        action.setText("إنهاء");
        action.setEnabled(true);
        action.setOnClickListener(v -> finish());
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            float cx = prefs.getFloat("last_cursor_x", 0.5f);
            float cy = prefs.getFloat("last_cursor_y", 0.5f);
            if (testing) view.setCursor(cx, cy);

            if (waitingCalibration) {
                int done = prefs.getInt("calibration_completed_index", -1);
                if (done == calIndex) {
                    waitingCalibration = false;
                    calIndex++;
                    handler.postDelayed(() -> {
                        if (calIndex < 9) title.setText("معايرة البؤبؤ — " + (calIndex + 1) + " / 9");
                        showCalibrationPoint();
                    }, 350);
                }
            }

            evaluateTest();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
