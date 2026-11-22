package com.cosmos90gt.ctrl;

import java.io.IOException;

/** A byte pipe to the mount: WiFi module (TCP 2000) or a USB-serial adapter. Shares one AUX layer. */
interface Transport {
    void open() throws IOException;
    /** Returns >0 bytes read, 0 on timeout (no data), -1 on end of stream. */
    int read(byte[] b) throws IOException;
    void write(byte[] b) throws IOException;
    void close();
    String label();
}
