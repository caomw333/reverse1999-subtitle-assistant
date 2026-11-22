package com.local.reverse1999.subtitles;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import ai.onnxruntime.OrtException;

final class ScreenSubtitleReader implements AutoCloseable {
    interface Listener {
        void ready();
        void translated(String text, long observedAt, long confirmedAt);
        void observedThrough(long observedAt, long lastSeenAt);
        void failed();
    }

    private static final long SCAN_INTERVAL_MS = 450;
    private static final long CLEAR_AFTER_MS = 1600;
    private static final String TAG = "Reverse1999Subtitles";

    private final Context context;
    private final MediaProjection projection;
    private final Listener listener;
    private final DisplayManager displays;
    private final HandlerThread thread = new HandlerThread("subtitle-screen-reader");
    private final Handler worker;
    private final Supplier<Rect> overlayBounds;
    private final Map<String, String> translations = new LinkedHashMap<String, String>(80, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 80;
        }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                DisplayMetrics metrics = metrics();
                resizeSurface(metrics.widthPixels, metrics.heightPixels);
            }
        }
    };

    private VirtualDisplay display;
    private ImageReader reader;
    private ChineseOcr ocr;
    private OpusTranslator translator;
    private volatile boolean closed;
    private long scanAt;
    private long lastTextAt;
    private long candidateAt;
    private String candidateKey = "";
    private String lastSent = "";
    private long lastSentAt;
    private int[] previousSignature;
    private String previousText = "";
    private long recognizedAt;
    private volatile boolean paused;
    private volatile int selectionRevision;
    private OcrRegion region;

    ScreenSubtitleReader(Context context, MediaProjection projection, Supplier<Rect> overlayBounds,
                         Listener listener) {
        this.context = context;
        this.projection = projection;
        this.listener = listener;
        this.overlayBounds = overlayBounds;
        region = OcrRegion.load(SubtitleOverlay.preferences(context));
        displays = context.getSystemService(DisplayManager.class);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::start);
    }

    void setSelecting(boolean selecting) {
        int revision = ++selectionRevision;
        paused = true;
        if (!selecting) worker.postDelayed(() -> {
            if (closed || revision != selectionRevision) return;
            region = OcrRegion.load(SubtitleOverlay.preferences(context));
            previousSignature = null;
            candidateKey = "";
            paused = false;
        }, 350);
    }

    private void start() {
        try {
            if (closed) return;
            DisplayMetrics metrics = metrics();
            reader = newReader(metrics.widthPixels, metrics.heightPixels);
            display = projection.createVirtualDisplay("1999 subtitle capture",
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            if (display == null) throw new IllegalStateException("Screen capture unavailable");
            if (Build.VERSION.SDK_INT < 34) displays.registerDisplayListener(displayListener, worker);

            ocr = new ChineseOcr(
                    ModelFiles.load(context, "ocr", "ch_PP-OCRv4_det_infer.onnx").getPath(),
                    ModelFiles.load(context, "ocr", "ch_PP-OCRv4_rec_infer.onnx").getPath());
            if (closed) return;
            translator = new OpusTranslator(
                    ModelFiles.load(context, "translation", "encoder_model_quantized.onnx").getPath(),
                    ModelFiles.load(context, "translation", "decoder_model_merged_quantized.onnx").getPath(),
                    context.getAssets().open("translation/opus-vocab.bin"));
            if (!closed) listener.ready();
        } catch (Exception | LinkageError error) {
            fail(error);
        }
    }

    void resize(int width, int height) {
        if (!closed) worker.post(() -> resizeSurface(width, height));
    }

    private void resizeSurface(int width, int height) {
        if (closed || display == null || width <= 0 || height <= 0
                || (reader.getWidth() == width && reader.getHeight() == height)) return;
        ImageReader replacement = null;
        try {
            replacement = newReader(width, height);
            display.resize(width, height, metrics().densityDpi);
            display.setSurface(replacement.getSurface());
            reader.close();
            reader = replacement;
            candidateKey = "";
            previousSignature = null;
        } catch (RuntimeException error) {
            if (replacement != null) replacement.close();
            fail(error);
        }
    }

    private DisplayMetrics metrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
        return metrics;
    }

    private ImageReader newReader(int width, int height) {
        ImageReader input = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        input.setOnImageAvailableListener(this::onFrame, worker);
        return input;
    }

    private void onFrame(ImageReader input) {
        if (closed || input != reader) return;
        long now = SystemClock.uptimeMillis();
        Frame frame;
        try (Image image = input.acquireLatestImage()) {
            if (image == null || paused || translator == null || image.getWidth() <= image.getHeight()
                    || now - scanAt < SCAN_INTERVAL_MS) return;
            scanAt = now;
            frame = Frame.subtitleArea(image, region.pixels(image.getWidth(), image.getHeight()), overlayBounds.get());
        } catch (RuntimeException error) {
            fail(error);
            return;
        }
        try {
            int[] signature = frame.signature();
            if (changed(signature) || now - recognizedAt >= 4000) {
                previousText = ocr.read(frame.pixels, frame.width, frame.height);
                previousSignature = signature;
                recognizedAt = now;
            }
            if (!closed && !paused) process(previousText, now);
        } catch (OrtException | RuntimeException error) {
            fail(error);
        }
    }

    private boolean changed(int[] signature) {
        if (previousSignature == null) return true;
        int changed = 0;
        for (int index = 0; index < signature.length; index++) {
            if (Math.abs(signature[index] - previousSignature[index]) >= 24 && ++changed >= 3) return true;
        }
        return false;
    }

    private void process(String chinese, long observedAt) throws OrtException {
        String key = Normalizer.normalize(chinese, Normalizer.Form.NFKC).replaceAll("[\\p{P}\\s]+", "");
        if (key.isEmpty()) {
            candidateKey = "";
            if (!lastSent.isEmpty() && observedAt - lastTextAt >= CLEAR_AFTER_MS) endLine(lastTextAt);
            return;
        }
        lastTextAt = observedAt;
        if (!key.equals(candidateKey)) {
            candidateKey = key;
            candidateAt = observedAt;
            return;
        }
        if (key.equals(lastSent)) {
            listener.observedThrough(lastSentAt, observedAt);
            return;
        }
        if (observedAt - candidateAt < SCAN_INTERVAL_MS) return;
        if (!lastSent.isEmpty()) endLine(candidateAt);
        String english = translations.get(key);
        if (english == null) {
            english = translator.translate(chinese, () -> closed);
            if (!english.isEmpty() && !closed) translations.put(key, english);
        }
        if (!closed && !paused && !english.isEmpty()) {
            lastSent = key;
            lastSentAt = candidateAt;
            DiagnosticLog.add("ocr_translated chinese_chars=" + chinese.length()
                    + " english_chars=" + english.length() + " wait_ms=" + (SystemClock.uptimeMillis() - candidateAt));
            listener.translated(english, candidateAt, observedAt);
        }
    }

    private void endLine(long endedAt) {
        listener.observedThrough(lastSentAt, Math.max(lastSentAt, endedAt));
        lastSent = "";
    }

    private void fail(Throwable error) {
        if (closed) return;
        Log.e(TAG, "Screen subtitle reading failed", error);
        DiagnosticLog.add("ocr_error type=" + error.getClass().getSimpleName());
        close();
        listener.failed();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.post(() -> {
            try {
                if (Build.VERSION.SDK_INT < 34) displays.unregisterDisplayListener(displayListener);
                if (display != null) display.release();
                if (reader != null) reader.close();
                if (ocr != null) ocr.close();
            } catch (OrtException | RuntimeException error) {
                Log.w(TAG, "Could not release OCR resources", error);
            } finally {
                try {
                    if (translator != null) translator.close();
                } catch (OrtException error) {
                    Log.w(TAG, "Could not release translation resources", error);
                }
                thread.quitSafely();
            }
        });
    }

    private static final class Frame {
        final int[] pixels;
        final int width;
        final int height;

        Frame(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }

        int[] signature() {
            int[] grid = new int[96 * 24];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 96; x++) {
                    int pixel = pixels[(y * height / 24) * width + x * width / 96];
                    grid[y * 96 + x] = (3 * ((pixel >> 16) & 255) + 6 * ((pixel >> 8) & 255)
                            + (pixel & 255)) / 10;
                }
            }
            return grid;
        }

        static Frame subtitleArea(Image image, Rect area, Rect overlay) {
            int sourceWidth = Math.max(1, area.width());
            int sourceHeight = Math.max(1, area.height());
            int width = Math.min(1120, sourceWidth);
            int height = Math.max(1, sourceHeight * width / sourceWidth);
            int[] pixels = new int[width * height];
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer bytes = plane.getBuffer();
            int stride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            for (int y = 0; y < height; y++) {
                int sourceY = Math.min(image.getHeight() - 1, area.top + y * sourceHeight / height);
                int row = sourceY * stride;
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.min(image.getWidth() - 1, area.left + x * sourceWidth / width);
                    if (overlay.contains(sourceX, sourceY)) continue;
                    int offset = row + sourceX * pixelStride;
                    pixels[y * width + x] = ((bytes.get(offset) & 255) << 16)
                            | ((bytes.get(offset + 1) & 255) << 8) | (bytes.get(offset + 2) & 255);
                }
            }
            return new Frame(pixels, width, height);
        }
    }
}
