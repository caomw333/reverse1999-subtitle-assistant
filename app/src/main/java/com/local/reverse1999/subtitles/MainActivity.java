package com.local.reverse1999.subtitles;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 1;
    private static final int REQUEST_PROJECTION = 2;
    private static final int REQUEST_NOTIFICATIONS = 3;
    private static final int REQUEST_EXPORT_LOG = 4;

    private final List<Game> games = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pendingStart = false;
            notice = 0;
            refresh();
        }
    };

    private TextView status;
    private TextView gameLabel;
    private Spinner gamePicker;
    private Button start;
    private Button preview;
    private CheckBox asrMode;
    private CheckBox ocrMode;
    private SubtitleOverlay previewOverlay;
    private boolean pendingStart;
    private int notice;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingStart = state.getBoolean("pendingStart");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, padding, padding, padding);
        TextView title = addText(content, 32);
        title.setText(R.string.app_name);
        title.setTextColor(0xfff8eee0);
        addText(content, 17).setText(R.string.intro);
        addText(content, 15).setText(R.string.instructions);
        gameLabel = addText(content, 14);
        gameLabel.setText(R.string.game_label);
        gamePicker = new Spinner(this);
        gamePicker.setContentDescription(getString(R.string.game_label));
        content.addView(gamePicker, new LinearLayout.LayoutParams(-1, dp(48)));
        addModePicker(content);
        start = addButton(content, R.string.start_subtitles);
        start.setOnClickListener(view -> {
            if (SubtitleService.active) {
                startService(new Intent(this, SubtitleService.class).setAction(SubtitleService.STOP));
            } else {
                begin();
            }
        });
        preview = addButton(content, R.string.preview_subtitles);
        preview.setOnClickListener(view -> preview());
        status = addText(content, 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addFontPicker(content);
        addTransparencySlider(content);
        addButton(content, R.string.learning_records).setOnClickListener(
                view -> startActivity(new Intent(this, LearningActivity.class)));
        addButton(content, R.string.export_log).setOnClickListener(view -> selectLogDestination());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(SubtitleService.STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGames();
        if (notice == R.string.overlay_required && Settings.canDrawOverlays(this)) notice = 0;
        refresh();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(statusReceiver);
        clearPreview();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pendingStart", pendingStart);
        super.onSaveInstanceState(state);
    }

    private void loadGames() {
        Game selected = (Game) gamePicker.getSelectedItem();
        games.clear();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo app : getPackageManager().queryIntentActivities(launcher, 0)) {
            String name = app.activityInfo.packageName;
            String label = app.activityInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            String launcherLabel = app.loadLabel(getPackageManager()).toString();
            if ((label.contains("重返未来") || launcherLabel.contains("重返未来")) &&
                    games.stream().noneMatch(game -> game.packageName.equals(name))) {
                games.add(new Game(name, label));
            }
        }
        games.sort(Comparator.comparing(game -> game.packageName));
        ArrayAdapter<Game> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, games);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gamePicker.setAdapter(adapter);
        if (selected != null) {
            for (int index = 0; index < games.size(); index++) {
                if (games.get(index).packageName.equals(selected.packageName)) gamePicker.setSelection(index);
            }
        }
    }

    private void refresh() {
        boolean active = SubtitleService.active;
        start.setEnabled(active || (!games.isEmpty() && !pendingStart && modeSelected()));
        start.setText(active ? R.string.stop_subtitles : R.string.start_subtitles);
        preview.setEnabled(!active && !pendingStart);
        gamePicker.setEnabled(!active && !pendingStart);
        asrMode.setEnabled(!active && !pendingStart);
        ocrMode.setEnabled(!active && !pendingStart);
        gamePicker.setVisibility(games.isEmpty() ? View.GONE : View.VISIBLE);
        gameLabel.setVisibility(games.isEmpty() ? View.GONE : View.VISIBLE);
        if (notice != 0) {
            status.setText(notice);
        } else if (games.isEmpty() && !active) {
            status.setText(R.string.no_game);
        } else if (!active && !modeSelected()) {
            status.setText(R.string.select_mode);
        } else {
            status.setText(SubtitleService.status);
        }
    }

    private void begin() {
        clearPreview();
        if (!ensureOverlayPermission()) return;
        if (asrMode.isChecked()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        requestCapture();
    }

    private boolean ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return true;
        notice = R.string.overlay_required;
        refresh();
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        pendingStart = true;
        notice = 0;
        refresh();
        startActivityForResult(consent, REQUEST_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS ||
                (requestCode == REQUEST_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)) {
            requestCapture();
        } else if (requestCode == REQUEST_AUDIO) {
            notice = R.string.audio_required;
            refresh();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_LOG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) exportLog(data.getData());
            return;
        }
        if (requestCode != REQUEST_PROJECTION) return;
        pendingStart = false;
        if (resultCode != RESULT_OK || data == null) {
            notice = R.string.capture_denied;
            refresh();
            return;
        }
        if (gamePicker.getSelectedItem() == null) loadGames();
        Game game = (Game) gamePicker.getSelectedItem();
        if (game == null) {
            notice = R.string.game_missing;
            refresh();
            return;
        }
        try {
            int uid = getPackageManager().getApplicationInfo(game.packageName, 0).uid;
            Intent service = new Intent(this, SubtitleService.class)
                    .putExtra(SubtitleService.CONSENT, data)
                    .putExtra(SubtitleService.GAME_UID, uid)
                    .putExtra(SubtitleService.ASR, asrMode.isChecked())
                    .putExtra(SubtitleService.OCR, ocrMode.isChecked());
            startForegroundService(service);
            pendingStart = true;
            notice = asrMode.isChecked() ? R.string.starting : R.string.ocr_starting;
            Intent launch = getPackageManager().getLaunchIntentForPackage(game.packageName);
            if (launch != null) startActivity(launch);
        } catch (PackageManager.NameNotFoundException e) {
            notice = R.string.game_missing;
        } catch (RuntimeException e) {
            pendingStart = false;
            notice = R.string.capture_failed;
        }
        refresh();
    }

    private void addModePicker(LinearLayout parent) {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        asrMode = new CheckBox(this);
        asrMode.setText(R.string.asr_mode);
        asrMode.setChecked(preferences.getBoolean(SubtitleService.ASR, true));
        parent.addView(asrMode);
        ocrMode = new CheckBox(this);
        ocrMode.setText(R.string.ocr_mode);
        ocrMode.setChecked(preferences.getBoolean(SubtitleService.OCR, false));
        parent.addView(ocrMode);
        asrMode.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean(SubtitleService.ASR, checked).apply();
            notice = 0;
            refresh();
        });
        ocrMode.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean(SubtitleService.OCR, checked).apply();
            notice = 0;
            refresh();
        });
    }

    private boolean modeSelected() {
        return asrMode.isChecked() || ocrMode.isChecked();
    }

    private void preview() {
        if (!ensureOverlayPermission()) return;
        clearPreview();
        try {
            previewOverlay = new SubtitleOverlay(this, ocrMode.isChecked());
            previewOverlay.showPreview(getString(R.string.preview_text), getString(R.string.preview_second));
            main.postDelayed(this::clearPreview, 15_000);
        } catch (RuntimeException e) {
            clearPreview();
            notice = R.string.overlay_required;
            refresh();
        }
    }

    private void addFontPicker(LinearLayout parent) {
        TextView label = addText(parent, 14);
        label.setText(R.string.subtitle_size);
        Spinner picker = new Spinner(this);
        picker.setContentDescription(getString(R.string.subtitle_size));
        ArrayAdapter<CharSequence> sizes = ArrayAdapter.createFromResource(
                this, R.array.subtitle_sizes, android.R.layout.simple_spinner_item);
        sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(sizes);
        SharedPreferences preferences = SubtitleOverlay.preferences(this);
        picker.setSelection(preferences.getInt(SubtitleOverlay.FONT_SIZE, SubtitleOverlay.DEFAULT_SIZE));
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapter, View row, int position, long id) {
                preferences.edit().putInt(SubtitleOverlay.FONT_SIZE, position).apply();
            }

            @Override public void onNothingSelected(AdapterView<?> adapter) {}
        });
        parent.addView(picker, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addTransparencySlider(LinearLayout parent) {
        SharedPreferences preferences = SubtitleOverlay.preferences(this);
        TextView label = addText(parent, 14);
        int transparency = preferences.getInt(SubtitleOverlay.BACKGROUND_TRANSPARENCY,
                SubtitleOverlay.DEFAULT_TRANSPARENCY);
        label.setText(getString(R.string.background_transparency_value, transparency));
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(getString(R.string.background_transparency));
        slider.setMax(100);
        slider.setProgress(transparency);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(getString(R.string.background_transparency_value, progress));
                if (fromUser) {
                    preferences.edit().putInt(SubtitleOverlay.BACKGROUND_TRANSPARENCY, progress).apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void selectLogDestination() {
        if (DiagnosticLog.snapshot().isEmpty()) {
            Toast.makeText(this, R.string.no_log, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "1999-subtitle-log.txt");
        startActivityForResult(picker, REQUEST_EXPORT_LOG);
    }

    private void exportLog(Uri destination) {
        String snapshot = DiagnosticLog.snapshot();
        Context app = getApplicationContext();
        new Thread(() -> {
            int result;
            try (OutputStream file = app.getContentResolver().openOutputStream(destination, "wt")) {
                if (file == null) throw new IOException("No document output stream");
                file.write(snapshot.getBytes(StandardCharsets.UTF_8));
                result = R.string.log_saved;
            } catch (IOException | RuntimeException error) {
                result = R.string.log_save_failed;
            }
            int message = result;
            main.post(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
        }, "subtitle-log-export").start();
    }

    private void clearPreview() {
        main.removeCallbacksAndMessages(null);
        if (previewOverlay != null) {
            previewOverlay.close();
            previewOverlay = null;
        }
    }

    private TextView addText(LinearLayout parent, int size) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(0xffe2e6e8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        parent.addView(text, params);
        return text;
    }

    private Button addButton(LinearLayout parent, int label) {
        Button button = new Button(this);
        button.setText(label);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Game {
        private final String packageName;
        private final String label;

        private Game(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }

        @Override
        public String toString() {
            return label + " (" + packageName + ")";
        }
    }
}
