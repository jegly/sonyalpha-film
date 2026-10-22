package com.filmsim.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders a contact sheet of looks the way the camera applies them: out = clip(M · g(x)).
 * Usage: Preview <assets dir> <brand or "all"> <out.png> [source image]
 */
public class Preview {
    public static void main(String[] args) throws IOException {
        File assets = new File(args[0]);
        String brand = args[1];
        BufferedImage src = args.length > 3 ? ImageIO.read(new File(args[3])) : testChart();
        src = scale(src, 320);
        List<String[]> films = new ArrayList<String[]>();
        BufferedReader br = new BufferedReader(new FileReader(new File(assets, "films.txt")));
        String line;
        while ((line = br.readLine()) != null) {
            String[] f = line.split("\\|");
            if (brand.equals("all") || f[2].equals(brand)) films.add(f);
        }
        br.close();
        int cols = 4, w = src.getWidth(), h = src.getHeight() + 22;
        int n = films.size() + 1;
        BufferedImage sheet = new BufferedImage(cols * w, ((n + cols - 1) / cols) * h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        draw(g, src, "Original (no film)", 0, w, h, cols);
        for (int i = 0; i < films.size(); i++) {
            String[] f = films.get(i);
            int[][] lp = load(new File(new File(assets, "films"), f[0]));
            draw(g, apply(src, lp[0], lp[1]), f[2] + " " + f[1], i + 1, w, h, cols);
        }
        ImageIO.write(sheet, "png", new File(args[2]));
    }

    static void draw(Graphics2D g, BufferedImage img, String label, int idx, int w, int h, int cols) {
        int x = (idx % cols) * w, y = (idx / cols) * h;
        g.drawImage(img, x, y, null);
        g.setColor(Color.BLACK);
        g.fillRect(x, y + img.getHeight(), w, 22);
        g.setColor(Color.WHITE);
        g.drawString(label, x + 6, y + img.getHeight() + 16);
    }

    static int[][] load(File f) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(f));
        try {
            in.readInt();
            int[] gamma = new int[1024], m = new int[9];
            for (int i = 0; i < 1024; i++) gamma[i] = in.readInt();
            for (int i = 0; i < 9; i++) m[i] = in.readInt();
            return new int[][] {gamma, m};
        } finally {
            in.close();
        }
    }

    static BufferedImage apply(BufferedImage src, int[] gamma, int[] m) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int px = src.getRGB(x, y);
                double[] u = new double[3];
                int[] ch = {(px >> 16) & 255, (px >> 8) & 255, px & 255};
                for (int c = 0; c < 3; c++) u[c] = gamma[(int) Math.round(ch[c] / 255.0 * 1023)] / 1023.0;
                int[] o = new int[3];
                for (int j = 0; j < 3; j++) {
                    double v = m[j * 3] / 1024.0 * u[0] + m[j * 3 + 1] / 1024.0 * u[1] + m[j * 3 + 2] / 1024.0 * u[2];
                    o[j] = (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
                }
                out.setRGB(x, y, (o[0] << 16) | (o[1] << 8) | o[2]);
            }
        }
        return out;
    }

    static BufferedImage scale(BufferedImage src, int w) {
        int h = src.getHeight() * w / src.getWidth();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Sky gradient, foliage, skin tones, a 24-patch colour chart, grey ramp and a hue sweep. */
    static BufferedImage testChart() {
        int w = 480, h = 320;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < 90; y++) {
            float t = y / 90f;
            g.setColor(new Color(lerp(70, 180, t), lerp(130, 205, t), lerp(200, 235, t)));
            g.drawLine(0, y, w, y);
        }
        g.setColor(new Color(250, 236, 200)); g.fillOval(380, 15, 50, 50);
        int[][] foliage = {{58, 92, 40}, {86, 120, 52}, {120, 150, 70}, {40, 66, 30}};
        for (int i = 0; i < 4; i++) { g.setColor(new Color(foliage[i][0], foliage[i][1], foliage[i][2])); g.fillRect(i * 60, 90, 60, 50); }
        int[][] skin = {{255, 224, 196}, {234, 192, 160}, {198, 150, 116}, {141, 98, 70}};
        for (int i = 0; i < 4; i++) { g.setColor(new Color(skin[i][0], skin[i][1], skin[i][2])); g.fillRect(240 + i * 60, 90, 60, 50); }
        int[][] chart = {{115, 82, 68}, {194, 150, 130}, {98, 122, 157}, {87, 108, 67}, {133, 128, 177}, {103, 189, 170},
            {214, 126, 44}, {80, 91, 166}, {193, 90, 99}, {94, 60, 108}, {157, 188, 64}, {224, 163, 46},
            {56, 61, 150}, {70, 148, 73}, {175, 54, 60}, {231, 199, 31}, {187, 86, 149}, {8, 133, 161},
            {243, 243, 242}, {200, 200, 200}, {160, 160, 160}, {122, 122, 121}, {85, 85, 85}, {52, 52, 52}};
        for (int i = 0; i < 24; i++) { g.setColor(new Color(chart[i][0], chart[i][1], chart[i][2])); g.fillRect((i % 12) * 40, 140 + (i / 12) * 40, 40, 40); }
        for (int x = 0; x < w; x++) { int v = x * 255 / (w - 1); g.setColor(new Color(v, v, v)); g.drawLine(x, 220, x, 260); }
        for (int x = 0; x < w; x++) { g.setColor(Color.getHSBColor(x / (float) w, 0.75f, 0.9f)); g.drawLine(x, 260, x, h); }
        g.dispose();
        return img;
    }

    static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
