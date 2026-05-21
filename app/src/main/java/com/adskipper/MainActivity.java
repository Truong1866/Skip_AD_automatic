package com.adskipper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_LOG    = "com.adskipper.LOG";
    public static final String EXTRA_LOG_MSG = "log_msg";

    private static final String PREFS   = "AdSkipperPrefs";
    private static final String KEY_API = "api_key";

    // Views
    private EditText      editApiKey;
    private Button        btnSaveKey, btnAccessibility, btnScreenCapture;
    private Button        btnOverlay;   // NEW: nút xin quyền overlay
    private Button        btnToggle, btnClearLog, btnAddTemplate, btnClearTemplates;
    private SwitchCompat  switchOverlay, switchYolo;
    private TextView      txtLog, txtAStatus, txtCStatus, txtAIStatus, txtTemplateCount;
    private View          dotA, dotC, dotAI;
    private View          dotOverlay;      // NEW: status dot cho overlay
    private TextView      txtOverlayStatus; // NEW

    private SharedPreferences prefs;
    private boolean running = false;
    private int     captureCode = 0;
    private Intent  captureData = null;
    private int     templateCount = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Launchers ─────────────────────────────────────────────────────

    private final ActivityResultLauncher<Intent> capturePermLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                captureCode = r.getResultCode();
                captureData = r.getData();
                ScreenCaptureService.setProjectionData(captureCode, captureData);
                setStatus(dotC, txtCStatus, true, "Đã cấp phép");
                log("✅ Quyền chụp màn hình OK");
            }
        });

    private final ActivityResultLauncher<Intent> pickImageLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                Uri uri = r.getData().getData();
                if (uri != null) loadTemplateFromUri(uri);
            }
        });

    // ── Log receiver ─────────────────────────────────────────────────

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            String msg = i.getStringExtra(EXTRA_LOG_MSG);
            if (msg != null) appendLog(msg);
        }
    };

    // ─────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        loadPrefs();
        setupListeners();
        updateStatuses();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, new IntentFilter(ACTION_LOG),
                Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, new IntentFilter(ACTION_LOG));
        }

        log("🚀 AdSkipper AI v2 — YOLO + Template + Claude fallback");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatuses();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(logReceiver); } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────
    // BIND VIEWS
    // ─────────────────────────────────────────────────────────────────

    private void bindViews() {
        editApiKey       = findViewById(R.id.editApiKey);
        btnSaveKey       = findViewById(R.id.btnSaveKey);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnScreenCapture = findViewById(R.id.btnScreenCapture);
        btnOverlay       = findViewById(R.id.btnOverlayPermission);
        btnToggle        = findViewById(R.id.btnToggleService);
        btnClearLog      = findViewById(R.id.btnClearLog);
        btnAddTemplate   = findViewById(R.id.btnAddTemplate);
        btnClearTemplates = findViewById(R.id.btnClearTemplates);
        switchOverlay    = findViewById(R.id.switchOverlay);
        switchYolo       = findViewById(R.id.switchYolo);
        txtLog           = findViewById(R.id.txtLog);
        txtAStatus       = findViewById(R.id.txtAccessibilityStatus);
        txtCStatus       = findViewById(R.id.txtCaptureStatus);
        txtAIStatus      = findViewById(R.id.txtAIStatus);
        txtTemplateCount = findViewById(R.id.txtTemplateCount);
        dotA             = findViewById(R.id.dotAccessibility);
        dotC             = findViewById(R.id.dotCapture);
        dotAI            = findViewById(R.id.dotAI);
        dotOverlay       = findViewById(R.id.dotOverlay);
        txtOverlayStatus = findViewById(R.id.txtOverlayStatus);
    }

    // ─────────────────────────────────────────────────────────────────
    // PREFS
    // ─────────────────────────────────────────────────────────────────

    private void loadPrefs() {
        String key = prefs.getString(KEY_API, "");
        if (!TextUtils.isEmpty(key)) {
            editApiKey.setText(key);
            ClaudeVisionClient.setApiKey(key);
        }
        templateCount = prefs.getInt("template_count", 0);
        updateTemplateCount();
    }

    // ─────────────────────────────────────────────────────────────────
    // LISTENERS
    // ─────────────────────────────────────────────────────────────────

    private void setupListeners() {
        btnSaveKey.setOnClickListener(v -> {
            String key = editApiKey.getText().toString().trim();
            prefs.edit().putString(KEY_API, key).apply();
            ClaudeVisionClient.setApiKey(key);
            setStatus(dotAI, txtAIStatus, !key.isEmpty(),
                key.isEmpty() ? "Không dùng" : "Sẵn sàng (fallback)");
            log("🔑 Claude API Key " + (key.isEmpty() ? "đã xóa" : "đã lưu"));
        });

        btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnScreenCapture.setOnClickListener(v -> {
            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            capturePermLauncher.launch(mpm.createScreenCaptureIntent());
        });

        // Xin quyền vẽ overlay (SYSTEM_ALERT_WINDOW)
        if (btnOverlay != null) {
            btnOverlay.setOnClickListener(v -> requestOverlayPermission());
        }

        btnToggle.setOnClickListener(v -> {
            if (!running) startAdSkipper();
            else stopAdSkipper();
        });

        btnClearLog.setOnClickListener(v -> txtLog.setText(""));

        btnAddTemplate.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_PICK);
            pick.setType("image/*");
            pickImageLauncher.launch(pick);
        });

        btnClearTemplates.setOnClickListener(v -> {
            clearSavedTemplates();
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc != null) svc.clearTemplates();
            log("🗑️ Đã xóa tất cả template");
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // OVERLAY PERMISSION
    // ─────────────────────────────────────────────────────────────────

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "✅ Đã có quyền overlay", Toast.LENGTH_SHORT).show();
            updateStatuses();
            return;
        }
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
        Toast.makeText(this,
            "Bật 'Cho phép hiển thị trên ứng dụng khác' rồi quay lại",
            Toast.LENGTH_LONG).show();
    }

    // ─────────────────────────────────────────────────────────────────
    // START / STOP
    // ─────────────────────────────────────────────────────────────────

    private void startAdSkipper() {
        if (!isAccessibilityOn()) {
            Toast.makeText(this, "⚠️ Bật Accessibility Service trước!", Toast.LENGTH_LONG).show();
            return;
        }
        if (captureCode == 0) {
            Toast.makeText(this, "⚠️ Cấp quyền chụp màn hình trước!", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "⚠️ Cần quyền 'Hiển thị trên ứng dụng khác' để dùng floating bubble!",
                Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        Intent svc = new Intent(this, ScreenCaptureService.class);
        svc.putExtra("showOverlay", switchOverlay.isChecked());
        startForegroundService(svc);

        running = true;
        btnToggle.setText("⏹  DỪNG BẢO VỆ");
        btnToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_red_light));

        // Load saved templates vào service sau khi service khởi động
        mainHandler.postDelayed(this::loadTemplatesIntoService, 1500);

        log("▶️ Dịch vụ bắt đầu — 1Hz scan, 1s delay click");
    }

    private void stopAdSkipper() {
        stopService(new Intent(this, ScreenCaptureService.class));
        running = false;
        btnToggle.setText("▶  BẮT ĐẦU BẢO VỆ");
        btnToggle.setBackgroundTintList(getColorStateList(R.color.colorAccent));
        log("⏹ Dịch vụ đã dừng");
    }

    // ─────────────────────────────────────────────────────────────────
    // TEMPLATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    private void loadTemplateFromUri(Uri uri) {
        try {
            InputStream is  = getContentResolver().openInputStream(uri);
            Bitmap      bmp = BitmapFactory.decodeStream(is);
            if (is != null) is.close();

            if (bmp == null) { log("❌ Không đọc được ảnh"); return; }

            String name = "template_" + (templateCount + 1);
            saveTemplateBitmap(name, bmp);
            templateCount++;
            prefs.edit().putInt("template_count", templateCount).apply();
            updateTemplateCount();

            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc != null) svc.addTemplate(name, bmp);
            else bmp.recycle();

            log("📷 Template #" + templateCount + " (" + bmp.getWidth() + "×" + bmp.getHeight() + ")");
            Toast.makeText(this, "✅ Template đã thêm!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            log("❌ Lỗi thêm template: " + e.getMessage());
        }
    }

    private void saveTemplateBitmap(String name, Bitmap bmp) throws Exception {
        FileOutputStream fos = openFileOutput(name + ".png", MODE_PRIVATE);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        fos.close();
    }

    private void loadTemplatesIntoService() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) return;
        int loaded = 0;
        for (int i = 1; i <= templateCount; i++) {
            try {
                FileInputStream fis = openFileInput("template_" + i + ".png");
                Bitmap bmp = BitmapFactory.decodeStream(fis);
                fis.close();
                if (bmp != null) { svc.addTemplate("template_" + i, bmp); loaded++; }
            } catch (Exception ignored) {}
        }
        if (loaded > 0) log("📂 Đã tải " + loaded + " template vào service");
    }

    private void clearSavedTemplates() {
        for (int i = 1; i <= templateCount; i++)
            deleteFile("template_" + i + ".png");
        templateCount = 0;
        prefs.edit().putInt("template_count", 0).apply();
        updateTemplateCount();
    }

    private void updateTemplateCount() {
        if (txtTemplateCount != null)
            txtTemplateCount.setText(templateCount + " ảnh");
    }

    // ─────────────────────────────────────────────────────────────────
    // STATUS
    // ─────────────────────────────────────────────────────────────────

    private void updateStatuses() {
        boolean acc = isAccessibilityOn();
        setStatus(dotA, txtAStatus, acc, acc ? "Đang hoạt động" : "Chưa bật");

        boolean overlay = Settings.canDrawOverlays(this);
        setStatus(dotOverlay, txtOverlayStatus, overlay,
            overlay ? "Đã cấp phép" : "Chưa cấp — nhấn để cấp");

        String key = prefs.getString(KEY_API, "");
        boolean hasKey = !TextUtils.isEmpty(key);
        setStatus(dotAI, txtAIStatus, hasKey,
            hasKey ? "Sẵn sàng (fallback)" : "Không dùng (tùy chọn)");
    }

    private void setStatus(View dot, TextView label, boolean ok, String text) {
        if (dot == null || label == null) return;
        dot.setBackground(getDrawable(ok ? R.drawable.dot_green : R.drawable.dot_red));
        label.setText(text);
        label.setTextColor(ok ? 0xFF44FF88 : 0xFFFF8844);
    }

    private boolean isAccessibilityOn() {
        String svc = getPackageName() + "/" + AdSkipperAccessibilityService.class.getName();
        try {
            String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(svc);
        } catch (Exception e) { return false; }
    }

    // ─────────────────────────────────────────────────────────────────
    // LOGGING
    // ─────────────────────────────────────────────────────────────────

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            if (txtLog == null) return;
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            txtLog.append("[" + ts + "] " + msg + "\n");
        });
    }

    /** Alias để code lemon gọi log() như cũ */
    void log(String msg) { appendLog(msg); }
}
