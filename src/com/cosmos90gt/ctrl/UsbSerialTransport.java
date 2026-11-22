package com.cosmos90gt.ctrl;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/** Minimal CP210x / FTDI (FT232R, FT-X) driver, 19200 8N1, no flow control, written against the
 *  plain Android USB host API (no libraries). UNVERIFIED: never tested against real hardware. */
final class UsbSerialTransport implements Transport {
    static final int BAUD = 19200;

    private final UsbManager mgr;
    private final UsbDevice dev;
    private UsbDeviceConnection c;
    private UsbInterface itf;
    private UsbEndpoint epIn, epOut;
    private byte[] tmp;
    private volatile boolean closed;

    UsbSerialTransport(Context ctx, UsbDevice d) {
        mgr = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        dev = d;
    }

    static boolean isCp210x(UsbDevice d) {
        int p = d.getProductId();
        return d.getVendorId() == 0x10C4 && (p == 0xEA60 || p == 0xEA70 || p == 0xEA71);
    }
    static boolean isFtdi(UsbDevice d) {
        int p = d.getProductId();
        return d.getVendorId() == 0x0403 && (p == 0x6001 || p == 0x6015);
    }
    static boolean supported(UsbDevice d) { return isCp210x(d) || isFtdi(d); }

    public String label() { return "USB serial " + String.format("%04X:%04X", dev.getVendorId(), dev.getProductId()) + " @" + BAUD + " 8N1"; }

    private void ctl(int type, int req, int val, int idx, byte[] data) throws IOException {
        int r = c.controlTransfer(type, req, val, idx, data, data == null ? 0 : data.length, 1000);
        if (r < 0) throw new IOException("USB control transfer failed (request 0x" + Integer.toHexString(req) + ")");
    }

    public void open() throws IOException {
        c = mgr.openDevice(dev);
        if (c == null) throw new IOException("openDevice failed (USB permission missing?)");
        itf = dev.getInterface(0);
        if (!c.claimInterface(itf, true)) throw new IOException("claimInterface failed");
        for (int i = 0; i < itf.getEndpointCount(); i++) {
            UsbEndpoint e = itf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) epIn = e; else epOut = e;
        }
        if (epIn == null || epOut == null) throw new IOException("bulk endpoints not found");
        tmp = new byte[512];
        if (isFtdi(dev)) {
            ctl(0x40, 0x00, 0x0000, 0, null);   // reset
            ctl(0x40, 0x03, 0x809C, 0, null);   // baud: 3 MHz / 156.25 = 19200
            ctl(0x40, 0x04, 0x0008, 0, null);   // 8 data bits, no parity, 1 stop
            ctl(0x40, 0x02, 0x0000, 0, null);   // no flow control
        } else {
            ctl(0x41, 0x00, 0x0001, 0, null);   // IFC_ENABLE
            ctl(0x41, 0x03, 0x0800, 0, null);   // line control: 8N1
            ctl(0x41, 0x1E, 0, 0, new byte[]{(byte) BAUD, (byte) (BAUD >> 8), (byte) (BAUD >> 16), (byte) (BAUD >> 24)});
        }
    }

    public int read(byte[] b) throws IOException {
        int n = c.bulkTransfer(epIn, tmp, tmp.length, 300);
        if (n < 0) return closed ? -1 : 0;  // timeout
        if (!isFtdi(dev)) { System.arraycopy(tmp, 0, b, 0, n); return n; }
        int mp = epIn.getMaxPacketSize(), o = 0;      // FTDI prefixes every packet with 2 status bytes
        for (int i = 0; i < n; i += mp) {
            int cl = Math.min(mp, n - i);
            if (cl > 2) { System.arraycopy(tmp, i + 2, b, o, cl - 2); o += cl - 2; }
        }
        return o;
    }

    public void write(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int len = Math.min(64, b.length - off);
            int r = c.bulkTransfer(epOut, b, off, len, 1000);
            if (r < 0) throw new IOException("USB bulk write failed");
            off += len;
        }
    }

    public void close() {
        closed = true;
        try { if (c != null) { if (itf != null) c.releaseInterface(itf); c.close(); } } catch (RuntimeException ignored) {}
    }
}
