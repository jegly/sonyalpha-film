package com.filmsim.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

/**
 * Background queue that makes developed copies (halation, grain, light leaks) of JPEGs at half size in
 * FILMSIM/DEV.
 * Each copy is written to a .TMP file and renamed when complete, so a cut-off run never leaves a broken JPEG.
 */
public class Developer {
    public interface Listener {
        /** True while the camera is busy with the card (taking a photo, recording); work waits meanwhile. */
        boolean cameraBusy();

        void onStatus(int queued);

        void onDone(String source, File result, long millis);

        void onFailed(String source, Throwable error);
    }

    private static final int SAMPLE = 2; // copies are half the photo's width and height

    private final File outDir;
    private final Listener listener;
    private final LinkedList<File> queue = new LinkedList<File>();
    private final java.util.HashMap<File, Develop.Options> options = new java.util.HashMap<File, Develop.Options>();
    private volatile boolean cancelled;
    private Thread worker;

    public Developer(File outDir, Listener listener) {
        this.outDir = outDir;
        this.listener = listener;
        cleanTemp();
    }

    public synchronized void add(File jpeg, Develop.Options opt) {
        if (queue.contains(jpeg)) return;
        queue.add(jpeg);
        options.put(jpeg, opt);
        listener.onStatus(queue.size());
        if (worker == null) {
            cancelled = false;
            worker = new Thread(new Runnable() {
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    loop();
                }
            }, "halation");
            worker.start();
        }
    }

    public synchronized int pending() {
        return queue.size();
    }

    /** Stops after the current strip; the unfinished copy is deleted. */
    public void cancel() {
        cancelled = true;
        synchronized (this) {
            queue.clear();
            options.clear();
        }
    }

    private void loop() {
        while (true) {
            File next;
            synchronized (this) {
                if (queue.isEmpty() || cancelled) {
                    worker = null;
                    queue.clear();
                    listener.onStatus(0);
                    return;
                }
                next = queue.peek();
            }
            Develop.Options opt;
            synchronized (this) {
                opt = options.get(next);
            }
            long t0 = System.currentTimeMillis();
            try {
                File result = develop(next, opt);
                listener.onDone(next.getName(), result, System.currentTimeMillis() - t0);
            } catch (Throwable t) {
                if (!cancelled) listener.onFailed(next.getName(), t);
            }
            synchronized (this) {
                queue.remove(next);
                options.remove(next);
                listener.onStatus(queue.size());
            }
        }
    }

    private File develop(File src, Develop.Options opt) throws IOException {
        waitUntilStable(src);
        outDir.mkdirs();
        File tmp = new File(outDir, outName(src.getName()) + ".TMP");
        File done = new File(outDir, outName(src.getName()) + ".JPG");
        final BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(src.getPath(), false);
        if (decoder == null) throw new IOException("cannot decode " + src.getName());
        final String path = src.getPath();
        final int fullW = decoder.getWidth(), fullH = decoder.getHeight();
        final int w = fullW / SAMPLE, h = fullH / SAMPLE;
        Develop.Source source = new Develop.Source() {
            public int width() {
                return w;
            }

            public int height() {
                return h;
            }

            public Develop.Image preview(int factor) throws IOException {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = SAMPLE * factor;
                Bitmap b = BitmapFactory.decodeFile(path, o);
                if (b == null) throw new IOException("preview decode failed");
                int[] px = new int[b.getWidth() * b.getHeight()];
                b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                Develop.Image img = new Develop.Image(b.getWidth(), b.getHeight(), px);
                b.recycle();
                return img;
            }

            public void read(int y, int count, int[] rgb) throws IOException {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = SAMPLE;
                Rect r = new Rect(0, y * SAMPLE, fullW, Math.min(fullH, (y + count) * SAMPLE));
                Bitmap b = decoder.decodeRegion(r, o);
                if (b == null) throw new IOException("region decode failed at row " + y);
                int bw = Math.min(w, b.getWidth()), bh = Math.min(count, b.getHeight());
                for (int row = 0; row < count; row++) {
                    int srcRow = Math.min(row, bh - 1);
                    b.getPixels(rgb, row * w, w, 0, srcRow, bw, 1);
                    for (int x = bw; x < w; x++) rgb[row * w + x] = rgb[row * w + bw - 1];
                }
                b.recycle();
            }
        };
        byte[] exif = null;
        try {
            exif = Develop.readExif(new FileInputStream(src));
        } catch (IOException ignored) {
        }
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
        boolean ok = false;
        try {
            new Develop(opt).process(source, new Develop.JpegWriter(os, w, h, 92, exif), new Develop.Gate() {
                public boolean proceed() {
                    while (!cancelled && listener.cameraBusy()) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                    return !cancelled;
                }
            });
            ok = true;
        } finally {
            os.close();
            decoder.recycle();
            if (!ok) tmp.delete();
        }
        done.delete();
        if (!tmp.renameTo(done)) throw new IOException("could not rename " + tmp.getName());
        return done;
    }

    /** The camera may still be writing the JPEG when it is queued. */
    private void waitUntilStable(File f) throws IOException {
        long last = -1;
        for (int i = 0; i < 60 && !cancelled; i++) {
            long size = f.length();
            if (size > 0 && size == last && !listener.cameraBusy()) return;
            last = size;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        if (cancelled) throw new IOException("cancelled");
    }

    /** 8.3-safe output name: "DV" plus the photo's number, e.g. JES05218.JPG -> DV05218. */
    static String outName(String photo) {
        StringBuilder digits = new StringBuilder();
        for (char c : photo.toCharArray()) {
            if (c == '.') break;
            if (Character.isDigit(c)) digits.append(c);
        }
        String d = digits.length() > 6 ? digits.substring(digits.length() - 6) : digits.toString();
        return "DV" + (d.length() > 0 ? d : "0");
    }

    private void cleanTemp() {
        File[] files = outDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().toUpperCase().endsWith(".TMP")) f.delete();
        }
    }
}
