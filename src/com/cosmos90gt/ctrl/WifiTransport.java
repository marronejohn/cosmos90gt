package com.cosmos90gt.ctrl;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** TCP to the SkyQ WiFi module (1.2.3.4:2000). The socket is bound to the WiFi network explicitly,
 *  so Android does not route it over mobile data because SkyQLink has no internet. */
final class WifiTransport implements Transport {
    static final String HOST = "1.2.3.4";
    static final int PORT = 2000;
    static final int CONNECT_TIMEOUT_MS = 5000;

    private final Context ctx;
    private volatile Socket s;
    private InputStream in;
    private OutputStream out;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private volatile boolean closed;

    WifiTransport(Context ctx) { this.ctx = ctx; }

    public String label() { return "WiFi " + HOST + ":" + PORT; }

    public void open() throws IOException {
        Network net = acquireWifi();
        if (closed) throw new IOException("closed");
        Socket sock = (net != null) ? net.getSocketFactory().createSocket() : new Socket();
        s = sock;
        sock.setTcpNoDelay(true);
        sock.setKeepAlive(true);
        sock.setSoTimeout(1000);
        sock.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
        in = sock.getInputStream();
        out = sock.getOutputStream();
    }

    private Network acquireWifi() {
        try {
            cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            final CountDownLatch latch = new CountDownLatch(1);
            final Network[] holder = new Network[1];
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { holder[0] = n; latch.countDown(); }
            };
            cm.requestNetwork(req, cb);
            latch.await(6, TimeUnit.SECONDS);
            return holder[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null; // fall back to the default route
        }
    }

    public int read(byte[] b) throws IOException {
        try { return in.read(b); } catch (SocketTimeoutException e) { return 0; }
    }

    public void write(byte[] b) throws IOException { out.write(b); out.flush(); }

    public void close() {
        closed = true;
        try { if (s != null) s.close(); } catch (IOException ignored) {}
        try { if (cm != null && cb != null) cm.unregisterNetworkCallback(cb); } catch (RuntimeException ignored) {}
        cb = null;
    }
}
