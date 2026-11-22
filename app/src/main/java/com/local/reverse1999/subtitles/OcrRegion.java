package com.local.reverse1999.subtitles;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.RectF;

final class OcrRegion {
    private static final String KEY = "ocrRegion.";
    private final RectF bounds;

    OcrRegion(float left, float top, float right, float bottom) {
        bounds = new RectF(left, top, right, bottom);
    }

    static OcrRegion load(SharedPreferences prefs) {
        return new OcrRegion(prefs.getFloat(KEY + "left", .09f), prefs.getFloat(KEY + "top", .54f),
                prefs.getFloat(KEY + "right", .91f), prefs.getFloat(KEY + "bottom", .92f));
    }

    void save(SharedPreferences prefs) {
        prefs.edit().putFloat(KEY + "left", bounds.left).putFloat(KEY + "top", bounds.top)
                .putFloat(KEY + "right", bounds.right).putFloat(KEY + "bottom", bounds.bottom).apply();
    }

    Rect pixels(int width, int height) {
        return new Rect(Math.round(bounds.left * width), Math.round(bounds.top * height),
                Math.round(bounds.right * width), Math.round(bounds.bottom * height));
    }
}
