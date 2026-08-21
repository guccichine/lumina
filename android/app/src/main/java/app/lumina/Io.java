package app.lumina;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class Io {
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static byte[] readN(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) break;
            off += n;
        }
        if (off == length) return data;
        byte[] clipped = new byte[off];
        System.arraycopy(data, 0, clipped, 0, off);
        return clipped;
    }
}
