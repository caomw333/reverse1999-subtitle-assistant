package com.local.reverse1999.subtitles;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class CaptionFusion {
    private static final long OCR_WAIT_MS = 2700;
    private static final long MAX_VOICE_WAIT_MS = 8500;
    private static final long SHOWN_OCR_RETENTION_MS = 20000;
    private static final long MAX_DIFFERENT_START_GAP_MS = 1100;
    private static final int WINDOW_SIZE = 12;
    private final Consumer<SubtitleHistory.Caption> show;
    private final Consumer<String> log;
    private final List<Voice> recentVoice = new ArrayList<>();
    private final List<Screen> screens = new ArrayList<>();
    private Voice activeVoice;
    private long nextId = 1;
    private long audioThrough;
    private boolean voiceEnabled;

    CaptionFusion(boolean voiceEnabled, Consumer<SubtitleHistory.Caption> show) {
        this(voiceEnabled, show, message -> {});
    }

    CaptionFusion(boolean voiceEnabled, Consumer<SubtitleHistory.Caption> show, Consumer<String> log) {
        this.voiceEnabled = voiceEnabled;
        this.show = show;
        this.log = log;
    }

    void audioProcessed(long through, long now) {
        audioThrough = Math.max(audioThrough, through);
        if (waitingForVoice()) update(now);
    }

    void voice(String text, boolean complete, long recognizedAt, long speechStart, long speechEnd, long now) {
        if (!voiceEnabled) return;
        if (activeVoice == null && text.isEmpty()) return;
        if (activeVoice == null) activeVoice = new Voice(nextId++, speechStart);
        Voice voice = activeVoice;
        voice.start = speechStart;
        voice.end = speechEnd;
        if (!complete) {
            if (!hasShownScreen(voice)) {
                voice.partialShown = true;
                show.accept(new SubtitleHistory.Caption(voice.id, text, false, recognizedAt));
            }
            return;
        }
        activeVoice = null;
        if (text.isEmpty()) {
            show.accept(new SubtitleHistory.Caption(voice.id, "", true, recognizedAt));
            update(now);
            return;
        }
        voice.caption = new SubtitleHistory.Caption(voice.id, text, true, recognizedAt);
        recentVoice.add(voice);
        if (recentVoice.size() > WINDOW_SIZE) recentVoice.remove(0);
        update(now);
        if (!voice.compared) show.accept(voice.caption);
    }

    void screen(String translated, long observedAt, long now) {
        screen(translated, observedAt, observedAt, now);
    }

    void screen(String translated, long observedAt, long confirmedAt, long now) {
        Screen screen = new Screen(translated, observedAt, confirmedAt);
        if (!voiceEnabled) {
            publishScreen(screen, now);
            return;
        }
        screens.add(screen);
        if (screens.size() > WINDOW_SIZE) {
            Screen oldest = screens.remove(0);
            if (oldest.shownId == 0) publishScreen(oldest, now);
        }
        update(now);
    }

    void screenObservedThrough(long observedAt, long lastSeenAt, long now) {
        for (Screen screen : screens) {
            if (screen.observedAt == observedAt) {
                screen.end = Math.max(screen.end, lastSeenAt);
                update(now);
                return;
            }
        }
    }

    boolean waitingForVoice() {
        return screens.stream().anyMatch(screen -> screen.shownId == 0);
    }

    void disableVoice(long now) {
        voiceEnabled = false;
        if (activeVoice != null) {
            show.accept(new SubtitleHistory.Caption(activeVoice.id, "", true, now));
            activeVoice = null;
        }
        update(now);
    }

    void update(long now) {
        for (Iterator<Screen> iterator = screens.iterator(); iterator.hasNext(); ) {
            Screen screen = iterator.next();
            Voice match = findVoice(screen);
            boolean waitingOnActive = activeVoice != null && (near(activeVoice, screen) || overlapsSpeech(activeVoice, screen))
                    && (match == null || distance(activeVoice, screen) + 250 < distance(match, screen));
            if (match != null && !waitingOnActive) {
                showMatch(match, screen, now);
                iterator.remove();
                continue;
            } else if (screen.shownId == 0) {
                long deadline = screen.observedAt + OCR_WAIT_MS;
                boolean timedOut = now >= screen.observedAt + MAX_VOICE_WAIT_MS;
                boolean processed = now >= deadline && audioThrough >= deadline;
                if (!voiceEnabled || timedOut || (processed && !waitingOnActive)) {
                    if (waitingOnActive && !hasShownScreen(activeVoice)
                            && (distance(activeVoice, screen) <= MAX_DIFFERENT_START_GAP_MS
                            || overlapsSpeech(activeVoice, screen))) {
                        screen.shownId = activeVoice.id;
                    }
                    String reason = !voiceEnabled ? "asr_disabled" : timedOut ? "timeout" : "no_clear_match";
                    publishScreen(screen, now, reason);
                }
            }
            if (screen.shownId != 0 && now - screen.publishedAt > SHOWN_OCR_RETENTION_MS) iterator.remove();
        }
    }

    private boolean hasShownScreen(Voice voice) {
        return screens.stream().anyMatch(screen -> screen.shownId != 0
                && (screen.shownId == voice.id || canMatchByTime(voice, screen)));
    }

    private Voice findVoice(Screen screen) {
        Voice best = null;
        long bestDistance = Long.MAX_VALUE;
        long runnerUp = Long.MAX_VALUE;
        for (Voice voice : recentVoice) {
            if (voice.compared || !inOrder(voice, screen)) continue;
            if (screen.shownId != 0 && screen.shownId == voice.id) return voice;
            boolean close = similar(voice.caption.text, screen.text);
            if (!close && isAmbiguousScreen(voice, screen)) continue;
            long gap = distance(voice, screen);
            if (!canMatchByTime(voice, screen)
                    && !(close && near(voice, screen))) continue;
            long score = gap - (close ? 1000 : 0) - Math.min(700, overlap(voice, screen) / 2);
            if (score < bestDistance) {
                runnerUp = bestDistance;
                bestDistance = score;
                best = voice;
            } else {
                runnerUp = Math.min(runnerUp, score);
            }
        }
        return best != null && (runnerUp == Long.MAX_VALUE || runnerUp - bestDistance >= 250) ? best : null;
    }

    private boolean isAmbiguousScreen(Voice voice, Screen candidate) {
        long gap = distance(voice, candidate);
        for (Screen other : screens) {
            if (other == candidate || !canMatchByTime(voice, other) || !inOrder(voice, other)) continue;
            if (distance(voice, other) <= gap + 250) return true;
        }
        return false;
    }

    private boolean inOrder(Voice voice, Screen screen) {
        for (Voice paired : recentVoice) {
            if (!paired.compared) continue;
            if ((paired.id < voice.id && paired.matchedScreenAt >= screen.observedAt)
                    || (paired.id > voice.id && paired.matchedScreenAt <= screen.observedAt)) return false;
        }
        return true;
    }

    private void showMatch(Voice voice, Screen screen, long now) {
        voice.compared = true;
        voice.matchedScreenAt = screen.observedAt;
        long id = voice.id;
        if (screen.shownId != 0 && screen.shownId != id) {
            if (voice.partialShown) show.accept(new SubtitleHistory.Caption(id, "", true, now));
            id = screen.shownId;
        }
        SubtitleHistory.Comparison comparison = similar(voice.caption.text, screen.text)
                ? SubtitleHistory.Comparison.CLOSE : SubtitleHistory.Comparison.DIFFERENT;
        log.accept("fusion_match segment=" + id + " start_delta_ms=" + (voice.start - screen.observedAt)
                + " overlap_ms=" + overlap(voice, screen)
                + " reason=" + (comparison == SubtitleHistory.Comparison.CLOSE ? "time_and_text" : "time")
                + " replaced_ocr=" + (screen.shownId != 0));
        show.accept(new SubtitleHistory.Caption(id, voice.caption.text, true,
                voice.caption.recognizedAt, comparison, screen.text));
    }

    private void publishScreen(Screen screen, long now) {
        publishScreen(screen, now, voiceEnabled ? "buffer_limit" : "ocr_only");
    }

    private void publishScreen(Screen screen, long now, String reason) {
        if (screen.shownId == 0) screen.shownId = nextId++;
        screen.publishedAt = now;
        log.accept("fusion_ocr segment=" + screen.shownId + " wait_ms=" + (now - screen.observedAt)
                + " reason=" + reason);
        show.accept(new SubtitleHistory.Caption(screen.shownId, screen.text, true, now, voiceEnabled
                ? SubtitleHistory.Comparison.OCR_UNPAIRED : SubtitleHistory.Comparison.OCR_ONLY, ""));
    }

    private static boolean near(Voice voice, Screen screen) {
        long delta = screen.observedAt - voice.start;
        return delta >= -1850 && delta <= 2100;
    }

    private static long distance(Voice voice, Screen screen) {
        return Math.abs(voice.start - screen.observedAt);
    }

    private static boolean overlapsSpeech(Voice voice, Screen screen) {
        return overlap(voice, screen) >= 250 && distance(voice, screen) <= 2100;
    }

    private static long overlap(Voice voice, Screen screen) {
        return Math.max(0, Math.min(voice.end, screen.end) - Math.max(voice.start, screen.observedAt));
    }

    private static boolean canMatchByTime(Voice voice, Screen screen) {
        if (screen.observedAt > voice.end + 300 || voice.start > screen.end + MAX_DIFFERENT_START_GAP_MS) {
            return false;
        }
        return distance(voice, screen) <= MAX_DIFFERENT_START_GAP_MS || overlapsSpeech(voice, screen);
    }

    static boolean similar(String left, String right) {
        String[] first = words(left);
        String[] second = words(right);
        if (first.length == 0 || second.length == 0) return false;
        int[] previous = new int[second.length + 1];
        for (int column = 1; column <= second.length; column++) previous[column] = column;
        for (int row = 1; row <= first.length; row++) {
            int[] next = new int[second.length + 1];
            next[0] = row;
            for (int column = 1; column <= second.length; column++) {
                int replacement = previous[column - 1] + (first[row - 1].equals(second[column - 1]) ? 0 : 1);
                next[column] = Math.min(replacement, 1 + Math.min(previous[column], next[column - 1]));
            }
            previous = next;
        }
        return previous[second.length] <= Math.max(first.length, second.length) * 0.28;
    }

    private static String[] words(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return normalized.isEmpty() ? new String[0] : normalized.split(" ");
    }

    private static final class Voice {
        final long id;
        long start;
        long end;
        SubtitleHistory.Caption caption;
        boolean compared;
        long matchedScreenAt;
        boolean partialShown;

        Voice(long id, long start) {
            this.id = id;
            this.start = start;
        }
    }

    private static final class Screen {
        final String text;
        final long observedAt;
        long end;
        long shownId;
        long publishedAt;

        Screen(String text, long observedAt, long confirmedAt) {
            this.text = text;
            this.observedAt = observedAt;
            end = confirmedAt;
        }
    }
}
