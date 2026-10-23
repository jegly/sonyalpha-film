package com.filmsim.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The user's own .cube LUTs from the LUTS folder on the card. Each one is converted to the camera's
 * curve + matrix the first time it's seen and cached in FILMSIM/LUTCACHE, so later starts are instant.
 */
public class CustomLuts {
    public static final String BRAND = "My LUTs";

    public interface Listener {
        void onConverting(int index, int total, String name);

        void onFailed(String name, String reason);
    }

    private final File lutDir, cacheDir;

    public CustomLuts(File cardRoot) {
        lutDir = new File(cardRoot, "LUTS");
        cacheDir = new File(new File(cardRoot, "FILMSIM"), "LUTCACHE");
    }

    /** Finds, converts (if needed) and returns the user's LUTs. Slow the first time; run it off the UI thread. */
    public List<FilmLibrary.Film> load(Listener listener) {
        List<FilmLibrary.Film> out = new ArrayList<FilmLibrary.Film>();
        File[] files = lutDir.listFiles();
        if (files == null) return out;
        List<File> luts = new ArrayList<File>();
        for (File f : files) {
            String n = f.getName().toUpperCase();
            if (f.isFile() && (n.endsWith(".CUBE") || n.endsWith(".CUB"))) luts.add(f);
        }
        File[] sorted = luts.toArray(new File[0]);
        Arrays.sort(sorted);
        cacheDir.mkdirs();
        int toConvert = 0;
        for (File f : sorted) {
            if (!cacheFile(f).isFile() && !badFile(f).isFile()) toConvert++;
        }
        int converted = 0;
        for (File f : sorted) {
            File cache = cacheFile(f);
            File err = new File(cacheDir, cache.getName().replace(".BIN", ".TXT"));
            if (badFile(f).isFile()) continue; // failed before; a changed file gets a new name and is tried again
            try {
                if (!cache.isFile()) {
                    listener.onConverting(++converted, toConvert, displayName(f));
                    Cube cube = Cube.load(f);
                    LutParams p = Decomposer.decompose(cube);
                    File tmp = new File(cacheDir, cache.getName() + ".TMP");
                    p.save(tmp);
                    writeError(err, meanError(cube, p));
                    if (!tmp.renameTo(cache)) throw new IOException("could not save the conversion");
                }
                out.add(new FilmLibrary.Film("custom:" + cache.getName(), displayName(f), BRAND, "Custom", readError(err), cache));
            } catch (Throwable t) {
                String why = t instanceof OutOfMemoryError ? "too big for the camera's memory"
                        : t.getMessage() != null && t.getMessage().startsWith("bad cube") ? "not a valid .cube file"
                        : t.getMessage() != null ? t.getMessage() : t.toString();
                listener.onFailed(displayName(f), why);
                try {
                    writeError(badFile(f), -1);
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    /** 8.3-safe cache name tied to the LUT's name and size, so an edited LUT gets converted again. */
    private File cacheFile(File lut) {
        return new File(cacheDir, String.format("%08X.BIN", (lut.getName() + "|" + lut.length()).hashCode()));
    }

    private File badFile(File lut) {
        return new File(cacheDir, cacheFile(lut).getName().replace(".BIN", ".BAD"));
    }

    private static String displayName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replace('_', ' ');
    }

    private static void writeError(File f, double err) throws IOException {
        FileWriter w = new FileWriter(f);
        try {
            w.write(String.valueOf(err));
        } finally {
            w.close();
        }
    }

    private static float readError(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            try {
                return Float.parseFloat(br.readLine().trim());
            } finally {
                br.close();
            }
        } catch (Exception e) {
            return 5f; // unknown: show it as "close"
        }
    }

    /** Mean RMS error (0-255) between the LUT and what the camera pipeline will produce. */
    static double meanError(Cube cube, LutParams p) {
        int g = 17, n = g * g * g;
        float[] x = new float[n * 3];
        int k = 0;
        for (int r = 0; r < g; r++) for (int gg = 0; gg < g; gg++) for (int b = 0; b < g; b++) {
            x[k++] = r / (float) (g - 1);
            x[k++] = gg / (float) (g - 1);
            x[k++] = b / (float) (g - 1);
        }
        float[] target = new float[n * 3];
        cube.sample(x, target);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double[] u = new double[3];
            for (int c = 0; c < 3; c++) {
                double pos = x[i * 3 + c] * 1023;
                int a = Math.min((int) pos, 1022);
                double fr = pos - a;
                u[c] = (p.gamma[a] * (1 - fr) + p.gamma[a + 1] * fr) / 1023.0;
            }
            double e = 0;
            for (int j = 0; j < 3; j++) {
                double o = 0;
                for (int c = 0; c < 3; c++) o += p.matrix[j * 3 + c] / 1024.0 * u[c];
                o = Math.max(0, Math.min(1, o));
                double d = (o - target[i * 3 + j]) * 255;
                e += d * d;
            }
            sum += Math.sqrt(e / 3);
        }
        return sum / n;
    }
}
