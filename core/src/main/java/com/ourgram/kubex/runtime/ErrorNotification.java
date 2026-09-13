package com.ourgram.kubex.runtime;

public final class ErrorNotification {
    private static final long INTERVAL_NANOS = 5_000_000_000L;
    private long lastNotification;
    private boolean sent;

    public String next(String report, long now) {
        if(sent && now - lastNotification < INTERVAL_NANOS) return null;
        sent = true;
        lastNotification = now;

        String[] lines = report.split("\\R");
        String location = lines.length > 1 ? lines[1].replace("[KubeX] ", "") : "Unknown location";
        int detailIndex = 2;
        if(lines.length > 2 && lines[2].startsWith("-> ")) {
            location = lines[2].substring(3);
            detailIndex = 3;
        }
        String detail = lines.length > detailIndex ? lines[detailIndex] : "See the console for details.";
        return "[KubeX] Unhandled async error\n" + shorten(location) + "\n" + shorten(detail)
        + "\nSee the console for details. Chat alerts are limited to once every 5 seconds.";
    }

    private String shorten(String text) {
        return text.length() > 200 ? text.substring(0, 197) + "..." : text;
    }
}