package com.cosmos90gt.ctrl;

import java.util.Arrays;

/** Stream parser: only checksum-valid frames are emitted; everything else is reported as junk. */
public final class AuxParser {
    public interface Sink {
        void onFrame(Aux.Frame f);
        void onJunk(int bytes);
    }

    private final byte[] buf = new byte[4096];
    private int n = 0;

    public void reset() { n = 0; }

    public void feed(byte[] d, int off, int len, Sink s) {
        for (int i = 0; i < len; i++) {
            if (n == buf.length) { s.onJunk(n); n = 0; }
            buf[n++] = d[off + i];
            drain(s);
        }
    }

    private void drain(Sink s) {
        while (true) {
            int i = 0;
            while (i < n && (buf[i] & 0xFF) != Aux.PREAMBLE) i++;
            if (i > 0) { s.onJunk(i); shift(i); }
            if (n < 2) return;
            int len = buf[1] & 0xFF;
            if (len < 3 || len > Aux.MAX_LEN) { s.onJunk(1); shift(1); continue; }
            int total = len + 3;
            if (n < total) return;
            byte[] f = Arrays.copyOf(buf, total);
            if (Aux.checksumOk(f)) { shift(total); s.onFrame(Aux.parse(f)); }
            else { s.onJunk(1); shift(1); }
        }
    }

    private void shift(int k) {
        System.arraycopy(buf, k, buf, 0, n - k);
        n -= k;
    }
}
