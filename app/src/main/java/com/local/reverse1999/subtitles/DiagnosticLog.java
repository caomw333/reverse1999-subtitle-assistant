package com.local.reverse1999.subtitles;

import android.os.SystemClock;

import java.util.ArrayDeque;

final class DiagnosticLog {
    private static final int MAX_EVENTS = 4096;
    private static final ArrayDeque<String> events = new ArrayDeque<>();

    private DiagnosticLog() {}

    static synchronized void add(String event) {
        if (events.size() == MAX_EVENTS) events.removeFirst();
        events.addLast(SystemClock.uptimeMillis() + " " + event);
    }

    static synchronized String snapshot() {
        if (events.isEmpty()) return "";
        return "Reverse1999 subtitles diagnostic log\n"
                + "Time column: milliseconds since Android boot (awake time)\n"
                + String.join("\n", events) + "\n";
    }
}
