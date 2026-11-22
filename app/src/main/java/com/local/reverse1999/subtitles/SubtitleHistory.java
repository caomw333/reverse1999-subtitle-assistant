package com.local.reverse1999.subtitles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SubtitleHistory {
    private static final int MAX_COMPLETED = 100;

    enum Comparison { NONE, OCR_ONLY, OCR_UNPAIRED, CLOSE, DIFFERENT }

    private final Map<Long, Caption> completed = new LinkedHashMap<>();
    private Caption partial;

    void accept(Caption caption) {
        if (!caption.complete) {
            partial = caption;
            return;
        }
        if (partial != null && partial.segment == caption.segment) partial = null;
        if (caption.text.isEmpty()) return;
        completed.put(caption.segment, caption);
        if (completed.size() > MAX_COMPLETED) completed.remove(completed.keySet().iterator().next());
    }

    List<Caption> all() {
        List<Caption> captions = new ArrayList<>(completed.values());
        if (partial != null) captions.add(partial);
        return captions;
    }

    static final class Caption {
        final long segment;
        final String text;
        final boolean complete;
        final long recognizedAt;
        final Comparison comparison;
        final String ocrTranslation;

        Caption(long segment, String text, boolean complete, long recognizedAt) {
            this(segment, text, complete, recognizedAt, Comparison.NONE, "");
        }

        Caption(long segment, String text, boolean complete, long recognizedAt,
                Comparison comparison, String ocrTranslation) {
            this.segment = segment;
            this.text = text;
            this.complete = complete;
            this.recognizedAt = recognizedAt;
            this.comparison = comparison;
            this.ocrTranslation = ocrTranslation;
        }

        String timingInfo() {
            return "segment=" + segment + " kind=" + (complete ? "final" : "partial")
                    + " chars=" + text.length() + " comparison=" + comparison;
        }
    }
}
