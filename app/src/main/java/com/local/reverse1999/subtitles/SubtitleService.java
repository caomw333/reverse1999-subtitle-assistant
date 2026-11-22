package com.local.reverse1999.subtitles;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public final class SubtitleService extends Service {
    static final String CONSENT = "projectionConsent";
    static final String GAME_UID = "gameUid";
    static final String ASR = "asr";
    static final String OCR = "ocr";
    static final String STOP = "com.local.reverse1999.subtitles.STOP";
    private static final String SELECT_OCR = "com.local.reverse1999.subtitles.SELECT_OCR";
    static final String STATUS_CHANGED = "com.local.reverse1999.subtitles.STATUS_CHANGED";

    static boolean active;
    static int status = R.string.stopped;

    private static final String CHANNEL = "captions";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final long SILENCE_MS = 12_000;
    private static final String TAG = "Reverse1999Subtitles";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object audioLock = new Object();

    private MediaProjection projection;
    private AudioRecord audio;
    private SubtitleOverlay overlay;
    private ScreenSubtitleReader screenReader;
    private OcrRegionPicker regionPicker;
    private CaptionFusion fusion;
    private String learningSession;
    private boolean asrEnabled;
    private boolean ocrEnabled;
    private boolean asrReady;
    private boolean ocrReady;
    private volatile boolean cancelled;
    private boolean stopping;

    private final Runnable updateFusion = () -> {
        if (stopping) return;
        fusion.update(SystemClock.uptimeMillis());
        scheduleFusion();
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            finish(R.string.projection_stopped);
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            if (screenReader != null) screenReader.resize(width, height);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            finish(R.string.stopped);
            return START_NOT_STICKY;
        }
        if (SELECT_OCR.equals(intent.getAction())) {
            if (!active) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            openRegionPicker();
            return START_NOT_STICKY;
        }
        if (active || stopping) return START_NOT_STICKY;

        asrEnabled = intent.getBooleanExtra(ASR, true);
        ocrEnabled = intent.getBooleanExtra(OCR, false);
        learningSession = UUID.randomUUID().toString();
        DiagnosticLog.add("session_start app=0.3.3 android_api=" + Build.VERSION.SDK_INT
                + " asr=" + asrEnabled + " ocr=" + ocrEnabled);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
            int starting = asrEnabled ? R.string.starting : R.string.ocr_starting;
            startForeground(NOTIFICATION_ID, notification(starting),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            active = true;
            setStatus(starting);

            Intent consent = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(CONSENT, Intent.class) : intent.getParcelableExtra(CONSENT);
            int gameUid = intent.getIntExtra(GAME_UID, -1);
            if (!asrEnabled && !ocrEnabled) {
                finish(R.string.select_mode);
                return START_NOT_STICKY;
            }
            if (consent == null || gameUid < 0) throw new IllegalArgumentException("Missing capture consent or game");
            if (!Settings.canDrawOverlays(this)) {
                finish(R.string.overlay_required);
                return START_NOT_STICKY;
            }
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IllegalStateException("MediaProjection unavailable");
            projection.registerCallback(projectionCallback, main);
            overlay = new SubtitleOverlay(this, ocrEnabled);
            if (ocrEnabled) overlay.addRegionPicker(this::openRegionPicker);
            fusion = new CaptionFusion(asrEnabled, this::showCaption, DiagnosticLog::add);
            if (ocrEnabled) startOcr();
            if (asrEnabled) startAsr(gameUid, ocrEnabled);
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start game capture", error);
            DiagnosticLog.add("capture_start_error type=" + error.getClass().getSimpleName());
            finish(R.string.capture_failed);
        }
        return START_NOT_STICKY;
    }

    private void startOcr() {
        try {
            screenReader = new ScreenSubtitleReader(this, projection, overlay::captureBounds,
                    new ScreenSubtitleReader.Listener() {
                @Override
                public void ready() {
                    main.post(() -> {
                        if (stopping || !ocrEnabled) return;
                        ocrReady = true;
                        if (asrEnabled && asrReady) setStatus(R.string.mixed_running);
                        if (!asrEnabled) setStatus(status == R.string.asr_fallback
                                ? R.string.asr_fallback : R.string.ocr_running);
                    });
                }

                @Override
                public void translated(String text, long observedAt, long confirmedAt) {
                    main.post(() -> {
                        if (stopping || !ocrEnabled) return;
                        fusion.screen(text, observedAt, confirmedAt, SystemClock.uptimeMillis());
                        scheduleFusion();
                    });
                }

                @Override
                public void observedThrough(long observedAt, long lastSeenAt) {
                    main.post(() -> {
                        if (stopping || !ocrEnabled) return;
                        fusion.screenObservedThrough(observedAt, lastSeenAt, SystemClock.uptimeMillis());
                        scheduleFusion();
                    });
                }

                @Override
                public void failed() {
                    main.post(SubtitleService.this::ocrFailed);
                }
            });
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start OCR", error);
            ocrFailed();
        }
    }

    private void openRegionPicker() {
        if (stopping || !ocrEnabled || screenReader == null || regionPicker != null) return;
        android.content.SharedPreferences preferences = SubtitleOverlay.preferences(this);
        screenReader.setSelecting(true);
        try {
            overlay.hideForSelection(true);
            regionPicker = new OcrRegionPicker(this, OcrRegion.load(preferences), region -> {
                if (region != null) region.save(preferences);
                closeRegionPicker();
                overlay.hideForSelection(false);
                screenReader.setSelecting(false);
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not open OCR region picker", error);
            overlay.hideForSelection(false);
            screenReader.setSelecting(false);
        }
    }

    private void closeRegionPicker() {
        if (regionPicker == null) return;
        regionPicker.close();
        regionPicker = null;
    }

    private void startAsr(int gameUid, boolean compareWithScreen) {
        try {
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUid(gameUid)
                    .build();
            new Thread(() -> recognize(capture, compareWithScreen), "game-subtitle-recognizer").start();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start ASR", error);
            asrFailed(R.string.capture_failed);
        }
    }

    private void ocrFailed() {
        if (stopping) return;
        ocrEnabled = false;
        closeRegionPicker();
        if (overlay != null) overlay.hideForSelection(false);
        if (asrEnabled) setStatus(R.string.ocr_fallback);
        else finish(R.string.ocr_failed);
    }

    private void asrFailed(int reason) {
        if (stopping) return;
        asrEnabled = false;
        if (ocrEnabled) {
            fusion.disableVoice(SystemClock.uptimeMillis());
            setStatus(R.string.asr_fallback);
        } else {
            finish(reason);
        }
    }

    private void scheduleFusion() {
        main.removeCallbacks(updateFusion);
        if (!stopping && fusion.waitingForVoice()) main.postDelayed(updateFusion, 250);
    }

    private void recognize(AudioPlaybackCaptureConfiguration capture, boolean compareWithScreen) {
        Model model;
        try {
            model = new Model(EnglishModel.prepare(this).getPath());
        } catch (IOException | RuntimeException | LinkageError error) {
            Log.e(TAG, "Could not load bundled English model", error);
            DiagnosticLog.add("model_error type=" + error.getClass().getSimpleName());
            main.post(() -> asrFailed(R.string.model_failed));
            return;
        }

        try (Model openedModel = model; Recognizer recognizer = new Recognizer(openedModel, SAMPLE_RATE)) {
            if (compareWithScreen) {
                recognizer.setWords(true);
                recognizer.setPartialWords(true);
            }
            long recordingStartedAt;
            synchronized (audioLock) {
                if (cancelled) return;
                audio = openAudio(capture);
                audio.startRecording();
                if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IOException("AudioRecord could not start");
                }
                recordingStartedAt = SystemClock.uptimeMillis();
            }
            main.post(() -> {
                if (stopping || !asrEnabled) return;
                asrReady = true;
                if (status != R.string.ocr_fallback) {
                    setStatus(ocrReady ? R.string.mixed_running : R.string.listening);
                }
            });
            readAudio(recognizer, audio, recordingStartedAt, compareWithScreen);
        } catch (IOException | RuntimeException | LinkageError error) {
            if (!cancelled) {
                Log.e(TAG, "Game audio recognition failed", error);
                DiagnosticLog.add("recognition_error type=" + error.getClass().getSimpleName());
                main.post(() -> asrFailed(R.string.capture_failed));
            }
        } finally {
            synchronized (audioLock) {
                if (audio != null) {
                    audio.release();
                    audio = null;
                }
            }
        }
    }

    private AudioRecord openAudio(AudioPlaybackCaptureConfiguration capture) throws IOException {
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("16 kHz mono PCM capture is unavailable");
        return new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(Math.max(minimum, SAMPLE_RATE / 2))
                .build();
    }

    private void readAudio(Recognizer recognizer, AudioRecord record, long recordingStartedAt,
                           boolean compareWithScreen) throws IOException {
        short[] samples = new short[SAMPLE_RATE / 10];
        long lastAudio = SystemClock.elapsedRealtime();
        long lastText = lastAudio;
        int lastStatus = R.string.listening;
        String previousPartial = "";
        long segment = 1;
        long firstPartialAt = 0;
        long samplesProcessed = 0;
        long lastProgress = 0;
        while (!cancelled) {
            int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
            if (cancelled) return;
            if (count <= 0) {
                DiagnosticLog.add("audio_read_error code=" + count);
                throw new IOException("AudioRecord.read returned " + count);
            }

            long now = SystemClock.elapsedRealtime();
            for (int index = 0; index < count; index++) {
                if (Math.abs((int) samples[index]) > 16) {
                    lastAudio = now;
                    break;
                }
            }
            boolean complete = recognizer.acceptWaveForm(samples, count);
            samplesProcessed += count;
            long recognizedAt = SystemClock.uptimeMillis();
            long through = Math.min(recognizedAt, recordingStartedAt + samplesProcessed * 1000 / SAMPLE_RATE);
            JSONObject result = parseResult(complete ? recognizer.getResult() : recognizer.getPartialResult());
            String text = getText(result, complete ? "text" : "partial");
            if (!text.isEmpty()) lastText = now;
            if (complete || (!text.isEmpty() && !text.equals(previousPartial))) {
                if (firstPartialAt == 0) firstPartialAt = through;
                JSONArray words = result.optJSONArray(complete ? "result" : "partial_result");
                int wordCount = words == null ? 0 : words.length();
                long speechStart = wordTime(words, 0, "start", recordingStartedAt, firstPartialAt, through);
                long speechEnd = wordTime(words, wordCount - 1, "end", recordingStartedAt, speechStart, through);
                DiagnosticLog.add("asr_recognized segment=" + segment + " final=" + complete
                        + " chars=" + text.length() + " speech_start=" + speechStart + " speech_end=" + speechEnd);
                main.post(() -> {
                    if (stopping || !asrEnabled) return;
                    long uptime = SystemClock.uptimeMillis();
                    fusion.voice(text, complete, recognizedAt, speechStart, speechEnd, uptime);
                    if (compareWithScreen) fusion.audioProcessed(through, uptime);
                    scheduleFusion();
                });
                lastProgress = through;
            } else if (compareWithScreen && through - lastProgress >= 250) {
                lastProgress = through;
                main.post(() -> {
                    if (!stopping && ocrEnabled && asrEnabled) {
                        fusion.audioProcessed(through, SystemClock.uptimeMillis());
                    }
                });
            }
            if (complete) {
                segment++;
                firstPartialAt = 0;
            }
            previousPartial = complete ? "" : text;
            int nextStatus = R.string.listening;
            if (now - lastAudio >= SILENCE_MS) {
                nextStatus = R.string.no_audio;
            } else if (now - lastText >= SILENCE_MS) {
                nextStatus = R.string.no_speech;
            }
            if (nextStatus != lastStatus) {
                lastStatus = nextStatus;
                int updated = nextStatus;
                main.post(() -> {
                    if (stopping || !asrEnabled || status == R.string.ocr_fallback) return;
                    if (ocrReady) setStatus(R.string.mixed_running);
                    else setStatus(updated);
                });
            }
        }
    }

    private static JSONObject parseResult(String result) {
        try {
            return new JSONObject(result);
        } catch (JSONException error) {
            Log.w(TAG, "Invalid ASR result", error);
            return new JSONObject();
        }
    }

    private static String getText(JSONObject result, String key) {
        String text = result.optString(key).trim();
        if (text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private static long wordTime(JSONArray words, int index, String field, long recordingStartedAt,
                                 long fallback, long through) {
        JSONObject word = words == null ? null : words.optJSONObject(index);
        double seconds = word == null ? -1 : word.optDouble(field, -1);
        if (seconds < 0 || !Double.isFinite(seconds)) return fallback;
        return Math.min(through, recordingStartedAt + Math.round(seconds * 1000));
    }

    private void showCaption(SubtitleHistory.Caption caption) {
        LearningStore.record(this, learningSession, caption);
        if (stopping || overlay == null) return;
        try {
            overlay.show(caption);
            DiagnosticLog.add("updated " + caption.timingInfo()
                    + " wait_ms=" + (SystemClock.uptimeMillis() - caption.recognizedAt));
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not display captions", error);
            DiagnosticLog.add("overlay_error type=" + error.getClass().getSimpleName());
            finish(R.string.overlay_required);
        }
    }

    private Notification notification(int message) {
        int immutable = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SubtitleService.class).setAction(STOP), immutable);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(message))
                .setStyle(new Notification.BigTextStyle().bigText(getString(message)))
                .setSmallIcon(R.drawable.ic_subtitle)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop_subtitles), stop).build());
        if (ocrEnabled) {
            PendingIntent select = PendingIntent.getService(this, 2,
                    new Intent(this, SubtitleService.class).setAction(SELECT_OCR), immutable);
            builder.addAction(new Notification.Action.Builder(null, getString(R.string.ocr_region), select).build());
        }
        return builder.build();
    }

    private void setStatus(int message) {
        if (stopping) return;
        status = message;
        DiagnosticLog.add("status " + getResources().getResourceEntryName(message));
        if (active) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
        sendBroadcast(new Intent(STATUS_CHANGED).setPackage(getPackageName()));
    }

    private void finish(int message) {
        if (stopping) return;
        active = false;
        setStatus(message);
        cleanUp();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (!stopping) {
            active = false;
            setStatus(R.string.stopped);
            cleanUp();
        }
        super.onDestroy();
    }

    private void cleanUp() {
        stopping = true;
        cancelled = true;
        if (fusion != null) fusion.disableVoice(SystemClock.uptimeMillis());
        DiagnosticLog.add("session_end");
        main.removeCallbacksAndMessages(null);
        closeRegionPicker();
        if (screenReader != null) {
            screenReader.close();
            screenReader = null;
        }
        synchronized (audioLock) {
            if (audio != null && audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    audio.stop();
                } catch (IllegalStateException error) {
                    Log.w(TAG, "Audio capture was already interrupted", error);
                }
            }
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (overlay != null) {
            overlay.close();
            overlay = null;
        }
    }
}
