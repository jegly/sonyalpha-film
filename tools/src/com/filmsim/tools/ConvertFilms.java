package com.filmsim.tools;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Converts the film LUTs listed in films.csv (file|name|brand|type) into the camera's tone curve + colour matrix
 * and writes them as app assets.
 *
 * Entries whose file is "design:<id>" are built by {@link Designer} instead of read from a LUT.
 *
 * Usage: ConvertFilms <films.csv> <lut dir> <assets dir>
 * Output: <assets>/films/NN.bin (LutParams format) and <assets>/films.txt (file|name|brand|type|error).
 */
public class ConvertFilms {
    public static void main(String[] args) throws IOException {
        File csv = new File(args[0]);
        File lutDir = new File(args[1]);
        File assets = new File(args[2]);
        File filmDir = new File(assets, "films");
        filmDir.mkdirs();

        StringBuilder index = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(csv));
        try {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|");
                LutParams p;
                double err;
                if (f[0].startsWith("design:")) {
                    // Hand-designed in the camera's own model, so it reproduces exactly (-1 marks "designed").
                    p = Designer.build(f[0].substring("design:".length()));
                    err = -1;
                } else {
                    File lut = new File(lutDir, f[0]);
                    Cube cube = f[0].endsWith(".png") ? loadHald(lut) : Cube.load(lut);
                    p = Decomposer.decompose(cube);
                    err = meanError(cube, p);
                }
                String file = String.format("%02d.bin", n++);
                p.save(new File(filmDir, file));
                index.append(file).append('|').append(f[1]).append('|').append(f[2]).append('|').append(f[3])
                        .append('|').append(String.format("%.1f", err)).append('\n');
                System.out.printf("%s  %-22s error %.1f/255%n", file, f[1], err);
            }
        } finally {
            br.close();
        }
        FileWriter w = new FileWriter(new File(assets, "films.txt"));
        try {
            w.write(index.toString());
        } finally {
            w.close();
        }
    }

    /** Reads a HaldCLUT PNG, subsampled to at most 64 steps per axis. */
    static Cube loadHald(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        int dim = img.getWidth();
        int level = (int) Math.round(Math.cbrt(dim));
        int steps = level * level;
        int out = Math.min(steps, 64);
        int stride = (steps - 1) / (out - 1);
        if ((steps - 1) % (out - 1) != 0) {
            out = steps;
            stride = 1;
        }
        Cube c = new Cube();
        c.size = out;
        c.data = new float[out * out * out * 3];
        for (int b = 0; b < out; b++) for (int g = 0; g < out; g++) for (int r = 0; r < out; r++) {
            int idx = r * stride + g * stride * steps + b * stride * steps * steps;
            int px = img.getRGB(idx % dim, idx / dim);
            int o = ((b * out + g) * out + r) * 3;
            c.data[o] = ((px >> 16) & 255) / 255f;
            c.data[o + 1] = ((px >> 8) & 255) / 255f;
            c.data[o + 2] = (px & 255) / 255f;
        }
        return c;
    }

    /** Mean RMS error (0-255 scale) between the LUT and what the camera pipeline will produce. */
    static double meanError(Cube cube, LutParams p) {
        int g = 33, n = g * g * g;
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
