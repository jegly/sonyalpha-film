package com.filmsim.app;

/**
 * A film look applied in software to a photo that has already been taken, using the same model the camera
 * runs live: out = clip(M · g(x)), with g the tone curve and M the colour matrix.
 *
 * Plain Java and whole-number maths, so it runs on the camera and can be checked on a computer.
 */
public class FilmLook {
    /**
     * The curve and matrix folded together: t[row * 3 + channel][value] is that input channel's share of one
     * output channel, x16384. A pixel then costs nine look-ups and a few additions, with no multiplying,
     * which matters on the camera's slow processor.
     */
    private final int[][] t = new int[9][256];
    private final boolean identity;

    /**
     * @param gamma    1024-point tone curve, values 0..1023
     * @param matrix   3x3 matrix x1024, row-major
     * @param strength 0..100, how much of the look to mix in
     */
    public FilmLook(int[] gamma, int[] matrix, int strength) {
        double a = Math.max(0, Math.min(100, strength)) / 100.0;
        boolean same = true;
        for (int i = 0; i < 1024; i++) if (Math.round((1 - a) * i + a * gamma[i]) != i) same = false;
        for (int i = 0; i < 9; i++) if (Math.round((1 - a) * ((i == 0 || i == 4 || i == 8) ? 1024 : 0) + a * matrix[i]) != ((i == 0 || i == 4 || i == 8) ? 1024 : 0)) same = false;
        identity = same;
        // tone curve for 8-bit input, output scaled to 0..4080 (255 x 16)
        int[] curve = new int[256];
        for (int i = 0; i < 256; i++) {
            double pos = i * 1023 / 255.0;
            int k = Math.min((int) pos, 1022);
            double fr = pos - k;
            double g = gamma[k] * (1 - fr) + gamma[k + 1] * fr;
            double mixed = (1 - a) * pos + a * g;
            curve[i] = (int) Math.round(mixed / 1023.0 * 4080);
        }
        for (int i = 0; i < 9; i++) {
            int id = (i == 0 || i == 4 || i == 8) ? 1024 : 0;
            int m = (int) Math.round((1 - a) * id + a * matrix[i]);
            // the rounding half goes into the first table of each row
            int round = i % 3 == 0 ? 8192 : 0;
            for (int v = 0; v < 256; v++) t[i][v] = m * curve[v] + round;
        }
    }

    /** True when the look changes nothing, so applying it can be skipped. */
    public boolean isIdentity() {
        return identity;
    }

    /** Applies the look to n packed RGB pixels starting at off. Alpha comes out opaque. */
    public void apply(int[] px, int off, int n) {
        final int[] rr = t[0], rg = t[1], rb = t[2], gr = t[3], gg = t[4], gb = t[5], br = t[6], bg = t[7], bb = t[8];
        for (int i = off, end = off + n; i < end; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int or = (rr[r] + rg[g] + rb[b]) >> 14;
            int og = (gr[r] + gg[g] + gb[b]) >> 14;
            int ob = (br[r] + bg[g] + bb[b]) >> 14;
            if (or < 0) or = 0; else if (or > 255) or = 255;
            if (og < 0) og = 0; else if (og > 255) og = 255;
            if (ob < 0) ob = 0; else if (ob > 255) ob = 255;
            px[i] = 0xFF000000 | (or << 16) | (og << 8) | ob;
        }
    }
}
