package com.example.retro;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Tiny read-only SMB2 client: guest login, open one file, read it, close.
 * Just enough to fetch ROMs from a NAS share without pulling in a large SMB library.
 * Plain Java (no Android APIs) so it can be tested on a computer too.
 */
public class Smb {
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final int NEGOTIATE = 0, SESSION_SETUP = 1, TREE_CONNECT = 3, CREATE = 5, CLOSE = 6, READ = 8;
    private static final long MORE_PROCESSING = 0xC0000016L;
    private static final int CHUNK = 65536;    // one credit per read keeps us compatible with SMB 2.0.2

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private long messageId, sessionId;
    private int treeId;

    /** Connects to host and logs in as guest on share (e.g. "Public"). */
    public Smb(String host, String share) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, 445), 5000);
        socket.setSoTimeout(15000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        // Dialects 2.0.2, 2.1, 3.0 (3.1.1 needs extra negotiate contexts we don't need)
        byte[] neg = new byte[36 + 6];
        le16(neg, 0, 36); le16(neg, 2, 3); le16(neg, 4, 1);
        le16(neg, 36, 0x0202); le16(neg, 38, 0x0210); le16(neg, 40, 0x0300);
        check(call(NEGOTIATE, neg), 0);

        // Anonymous NTLM login, which a guest-enabled share accepts. First message: NEGOTIATE.
        byte[] ntlm1 = new byte[32];
        System.arraycopy("NTLMSSP\0".getBytes("ASCII"), 0, ntlm1, 0, 8);
        le32(ntlm1, 8, 1);
        le32(ntlm1, 12, 0x20088A05);  // unicode, request target, NTLM, anonymous, always sign, ext. security, 128-bit
        byte[] r = call(SESSION_SETUP, sessionSetup(ntlm1));
        check(r, MORE_PROCESSING);
        sessionId = le64(r, 40);

        // Second message: anonymous AUTHENTICATE (one zero byte as LM response, everything else empty)
        byte[] ntlm3 = new byte[64 + 1];
        System.arraycopy("NTLMSSP\0".getBytes("ASCII"), 0, ntlm3, 0, 8);
        le32(ntlm3, 8, 3);
        le16(ntlm3, 12, 1); le16(ntlm3, 14, 1); le32(ntlm3, 16, 64);   // LM response: Z(1)
        for (int f = 20; f <= 52; f += 8) le32(ntlm3, f + 4, 65);      // other fields empty, offset at end
        le32(ntlm3, 60, 0x20088A05);
        check(call(SESSION_SETUP, sessionSetup(ntlm3)), 0);

        byte[] path = ("\\\\" + host + "\\" + share).getBytes(UTF16);
        byte[] tc = new byte[8 + path.length];
        le16(tc, 0, 9); le16(tc, 4, 64 + 8); le16(tc, 6, path.length);
        System.arraycopy(path, 0, tc, 8, path.length);
        r = call(TREE_CONNECT, tc);
        check(r, 0);
        treeId = (int) le32(r, 36);
    }

    /** Reads a whole file, path relative to the share with / or \ separators. */
    public byte[] read(String path) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        read(path, data);
        return data.toByteArray();
    }

    /** Streams a whole file to out (for big files, so they never sit in memory). */
    public void read(String path, OutputStream data) throws IOException {
        byte[] name = path.replace('/', '\\').replaceAll("^\\\\+", "").getBytes(UTF16);
        byte[] cr = new byte[56 + Math.max(name.length, 1)];
        le16(cr, 0, 57);
        le32(cr, 4, 2);                 // impersonation level: impersonation
        le32(cr, 24, 0x00120089);       // FILE_GENERIC_READ
        le32(cr, 32, 1);                // share read
        le32(cr, 36, 1);                // FILE_OPEN
        le32(cr, 40, 0x40);             // non-directory file
        le16(cr, 44, 64 + 56); le16(cr, 46, name.length);
        System.arraycopy(name, 0, cr, 56, name.length);
        byte[] r = call(CREATE, cr);
        check(r, 0);
        long size = le64(r, 64 + 48);
        byte[] fileId = Arrays.copyOfRange(r, 64 + 64, 64 + 80);

        long done = 0;
        try {
            while (done < size) {
                byte[] rd = new byte[49];
                le16(rd, 0, 49); rd[2] = 0x50;
                le32(rd, 4, (int) Math.min(CHUNK, size - done));
                le64(rd, 8, done);
                System.arraycopy(fileId, 0, rd, 16, 16);
                r = call(READ, rd);
                check(r, 0);
                int off = r[64 + 2] & 0xFF, len = (int) le32(r, 64 + 4);
                if (len == 0) break;
                data.write(r, off, len);
                done += len;
            }
        } finally {
            byte[] cl = new byte[24];
            le16(cl, 0, 24);
            System.arraycopy(fileId, 0, cl, 8, 16);
            try { call(CLOSE, cl); } catch (IOException ignored) {}
        }
    }

    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static byte[] sessionSetup(byte[] token) {
        byte[] b = new byte[24 + token.length];
        le16(b, 0, 25); b[3] = 1;       // signing enabled (not required)
        le16(b, 12, 64 + 24); le16(b, 14, token.length);
        System.arraycopy(token, 0, b, 24, token.length);
        return b;
    }

    /** Sends one SMB2 command and returns the whole response (header + body). */
    private byte[] call(int command, byte[] body) throws IOException {
        byte[] h = new byte[64];
        h[0] = (byte) 0xFE; h[1] = 'S'; h[2] = 'M'; h[3] = 'B';
        le16(h, 4, 64);
        le16(h, 6, 1);                  // credit charge
        le16(h, 12, command);
        le16(h, 14, 32);                // credits requested
        le64(h, 24, messageId++);
        le32(h, 36, treeId);
        le64(h, 40, sessionId);
        int len = 64 + body.length;
        byte[] nb = {0, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
        out.write(nb); out.write(h); out.write(body); out.flush();
        while (true) {
            int n = in.readInt() & 0xFFFFFF;
            byte[] r = new byte[n];
            in.readFully(r);
            if (le32(r, 8) == 0x103L) continue;    // STATUS_PENDING: the real answer follows
            return r;
        }
    }

    private static void check(byte[] r, long expected) throws IOException {
        long status = le32(r, 8);
        if (status != expected) throw new IOException(String.format("SMB command %d failed: 0x%08X", le16(r, 12), status));
    }

    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    private static void le64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >> (8 * i)); }
    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    private static long le32(byte[] b, int o) { long v = 0; for (int i = 3; i >= 0; i--) v = v << 8 | (b[o + i] & 0xFF); return v; }
    private static long le64(byte[] b, int o) { long v = 0; for (int i = 7; i >= 0; i--) v = v << 8 | (b[o + i] & 0xFF); return v; }
}
