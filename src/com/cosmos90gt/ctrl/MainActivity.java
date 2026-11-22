package com.cosmos90gt.ctrl;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements LinkManager.Listener {
    private static final String ACTION_USB_PERM = "com.cosmos90gt.ctrl.USB_PERMISSION";
    private static final int REQ_EXPORT = 7, REQ_LOC = 8;
    private static final int OK = 0xFF4CAF50, WARN = 0xFFFFB300, BAD = 0xFFEF5350, GREY = 0xFF9AA0A6;

    private final HexLog log = new HexLog();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AuxParser parser = new AuxParser();
    private LinkManager link;

    // link / AUX state (main thread only)
    private boolean connected, cmdMode, usbActive;
    private int rate = 5;
    private int nReq, nValid, nEcho, nUnexpected, nJunk;
    private long lastValid;
    private final ArrayList<long[]> sentFrames = new ArrayList<long[]>();   // {hash, time}
    private final ArrayList<byte[]> sentRaw = new ArrayList<byte[]>();
    private final ArrayList<long[]> pending = new ArrayList<long[]>();      // {dev, cmd, time}
    private String azText = "--", altText = "--", azVer = "-", altVer = "-";
    private int activeDev = -1;
    private boolean activePositive;

    // views
    private ScrollView pageControl, pageDiag, pageLog;
    private TextView statusTv, hintTv, posTv, healthTv, verTv, rateTv, consoleTv, verdictTv, logTv;
    private ScrollView consoleScroll;
    private EditText cmdEt, rawEt;

    // ---------------------------------------------------------------- lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        link = new LinkManager(getApplicationContext(), log, ui, this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);

        LinearLayout tabs = row();
        tabs.addView(weight(btn("Control", new View.OnClickListener() { public void onClick(View v) { show(0); } })));
        tabs.addView(weight(btn("Diagnostics", new View.OnClickListener() { public void onClick(View v) { show(1); } })));
        tabs.addView(weight(btn("Log", new View.OnClickListener() { public void onClick(View v) { show(2); } })));
        root.addView(tabs);

        pageControl = page(buildControl());
        pageDiag = page(buildDiag());
        pageLog = page(buildLog());
        root.addView(pageControl);
        root.addView(pageDiag);
        root.addView(pageLog);
        setContentView(root);
        show(0);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbRx, f);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);

        ui.postDelayed(poll, 1000);
        ui.postDelayed(logTick, 1000);
        refreshHealth();
    }

    @Override protected void onResume() { super.onResume(); updateWifiHint(); }

    @Override protected void onPause() { super.onPause(); if (activeDev >= 0) stopAll(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        try { unregisterReceiver(usbRx); } catch (RuntimeException ignored) {}
        link.stop();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { updateWifiHint(); }

    // ---------------------------------------------------------------- UI builders
    private LinearLayout buildControl() {
        LinearLayout c = col();
        statusTv = tv("Disconnected", 18, false); statusTv.setTypeface(Typeface.DEFAULT_BOLD); statusTv.setTextColor(GREY);
        hintTv = tv("", 13, false); hintTv.setTextColor(GREY);
        c.addView(statusTv); c.addView(hintTv);

        LinearLayout r1 = row();
        r1.addView(weight(btn("Connect WiFi", new View.OnClickListener() { public void onClick(View v) {
            usbActive = false; link.startWifi(); } })));
        r1.addView(weight(btn("Connect USB", new View.OnClickListener() { public void onClick(View v) { connectUsb(); } })));
        r1.addView(weight(btn("Disconnect", new View.OnClickListener() { public void onClick(View v) {
            usbActive = false; link.stop(); } })));
        c.addView(r1);
        c.addView(btn("Open WiFi settings", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } }));

        posTv = tv("", 20, true); c.addView(posTv);
        healthTv = tv("", 13, true); c.addView(healthTv);
        verTv = tv("", 12, true); verTv.setTextColor(GREY); c.addView(verTv);

        rateTv = tv("", 15, false);
        c.addView(rateTv);
        SeekBar sb = new SeekBar(this);
        sb.setMax(8); sb.setProgress(rate - 1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                rate = p + 1; updateRateText();
                if (user && activeDev >= 0) sendSlew(activeDev, activePositive, rate); // live rate change
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        c.addView(sb);
        updateRateText();

        LinearLayout up = row(); up.setGravity(Gravity.CENTER);
        up.addView(spacer()); up.addView(holdBtn("Alt +", Aux.DEV_ALT, true)); up.addView(spacer());
        LinearLayout mid = row(); mid.setGravity(Gravity.CENTER);
        mid.addView(holdBtn("Az -", Aux.DEV_AZ, false));
        Button stop = btn("STOP", new View.OnClickListener() { public void onClick(View v) { stopAll(); } });
        stop.setBackgroundColor(0xFFB71C1C); stop.setTextColor(Color.WHITE);
        mid.addView(weight(stop));
        mid.addView(holdBtn("Az +", Aux.DEV_AZ, true));
        LinearLayout dn = row(); dn.setGravity(Gravity.CENTER);
        dn.addView(spacer()); dn.addView(holdBtn("Alt -", Aux.DEV_ALT, false)); dn.addView(spacer());
        c.addView(up); c.addView(mid); c.addView(dn);

        TextView note = tv("Hold a direction button to slew; release stops. Direction/rate scale are UNVERIFIED on this mount.", 11, false);
        note.setTextColor(GREY); c.addView(note);
        return c;
    }

    private LinearLayout buildDiag() {
        LinearLayout c = col();
        c.addView(tv("WiFi-module command mode (WiFly RN-171). Pauses AUX polling while active.", 13, false));
        LinearLayout r1 = row();
        r1.addView(weight(btn("Enter ($$$)", new View.OnClickListener() { public void onClick(View v) { enterCmd(); } })));
        r1.addView(weight(btn("Exit", new View.OnClickListener() { public void onClick(View v) { exitCmd(); } })));
        r1.addView(weight(btn("Run all", new View.OnClickListener() { public void onClick(View v) { runAll(); } })));
        c.addView(r1);
        LinearLayout r2 = row();
        String[] names = {"ver", "get uart", "get comm", "show sys", "show connection"};
        for (final String n : names)
            r2.addView(weight(btn(n.replace("show connection", "show conn"), new View.OnClickListener() { public void onClick(View v) { cmd(n); } })));
        c.addView(r2);
        cmdEt = new EditText(this); cmdEt.setHint("custom module command"); cmdEt.setTextColor(Color.WHITE);
        cmdEt.setHintTextColor(GREY); cmdEt.setSingleLine(true);
        c.addView(cmdEt);
        c.addView(btn("Send command", new View.OnClickListener() { public void onClick(View v) {
            cmd(cmdEt.getText().toString().trim()); } }));

        verdictTv = tv("", 14, false); verdictTv.setTypeface(Typeface.DEFAULT_BOLD); c.addView(verdictTv);
        consoleTv = tv("", 12, true);
        consoleScroll = new ScrollView(this); consoleScroll.addView(consoleTv);
        c.addView(consoleScroll, new LinearLayout.LayoutParams(-1, dp(220)));
        c.addView(btn("Clear console", new View.OnClickListener() { public void onClick(View v) { consoleTv.setText(""); verdictTv.setText(""); } }));

        c.addView(tv("Raw AUX frame: hex \"dst cmd [data...]\", source 0x20 is added (e.g. \"10 FE\")", 13, false));
        rawEt = new EditText(this); rawEt.setHint("10 FE"); rawEt.setTextColor(Color.WHITE); rawEt.setHintTextColor(GREY);
        rawEt.setSingleLine(true); rawEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        c.addView(rawEt);
        LinearLayout r3 = row();
        r3.addView(weight(btn("Send raw", new View.OnClickListener() { public void onClick(View v) { sendRaw(); } })));
        r3.addView(weight(btn("Poll now", new View.OnClickListener() { public void onClick(View v) { pollOnce(); } })));
        r3.addView(weight(btn("Get versions", new View.OnClickListener() { public void onClick(View v) { getVersions(); } })));
        c.addView(r3);
        return c;
    }

    private LinearLayout buildLog() {
        LinearLayout c = col();
        LinearLayout r = row();
        r.addView(weight(btn("Clear", new View.OnClickListener() { public void onClick(View v) { log.clear(); logTv.setText(""); } })));
        r.addView(weight(btn("Export", new View.OnClickListener() { public void onClick(View v) { exportLog(); } })));
        r.addView(weight(btn("Share", new View.OnClickListener() { public void onClick(View v) { shareLog(); } })));
        c.addView(r);
        logTv = tv("", 10, true);
        c.addView(logTv);
        return c;
    }

    private void show(int i) {
        pageControl.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        pageDiag.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        if (i == 2) refreshLog();
    }

    private ScrollView page(LinearLayout content) {
        ScrollView s = new ScrollView(this);
        s.addView(content);
        s.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        return s;
    }

    private void updateRateText() {
        rateTv.setText("Slew rate: " + rate + "   (1 = slowest, 9 = fastest; scale UNVERIFIED)");
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(8), dp(12), dp(16));
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private View weight(View v) { v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f)); return v; }
    private View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f)); return v; }
    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }
    private TextView tv(String t, int sp, boolean mono) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(sp); v.setTextColor(Color.WHITE);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(0, dp(4), 0, dp(4));
        return v;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private View holdBtn(String label, final int dev, final boolean positive) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(18);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(72), 1f));
        b.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        v.setPressed(true);
                        activeDev = dev; activePositive = positive;
                        sendSlew(dev, positive, rate);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        stopAxis(dev);
                        activeDev = -1;
                        return true;
                }
                return true;
            }
        });
        return b;
    }

    // ---------------------------------------------------------------- link callbacks
    public void onStatus(int state, String text) {
        connected = (state == LinkManager.CONNECTED);
        statusTv.setText(text);
        statusTv.setTextColor(state == LinkManager.CONNECTED ? OK
                : (state == LinkManager.CONNECTING || state == LinkManager.IDLE) ? GREY
                : state == LinkManager.RETRY ? WARN : BAD);
        if (!connected) { cmdMode = false; activeDev = -1; }
        updateWifiHint();
    }

    public void onConnected() {
        parser.reset(); pending.clear(); sentFrames.clear(); sentRaw.clear();
        cmdMode = false;
        nReq = nValid = nEcho = nUnexpected = nJunk = 0; lastValid = 0;
        azText = altText = "--";
        ui.postDelayed(new Runnable() { public void run() { getVersions(); } }, 300);
        refreshHealth();
    }

    public void onRx(byte[] d) {
        if (cmdMode) {
            appendConsole(new String(d, java.nio.charset.Charset.forName("ISO-8859-1")));
            return;
        }
        parser.feed(d, 0, d.length, new AuxParser.Sink() {
            public void onFrame(Aux.Frame f) { handleFrame(f); }
            public void onJunk(int n) { nJunk += n; log.note("RX: " + n + " byte(s) not part of a checksum-valid AUX frame (junk)"); }
        });
        refreshHealth();
    }

    // ---------------------------------------------------------------- AUX layer
    private void sendAux(int dev, int cmd, byte[] data, boolean expectReply) {
        if (!connected || cmdMode) return;
        byte[] f = Aux.build(Aux.SRC_APP, dev, cmd, data);
        long now = System.currentTimeMillis();
        sentRaw.add(f); sentFrames.add(new long[]{0, now});
        while (!sentRaw.isEmpty() && now - sentFrames.get(0)[1] > 4000) { sentRaw.remove(0); sentFrames.remove(0); }
        if (expectReply) { nReq++; pending.add(new long[]{dev, cmd, now}); }
        while (!pending.isEmpty() && now - pending.get(0)[2] > 5000) pending.remove(0);
        link.send(f);
    }

    private void sendSlew(int dev, boolean positive, int r) {
        if (!connected || cmdMode) { toast("Not connected"); return; }
        sendAux(dev, positive ? Aux.CMD_MOVE_POS : Aux.CMD_MOVE_NEG, new byte[]{(byte) r}, false);
    }

    /** Rate 0 on both directions, so it stops whichever way the mount reads 0x24/0x25. */
    private void stopAxis(int dev) {
        sendAux(dev, Aux.CMD_MOVE_POS, new byte[]{0}, false);
        sendAux(dev, Aux.CMD_MOVE_NEG, new byte[]{0}, false);
    }

    private void stopAll() {
        activeDev = -1;
        stopAxis(Aux.DEV_AZ); stopAxis(Aux.DEV_ALT);
        ui.postDelayed(new Runnable() { public void run() { stopAxis(Aux.DEV_AZ); stopAxis(Aux.DEV_ALT); } }, 200);
    }

    private void pollOnce() {
        if (!connected || cmdMode) { toast("Not connected / in command mode"); return; }
        sendAux(Aux.DEV_AZ, Aux.CMD_GET_POS, null, true);
        ui.postDelayed(new Runnable() { public void run() { sendAux(Aux.DEV_ALT, Aux.CMD_GET_POS, null, true); } }, 150);
    }

    private void getVersions() {
        sendAux(Aux.DEV_AZ, Aux.CMD_GET_VERSION, null, true);
        ui.postDelayed(new Runnable() { public void run() { sendAux(Aux.DEV_ALT, Aux.CMD_GET_VERSION, null, true); } }, 150);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (connected && !cmdMode) pollOnce();
            refreshHealth();
            ui.postDelayed(this, 1000);
        }
    };

    private boolean isEcho(byte[] raw) {
        for (int i = 0; i < sentRaw.size(); i++)
            if (Arrays.equals(sentRaw.get(i), raw)) { sentRaw.remove(i); sentFrames.remove(i); return true; }
        return false;
    }

    private void handleFrame(Aux.Frame f) {
        if (isEcho(f.raw)) {
            nEcho++;
            log.note("^ ECHO of our own TX (module mirror) - NOT a mount reply");
            return;
        }
        boolean fromMount = f.dst == Aux.SRC_APP && (f.src == Aux.DEV_AZ || f.src == Aux.DEV_ALT);
        if (!fromMount) {
            nUnexpected++;
            log.note(String.format(Locale.US, "^ checksum-valid but unexpected addresses src=%02X dst=%02X - ignored", f.src, f.dst));
            return;
        }
        nValid++; lastValid = System.currentTimeMillis();
        for (int i = 0; i < pending.size(); i++)
            if (pending.get(i)[0] == f.src && pending.get(i)[1] == f.cmd) { pending.remove(i); break; }
        log.note(String.format(Locale.US, "^ VALID mount reply from 0x%02X cmd 0x%02X", f.src, f.cmd));
        boolean az = f.src == Aux.DEV_AZ;
        if (f.cmd == Aux.CMD_GET_POS && f.data.length >= 3) {
            int u = Aux.u24(f.data, 0);
            double deg = Aux.degrees(Aux.signed24(u));
            String t = az ? String.format(Locale.US, "%9.4f\u00B0  raw %06X", Aux.norm360(deg), u)
                          : String.format(Locale.US, "%9.4f\u00B0  raw %06X", deg > 180 ? deg - 360 : deg, u);
            if (az) azText = t; else altText = t;
            posTv.setText("Az  " + azText + "\nAlt " + altText);
        } else if (f.cmd == Aux.CMD_GET_VERSION) {
            String v = f.data.length == 2 ? (f.data[0] & 0xFF) + "." + (f.data[1] & 0xFF) : HexLog.hex(f.data, 0, f.data.length);
            if (az) azVer = v; else altVer = v;
            verTv.setText("Firmware  Az: " + azVer + "   Alt: " + altVer);
        }
    }

    private void refreshHealth() {
        if (posTv != null && !connected) posTv.setText("Az  --\nAlt --");
        long now = System.currentTimeMillis();
        String s = "AUX requests " + nReq + " | valid mount replies " + nValid + "\nmodule echoes " + nEcho
                + " | unexpected " + nUnexpected + " | junk bytes " + nJunk + "\n";
        int col;
        if (!connected) { s += "No link."; col = GREY; }
        else if (nValid > 0 && now - lastValid < 4000) { s += "Mount is answering."; col = OK; }
        else if (nReq >= 4) { s += "NO valid mount reply. Echoes are the module mirroring our writes, not the mount. See Diagnostics > Run all (URX counter)."; col = BAD; }
        else { s += "Waiting for first reply..."; col = WARN; }
        healthTv.setText(s); healthTv.setTextColor(col);
    }

    private void sendRaw() {
        if (!connected || cmdMode) { toast("Connect first (and leave command mode)"); return; }
        try {
            String[] p = rawEt.getText().toString().trim().split("[\\s,]+");
            if (p.length < 2) { toast("Need at least: dst cmd"); return; }
            byte[] data = new byte[p.length - 2];
            for (int i = 2; i < p.length; i++) data[i - 2] = (byte) Integer.parseInt(p[i].replace("0x", ""), 16);
            sendAux(Integer.parseInt(p[0].replace("0x", ""), 16) & 0xFF, Integer.parseInt(p[1].replace("0x", ""), 16) & 0xFF, data, true);
        } catch (NumberFormatException e) { toast("Bad hex"); }
    }

    // ---------------------------------------------------------------- module command mode
    private void enterCmd() {
        if (!connected) { toast("Connect via WiFi first"); return; }
        cmdMode = true;
        appendConsole("\n> entering command mode ($$$)\n");
        log.note("Entering module command mode");
        ui.postDelayed(new Runnable() { public void run() { if (connected && cmdMode) link.send("$$$".getBytes()); } }, 400);
    }

    private void cmd(String c) {
        if (c.length() == 0) return;
        if (!cmdMode) { toast("Press Enter ($$$) first"); return; }
        appendConsole("\n> " + c + "\n");
        link.send((c + "\r\n").getBytes());
    }

    private void exitCmd() {
        if (!cmdMode) return;
        appendConsole("\n> exit\n");
        link.send("exit\r\n".getBytes());
        ui.postDelayed(new Runnable() { public void run() { cmdMode = false; parser.reset(); log.note("Left command mode"); } }, 900);
    }

    private void runAll() {
        if (!connected) { toast("Connect via WiFi first"); return; }
        if (!cmdMode) enterCmd();
        String[] seq = {"ver", "get uart", "get comm", "show sys", "show connection"};
        for (int i = 0; i < seq.length; i++) {
            final String c = seq[i];
            ui.postDelayed(new Runnable() { public void run() { cmd(c); } }, 1300 + i * 900L);
        }
    }

    private void appendConsole(String s) {
        consoleTv.append(s);
        consoleScroll.post(new Runnable() { public void run() { consoleScroll.fullScroll(View.FOCUS_DOWN); } });
        analyseCounters();
    }

    private static int lastInt(String text, String key) {
        Matcher m = Pattern.compile(key + "=(\\d+)").matcher(text);
        int v = -1;
        while (m.find()) v = Integer.parseInt(m.group(1));
        return v;
    }

    private void analyseCounters() {
        String t = consoleTv.getText().toString();
        int utx = lastInt(t, "UTX"), urx = lastInt(t, "URX"), err = lastInt(t, "RXerr"), drop = lastInt(t, "RXdrop");
        if (utx < 0 || urx < 0) return;
        if (utx > 0 && urx == 0) {
            verdictTv.setTextColor(BAD);
            verdictTv.setText("UTX=" + utx + ", URX=0 (RXerr=" + err + ", RXdrop=" + drop + "): the module has sent bytes toward the mount "
                    + "and received nothing back. Same pattern as your earlier tests: the module<->mount serial link or the mount board is not answering. "
                    + "No WiFi-side change fixes that; try the USB-serial route.");
        } else if (urx > 0) {
            verdictTv.setTextColor(OK);
            verdictTv.setText("URX=" + urx + ": bytes have arrived from the mount side. The serial link is not completely dead.");
        } else {
            verdictTv.setTextColor(WARN);
            verdictTv.setText("UTX=" + utx + ", URX=" + urx + ": nothing sent yet - send some AUX frames first, then re-run show sys.");
        }
    }

    // ---------------------------------------------------------------- USB
    private void connectUsb() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice found = null;
        StringBuilder all = new StringBuilder();
        for (UsbDevice d : um.getDeviceList().values()) {
            all.append(String.format("%04X:%04X ", d.getVendorId(), d.getProductId()));
            if (found == null && UsbSerialTransport.supported(d)) found = d;
        }
        if (found == null) {
            onStatus(LinkManager.ERROR, "No supported USB serial adapter (CP210x / FTDI FT232R). Attached: " + (all.length() == 0 ? "none" : all));
            return;
        }
        if (!um.hasPermission(found)) {
            Intent i = new Intent(ACTION_USB_PERM).setPackage(getPackageName());
            um.requestPermission(found, PendingIntent.getBroadcast(this, 0, i, 0));
            return;
        }
        usbActive = true;
        link.startUsb(found);
    }

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERM.equals(a)) {
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) { usbActive = true; link.startUsb(d); }
                else onStatus(LinkManager.ERROR, "USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a) && usbActive) {
                usbActive = false; link.stop();
                onStatus(LinkManager.ERROR, "USB adapter unplugged");
            }
        }
    };

    // ---------------------------------------------------------------- WiFi hint
    private void updateWifiHint() {
        if (hintTv == null) return;
        String ssid = "";
        try {
            WifiInfo wi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            if (wi != null && wi.getSSID() != null) ssid = wi.getSSID().replace("\"", "");
        } catch (RuntimeException ignored) {}
        String h;
        if (ssid.startsWith("SkyQLink")) { h = "WiFi: " + ssid + " \u2713"; hintTv.setTextColor(OK); }
        else if (ssid.length() == 0 || ssid.contains("unknown")) { h = "Join the open WiFi network SkyQLink-xx (no password) in Android settings. If Android asks to stay connected without internet, say yes. Allow Location to see the WiFi name here."; hintTv.setTextColor(GREY); }
        else { h = "WiFi is \"" + ssid + "\". Join SkyQLink-xx (open, no password); the scope serves only one client at a time."; hintTv.setTextColor(WARN); }
        hintTv.setText(h);
    }

    // ---------------------------------------------------------------- log
    private final Runnable logTick = new Runnable() {
        public void run() { if (pageLog.getVisibility() == View.VISIBLE) refreshLog(); ui.postDelayed(this, 1000); }
    };

    private void refreshLog() {
        logTv.setText(log.tail(250));
        pageLog.post(new Runnable() { public void run() { pageLog.fullScroll(View.FOCUS_DOWN); } });
    }

    private String logHeader() {
        return "Cosmos 90GT log, exported " + new Date() + "\nTX/RX = bytes on the link; '--' lines are annotations.\n\n";
    }

    private void exportLog() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "cosmos90gt-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override protected void onActivityResult(int rc, int res, final Intent data) {
        if (rc != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        final String text = logHeader() + log.all();
        new Thread(new Runnable() {
            public void run() {
                try {
                    OutputStream os = getContentResolver().openOutputStream(u);
                    os.write(text.getBytes("UTF-8")); os.close();
                    ui.post(new Runnable() { public void run() { toast("Log saved"); } });
                } catch (Exception e) {
                    ui.post(new Runnable() { public void run() { toast("Save failed"); } });
                }
            }
        }).start();
    }

    private void shareLog() {
        String t = log.all();
        if (t.length() > 200000) t = t.substring(t.length() - 200000);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, logHeader() + t);
        startActivity(Intent.createChooser(i, "Share log"));
    }
}
