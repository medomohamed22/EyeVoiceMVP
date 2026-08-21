package com.example.eyevoice;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS = "eye_voice_prefs";
    public static final String MODE_VOICE = "voice";
    public static final String MODE_BLINK = "blink";

    private TextView statusText;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> updateStatus());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button permissionsButton = findViewById(R.id.permissionsButton);
        Button overlayButton = findViewById(R.id.overlayButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button trainingButton = findViewById(R.id.trainingButton);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);
        RadioGroup modeGroup = findViewById(R.id.modeGroup);
        RadioButton modeVoice = findViewById(R.id.modeVoice);
        RadioButton modeBlink = findViewById(R.id.modeBlink);

        String savedMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("control_mode", MODE_VOICE);
        if (MODE_BLINK.equals(savedMode)) modeBlink.setChecked(true);
        else modeVoice.setChecked(true);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String mode = checkedId == R.id.modeBlink ? MODE_BLINK : MODE_VOICE;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("control_mode", mode).apply();
        });

        permissionsButton.setOnClickListener(v -> requestRuntimePermissions());

        overlayButton.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        trainingButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                statusText.setText("اسمح أولًا بالظهور فوق التطبيقات، ثم ابدأ التدريب.");
                return;
            }
            startController();
            startActivity(new Intent(this, TrainingActivity.class));
        });

        startButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                statusText.setText("لازم تسمح بالظهور فوق التطبيقات أولًا.");
                return;
            }
            boolean calibrated = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("calibrated", false);
            if (!calibrated) {
                statusText.setText("اعمل اختبار وتعلّم/معايرة أولًا للحصول على دقة مناسبة.");
                return;
            }
            startController();
            statusText.setText("التحكم يعمل. افتح تطبيقًا آخر وجرب الوضع المختار.");
        });

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, GazeForegroundService.class));
            statusText.setText("تم إيقاف التحكم.");
        });

        updateStatus();
    }

    private void startController() {
        Intent serviceIntent = new Intent(this, GazeForegroundService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean calibrated = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("calibrated", false);
        float score = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat("training_score", 0f);

        statusText.setText(
                "Overlay: " + (overlay ? "جاهز" : "غير مسموح") +
                "\nالمعايرة: " + (calibrated ? "تمت" : "لم تتم") +
                (calibrated ? "\nآخر نتيجة تدريب: " + Math.round(score) + "%" : "") +
                "\nAccessibility: فعّله يدويًا من إعدادات الهاتف."
        );
    }
}
