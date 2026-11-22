package com.cosmos90gt.ctrl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Timestamped hex log with TX/RX direction markers. Thread-safe. Pure Java. */
public final class HexLog {
    private static final int MAX_LINES = 8000;
    private final ArrayList<String> lines = new ArrayList<String>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static String hex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", d[off + i] & 0xFF));
        }
        return sb.toString();
    }

    public static String ascii(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = d[off + i] & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return sb.toString();
    }

    /** dir is "TX" or "RX". */
    public synchronized void add(String dir, byte[] d, int off, int len) {
        put(dir + " " + len + "B  " + hex(d, off, len) + "  |" + ascii(d, off, len) + "|");
    }

    public synchronized void note(String s) { put("--  " + s); }

    private void put(String s) {
        lines.add(fmt.format(new Date()) + " " + s);
        if (lines.size() > MAX_LINES) lines.subList(0, 1000).clear();
    }

    public synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    public synchronized String all() { return tail(Integer.MAX_VALUE); }
    public synchronized void clear() { lines.clear(); }
}
