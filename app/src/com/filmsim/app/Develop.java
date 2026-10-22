package com.filmsim.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * After-shot "develop" step that adds what the camera can't do live: CineStill-style halation, film grain
 * and light leaks. Runs in horizontal strips so a full-size photo never has to fit in the camera's memory,
 * and writes the result with a streaming baseline JPEG encoder.
 *
 * Plain Java (no Android classes) so the same code can be checked on a computer.
 */
public class Develop {
    /** Supplies the photo: small previews for the glow, then full rows top to bottom. */
    public interface Source {
        int width();

        int height();

        /** The whole photo shrunk by about `factor` (any size close to width/factor x height/factor is fine). */
        Image preview(int factor) throws IOException;

        /** Fills rows [y, y + count) as packed RGB, row-major, into rgb starting at offset 0. */
        void read(int y, int count, int[] rgb) throws IOException;
    }

    public static class Image {
        public final int w, h;
        public final int[] px;

        public Image(int w, int h, int[] px) {
            this.w = w;
            this.h = h;
            this.px = px;
        }
    }

    /** Lets the caller pause processing (e.g. while the camera writes a photo) or give up. */
    public interface Gate {
        /** Returns false to abandon processing. May block. */
        boolean proceed();
    }

    /** What to add. Grain levels: 0 off, 1 fine, 2 medium, 3 heavy. */
    public static class Options {
        public boolean halation;
        public int grain;
        public boolean lightLeak;
        /** Black and white film: grain without colour. */
        public boolean monochrome;
        /** Varies grain and leaks between photos; use something unique per photo. */
        public long seed;

        public boolean any() {
            return halation || grain > 0 || lightLeak;
        }
    }

    private static final int STRIP = 64; // output rows per pass, multiple of 16 for the encoder

    // Halation: a warm glow hugging the light, and a wide deep red halo around it.
    private static final int THRESHOLD = 200;
    private static final float CORE_STRENGTH = 1.6f, HALO_STRENGTH = 2.6f;
    private static final float[] CORE_TINT = {1.0f, 0.5f, 0.22f};
    private static final float[] HALO_TINT = {1.0f, 0.18f, 0.06f};

    // Grain: peak strength in levels (0-255) and clump size in pixels at 3000 px wide, per level.
    private static final float[] GRAIN_STRENGTH = {0, 7f, 11f, 17f};
    private static final float[] GRAIN_CLUMP = {1, 1.1f, 1.6f, 2.3f};

    // Light leak colours: red, orange, amber, and the occasional pink.
    private static final int[] LEAK_COLOURS = {0xFF3B1F, 0xFF7A1A, 0xFFB347, 0xFF2E6B};

    private final Options opt;

    public Develop(Options options) {
        this.opt = options;
    }

    public void process(Source src, JpegWriter out, Gate gate) throws IOException {
        int w = src.width(), h = src.height();
        // halation glow maps from small previews: the core at about 1/4 size, the halo at about 1/16
        Image small = null, tiny = null;
        float[] core = null, halo = null;
        if (opt.halation) {
            small = src.preview(4);
            core = highlights(small);
            blur(core, small.w, small.h, Math.max(1, Math.round(small.w * 0.004f)));
            tiny = src.preview(16);
            halo = highlights(tiny);
            blur(halo, tiny.w, tiny.h, Math.max(1, Math.round(tiny.w * 0.025f)));
        }
        if (gate != null && !gate.proceed()) throw new IOException("cancelled");

        java.util.Random rnd = new java.util.Random(opt.seed);
        int noiseSeed = (int) (opt.seed ^ (opt.seed >>> 32));

        // grain: two clump sizes blended, scaled to the photo's width
        float cell = GRAIN_CLUMP[opt.grain] * w / 3000f;
        float strength = GRAIN_STRENGTH[opt.grain];

        // light leak: 1-3 soft blobs just outside one edge, stretched along it
        int blobs = 1 + rnd.nextInt(3);
        boolean vertical = rnd.nextInt(4) == 0; // mostly the side edges, sometimes top or bottom
        boolean far = rnd.nextBoolean();
        float[] bx = new float[blobs], by = new float[blobs], sx = new float[blobs], sy = new float[blobs], amp = new float[blobs];
        float[][] col = new float[blobs][];
        float edge = vertical ? h : w, along = vertical ? w : h;
        for (int i = 0; i < blobs; i++) {
            float across = edge * (-0.06f + rnd.nextFloat() * 0.1f);   // centre just outside or at the edge
            float pos = along * (0.1f + rnd.nextFloat() * 0.8f);
            float sAcross = edge * (0.10f + rnd.nextFloat() * 0.18f);
            float sAlong = along * (0.18f + rnd.nextFloat() * 0.35f);
            if (far) across = edge - 1 - across;
            bx[i] = vertical ? pos : across;
            by[i] = vertical ? across : pos;
            sx[i] = vertical ? sAlong : sAcross;
            sy[i] = vertical ? sAcross : sAlong;
            amp[i] = 0.45f + rnd.nextFloat() * 0.4f;
            col[i] = rgb(LEAK_COLOURS[rnd.nextInt(10) == 0 ? 3 : rnd.nextInt(3)]);
        }
        float leakNoise = w * 0.14f;

        int[] band = new int[w * STRIP];
        float sxc = small == null ? 0 : small.w / (float) w, syc = small == null ? 0 : small.h / (float) h;
        float sxh = tiny == null ? 0 : tiny.w / (float) w, syh = tiny == null ? 0 : tiny.h / (float) h;
        for (int y0 = 0; y0 < h; y0 += STRIP) {
            if (gate != null && !gate.proceed()) throw new IOException("cancelled");
            int n = Math.min(STRIP, h - y0);
            src.read(y0, n, band);
            for (int r = 0; r < n; r++) {
                int y = y0 + r;
                float cy = (y + 0.5f) * syc - 0.5f, hy = (y + 0.5f) * syh - 0.5f;
                int base = r * w;
                for (int x = 0; x < w; x++) {
                    int px = band[base + x];
                    float R = (px >> 16) & 255, G = (px >> 8) & 255, B = px & 255;
                    if (opt.halation) {
                        float c = sample(core, small.w, small.h, (x + 0.5f) * sxc - 0.5f, cy) * CORE_STRENGTH;
                        float g = sample(halo, tiny.w, tiny.h, (x + 0.5f) * sxh - 0.5f, hy) * HALO_STRENGTH;
                        if (c > 0.002f || g > 0.002f) {
                            R = screen(R, c * CORE_TINT[0] + g * HALO_TINT[0]);
                            G = screen(G, c * CORE_TINT[1] + g * HALO_TINT[1]);
                            B = screen(B, c * CORE_TINT[2] + g * HALO_TINT[2]);
                        }
                    }
                    if (opt.lightLeak) {
                        float lr = 0, lg = 0, lb = 0;
                        for (int i = 0; i < blobs; i++) {
                            float dx = (x - bx[i]) / sx[i], dy = (y - by[i]) / sy[i];
                            float d2 = dx * dx + dy * dy;
                            if (d2 > 9) continue;
                            // uneven edges: two slow random fields nudge the falloff
                            float rough = 0.82f + 0.2f * valueNoise(x / leakNoise, y / leakNoise, noiseSeed + i * 7919)
                                    + 0.1f * valueNoise(x / (leakNoise * 0.4f), y / (leakNoise * 0.4f), noiseSeed + i * 7919 + 53);
                            float t = (float) Math.exp(-d2) * amp[i] * rough;
                            lr += t * col[i][0];
                            lg += t * col[i][1];
                            lb += t * col[i][2];
                        }
                        if (lr > 0.002f) {
                            // a hot core burns towards yellow-white
                            float hot = Math.max(0, lr - 0.75f);
                            R = screen(R, lr);
                            G = screen(G, lg + hot * 0.8f);
                            B = screen(B, lb + hot * 0.4f);
                        }
                    }
                    if (opt.grain > 0) {
                        float lum = (0.299f * R + 0.587f * G + 0.114f * B) / 255f;
                        if (lum < 0) lum = 0;
                        if (lum > 1) lum = 1;
                        // most visible in midtones and shadows, fading out in the highlights
                        float weight = 0.15f + 0.85f * Math.min(1f, 2.6f * (float) Math.sqrt(lum) * (1 - lum));
                        float gn = 0.65f * valueNoise(x / cell, y / cell, noiseSeed)
                                + 0.35f * valueNoise(x / (cell * 2.3f), y / (cell * 2.3f), noiseSeed + 101);
                        float d = gn * strength * weight * 1.6f;
                        if (opt.monochrome) {
                            R += d;
                            G += d;
                            B += d;
                        } else {
                            float cr = valueNoise(x / cell, y / cell, noiseSeed + 211) * strength * weight * 0.45f;
                            float cb = valueNoise(x / cell, y / cell, noiseSeed + 307) * strength * weight * 0.45f;
                            R += d + cr;
                            G += d - 0.5f * (cr + cb);
                            B += d + cb;
                        }
                    }
                    band[base + x] = (clamp255(R) << 16) | (clamp255(G) << 8) | clamp255(B);
                }
            }
            out.writeRows(band, n);
        }
        out.finish();
    }

    private static float[] rgb(int c) {
        return new float[] {((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
    }

    private static int clamp255(float v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : (int) v;
    }

    /** Smooth random field in [-1, 1]: hashed values on an integer grid, blended with a smoothstep curve. */
    private static float valueNoise(float x, float y, int seed) {
        int xi = (int) x, yi = (int) y;
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(xi, yi, seed), b = hash(xi + 1, yi, seed);
        float c = hash(xi, yi + 1, seed), d = hash(xi + 1, yi + 1, seed);
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    /** 0..1 per pixel for how far above the highlight threshold it is. */
    private static float[] highlights(Image img) {
        float[] m = new float[img.w * img.h];
        for (int i = 0; i < m.length; i++) {
            int px = img.px[i];
            int rr = (px >> 16) & 255, gg = (px >> 8) & 255, bb = px & 255;
            int lum = (rr * 77 + gg * 150 + bb * 29) >> 8;
            int v = Math.max(lum, Math.max(rr, Math.max(gg, bb)) - 20);
            float t = v <= THRESHOLD ? 0 : (v - THRESHOLD) / (float) (255 - THRESHOLD);
            m[i] = t * t;
        }
        return m;
    }

    /** Three box passes each way, close to a gaussian. */
    private static void blur(float[] m, int w, int h, int r) {
        float[] tmp = new float[Math.max(w, h)], line = new float[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) boxRow(m, y * w, w, r, tmp);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) line[y] = m[y * w + x];
            for (int pass = 0; pass < 3; pass++) boxRow(line, 0, h, r, tmp);
            for (int y = 0; y < h; y++) m[y * w + x] = line[y];
        }
    }

    private static float sample(float[] m, int w, int h, float x, float y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        int x0 = (int) x, y0 = (int) y;
        if (x0 >= w - 1) x0 = w - 2;
        if (y0 >= h - 1) y0 = h - 2;
        if (x0 < 0 || y0 < 0) return m[0];
        float fx = Math.min(1, x - x0), fy = Math.min(1, y - y0);
        int i = y0 * w + x0;
        float top = m[i] + (m[i + 1] - m[i]) * fx;
        float bot = m[i + w] + (m[i + w + 1] - m[i + w]) * fx;
        return top + (bot - top) * fy;
    }

    private static float screen(float c, float glow) {
        if (glow > 1) glow = 1;
        if (c > 255) c = 255;
        return 255 - (255 - c) * (1 - glow);
    }

    /** In-place running box blur of one row segment. */
    private static void boxRow(float[] a, int off, int n, int r, float[] tmp) {
        System.arraycopy(a, off, tmp, 0, n);
        float sum = 0;
        int win = 2 * r + 1;
        for (int i = -r; i <= r; i++) sum += tmp[clamp(i, n)];
        for (int i = 0; i < n; i++) {
            a[off + i] = sum / win;
            sum += tmp[clamp(i + r + 1, n)] - tmp[clamp(i - r, n)];
        }
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }

    /** Returns the original's Exif APP1 segment (marker included) so the copy keeps its date and settings. */
    public static byte[] readExif(InputStream in) throws IOException {
        try {
            if (in.read() != 0xFF || in.read() != 0xD8) return null;
            while (true) {
                int ff = in.read(), marker = in.read();
                if (ff != 0xFF || marker < 0 || marker == 0xDA || marker == 0xD9) return null;
                int len = (in.read() << 8) | in.read();
                if (len < 2) return null;
                byte[] seg = new byte[len - 2];
                int got = 0;
                while (got < seg.length) {
                    int n = in.read(seg, got, seg.length - got);
                    if (n < 0) return null;
                    got += n;
                }
                if (marker == 0xE1 && seg.length > 6 && seg[0] == 'E' && seg[1] == 'x' && seg[2] == 'i' && seg[3] == 'f') {
                    byte[] out = new byte[len + 2];
                    out[0] = (byte) 0xFF;
                    out[1] = (byte) 0xE1;
                    out[2] = (byte) (len >> 8);
                    out[3] = (byte) len;
                    System.arraycopy(seg, 0, out, 4, seg.length);
                    return out;
                }
            }
        } finally {
            in.close();
        }
    }

    /** Streaming baseline JPEG encoder, 4:2:0, fed 16 rows at a time. */
    public static class JpegWriter {
        private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
            28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61,
            54, 47, 55, 62, 63};
        private static final int[] LUMA_Q = {
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51,
            87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99};
        private static final int[] CHROMA_Q = {
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99};
        // standard Huffman tables (ITU T.81 annex K)
        private static final int[] DC_L_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
        private static final int[] DC_L_VAL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        private static final int[] DC_C_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
        private static final int[] DC_C_VAL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        private static final int[] AC_L_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
        private static final int[] AC_L_VAL = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14,
            0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09,
            0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a,
            0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65,
            0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88,
            0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9,
            0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca,
            0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea,
            0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};
        private static final int[] AC_C_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
        private static final int[] AC_C_VAL = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32,
            0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16,
            0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39,
            0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64,
            0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86,
            0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8,
            0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9,
            0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};

        private final OutputStream out;
        private final int width, height;
        private final int[] qY = new int[64], qC = new int[64];
        private final int[][] dcL, dcC, acL, acC; // [symbol] -> {code, length}
        private final float[] cos = new float[64];
        private int bitBuf, bitCount;
        private int predY, predCb, predCr;
        private int rowsWritten;
        // one MCU row (16 rows) of Y, Cb, Cr at full and half resolution
        private final float[] y16, cb8, cr8;
        private final int mcuW;
        private final float[] block = new float[64], coef = new float[64], dctTmp = new float[64];
        private final int[] zz = new int[64];

        public JpegWriter(OutputStream out, int width, int height, int quality, byte[] exifApp1) throws IOException {
            this.out = out;
            this.width = width;
            this.height = height;
            int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
            for (int i = 0; i < 64; i++) {
                qY[i] = Math.max(1, Math.min(255, (LUMA_Q[i] * scale + 50) / 100));
                qC[i] = Math.max(1, Math.min(255, (CHROMA_Q[i] * scale + 50) / 100));
            }
            dcL = huff(DC_L_BITS, DC_L_VAL);
            dcC = huff(DC_C_BITS, DC_C_VAL);
            acL = huff(AC_L_BITS, AC_L_VAL);
            acC = huff(AC_C_BITS, AC_C_VAL);
            for (int u = 0; u < 8; u++) {
                for (int x = 0; x < 8; x++) cos[u * 8 + x] = (float) Math.cos((2 * x + 1) * u * Math.PI / 16);
            }
            mcuW = (width + 15) / 16;
            y16 = new float[mcuW * 16 * 16];
            cb8 = new float[mcuW * 8 * 8];
            cr8 = new float[mcuW * 8 * 8];
            writeHeaders(exifApp1);
        }

        private static int[][] huff(int[] bits, int[] vals) {
            int[][] table = new int[256][];
            int code = 0, k = 0;
            for (int len = 1; len <= 16; len++) {
                for (int i = 0; i < bits[len - 1]; i++) table[vals[k++]] = new int[] {code++, len};
                code <<= 1;
            }
            return table;
        }

        private void writeHeaders(byte[] exif) throws IOException {
            out.write(0xFF);
            out.write(0xD8);
            if (exif != null) {
                out.write(exif);
            } else {
                out.write(new byte[] {(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
            }
            // DQT
            out.write(new byte[] {(byte) 0xFF, (byte) 0xDB, 0, (byte) 132});
            out.write(0);
            for (int i = 0; i < 64; i++) out.write(qY[ZIGZAG[i]]);
            out.write(1);
            for (int i = 0; i < 64; i++) out.write(qC[ZIGZAG[i]]);
            // SOF0
            out.write(new byte[] {(byte) 0xFF, (byte) 0xC0, 0, 17, 8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 3,
                1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});
            // DHT
            writeDht(0x00, DC_L_BITS, DC_L_VAL);
            writeDht(0x10, AC_L_BITS, AC_L_VAL);
            writeDht(0x01, DC_C_BITS, DC_C_VAL);
            writeDht(0x11, AC_C_BITS, AC_C_VAL);
            // SOS
            out.write(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 12, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0});
        }

        private void writeDht(int cls, int[] bits, int[] vals) throws IOException {
            int len = 2 + 1 + 16 + vals.length;
            out.write(0xFF);
            out.write(0xC4);
            out.write(len >> 8);
            out.write(len);
            out.write(cls);
            for (int b : bits) out.write(b);
            for (int v : vals) out.write(v);
        }

        /** Takes `count` rows (16 per call, fewer only for the last call) of packed RGB. */
        public void writeRows(int[] rgb, int count) throws IOException {
            for (int r0 = 0; r0 < count; r0 += 16) {
                int n = Math.min(16, count - r0);
                fillMcuRow(rgb, r0, n);
                encodeMcuRow();
                rowsWritten += n;
            }
        }

        private void fillMcuRow(int[] rgb, int r0, int n) {
            int fullW = mcuW * 16;
            java.util.Arrays.fill(cb8, 0);
            java.util.Arrays.fill(cr8, 0);
            for (int r = 0; r < 16; r++) {
                int sr = r0 + Math.min(r, n - 1); // repeat the last row past the bottom edge
                for (int x = 0; x < fullW; x++) {
                    int px = rgb[sr * width + Math.min(x, width - 1)];
                    int R = (px >> 16) & 255, G = (px >> 8) & 255, B = px & 255;
                    y16[r * fullW + x] = 0.299f * R + 0.587f * G + 0.114f * B - 128;
                    int ci = (r >> 1) * (mcuW * 8) + (x >> 1);
                    cb8[ci] += (-0.168736f * R - 0.331264f * G + 0.5f * B) * 0.25f;
                    cr8[ci] += (0.5f * R - 0.418688f * G - 0.081312f * B) * 0.25f;
                }
            }
        }

        private void encodeMcuRow() throws IOException {
            int fullW = mcuW * 16, cW = mcuW * 8;
            for (int m = 0; m < mcuW; m++) {
                for (int by = 0; by < 2; by++) {
                    for (int bx = 0; bx < 2; bx++) {
                        for (int y = 0; y < 8; y++) {
                            System.arraycopy(y16, (by * 8 + y) * fullW + m * 16 + bx * 8, block, y * 8, 8);
                        }
                        predY = encodeBlock(qY, dcL, acL, predY);
                    }
                }
                for (int y = 0; y < 8; y++) System.arraycopy(cb8, y * cW + m * 8, block, y * 8, 8);
                predCb = encodeBlock(qC, dcC, acC, predCb);
                for (int y = 0; y < 8; y++) System.arraycopy(cr8, y * cW + m * 8, block, y * 8, 8);
                predCr = encodeBlock(qC, dcC, acC, predCr);
            }
        }

        private int encodeBlock(int[] q, int[][] dc, int[][] ac, int pred) throws IOException {
            // separable forward DCT
            float[] t = dctTmp;
            for (int y = 0; y < 8; y++) {
                for (int u = 0; u < 8; u++) {
                    float s = 0;
                    for (int x = 0; x < 8; x++) s += block[y * 8 + x] * cos[u * 8 + x];
                    t[y * 8 + u] = s * (u == 0 ? 0.35355339f : 0.5f);
                }
            }
            for (int u = 0; u < 8; u++) {
                for (int v = 0; v < 8; v++) {
                    float s = 0;
                    for (int y = 0; y < 8; y++) s += t[y * 8 + u] * cos[v * 8 + y];
                    coef[v * 8 + u] = s * (v == 0 ? 0.35355339f : 0.5f);
                }
            }
            for (int i = 0; i < 64; i++) zz[i] = Math.round(coef[ZIGZAG[i]] / q[ZIGZAG[i]]);
            int diff = zz[0] - pred;
            int cat = category(diff);
            emit(dc[cat]);
            if (cat > 0) bits(diff < 0 ? diff + (1 << cat) - 1 : diff, cat);
            int run = 0;
            for (int i = 1; i < 64; i++) {
                if (zz[i] == 0) {
                    run++;
                    continue;
                }
                while (run > 15) {
                    emit(ac[0xF0]);
                    run -= 16;
                }
                int c = category(zz[i]);
                emit(ac[(run << 4) | c]);
                bits(zz[i] < 0 ? zz[i] + (1 << c) - 1 : zz[i], c);
                run = 0;
            }
            if (run > 0) emit(ac[0x00]);
            return zz[0];
        }

        private static int category(int v) {
            v = Math.abs(v);
            int c = 0;
            while (v > 0) {
                c++;
                v >>= 1;
            }
            return c;
        }

        private void emit(int[] code) throws IOException {
            bits(code[0], code[1]);
        }

        private void bits(int value, int len) throws IOException {
            for (int i = len - 1; i >= 0; i--) {
                bitBuf = (bitBuf << 1) | ((value >> i) & 1);
                if (++bitCount == 8) {
                    out.write(bitBuf);
                    if (bitBuf == 0xFF) out.write(0);
                    bitBuf = 0;
                    bitCount = 0;
                }
            }
        }

        public void finish() throws IOException {
            if (bitCount > 0) bits((1 << (8 - bitCount)) - 1, 8 - bitCount);
            out.write(0xFF);
            out.write(0xD9);
            out.flush();
        }
    }
}
