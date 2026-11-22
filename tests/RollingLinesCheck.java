package com.local.reverse1999.subtitles;

import java.util.Arrays;
import java.util.List;

final class RollingLinesCheck {
    private static RollingSubtitleLines.Line line(long segment, int number, int start, String text) {
        return new RollingSubtitleLines.Line(segment, number, start, text);
    }

    private static void expect(RollingSubtitleLines display, String top, String bottom) {
        assert display.top() == null ? top.isEmpty() : display.top().text.equals(top);
        assert display.bottom() != null && display.bottom().text.equals(bottom);
    }

    public static void main(String[] args) {
        RollingSubtitleLines display = new RollingSubtitleLines();
        List<RollingSubtitleLines.Line> four = Arrays.asList(
                line(1, 0, 0, "one"), line(1, 1, 4, "two"),
                line(1, 2, 8, "three"), line(1, 3, 14, "four"));
        display.update(four, false);
        expect(display, "one", "two");
        display.advance();
        expect(display, "two", "three");
        display.update(Arrays.asList(four.get(0), four.get(1), four.get(2), four.get(3),
                line(2, 0, 0, "new sentence")), false);
        expect(display, "two", "three");
        display.advance();
        expect(display, "three", "four");
        display.advance();
        expect(display, "four", "new sentence");
        assert !display.hasNext();

        RollingSubtitleLines resized = new RollingSubtitleLines();
        resized.update(four, false);
        resized.advance();
        resized.update(Arrays.asList(line(1, 0, 0, "one two"), line(1, 1, 8, "three four")), true);
        expect(resized, "one two", "three four");

        RollingSubtitleLines revised = new RollingSubtitleLines();
        revised.update(four, false);
        revised.advance();
        revised.update(Arrays.asList(four.get(0), line(1, 1, 4, "two revised")), false);
        expect(revised, "one", "two revised");
        System.out.println("Verified 1-2, 2-3, 3-4 scrolling, queued captions, corrections and font reflow.");
    }
}
