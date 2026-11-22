package com.local.reverse1999.subtitles;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class LearningStore extends SQLiteOpenHelper {
    static final int PAGE_SIZE = 100;
    private static final Executor WORKER = Executors.newSingleThreadExecutor();
    private static LearningStore instance;

    private LearningStore(Context context) {
        super(context, "learning.db", null, 1);
    }

    static synchronized LearningStore open(Context context) {
        if (instance == null) instance = new LearningStore(context.getApplicationContext());
        return instance;
    }

    static void runInBackground(Runnable work) {
        WORKER.execute(work);
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE sentences (id INTEGER PRIMARY KEY, session TEXT NOT NULL, "
                + "segment INTEGER NOT NULL, saved_at INTEGER NOT NULL, text TEXT NOT NULL, "
                + "ocr_translation TEXT NOT NULL, UNIQUE(session, segment))");
        database.execSQL("CREATE TABLE sentence_words (sentence_id INTEGER NOT NULL "
                + "REFERENCES sentences(id) ON DELETE CASCADE, word TEXT NOT NULL, occurrences INTEGER NOT NULL, "
                + "PRIMARY KEY(sentence_id, word))");
        database.execSQL("CREATE INDEX sentence_words_by_word ON sentence_words(word)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing learning database migration " + oldVersion + " to " + newVersion);
    }

    static void record(Context context, String session, SubtitleHistory.Caption caption) {
        if (!caption.complete || caption.text.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        long timestamp = System.currentTimeMillis();
        runInBackground(() -> {
            try {
                open(app).save(session, caption, timestamp, LearningWords.vocabulary(app));
            } catch (RuntimeException error) {
                Log.e("Reverse1999Subtitles", "Could not save subtitle", error);
                DiagnosticLog.add("learning_save_failed type=" + error.getClass().getSimpleName());
            }
        });
    }

    private void save(String session, SubtitleHistory.Caption caption, long timestamp, CetVocabulary vocabulary) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long id = -1;
            boolean sameText = false;
            try (Cursor existing = database.query("sentences", new String[]{"id", "text"},
                    "session = ? AND segment = ?", new String[]{session, Long.toString(caption.segment)},
                    null, null, null)) {
                if (existing.moveToFirst()) {
                    id = existing.getLong(0);
                    sameText = caption.text.equals(existing.getString(1));
                }
            }

            ContentValues row = new ContentValues();
            row.put("text", caption.text);
            row.put("ocr_translation", caption.ocrTranslation);
            if (id == -1) {
                row.put("session", session);
                row.put("segment", caption.segment);
                row.put("saved_at", timestamp);
                id = database.insertOrThrow("sentences", null, row);
            } else {
                database.update("sentences", row, "id = ?", new String[]{Long.toString(id)});
            }

            if (!sameText) {
                database.delete("sentence_words", "sentence_id = ?", new String[]{Long.toString(id)});
                Map<String, Integer> counts = new HashMap<>();
                for (CetVocabulary.Match match : vocabulary.matches(caption.text)) {
                    counts.merge(match.word, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> item : counts.entrySet()) {
                    ContentValues word = new ContentValues();
                    word.put("sentence_id", id);
                    word.put("word", item.getKey());
                    word.put("occurrences", item.getValue());
                    database.insertOrThrow("sentence_words", null, word);
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    List<Sentence> sentences(long beforeId) {
        return sentencePage("SELECT id, saved_at, text, ocr_translation FROM sentences "
                + "WHERE id < ? ORDER BY id DESC LIMIT " + (PAGE_SIZE + 1),
                new String[]{Long.toString(beforeId)});
    }

    List<Sentence> sentencesForWord(String word, long beforeId) {
        return sentencePage("SELECT s.id, s.saved_at, s.text, s.ocr_translation FROM sentence_words w "
                + "JOIN sentences s ON s.id = w.sentence_id WHERE w.word = ? AND s.id < ? "
                + "ORDER BY s.id DESC LIMIT " + (PAGE_SIZE + 1), new String[]{word, Long.toString(beforeId)});
    }

    private List<Sentence> sentencePage(String sql, String[] arguments) {
        List<Sentence> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, arguments)) {
            while (cursor.moveToNext()) {
                rows.add(new Sentence(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                        cursor.getString(3)));
            }
        }
        return rows;
    }

    Map<String, Long> wordCounts() {
        Map<String, Long> counts = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT word, SUM(occurrences) FROM sentence_words GROUP BY word", null)) {
            while (cursor.moveToNext()) counts.put(cursor.getString(0), cursor.getLong(1));
        }
        return counts;
    }

    static final class Sentence {
        final long id;
        final long savedAt;
        final String text;
        final String ocrTranslation;

        Sentence(long id, long savedAt, String text, String ocrTranslation) {
            this.id = id;
            this.savedAt = savedAt;
            this.text = text;
            this.ocrTranslation = ocrTranslation;
        }
    }
}
