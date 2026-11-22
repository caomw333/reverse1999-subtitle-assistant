package com.local.reverse1999.subtitles;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LearningWords {
    static final String MUTED = "mutedVocabulary";
    private static CetVocabulary vocabulary;

    private LearningWords() {}

    static synchronized CetVocabulary vocabulary(Context context) {
        if (vocabulary == null) {
            try {
                vocabulary = new CetVocabulary(new InputStreamReader(
                        context.getAssets().open("vocabulary/cet.tsv"), StandardCharsets.UTF_8));
            } catch (IOException error) {
                Log.e("Reverse1999Subtitles", "Could not load CET vocabulary", error);
                try {
                    vocabulary = new CetVocabulary(new StringReader(""));
                } catch (IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return vocabulary;
    }

    static Set<String> muted(SharedPreferences preferences) {
        return preferences.getStringSet(MUTED, Collections.emptySet());
    }

    static void toggle(SharedPreferences preferences, String word) {
        Set<String> next = new HashSet<>(muted(preferences));
        if (!next.remove(word)) next.add(word);
        preferences.edit().putStringSet(MUTED, next).apply();
    }

    static CharSequence highlight(String text, int offset, List<CetVocabulary.Match> matches, Set<String> muted) {
        SpannableString styled = null;
        for (CetVocabulary.Match match : matches) {
            int start = Math.max(0, match.start - offset);
            int end = Math.min(text.length(), match.end - offset);
            if (start >= end || muted.contains(match.word)) continue;
            if (styled == null) styled = new SpannableString(text);
            styled.setSpan(new ForegroundColorSpan(0xffffd16f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled == null ? text : styled;
    }
}
