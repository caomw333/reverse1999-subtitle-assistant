package com.local.reverse1999.subtitles;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SubtitleOverlay implements AutoCloseable {
    static final String FONT_SIZE = "fontSize";
    static final String BACKGROUND_TRANSPARENCY = "backgroundTransparency";
    static final int DEFAULT_TRANSPARENCY = 5;
    static final int DEFAULT_SIZE = 1;
    private static final int[] FONT_SIZES = {16, 21, 26, 32};
    private static final long SCROLL_DURATION_MS = 200;

    private final Context context;
    private final WindowManager windows;
    private final WindowManager.LayoutParams layout;
    private final LinearLayout root;
    private final GradientDrawable background = new GradientDrawable();
    private final Point screen = new Point();
    private final LinearLayout collapsed;
    private final LinearLayout expanded;
    private final FrameLayout viewport;
    private final LinearLayout rows;
    private final TextView previousLine;
    private final TextView latestLine;
    private final TextView incomingLine;
    private final ListView historyList;
    private final HistoryAdapter historyAdapter = new HistoryAdapter();
    private final SubtitleHistory history = new SubtitleHistory();
    private final RollingSubtitleLines rolling = new RollingSubtitleLines();
    private final Map<Long, WrappedCaption> wrappedCaptions = new HashMap<>();
    private final Runnable rollNext = this::rollNext;
    private final SharedPreferences preferences;
    private final CetVocabulary vocabulary;
    private final boolean aboveGameSubtitles;
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceChanged = (prefs, key) -> {
        if (FONT_SIZE.equals(key)) render();
        if (BACKGROUND_TRANSPARENCY.equals(key)) updateBackground();
        if (LearningWords.MUTED.equals(key) && !this.scrolling) {
            drawVisibleLines();
            historyAdapter.notifyDataSetChanged();
        }
    };
    private boolean attached;
    private boolean hiddenForSelection;
    private volatile Rect captureBounds = new Rect();
    private boolean isExpanded;
    private boolean scrolling;
    private int lineHeight;
    private int wrapWidth;
    private float textSizePixels;
    private long nextScrollAt;
    private String positionKey;
    private float positionX = 0.5f;
    private float positionY = -1f;

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("subtitles", Context.MODE_PRIVATE);
    }

    SubtitleOverlay(Context owner, boolean aboveGameSubtitles) {
        this.aboveGameSubtitles = aboveGameSubtitles;
        if (Build.VERSION.SDK_INT >= 30) {
            Context display = owner.createDisplayContext(owner.getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY));
            context = display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } else {
            context = owner;
        }
        windows = context.getSystemService(WindowManager.class);
        preferences = preferences(owner);
        vocabulary = LearningWords.vocabulary(owner);
        root = new LinearLayout(context) {
            @Override
            protected void onConfigurationChanged(Configuration configuration) {
                super.onConfigurationChanged(configuration);
                post(() -> {
                    if (attached) {
                        updateScreenBounds();
                        render();
                    }
                });
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        background.setCornerRadius(dp(12));
        root.setBackground(background);
        root.setClipToOutline(true);
        updateBackground();

        collapsed = column();
        collapsed.setPadding(dp(14), dp(5), dp(14), dp(5));
        collapsed.setGravity(Gravity.CENTER_HORIZONTAL);
        collapsed.setOnClickListener(view -> toggleHistory());
        DragGesture drag = new DragGesture();
        collapsed.setOnTouchListener(drag);
        viewport = new FrameLayout(context);
        rows = column();
        previousLine = compactLine();
        previousLine.setTextColor(0xffc4ccd1);
        latestLine = compactLine();
        incomingLine = compactLine();
        rows.addView(previousLine, new LinearLayout.LayoutParams(-1, 0));
        rows.addView(latestLine, new LinearLayout.LayoutParams(-1, 0));
        rows.addView(incomingLine, new LinearLayout.LayoutParams(-1, 0));
        viewport.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        collapsed.addView(viewport, new LinearLayout.LayoutParams(-1, 0));
        root.addView(collapsed);

        expanded = column();
        Button collapse = new Button(context);
        collapse.setText(R.string.collapse_history);
        collapse.setOnClickListener(view -> toggleHistory());
        collapse.setOnTouchListener(drag);
        expanded.addView(collapse, new LinearLayout.LayoutParams(-1, -2));
        historyList = new ListView(context);
        historyList.setAdapter(historyAdapter);
        historyList.setStackFromBottom(true);
        historyList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        historyList.setDividerHeight(dp(1));
        expanded.addView(historyList, new LinearLayout.LayoutParams(-1, dp(220)));
        expanded.setVisibility(View.GONE);
        root.addView(expanded);

        layout = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            layout.setFitInsetsIgnoringVisibility(true);
        }
        updateScreenBounds();
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            refreshCaptureBounds();
            if (attached && bottom - top != oldBottom - oldTop) applyWindowPosition(layout.width);
        });
        preferences.registerOnSharedPreferenceChangeListener(appearanceChanged);
    }

    void show(SubtitleHistory.Caption caption) {
        history.accept(caption);
        render();
    }

    void showPreview(String first, String second) {
        long now = SystemClock.uptimeMillis();
        history.accept(new SubtitleHistory.Caption(1, first, true, now));
        history.accept(new SubtitleHistory.Caption(2, second, true, now));
        render();
    }

    private void render() {
        List<SubtitleHistory.Caption> items = history.all();
        root.setVisibility(items.isEmpty() || hiddenForSelection ? View.GONE : View.VISIBLE);
        if (root.getVisibility() != View.VISIBLE) captureBounds = new Rect();
        if (items.isEmpty()) {
            rolling.update(Collections.emptyList(), false);
            nextScrollAt = 0;
            root.removeCallbacks(rollNext);
            resetAnimation();
            return;
        }

        float widthFraction = screen.x > screen.y ? 0.70f : 0.88f;
        int panelWidth = Math.max(1, Math.min(dp(850), Math.round(screen.x * widthFraction)));
        int lineWidth = Math.max(1, panelWidth - dp(28));
        previousLine.setTextSize(fontSize());
        latestLine.setTextSize(fontSize());
        incomingLine.setTextSize(fontSize());
        boolean reflow = lineWidth != wrapWidth || latestLine.getTextSize() != textSizePixels;
        if (reflow) {
            wrapWidth = lineWidth;
            textSizePixels = latestLine.getTextSize();
            lineHeight = latestLine.getPaint().getFontMetricsInt(null) + dp(4);
            previousLine.getLayoutParams().height = lineHeight;
            latestLine.getLayoutParams().height = lineHeight;
            incomingLine.getLayoutParams().height = lineHeight;
            rows.getLayoutParams().height = lineHeight * 3;
            viewport.getLayoutParams().height = lineHeight * 2;
            viewport.requestLayout();
            wrappedCaptions.clear();
            resetAnimation();
        }

        RollingSubtitleLines.Line before = rolling.bottom();
        rolling.update(wrap(items), reflow);
        RollingSubtitleLines.Line after = rolling.bottom();
        if (after == null || reflow || before == null
                || before.segment != after.segment || before.number != after.number) {
            nextScrollAt = SystemClock.uptimeMillis() + readingTime(after);
        } else if (!before.text.equals(after.text)) {
            nextScrollAt = Math.max(nextScrollAt, SystemClock.uptimeMillis() + 400);
        }

        if (isExpanded) {
            int screenHeight = screen.y;
            int available = aboveGameSubtitles ? (int) (screenHeight * 0.50f) - dp(68)
                    : (int) (screenHeight * 0.43f);
            historyList.getLayoutParams().height = Math.max(dp(32), Math.min(dp(280), available));
            historyList.requestLayout();
            historyAdapter.update(items);
        }
        if (!scrolling) drawVisibleLines();
        applyWindowPosition(panelWidth);
        if (!attached) {
            windows.addView(root, layout);
            attached = true;
        }
        scheduleScroll();
    }

    Rect captureBounds() {
        return captureBounds;
    }

    void addRegionPicker(Runnable select) {
        Button button = new Button(context);
        button.setText(R.string.ocr_region);
        button.setOnClickListener(view -> select.run());
        expanded.addView(button, 1, new LinearLayout.LayoutParams(-1, -2));
    }

    void hideForSelection(boolean hidden) {
        hiddenForSelection = hidden;
        render();
    }

    private void refreshCaptureBounds() {
        if (root.getVisibility() != View.VISIBLE) {
            captureBounds = new Rect();
            return;
        }
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        captureBounds = new Rect(origin[0], origin[1], origin[0] + root.getWidth(), origin[1] + root.getHeight());
    }

    private void updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windows.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(layout.getFitInsetsTypes());
            screen.set(bounds.width() - insets.left - insets.right, bounds.height() - insets.top - insets.bottom);
        } else {
            windows.getDefaultDisplay().getSize(screen);
        }
        String key = "position." + (aboveGameSubtitles ? "ocr." : "asr.")
                + (screen.x > screen.y ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT);
        if (!key.equals(positionKey)) {
            positionKey = key;
            positionX = preferences.getFloat(key + ".x", 0.5f);
            positionY = preferences.getFloat(key + ".y", -1f);
        }
    }

    private void applyWindowPosition(int panelWidth) {
        int maxX = Math.max(0, screen.x - panelWidth);
        int maxY = verticalTravel();
        int x = Math.round(positionX * maxX);
        int y = positionY >= 0 ? Math.round(positionY * maxY)
                : aboveGameSubtitles ? Math.min(dp(12), maxY) : Math.max(0, maxY - dp(48));
        if (layout.width == panelWidth && layout.x == x && layout.y == y) return;
        layout.width = panelWidth;
        layout.x = x;
        layout.y = y;
        if (attached) {
            windows.updateViewLayout(root, layout);
            root.post(this::refreshCaptureBounds);
        }
    }

    private int verticalTravel() {
        int height = root.getHeight() > 0 ? root.getHeight() : 2 * lineHeight + dp(10);
        return Math.max(0, screen.y - height);
    }

    private void updateBackground() {
        int transparency = isExpanded ? DEFAULT_TRANSPARENCY
                : preferences.getInt(BACKGROUND_TRANSPARENCY, DEFAULT_TRANSPARENCY);
        int alpha = Math.round(255 * (100 - transparency) / 100f);
        background.setColor((alpha << 24) | 0x111820);
    }

    private final class DragGesture implements View.OnTouchListener {
        private final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX;
        private float downY;
        private float dragX;
        private float dragY;
        private boolean dragging;
        private int pointer;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pointer = event.getPointerId(0);
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragX = layout.x;
                    dragY = layout.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(pointer);
                    if (index < 0) return true;
                    float deltaX = event.getRawX(index) - downX;
                    float deltaY = event.getRawY(index) - downY;
                    if (!dragging && Math.hypot(deltaX, deltaY) <= touchSlop) return true;
                    dragging = true;
                    int maxX = Math.max(0, screen.x - layout.width);
                    int maxY = verticalTravel();
                    dragX = Math.max(0, Math.min(maxX, dragX + deltaX));
                    dragY = Math.max(0, Math.min(maxY, dragY + deltaY));
                    downX = event.getRawX(index);
                    downY = event.getRawY(index);
                    positionX = fraction(dragX, maxX);
                    positionY = fraction(dragY, maxY);
                    applyWindowPosition(layout.width);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) != pointer) return true;
                    savePosition();
                    pointer = -1;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointer >= 0 && !dragging) view.performClick();
                    else savePosition();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    savePosition();
                    return true;
                default:
                    return true;
            }
        }

        private float fraction(float offset, int travel) {
            return travel > 0 ? Math.max(0, Math.min(1, offset / travel)) : 0;
        }

        private void savePosition() {
            if (dragging) {
                preferences.edit().putFloat(positionKey + ".x", positionX)
                        .putFloat(positionKey + ".y", positionY).apply();
                dragging = false;
            }
        }
    }

    private List<RollingSubtitleLines.Line> wrap(List<SubtitleHistory.Caption> items) {
        List<SubtitleHistory.Caption> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingLong(caption -> caption.segment));
        Map<Long, WrappedCaption> current = new HashMap<>();
        List<RollingSubtitleLines.Line> lines = new ArrayList<>();
        for (SubtitleHistory.Caption caption : ordered) {
            WrappedCaption wrapped = wrappedCaptions.get(caption.segment);
            if (wrapped == null || !wrapped.text.equals(caption.text)) {
                List<RollingSubtitleLines.Line> visualLines = new ArrayList<>();
                StaticLayout measured = StaticLayout.Builder.obtain(caption.text, 0, caption.text.length(),
                                latestLine.getPaint(), wrapWidth)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                        .setIncludePad(false)
                        .build();
                for (int index = 0; index < measured.getLineCount(); index++) {
                    int start = measured.getLineStart(index);
                    String raw = caption.text.substring(start, measured.getLineEnd(index));
                    String line = raw.trim();
                    if (!line.isEmpty()) {
                        visualLines.add(new RollingSubtitleLines.Line(caption.segment, index,
                                start + raw.indexOf(line), line));
                    }
                }
                wrapped = new WrappedCaption(caption.text, visualLines, vocabulary.matches(caption.text));
            }
            current.put(caption.segment, wrapped);
            lines.addAll(wrapped.lines);
        }
        wrappedCaptions.clear();
        wrappedCaptions.putAll(current);
        return lines;
    }

    private void scheduleScroll() {
        root.removeCallbacks(rollNext);
        if (!scrolling && rolling.hasNext()) {
            root.postDelayed(rollNext, Math.max(0, nextScrollAt - SystemClock.uptimeMillis()));
        }
    }

    private void rollNext() {
        if (!attached || !rolling.hasNext()) return;
        rolling.advance();
        nextScrollAt = SystemClock.uptimeMillis() + readingTime(rolling.bottom());
        if (isExpanded) {
            drawVisibleLines();
            scheduleScroll();
            return;
        }
        scrolling = true;
        nextScrollAt += SCROLL_DURATION_MS;
        latestLine.setText(styledLine(rolling.top()));
        incomingLine.setText(styledLine(rolling.bottom()));
        rows.animate().translationY(-lineHeight).setDuration(SCROLL_DURATION_MS).withEndAction(() -> {
            rows.setTranslationY(0);
            scrolling = false;
            drawVisibleLines();
            scheduleScroll();
        }).start();
    }

    private void drawVisibleLines() {
        String previous = lineText(rolling.top());
        String latest = lineText(rolling.bottom());
        previousLine.setText(styledLine(rolling.top()));
        latestLine.setText(styledLine(rolling.bottom()));
        incomingLine.setText("");
        collapsed.setContentDescription(context.getString(R.string.expand_history_accessibility,
                previous.isEmpty() ? latest : previous + "\n" + latest));
    }

    private static String lineText(RollingSubtitleLines.Line line) {
        return line == null ? "" : line.text;
    }

    private CharSequence styledLine(RollingSubtitleLines.Line line) {
        if (line == null) return "";
        WrappedCaption caption = wrappedCaptions.get(line.segment);
        return caption == null ? line.text : LearningWords.highlight(line.text, line.start, caption.matches,
                LearningWords.muted(preferences));
    }

    private static long readingTime(RollingSubtitleLines.Line line) {
        return Math.max(1000, Math.min(2600, 500L + lineText(line).length() * 33L));
    }

    private void resetAnimation() {
        rows.animate().withEndAction(null).cancel();
        rows.setTranslationY(0);
        scrolling = false;
    }

    private void toggleHistory() {
        isExpanded = !isExpanded;
        updateBackground();
        resetAnimation();
        DiagnosticLog.add(isExpanded ? "history_expanded" : "history_collapsed");
        collapsed.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        expanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        render();
        if (isExpanded && historyAdapter.getCount() > 0) {
            historyList.setSelection(historyAdapter.getCount() - 1);
        }
    }

    @Override
    public void close() {
        preferences.unregisterOnSharedPreferenceChangeListener(appearanceChanged);
        root.removeCallbacks(rollNext);
        resetAnimation();
        if (attached) {
            windows.removeViewImmediate(root);
            attached = false;
        }
    }

    private int fontSize() {
        int index = preferences.getInt(FONT_SIZE, DEFAULT_SIZE);
        return FONT_SIZES[Math.max(0, Math.min(index, FONT_SIZES.length - 1))];
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView captionText() {
        TextView text = new TextView(context);
        text.setTextColor(0xffffffff);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private TextView compactLine() {
        TextView text = captionText();
        text.setMaxLines(1);
        text.setHorizontallyScrolling(true);
        text.setIncludeFontPadding(false);
        text.setShadowLayer(dp(2), 0, dp(1), 0xff000000);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private static final class WrappedCaption {
        final String text;
        final List<RollingSubtitleLines.Line> lines;
        final List<CetVocabulary.Match> matches;

        WrappedCaption(String text, List<RollingSubtitleLines.Line> lines, List<CetVocabulary.Match> matches) {
            this.text = text;
            this.lines = lines;
            this.matches = matches;
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class HistoryAdapter extends BaseAdapter {
        private List<SubtitleHistory.Caption> items = Collections.emptyList();

        void update(List<SubtitleHistory.Caption> captions) {
            items = captions;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public SubtitleHistory.Caption getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return getItem(position).segment; }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView == null ? captionText() : (TextView) convertView;
            row.setPadding(dp(14), dp(7), dp(14), dp(7));
            row.setTextSize(fontSize());
            SubtitleHistory.Caption caption = getItem(position);
            Set<String> muted = LearningWords.muted(preferences);
            CharSequence highlighted = LearningWords.highlight(caption.text, 0, vocabulary.matches(caption.text), muted);
            switch (caption.comparison) {
                case CLOSE:
                case DIFFERENT:
                    int comparison = caption.comparison == SubtitleHistory.Comparison.CLOSE
                            ? R.string.ocr_comparison_close : R.string.ocr_comparison_different;
                    row.setText(new SpannableStringBuilder(highlighted).append("\n")
                            .append(context.getString(comparison)).append("\n")
                            .append(context.getString(R.string.ocr_comparison_translation, caption.ocrTranslation)));
                    break;
                case OCR_ONLY:
                    row.setText(new SpannableStringBuilder(context.getString(R.string.ocr_translation_label))
                            .append(" ").append(highlighted));
                    break;
                case OCR_UNPAIRED:
                    row.setText(new SpannableStringBuilder(context.getString(R.string.ocr_unpaired))
                            .append("\n").append(highlighted));
                    break;
                default:
                    row.setText(highlighted);
            }
            return row;
        }
    }
}
