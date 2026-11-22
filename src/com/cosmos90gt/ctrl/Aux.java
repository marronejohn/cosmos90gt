package com.cosmos90gt.ctrl;

import java.util.Arrays;

/** Celestron AUX frame layer. Pure Java (no Android deps) so it can be unit-tested on a PC.
 *  Frame: 0x3B, len(=3+dataLen), src, dst, cmd, data..., checksum
 *  checksum = (-(sum of len..data)) & 0xFF   (VERIFIED structure from captured traffic) */
public final class Aux {
    private Aux() {}

    public static final int PREAMBLE = 0x3B;
    public static final int SRC_APP = 0x20;   // VERIFIED (probe builds)
    public static final int DEV_AZ = 0x10;    // VERIFIED
    public static final int DEV_ALT = 0x11;   // VERIFIED
    public static final int CMD_GET_POS = 0x01;     // VERIFIED
    public static final int CMD_MOVE_POS = 0x24;    // VERIFIED (variable-rate slew, 1 data byte = rate)
    public static final int CMD_MOVE_NEG = 0x25;    // VERIFIED to exist; exact semantics see README
    public static final int CMD_GET_VERSION = 0xFE; // VERIFIED
    // UNVERIFIED standard-map leads (not used by the UI): focus 0x03, GPS 0x04
    public static final int MAX_LEN = 32;

    public static final class Frame {
        public final byte[] raw;
        public final int src, dst, cmd;
        public final byte[] data;
        Frame(byte[] raw) {
            this.raw = raw;
            this.src = raw[2] & 0xFF;
            this.dst = raw[3] & 0xFF;
            this.cmd = raw[4] & 0xFF;
            this.data = Arrays.copyOfRange(raw, 5, raw.length - 1);
        }
    }

    public static byte[] build(int src, int dst, int cmd, byte[] data) {
        if (data == null) data = new byte[0];
        int len = 3 + data.length;
        byte[] f = new byte[len + 3];
        f[0] = (byte) PREAMBLE;
        f[1] = (byte) len;
        f[2] = (byte) src;
        f[3] = (byte) dst;
        f[4] = (byte) cmd;
        System.arraycopy(data, 0, f, 5, data.length);
        int sum = 0;
        for (int i = 1; i < f.length - 1; i++) sum += f[i] & 0xFF;
        f[f.length - 1] = (byte) ((-sum) & 0xFF);
        return f;
    }

    public static byte[] slew(int dev, boolean positive, int rate) {
        return build(SRC_APP, dev, positive ? CMD_MOVE_POS : CMD_MOVE_NEG, new byte[]{(byte) rate});
    }

    /** True if the frame (starting at the 0x3B) has a valid checksum. */
    public static boolean checksumOk(byte[] f) {
        int sum = 0;
        for (int i = 1; i < f.length; i++) sum += f[i] & 0xFF;
        return (sum & 0xFF) == 0;
    }

    public static Frame parse(byte[] raw) { return new Frame(raw); }

    /** 3 data bytes, big-endian, as an unsigned 24-bit value. */
    public static int u24(byte[] d, int off) {
        return ((d[off] & 0xFF) << 16) | ((d[off + 1] & 0xFF) << 8) | (d[off + 2] & 0xFF);
    }
    public static int signed24(int u) { return u >= 0x800000 ? u - 0x1000000 : u; }
    /** 2^24 counts = 360 degrees. */
    public static double degrees(int signed24) { return signed24 * 360.0 / 16777216.0; }
    public static double norm360(double d) { d %= 360.0; return d < 0 ? d + 360.0 : d; }
}
