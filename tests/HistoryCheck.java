package com.local.reverse1999.subtitles;

public final class HistoryCheck {
    public static void main(String[] args) {
        SubtitleHistory history = new SubtitleHistory();
        history.accept(new SubtitleHistory.Caption(1, "Hello", false, 0));
        history.accept(new SubtitleHistory.Caption(1, "Hello there", false, 0));
        assert history.all().size() == 1;
        history.accept(new SubtitleHistory.Caption(1, "Hello there.", true, 0));
        history.accept(new SubtitleHistory.Caption(2, "Next line", false, 0));
        assert history.all().get(0).text.equals("Hello there.");
        assert history.all().get(1).text.equals("Next line");
        history.accept(new SubtitleHistory.Caption(2, "Next line.", true, 0));
        history.accept(new SubtitleHistory.Caption(3, "", true, 0));
        assert history.all().size() == 2;
        assert history.all().get(1).text.equals("Next line.");
        for (int segment = 4; segment <= 110; segment++) {
            history.accept(new SubtitleHistory.Caption(segment, "Final " + segment, true, 0));
        }
        assert history.all().size() == 100;
        assert history.all().get(0).segment == 11;
        history.accept(new SubtitleHistory.Caption(111, "Newest partial", false, 0));
        assert history.all().get(99).segment == 110;
        assert history.all().get(100).segment == 111;
        System.out.println("History checks passed: partial updates, finalization, retained last two, bounded history.");
    }
}
