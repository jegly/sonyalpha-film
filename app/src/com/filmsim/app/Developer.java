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
 * Background queue that makes developed copies (halation, grain, light leaks, and film looks chosen in
 * playback) in FILMSIM/DEV, at a half (Large) or quarter (Small) of the photo's width and height.
 * A RAW file is developed from the JPEG preview stored inside it. The original photo is only ever read.
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

    private static final long MB = 1024 * 1024;
    /** BitmapFactory.Options.inMutable: on the camera (Android 4.1) but not in the old SDK the app is built with. */
    private static final java.lang.reflect.Field IN_MUTABLE = inMutableField();

    private static java.lang.reflect.Field inMutableField() {
        try {
            return BitmapFactory.Options.class.getField("inMutable");
        } catch (Throwable t) {
            return null;
        }
    }

    private final File outDir;
    private final Listener listener;
    /** One copy to make: the photo, what to do to it, and the output name without extension. */
    private static class Job {
        final File src;
        final Develop.Options opt;
        final String out;

        Job(File src, Develop.Options opt, String out) {
            this.src = src;
            this.opt = opt;
            this.out = out;
        }
    }

    /** Second letter of a copy's name: V for the first copy, then 2-9 and A-Z for further versions. */
    private static final String SLOTS = "V23456789ABCDEFGHJKLMNPQRSTUWXYZ";

    private final LinkedList<Job> queue = new LinkedList<Job>();
    private volatile boolean cancelled;
    private Thread worker;
    /** Size, effects and time per stage of the last copy, for the log. */
    public volatile String lastTiming = "";
    /** How much memory the camera gives the app, and which path was used. */
    public volatile String memNote = "";

    public Developer(File outDir, Listener listener) {
        this.outDir = outDir;
        this.listener = listener;
        cleanTemp();
    }

    /** The copy made right after a shot: always DVnnnnn.JPG. */
    public synchronized void add(File jpeg, Develop.Options opt) {
        String out = outName(jpeg.getName());
        if (queued(out)) return;
        enqueue(new Job(jpeg, opt, out));
    }

    /** A copy asked for in playback (JPEG or RAW). Each one gets its own name, which is returned. */
    public synchronized String addCopy(File photo, Develop.Options opt) {
        String out = null;
        for (int i = 0; i < SLOTS.length(); i++) {
            out = outName(photo.getName(), SLOTS.charAt(i));
            if (!new File(outDir, out + ".JPG").exists() && !queued(out)) break;
        }
        enqueue(new Job(photo, opt, out));
        return out;
    }

    /** The newest copy of a photo, or null when it has none. */
    public static File newestCopy(File outDir, String photo) {
        File best = null;
        for (int i = 0; i < SLOTS.length(); i++) {
            File f = new File(outDir, outName(photo, SLOTS.charAt(i)) + ".JPG");
            if (f.isFile()) best = f;
            else if (i > 0) break;
        }
        return best;
    }

    private boolean queued(String out) {
        for (Job j : queue) {
            if (j.out.equals(out)) return true;
        }
        return false;
    }

    private void enqueue(Job job) {
        queue.add(job);
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
        }
    }

    private void loop() {
        while (true) {
            Job next;
            synchronized (this) {
                if (queue.isEmpty() || cancelled) {
                    worker = null;
                    queue.clear();
                    listener.onStatus(0);
                    return;
                }
                next = queue.peek();
            }
            long t0 = System.currentTimeMillis();
            try {
                File result = develop(next);
                listener.onDone(next.src.getName(), result, System.currentTimeMillis() - t0);
            } catch (Throwable t) {
                if (!cancelled) listener.onFailed(next.src.getName(), t);
            }
            synchronized (this) {
                queue.remove(next);
                listener.onStatus(queue.size());
            }
        }
    }

    private File develop(Job job) throws IOException {
        Develop.Options opt = job.opt;
        waitUntilStable(job.src);
        outDir.mkdirs();
        File tmp = new File(outDir, job.out + ".TMP");
        File done = new File(outDir, job.out + ".JPG");
        File src = job.src;
        File preview = null;
        if (src.getName().toUpperCase().endsWith(".ARW")) {
            // a RAW file: work from the JPEG preview stored inside it, copied out to a scratch file
            byte[] jpeg = ArwPreview.read(src);
            if (jpeg == null) throw new IOException("no preview inside " + src.getName());
            preview = new File(outDir, job.out + ".PRV");
            FileOutputStream fo = new FileOutputStream(preview);
            try {
                fo.write(jpeg);
            } finally {
                fo.close();
            }
            src = preview;
        }
        byte[] exif = null;
        try {
            exif = Develop.readExif(new FileInputStream(src));
        } catch (IOException ignored) {
        }
        boolean ok = false;
        try {
            String how;
            try {
                how = developNative(src, tmp, opt, exif);
            } catch (OutOfMemoryError e) {
                // ran out part way through: start again the slow way, which needs very little memory
                memNote += ", out of memory, streaming";
                tmp.delete();
                how = null;
            }
            if (how == null) how = developStreaming(src, tmp, opt, exif);
            lastTiming = (opt.look != null ? "film " : "") + (opt.halation ? "halation " : "") + (opt.grain > 0 ? "grain" + opt.grain + " " : "")
                    + (opt.lightLeak ? "leak " : "") + "| " + how;
            ok = true;
        } finally {
            if (!ok) tmp.delete();
            if (preview != null) preview.delete();
        }
        done.delete();
        if (!tmp.renameTo(done)) throw new IOException("could not rename " + tmp.getName());
        return done;
    }

    private Develop.Gate gate() {
        return new Develop.Gate() {
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
        };
    }

    /**
     * Fast path: the camera's native decoder and encoder with the whole half-size photo in one bitmap.
     * Returns null (and leaves nothing behind) when there isn't enough memory, so the caller streams instead.
     */
    private String developNative(File src, File tmp, Develop.Options opt, byte[] exif) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), bounds);
        final int sample = opt.sample;
        long bytes = (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) * 4;
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        String mem = "heap " + rt.maxMemory() / MB + " MB, free " + free / MB + " MB";
        // the grain texture and the working rows need room too
        long work = (opt.grain > 0 ? 7 : 1) * MB + 3 * MB;
        // Usual way: the decoded bitmap plus an editable copy of it. When that doesn't fit, ask the decoder for
        // an editable bitmap straight away, which needs half the memory.
        // Small sources (the preview from a RAW) always go this way: the log showed the copy failing with 19 MB free.
        boolean direct = IN_MUTABLE != null && (sample == 1 || free < bytes * 2 + 4 * MB);
        if (bounds.outWidth <= 0 || free < bytes + work || (IN_MUTABLE == null && free < bytes * 2 + 4 * MB)) {
            memNote = mem + ", streaming";
            return null;
        }
        long t0 = System.currentTimeMillis();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap decoded = null;
        BitmapRegionDecoder region = null;
        if (direct) {
            // one big block is needed; tidy the heap first so it is more likely to be there
            System.gc();
            try {
                IN_MUTABLE.setBoolean(o, true);
                decoded = BitmapFactory.decodeFile(src.getPath(), o);
            } catch (OutOfMemoryError e) {
                decoded = null;
            } catch (Exception e) {
                decoded = null;
            }
            if (decoded != null && !decoded.isMutable()) {
                // no room for the copy that would be needed
                decoded.recycle();
                memNote = mem + ", decoder gave a read-only bitmap, streaming";
                return null;
            }
        }
        if (decoded == null && !direct) try {
            // the region decoder is noticeably quicker than decodeFile on the camera
            region = BitmapRegionDecoder.newInstance(src.getPath(), false);
            if (region != null) decoded = region.decodeRegion(new Rect(0, 0, region.getWidth(), region.getHeight()), o);
        } catch (OutOfMemoryError e) {
            decoded = null;
        } finally {
            if (region != null) region.recycle();
        }
        if (decoded == null && !direct) {
            try {
                decoded = BitmapFactory.decodeFile(src.getPath(), o);
            } catch (OutOfMemoryError e) {
                decoded = null;
            }
        }
        if (decoded == null) {
            memNote = mem + ", decode failed, streaming";
            return null;
        }
        final Bitmap full;
        if (decoded.isMutable()) {
            full = decoded;
        } else {
            Bitmap copy = null;
            try {
                copy = decoded.copy(Bitmap.Config.ARGB_8888, true);
            } catch (OutOfMemoryError e) {
                copy = null;
            }
            decoded.recycle();
            if (copy == null) {
                memNote = mem + ", no room for an editable copy, streaming";
                return null;
            }
            full = copy;
        }
        memNote = mem + (direct ? ", native (direct)" : ", native");
        long t1 = System.currentTimeMillis();
        final int w = full.getWidth(), h = full.getHeight();
        try {
            Develop dev = new Develop(opt);
            dev.process(new Develop.Source() {
                public int width() {
                    return w;
                }

                public int height() {
                    return h;
                }

                public Develop.Image preview(int factor) {
                    int pw = Math.max(2, w / factor), ph = Math.max(2, h / factor);
                    Bitmap small = Bitmap.createScaledBitmap(full, pw, ph, true);
                    int[] px = new int[pw * ph];
                    small.getPixels(px, 0, pw, 0, 0, pw, ph);
                    if (small != full) small.recycle();
                    return new Develop.Image(pw, ph, px);
                }

                public void read(int y, int count, int[] rgb) {
                    full.getPixels(rgb, 0, w, 0, y, w, count);
                }
            }, new Develop.Sink() {
                private int row;

                public void writeRows(int[] rgb, int count) {
                    full.setPixels(rgb, 0, w, 0, row, w, count);
                    row += count;
                }

                public void finish() {
                }
            }, gate());
            long t2 = System.currentTimeMillis();
            OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
            try {
                // the camera's encoder writes its own start marker; put the original Exif right after ours
                os.write(0xFF);
                os.write(0xD8);
                if (exif != null) os.write(exif);
                full.compress(Bitmap.CompressFormat.JPEG, 92, new SkipStart(os));
            } finally {
                os.close();
            }
            long t3 = System.currentTimeMillis();
            return w + "x" + h + " native: decode " + (t1 - t0) + " ms, " + dev.timing + ", camera encode " + (t3 - t2) + " ms";
        } finally {
            full.recycle();
        }
    }

    /** Low-memory path: decodes and encodes in strips so the photo never has to fit in memory at once. */
    private String developStreaming(File src, File tmp, Develop.Options opt, byte[] exif) throws IOException {
        final BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(src.getPath(), false);
        if (decoder == null) throw new IOException("cannot decode " + src.getName());
        final String path = src.getPath();
        final int fullW = decoder.getWidth(), fullH = decoder.getHeight();
        final int sample = opt.sample;
        final int w = fullW / sample, h = fullH / sample;
        Develop.Source source = new Develop.Source() {
            public int width() {
                return w;
            }

            public int height() {
                return h;
            }

            public Develop.Image preview(int factor) throws IOException {
                // the region decoder is already set up and is quicker than decoding the file again
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample * factor;
                Bitmap b = decoder.decodeRegion(new Rect(0, 0, fullW, fullH), o);
                if (b == null) b = BitmapFactory.decodeFile(path, o);
                if (b == null) throw new IOException("preview decode failed");
                int[] px = new int[b.getWidth() * b.getHeight()];
                b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                Develop.Image img = new Develop.Image(b.getWidth(), b.getHeight(), px);
                b.recycle();
                return img;
            }

            public void read(int y, int count, int[] rgb) throws IOException {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Rect r = new Rect(0, y * sample, fullW, Math.min(fullH, (y + count) * sample));
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
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
        try {
            Develop dev = new Develop(opt);
            dev.process(source, new Develop.JpegWriter(os, w, h, 92, exif), gate());
            return w + "x" + h + " streaming: " + dev.timing;
        } finally {
            os.close();
            decoder.recycle();
        }
    }

    /** Drops the first two bytes (the encoder's own start-of-image marker). */
    private static class SkipStart extends java.io.FilterOutputStream {
        private int skip = 2;

        SkipStart(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            if (skip > 0) {
                skip--;
                return;
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (skip > 0 && len > 0) {
                skip--;
                off++;
                len--;
            }
            if (len > 0) out.write(b, off, len);
        }
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
        return outName(photo, 'V');
    }

    static String outName(String photo, char slot) {
        StringBuilder digits = new StringBuilder();
        for (char c : photo.toCharArray()) {
            if (c == '.') break;
            if (Character.isDigit(c)) digits.append(c);
        }
        String d = digits.length() > 6 ? digits.substring(digits.length() - 6) : digits.toString();
        return "D" + slot + (d.length() > 0 ? d : "0");
    }

    private void cleanTemp() {
        File[] files = outDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toUpperCase();
            if (n.endsWith(".TMP") || n.endsWith(".PRV")) f.delete();
        }
    }
}
