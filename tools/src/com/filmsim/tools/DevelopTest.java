package com.filmsim.tools;

import com.filmsim.app.Develop;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.ImageIO;

/**
 * Runs the camera's develop step on a computer: same strip processing and JPEG encoder.
 * Usage: DevelopTest <in.jpg> <out.jpg> <scale> <effects...> where effects are any of
 * halation, grain1, grain2, grain3, grain4, leak, mono, seed=N
 */
public class DevelopTest {
    public static void main(String[] args) throws IOException {
        File in = new File(args[0]);
        int scale = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        Develop.Options opt = new Develop.Options();
        opt.seed = 1;
        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if (a.equals("halation")) opt.halation = true;
            else if (a.startsWith("grain")) opt.grain = Integer.parseInt(a.substring(5));
            else if (a.equals("leak")) opt.lightLeak = true;
            else if (a.equals("mono")) opt.monochrome = true;
            else if (a.startsWith("seed=")) opt.seed = Long.parseLong(a.substring(5));
        }
        BufferedImage full = ImageIO.read(in);
        final BufferedImage img = new BufferedImage(full.getWidth() / scale, full.getHeight() / scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(full, 0, 0, img.getWidth(), img.getHeight(), null);
        g.dispose();
        Develop.Source src = new Develop.Source() {
            public int width() { return img.getWidth(); }
            public int height() { return img.getHeight(); }
            public void read(int y, int count, int[] argb) {
                img.getRGB(0, y, img.getWidth(), count, argb, 0, img.getWidth());
            }
            public Develop.Image preview(int factor) {
                int pw = Math.max(2, img.getWidth() / factor), ph = Math.max(2, img.getHeight() / factor);
                BufferedImage small = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
                Graphics2D sg = small.createGraphics();
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                sg.drawImage(img, 0, 0, pw, ph, null);
                sg.dispose();
                return new Develop.Image(pw, ph, small.getRGB(0, 0, pw, ph, null, 0, pw));
            }
        };
        byte[] exif = Develop.readExif(new FileInputStream(in));
        long t0 = System.currentTimeMillis();
        OutputStream os = new BufferedOutputStream(new FileOutputStream(args[1]));
        try {
            Develop dev = new Develop(opt);
            dev.process(src, new Develop.JpegWriter(os, img.getWidth(), img.getHeight(), 92, exif), null);
            System.out.println("  " + dev.timing);
        } finally {
            os.close();
        }
        long t1 = System.currentTimeMillis();
        BufferedImage check = ImageIO.read(new File(args[1]));
        System.out.println(img.getWidth() + "x" + img.getHeight() + " in " + (t1 - t0) + " ms, exif " + (exif == null ? "none" : exif.length + " bytes")
                + ", decodes back as " + (check == null ? "NOTHING" : check.getWidth() + "x" + check.getHeight()));
    }
}
