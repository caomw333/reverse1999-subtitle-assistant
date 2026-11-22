package com.local.reverse1999.subtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LearningActivity extends Activity {
    private final List<LearningStore.Sentence> sentences = new ArrayList<>();
    private final List<Word> words = new ArrayList<>();
    private final List<Word> filteredWords = new ArrayList<>();
    private ListView list;
    private TextView empty;
    private Button sentenceTab;
    private Button wordTab;
    private Button more;
    private EditText search;
    private Rows rows;
    private SharedPreferences preferences;
    private CetVocabulary vocabulary;
    private boolean wordMode;
    private int request;
    private long beforeId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = SubtitleOverlay.preferences(this);
        vocabulary = LearningWords.vocabulary(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        content.setPadding(padding, padding, padding, padding);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                content.setPadding(padding + bars.left, padding + bars.top, padding + bars.right,
                        padding + bars.bottom);
            }
            return insets;
        });

        LinearLayout tabs = new LinearLayout(this);
        sentenceTab = button(R.string.saved_sentences);
        sentenceTab.setOnClickListener(view -> select(false));
        tabs.addView(sentenceTab, new LinearLayout.LayoutParams(0, -2, 1));
        wordTab = button(R.string.learning_vocabulary);
        wordTab.setOnClickListener(view -> select(true));
        tabs.addView(wordTab, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.search_vocabulary);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { filterWords(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        content.addView(search, new LinearLayout.LayoutParams(-1, -2));

        empty = new TextView(this);
        empty.setPadding(dp(10), dp(12), dp(10), dp(12));
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        list = new ListView(this);
        rows = new Rows();
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, row, position, id) -> {
            if (wordMode) showWord(filteredWords.get(position));
        });
        list.setEmptyView(empty);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        more = button(R.string.load_older_sentences);
        more.setOnClickListener(view -> loadSentences(request));
        content.addView(more, new LinearLayout.LayoutParams(-1, -2));
        content.setFocusableInTouchMode(true);
        setContentView(content);
        content.requestFocus();
        if (state != null) search.setText(state.getString("wordSearch", ""));
        select(state != null && state.getBoolean("wordMode"));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("wordMode", wordMode);
        state.putString("wordSearch", search.getText().toString());
        super.onSaveInstanceState(state);
    }

    private void select(boolean showWords) {
        wordMode = showWords;
        int id = ++request;
        search.setVisibility(showWords ? View.VISIBLE : View.GONE);
        sentenceTab.setEnabled(showWords);
        wordTab.setEnabled(!showWords);
        empty.setText(R.string.learning_loading);
        more.setVisibility(View.GONE);
        if (showWords) {
            words.clear();
            filteredWords.clear();
            rows.notifyDataSetChanged();
            loadWords(id);
        } else {
            sentences.clear();
            beforeId = Long.MAX_VALUE;
            rows.notifyDataSetChanged();
            loadSentences(id);
        }
        list.setSelection(0);
    }

    private void loadWords(int id) {
        LearningStore.runInBackground(() -> {
            try {
                Map<String, Long> counts = LearningStore.open(this).wordCounts();
                List<Word> result = new ArrayList<>();
                for (String word : vocabulary.words()) {
                    result.add(new Word(word, vocabulary.level(word), counts.getOrDefault(word, 0L)));
                }
                result.sort(Comparator.comparingLong((Word word) -> word.count).reversed()
                        .thenComparing(word -> word.text));
                runOnUiThread(() -> {
                    if (!accept(id)) return;
                    words.addAll(result);
                    empty.setText(R.string.no_matching_words);
                    filterWords();
                });
            } catch (RuntimeException error) {
                showLoadError(id);
            }
        });
    }

    private void loadSentences(int id) {
        long cursor = beforeId;
        more.setEnabled(false);
        LearningStore.runInBackground(() -> {
            try {
                List<LearningStore.Sentence> page = LearningStore.open(this).sentences(cursor);
                runOnUiThread(() -> {
                    if (!accept(id)) return;
                    boolean hasMore = page.size() > LearningStore.PAGE_SIZE;
                    if (hasMore) page.remove(page.size() - 1);
                    sentences.addAll(page);
                    if (!page.isEmpty()) beforeId = page.get(page.size() - 1).id;
                    empty.setText(R.string.no_saved_sentences);
                    more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                    more.setEnabled(true);
                    rows.notifyDataSetChanged();
                });
            } catch (RuntimeException error) {
                showLoadError(id);
            }
        });
    }

    private boolean accept(int id) {
        return id == request && !isFinishing() && !isDestroyed();
    }

    private void showLoadError(int id) {
        runOnUiThread(() -> {
            if (!accept(id)) return;
            empty.setText(R.string.learning_load_error);
            more.setEnabled(true);
            Toast.makeText(this, R.string.learning_load_error, Toast.LENGTH_SHORT).show();
        });
    }

    private void filterWords() {
        if (!wordMode) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        filteredWords.clear();
        for (Word word : words) {
            if (word.text.contains(query)) filteredWords.add(word);
        }
        rows.notifyDataSetChanged();
    }

    private void showWord(Word word) {
        boolean muted = LearningWords.muted(preferences).contains(word.text);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);
        TextView meaning = new TextView(this);
        meaning.setTextSize(16);
        meaning.setText(definition(word.text) + "\n" + getString(R.string.word_exposure_count, word.count));
        content.addView(meaning);
        ListView examples = new ListView(this);
        android.widget.ArrayAdapter<CharSequence> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1);
        examples.setAdapter(adapter);
        content.addView(examples, new LinearLayout.LayoutParams(-1,
                Math.min(dp(300), getResources().getDisplayMetrics().heightPixels / 3)));
        Button earlier = button(R.string.load_older_sentences);
        earlier.setVisibility(View.GONE);
        content.addView(earlier, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(word.text)
                .setView(content)
                .setPositiveButton(muted ? R.string.resume_highlight : R.string.mute_highlight, (prompt, action) -> {
                    LearningWords.toggle(preferences, word.text);
                    rows.notifyDataSetChanged();
                })
                .setNegativeButton(android.R.string.ok, null)
                .create();
        dialog.show();
        loadExamples(word.text, Long.MAX_VALUE, dialog, adapter, earlier);
    }

    private void loadExamples(String word, long beforeId, AlertDialog dialog,
                              android.widget.ArrayAdapter<CharSequence> adapter, Button earlier) {
        earlier.setEnabled(false);
        LearningStore.runInBackground(() -> {
            try {
                List<LearningStore.Sentence> page = LearningStore.open(this).sentencesForWord(word, beforeId);
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isDestroyed()) return;
                    boolean more = page.size() > LearningStore.PAGE_SIZE;
                    if (more) page.remove(page.size() - 1);
                    for (LearningStore.Sentence sentence : page) {
                        adapter.add(LearningWords.highlight(sentence.text, 0, vocabulary.matches(sentence.text),
                                LearningWords.muted(preferences)));
                    }
                    if (adapter.getCount() == 0) adapter.add(getString(R.string.no_word_examples));
                    earlier.setVisibility(more ? View.VISIBLE : View.GONE);
                    earlier.setEnabled(true);
                    if (more) earlier.setOnClickListener(view ->
                            loadExamples(word, page.get(page.size() - 1).id, dialog, adapter, earlier));
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) adapter.add(getString(R.string.learning_load_error));
                    earlier.setEnabled(true);
                });
            }
        });
    }

    private String definition(String word) {
        String meaning = vocabulary.meaning(word);
        return meaning.isEmpty() ? getString(R.string.no_word_meaning) : meaning;
    }

    private Button button(int title) {
        Button button = new Button(this);
        button.setText(title);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return wordMode ? filteredWords.size() : sentences.size(); }
        @Override public Object getItem(int position) {
            return wordMode ? filteredWords.get(position) : sentences.get(position);
        }
        @Override public long getItemId(int position) {
            return wordMode ? position : sentences.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView == null ? new TextView(LearningActivity.this) : (TextView) convertView;
            row.setTextSize(16);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            if (wordMode) {
                Word word = filteredWords.get(position);
                boolean muted = LearningWords.muted(preferences).contains(word.text);
                row.setText(getString(R.string.word_row, word.text, word.level, word.count,
                        getString(muted ? R.string.word_muted : R.string.word_highlighted))
                        + "\n" + definition(word.text));
            } else {
                LearningStore.Sentence sentence = sentences.get(position);
                CharSequence highlighted = LearningWords.highlight(sentence.text, 0, vocabulary.matches(sentence.text),
                        LearningWords.muted(preferences));
                Date date = new Date(sentence.savedAt);
                SpannableStringBuilder body = new SpannableStringBuilder(
                        DateFormat.getDateFormat(LearningActivity.this).format(date));
                body.append(" ").append(DateFormat.getTimeFormat(LearningActivity.this).format(date))
                        .append("\n").append(highlighted);
                if (!sentence.ocrTranslation.isEmpty() && !sentence.ocrTranslation.equals(sentence.text)) {
                    body.append("\n").append(getString(R.string.ocr_comparison_translation, sentence.ocrTranslation));
                }
                row.setText(body);
            }
            return row;
        }
    }

    private static final class Word {
        final String text;
        final String level;
        final long count;

        Word(String text, String level, long count) {
            this.text = text;
            this.level = level;
            this.count = count;
        }
    }
}
