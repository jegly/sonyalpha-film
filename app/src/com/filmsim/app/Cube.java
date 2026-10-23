// Based on OpenMemoriesLUT by Pichuworks (MIT), see tools/LICENSE-OpenMemoriesLUT.txt
package com.filmsim.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads a .cube 3D LUT and samples it with trilinear interpolation.
 * In a .cube file red changes fastest: flat index = (b * size + g) * size + r.
 */
public class Cube {
    public int size;
    public float[] data; // size³ * 3, index(r, g, b) = ((b * size + g) * size + r) * 3

    public static Cube load(File f) throws IOException {
        Cube c = new Cube();
        BufferedReader br = new BufferedReader(new FileReader(f), 64 * 1024);
        try {
            float[] buf = null;
            int n = 0;
            float[] triple = new float[3];
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                char first = line.charAt(0);
                if ((first >= '0' && first <= '9') || first == '-' || first == '.' || first == '+') {
                    if (buf == null) throw new IOException("values before LUT_3D_SIZE");
                    if (parseTriple(line, triple) && n + 3 <= buf.length) {
                        buf[n++] = triple[0];
                        buf[n++] = triple[1];
                        buf[n++] = triple[2];
                    }
                    continue;
                }
                String upper = line.toUpperCase();
                if (upper.startsWith("LUT_3D_SIZE") || upper.startsWith("LUT_SIZE")) {
                    c.size = Integer.parseInt(upper.substring(upper.indexOf(' ') + 1).trim());
                    if (c.size < 2 || c.size > 65) throw new IOException("LUT size " + c.size + " is too big, use a 33 or 65 point LUT");
                    buf = new float[c.size * c.size * c.size * 3];
                } else if (upper.startsWith("LUT_1D_SIZE")) {
                    throw new IOException("1D LUTs aren't supported");
                }
                // TITLE, DOMAIN_MIN/MAX and other keywords are ignored
            }
            if (buf == null || n != buf.length) {
                throw new IOException("bad cube: size=" + c.size + " values=" + n / 3);
            }
            c.data = buf;
            return c;
        } finally {
            br.close();
        }
    }

    /** Three space-separated numbers, without regex (much quicker on the camera). */
    private static boolean parseTriple(String line, float[] out) {
        int i = 0, len = line.length();
        for (int k = 0; k < 3; k++) {
            while (i < len && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            int start = i;
            while (i < len && line.charAt(i) != ' ' && line.charAt(i) != '\t') i++;
            if (start == i) return false;
            try {
                out[k] = Float.parseFloat(line.substring(start, i));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Trilinear sampling. X: input points, length N*3 (0..1); out: length N*3. */
    public void sample(float[] X, float[] out) {
        int s = size;
        float max = s - 1;
        for (int i = 0; i < X.length; i += 3) {
            float r = X[i] * max, g = X[i + 1] * max, b = X[i + 2] * max;
            if (r < 0) r = 0; else if (r > max) r = max;
            if (g < 0) g = 0; else if (g > max) g = max;
            if (b < 0) b = 0; else if (b > max) b = max;
            int r0 = (int) r, g0 = (int) g, b0 = (int) b;
            if (r0 >= s - 1) r0 = s - 2;
            if (g0 >= s - 1) g0 = s - 2;
            if (b0 >= s - 1) b0 = s - 2;
            float fr = r - r0, fg = g - g0, fb = b - b0;
            float acc0 = 0, acc1 = 0, acc2 = 0;
            for (int corner = 0; corner < 8; corner++) {
                int dr = (corner >> 2) & 1, dg = (corner >> 1) & 1, db = corner & 1;
                float w = (dr == 1 ? fr : 1 - fr) * (dg == 1 ? fg : 1 - fg) * (db == 1 ? fb : 1 - fb);
                int idx = (((b0 + db) * s + (g0 + dg)) * s + (r0 + dr)) * 3;
                acc0 += w * data[idx];
                acc1 += w * data[idx + 1];
                acc2 += w * data[idx + 2];
            }
            out[i] = acc0;
            out[i + 1] = acc1;
            out[i + 2] = acc2;
        }
    }
}
