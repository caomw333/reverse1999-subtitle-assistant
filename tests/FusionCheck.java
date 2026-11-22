package com.local.reverse1999.subtitles;

final class FusionCheck {
    public static void main(String[] args) {
        SubtitleHistory history = new SubtitleHistory();
        CaptionFusion mixed = new CaptionFusion(true, history::accept);
        mixed.screen("We need to leave.", 10000, 10800);
        assert history.all().isEmpty();
        mixed.voice("We need", false, 11000, 11000, 11000, 11000);
        mixed.voice("We need to leave", true, 12000, 10100, 11600, 12000);
        assert history.all().size() == 1;
        assert history.all().get(0).comparison == SubtitleHistory.Comparison.CLOSE;

        mixed.voice("The storm is coming", true, 17600, 16300, 17200, 17600);
        mixed.voice("I must", false, 19100, 19100, 19100, 19100);
        mixed.screen("Rain is approaching.", 16100, 19120);
        assert history.all().size() == 3;
        assert history.all().get(1).text.equals("The storm is coming");
        assert history.all().get(1).comparison == SubtitleHistory.Comparison.DIFFERENT;
        assert history.all().get(2).text.equals("I must");
        mixed.voice("I must go now", true, 19900, 18900, 19600, 19900);

        mixed.screen("Wait here.", 24500, 25100);
        mixed.audioProcessed(27199, 27199);
        assert history.all().size() == 3;
        mixed.audioProcessed(27200, 27200);
        assert history.all().get(3).comparison == SubtitleHistory.Comparison.OCR_UNPAIRED;

        SubtitleHistory ocrHistory = new SubtitleHistory();
        CaptionFusion ocrOnly = new CaptionFusion(false, ocrHistory::accept);
        ocrOnly.screen("Keep moving.", 1000, 1600);
        assert ocrHistory.all().get(0).text.equals("Keep moving.");
        assert ocrHistory.all().get(0).comparison == SubtitleHistory.Comparison.OCR_ONLY;

        SubtitleHistory voiceHistory = new SubtitleHistory();
        CaptionFusion voiceOnly = new CaptionFusion(true, voiceHistory::accept);
        voiceOnly.voice("Keep moving", true, 1600, 1000, 1450, 1600);
        assert voiceHistory.all().get(0).comparison == SubtitleHistory.Comparison.NONE;

        SubtitleHistory adjacent = new SubtitleHistory();
        CaptionFusion nearby = new CaptionFusion(true, adjacent::accept);
        nearby.voice("I was expecting you", true, 12100, 10000, 11500, 12100);
        nearby.screen("Go to the next room.", 11800, 12500);
        nearby.audioProcessed(14500, 14500);
        assert adjacent.all().size() == 2;
        assert adjacent.all().get(1).comparison == SubtitleHistory.Comparison.OCR_UNPAIRED;
        lateVoiceReusesOcrRow();
        stalledAudioDoesNotRaceOcr();
        activeVoiceReusesRowAtHardTimeout();
        intervalAndOrderPreventCrossing();
        System.out.println("ASR, OCR, adjacent sentences, audio progress, and late single-row replacement passed.");
    }

    private static void lateVoiceReusesOcrRow() {
        SubtitleHistory history = new SubtitleHistory();
        CaptionFusion fusion = new CaptionFusion(true, history::accept);
        fusion.screen("Rain is approaching.", 10000, 10800);
        fusion.audioProcessed(12700, 12700);
        long id = history.all().get(0).segment;
        fusion.voice("The storm", false, 13300, 10200, 10600, 13300);
        assert history.all().size() == 1;
        assert history.all().get(0).text.equals("Rain is approaching.");
        fusion.voice("The storm is coming", true, 14000, 10200, 12600, 14000);
        assert history.all().size() == 1;
        SubtitleHistory.Caption caption = history.all().get(0);
        assert caption.segment == id;
        assert caption.text.equals("The storm is coming");
        assert caption.ocrTranslation.equals("Rain is approaching.");
        assert caption.comparison == SubtitleHistory.Comparison.DIFFERENT;
    }

    private static void stalledAudioDoesNotRaceOcr() {
        SubtitleHistory history = new SubtitleHistory();
        CaptionFusion fusion = new CaptionFusion(true, history::accept);
        fusion.screen("We should flee.", 20000, 20800);
        fusion.audioProcessed(20500, 23300);
        assert history.all().isEmpty();
        fusion.voice("Make a run for it", true, 24000, 19800, 21800, 24000);
        assert history.all().size() == 1;
        assert history.all().get(0).comparison == SubtitleHistory.Comparison.DIFFERENT;
    }

    private static void activeVoiceReusesRowAtHardTimeout() {
        SubtitleHistory history = new SubtitleHistory();
        CaptionFusion fusion = new CaptionFusion(true, history::accept);
        fusion.screen("The train has left.", 30000, 30700);
        fusion.voice("We have", false, 31200, 30000, 30900, 31200);
        long id = history.all().get(0).segment;
        fusion.audioProcessed(32700, 32700);
        assert !history.all().get(0).complete;
        fusion.update(38500);
        assert history.all().size() == 1;
        assert history.all().get(0).segment == id;
        assert history.all().get(0).comparison == SubtitleHistory.Comparison.OCR_UNPAIRED;
        fusion.voice("We have missed the train", true, 39000, 30100, 35900, 39000);
        assert history.all().size() == 1;
        assert history.all().get(0).segment == id;
        assert history.all().get(0).text.equals("We have missed the train");
    }

    private static void intervalAndOrderPreventCrossing() {
        SubtitleHistory history = new SubtitleHistory();
        CaptionFusion fusion = new CaptionFusion(true, history::accept);
        fusion.screen("A different way of phrasing it.", 10000, 10450, 10800);
        fusion.screenObservedThrough(10000, 13000, 13000);
        fusion.voice("This is the first sentence", true, 13100, 11400, 12400, 13100);
        assert history.all().size() == 1;
        assert history.all().get(0).comparison == SubtitleHistory.Comparison.DIFFERENT;
        fusion.screen("Another screen line.", 13200, 13650, 14000);
        fusion.voice("Now we are leaving", true, 14600, 13400, 14000, 14600);
        assert history.all().size() == 2;
        assert history.all().get(1).ocrTranslation.equals("Another screen line.");
    }
}
