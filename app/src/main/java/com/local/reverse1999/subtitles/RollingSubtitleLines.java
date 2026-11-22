package com.local.reverse1999.subtitles;

import java.util.Collections;
import java.util.List;

final class RollingSubtitleLines {
    private List<Line> lines = Collections.emptyList();
    private int bottom = -1;

    void update(List<Line> replacement, boolean reflow) {
        Line currentBottom = bottom();
        Line currentTop = top();
        int position = find(replacement, currentBottom, reflow);
        if (position < 0) position = find(replacement, currentTop, reflow);
        if (position < 0 && currentBottom != null) {
            // A revised interim result can shrink to fewer visual lines.
            for (int index = 0; index < replacement.size(); index++) {
                if (replacement.get(index).segment > currentBottom.segment) break;
                position = index;
            }
        }
        if (position < 0) position = Math.min(1, replacement.size() - 1);
        lines = replacement;
        bottom = position;
    }

    Line top() {
        return bottom > 0 ? lines.get(bottom - 1) : null;
    }

    Line bottom() {
        return bottom >= 0 ? lines.get(bottom) : null;
    }

    boolean hasNext() {
        return bottom + 1 < lines.size();
    }

    void advance() {
        if (hasNext()) bottom++;
    }

    private static int find(List<Line> candidates, Line target, boolean reflow) {
        if (target == null) return -1;
        int reflowed = -1;
        for (int index = 0; index < candidates.size(); index++) {
            Line candidate = candidates.get(index);
            if (candidate.segment != target.segment) continue;
            if (reflow && candidate.start <= target.start) reflowed = index;
            if (!reflow && candidate.number == target.number) return index;
        }
        return reflowed;
    }

    static final class Line {
        final long segment;
        final int number;
        final int start;
        final String text;

        Line(long segment, int number, int start, String text) {
            this.segment = segment;
            this.number = number;
            this.start = start;
            this.text = text;
        }
    }
}
