import com.cosmos90gt.ctrl.*;
import java.util.*;

public class AuxTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        byte[] getPos = Aux.build(0x20, 0x10, 0x01, null);
        check(HexLog.hex(getPos, 0, getPos.length).equals("3B 03 20 10 01 CC"), "GET_POS az frame = 3B 03 20 10 01 CC");
        check(getPos.length == 6, "GET_POS is 6 bytes on the wire");
        check(Aux.checksumOk(getPos), "checksum validates");

        byte[] slew = Aux.slew(0x11, true, 5);
        check(slew.length == 7 && Aux.checksumOk(slew) && (slew[4] & 0xFF) == 0x24, "slew frame is 7 bytes, cmd 0x24, valid");

        byte[] reply = Aux.build(0x10, 0x20, 0x01, new byte[]{0x40, 0x00, 0x00});
        final List<Aux.Frame> frames = new ArrayList<Aux.Frame>();
        final int[] junk = {0};
        AuxParser.Sink sink = new AuxParser.Sink() {
            public void onFrame(Aux.Frame f) { frames.add(f); }
            public void onJunk(int n) { junk[0] += n; }
        };
        AuxParser p = new AuxParser();
        // echo + reply arriving one byte at a time, with garbage before and a corrupted frame in between
        byte[] bad = getPos.clone(); bad[5] ^= 0x55;
        byte[] stream = concat(new byte[]{0x01, 0x02}, getPos, bad, reply);
        for (byte b : stream) p.feed(new byte[]{b}, 0, 1, sink);
        check(frames.size() == 2, "parser yields 2 valid frames (echo + reply), got " + frames.size());
        check(junk[0] == 2 + bad.length, "corrupt frame + garbage reported as junk (" + junk[0] + " bytes)");
        Aux.Frame r = frames.get(1);
        check(r.src == 0x10 && r.dst == 0x20 && r.cmd == 0x01 && r.data.length == 3, "reply fields");
        double deg = Aux.degrees(Aux.signed24(Aux.u24(r.data, 0)));
        check(Math.abs(deg - 90.0) < 1e-9, "0x400000 = 90 deg");
        check(Math.abs(Aux.degrees(Aux.signed24(0xC00000)) + 90.0) < 1e-9, "0xC00000 = -90 deg signed");
        check(Math.abs(Aux.norm360(-90.0) - 270.0) < 1e-9, "-90 normalizes to 270");

        System.out.println(fails == 0 ? "ALL TESTS PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] o = new byte[n]; int i = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, o, i, p.length); i += p.length; }
        return o;
    }
}
