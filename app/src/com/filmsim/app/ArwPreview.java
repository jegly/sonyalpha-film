package com.filmsim.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reads the JPEG preview that Sony stores inside every ARW raw file (1616 x 1080 on the A7 II).
 * An ARW is a TIFF: each image directory can point at a JPEG with tags 0x0201 (offset) and 0x0202 (length).
 * The biggest one found is the preview; the small one is the thumbnail. The file is only ever read.
 */
public class ArwPreview {
    private static final int MAX_PREVIEW = 8 * 1024 * 1024;

    /** Returns the preview JPEG, or null when the file has none. */
    public static byte[] read(File arw) throws IOException {
        RandomAccessFile f = new RandomAccessFile(arw, "r");
        try {
            long size = f.length();
            byte[] head = new byte[8];
            f.readFully(head);
            boolean little = head[0] == 'I' && head[1] == 'I';
            if (!little && !(head[0] == 'M' && head[1] == 'M')) return null;
            if (u16(head, 2, little) != 42) return null;
            long ifd = u32(head, 4, little);
            long bestOff = 0, bestLen = 0;
            // the main directory and the ones chained after it; a few is plenty
            for (int n = 0; n < 4 && ifd >= 8 && ifd + 2 <= size; n++) {
                f.seek(ifd);
                byte[] cnt = new byte[2];
                f.readFully(cnt);
                int entries = u16(cnt, 0, little);
                if (entries > 512 || ifd + 2 + entries * 12L + 4 > size) break;
                byte[] dir = new byte[entries * 12 + 4];
                f.readFully(dir);
                long off = 0, len = 0;
                for (int i = 0; i < entries; i++) {
                    int tag = u16(dir, i * 12, little);
                    if (tag == 0x0201) off = u32(dir, i * 12 + 8, little);
                    else if (tag == 0x0202) len = u32(dir, i * 12 + 8, little);
                }
                if (off > 0 && len > bestLen && len <= MAX_PREVIEW && off + len <= size) {
                    bestOff = off;
                    bestLen = len;
                }
                ifd = u32(dir, entries * 12, little);
            }
            if (bestLen < 4) return null;
            byte[] jpeg = new byte[(int) bestLen];
            f.seek(bestOff);
            f.readFully(jpeg);
            if ((jpeg[0] & 255) != 0xFF || (jpeg[1] & 255) != 0xD8) return null;
            return jpeg;
        } finally {
            f.close();
        }
    }

    private static int u16(byte[] b, int o, boolean little) {
        return little ? (b[o] & 255) | ((b[o + 1] & 255) << 8) : ((b[o] & 255) << 8) | (b[o + 1] & 255);
    }

    private static long u32(byte[] b, int o, boolean little) {
        long v = little
                ? (b[o] & 255L) | ((b[o + 1] & 255L) << 8) | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24)
                : ((b[o] & 255L) << 24) | ((b[o + 1] & 255L) << 16) | ((b[o + 2] & 255L) << 8) | (b[o + 3] & 255L);
        return v;
    }
}
