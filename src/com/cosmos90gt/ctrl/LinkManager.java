package com.cosmos90gt.ctrl;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the connection thread: connect, read loop, auto-reconnect with backoff (WiFi).
 *  All socket / USB I/O happens off the main thread; callbacks are delivered on the main thread. */
final class LinkManager {
    interface Listener {
        void onStatus(int state, String text);
        void onConnected();
        void onRx(byte[] data);
    }

    static final int IDLE = 0, CONNECTING = 1, CONNECTED = 2, RETRY = 3, BUSY = 4, ERROR = 5;

    private final Context ctx;
    private final HexLog log;
    private final Handler ui;
    private final Listener l;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile int generation;
    private volatile Transport tr;

    LinkManager(Context ctx, HexLog log, Handler ui, Listener l) {
        this.ctx = ctx; this.log = log; this.ui = ui; this.l = l;
    }

    boolean isUp() { return tr != null && running; }

    synchronized void startWifi() { start(null); }
    synchronized void startUsb(UsbDevice d) { start(d); }

    private void start(final UsbDevice usb) {
        stop();
        running = true;
        final int gen = ++generation;
        new Thread(new Runnable() { public void run() { loop(usb, gen); } }, "link").start();
    }

    synchronized void stop() {
        running = false;
        generation++;
        Transport t = tr;
        if (t != null) t.close();
        post(generation, new Runnable() { public void run() { l.onStatus(IDLE, "Disconnected"); } });
    }

    private boolean alive(int gen) { return running && gen == generation; }

    private void post(final int gen, final Runnable r) {
        ui.post(new Runnable() { public void run() { if (gen == generation) r.run(); } });
    }

    private void status(int gen, final int state, final String text) {
        post(gen, new Runnable() { public void run() { l.onStatus(state, text); } });
    }

    /** Queue a write on the single writer thread; a failed write closes the link so it reconnects. */
    void send(final byte[] b) {
        final Transport t = tr;
        if (t == null) return;
        writer.execute(new Runnable() {
            public void run() {
                try { log.add("TX", b, 0, b.length); t.write(b); }
                catch (IOException e) { log.note("TX failed: " + e); t.close(); }
            }
        });
    }

    private void loop(UsbDevice usb, final int gen) {
        int attempt = 0;
        while (alive(gen)) {
            Transport t = (usb == null) ? new WifiTransport(ctx) : new UsbSerialTransport(ctx, usb);
            tr = t;
            status(gen, CONNECTING, usb == null ? "Connecting to 1.2.3.4:2000 ..." : "Opening USB serial ...");
            String why = null;
            int endState = RETRY;
            boolean gotData = false;
            try {
                t.open();
                if (!alive(gen)) break;
                attempt = 0;
                long up = System.currentTimeMillis();
                log.note("Connected: " + t.label());
                status(gen, CONNECTED, "Connected: " + t.label());
                post(gen, new Runnable() { public void run() { l.onConnected(); } });
                byte[] buf = new byte[2048];
                int n;
                while (alive(gen) && (n = t.read(buf)) >= 0) {
                    if (n > 0) {
                        gotData = true;
                        log.add("RX", buf, 0, n);
                        final byte[] copy = Arrays.copyOf(buf, n);
                        post(gen, new Runnable() { public void run() { l.onRx(copy); } });
                    }
                }
                if (alive(gen)) {
                    long life = System.currentTimeMillis() - up;
                    if (!gotData && life < 4000 && usb == null) {
                        endState = BUSY;
                        why = "Module closed the connection straight away - probably busy with another client. Close other controller apps or power-cycle the scope.";
                    } else {
                        why = "Connection closed after " + (life / 1000) + " s.";
                    }
                }
            } catch (IOException e) {
                if (alive(gen)) {
                    why = describe(e);
                    String m = String.valueOf(e.getMessage()).toLowerCase();
                    if (e instanceof SocketTimeoutException || m.contains("refused")) endState = BUSY;
                }
            } finally {
                t.close();
                tr = null;
            }
            if (!alive(gen)) break;
            log.note(why);
            if (usb != null) { status(gen, ERROR, why + " (USB: not retrying)"); break; }
            long delay = Math.min(15000L, 1000L << Math.min(attempt, 4));
            attempt++;
            for (long w = 0; w < delay && alive(gen); w += 100) {
                if (w % 1000 == 0) status(gen, endState, why + "  Retrying in " + ((delay - w + 999) / 1000) + " s");
                try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static String describe(IOException e) {
        String m = String.valueOf(e.getMessage());
        String lm = m.toLowerCase();
        if (e instanceof SocketTimeoutException)
            return "No answer from 1.2.3.4:2000 (timeout). Are you on the scope's SkyQLink WiFi? The module serves one client at a time - it may be busy.";
        if (lm.contains("refused"))
            return "Connection refused by 1.2.3.4:2000. Module busy with another client, or wedged - power-cycle the scope.";
        if (lm.contains("unreachable"))
            return "Network unreachable - not joined to the SkyQLink WiFi?";
        if (lm.contains("software caused connection abort"))
            return "Connection aborted by the stack/module (seen in earlier tests). Reconnecting.";
        return e.getClass().getSimpleName() + ": " + m;
    }
}
