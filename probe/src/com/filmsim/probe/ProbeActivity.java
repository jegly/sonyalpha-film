package com.filmsim.probe;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Hardware probe for the A7 II colour pipeline.
 *
 * Logs what the ISP supports, then lets the user step through a few test looks
 * (matrix orientation, tone curve, two film looks, WB shift, colour depth) and
 * take a photo in each. Every change is undone when leaving a step and on exit.
 */
public class ProbeActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener {
    private static final String[] COLOR_TYPES = {"red", "green", "blue", "cyan", "magenta", "yellow"};
    private static final int[] IDENTITY = {1024, 0, 0, 0, 1024, 0, 0, 0, 1024};

    private static final String[] STEP_NAMES = {
        "0  Normal (nothing changed)",
        "1  Matrix test: B&W = good, all RED = transposed",
        "2  Curve test: photo negative",
        "3  Curve test: 4-level posterize",
        "4  Film: Kodak Gold 200",
        "5  Film: Lomo Cross-Process",
        "6  WB shift: max amber",
        "7  Colour depth: red max, others min",
    };

    private SurfaceHolder surfaceHolder;
    private TextView title;
    private TextView status;
    private CameraEx camera;
    private FileWriter log;
    /** Log lines held back while a photo is being saved, so the card is never written mid-capture. */
    private final List<String> pendingLog = new ArrayList<String>();
    private int step = 0;
    private boolean takingPicture;

    private boolean gammaSupported;
    private boolean matrixSupported;
    private boolean wbShiftSupported;
    private List<String> colorDepthTypes;
    private int[] origMatrix;
    private boolean origWbMode;
    private int origWbLB, origWbCC;
    private int[] origDepth = new int[COLOR_TYPES.length];
    private int maxWbLB;
    private int[] minDepth = new int[COLOR_TYPES.length];
    private int[] maxDepth = new int[COLOR_TYPES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        title = (TextView) findViewById(R.id.title);
        status = (TextView) findViewById(R.id.status);
    }

    @Override
    protected void onResume() {
        super.onResume();
        openLog();
        notifyAppInfo();
        try {
            camera = CameraEx.open(0, null);
            camera.setShutterListener(this);
        } catch (Throwable t) {
            log("CameraEx.open failed: " + t);
            status.setText("Camera open failed: " + t);
            return;
        }
        surfaceHolder.addCallback(this);
        probe();
        step = 0;
        showStep();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (camera != null) {
            restoreAll();
            surfaceHolder.removeCallback(this);
            try {
                camera.release();
            } catch (Throwable t) {
                log("release failed: " + t);
            }
            camera = null;
        }
        takingPicture = false;
        log("exit");
        flushPendingLog();
        closeLog();
    }

    // ---------------- probing ----------------

    private void probe() {
        log("==== probe " + Build.MODEL + " / " + Build.DISPLAY + " / sdk " + Build.VERSION.SDK_INT);
        try {
            Camera.Parameters sup = camera.getSupportedParameters();
            CameraEx.ParametersModifier supMod = camera.createParametersModifier(sup);
            gammaSupported = supMod.isExtendedGammaTableSupported();
            matrixSupported = supMod.isRGBMatrixSupported();
            wbShiftSupported = supMod.isWhiteBalanceShiftModeSupported();
            colorDepthTypes = supMod.getSupportedColorDepthTypes();
            log("extendedGammaTable supported: " + gammaSupported);
            log("RGBMatrix supported: " + matrixSupported);
            log("WB shift supported: " + wbShiftSupported);
            log("colorDepth types: " + colorDepthTypes);
            log("pictureControlExposureShift supported: " + safe(new Probe() {
                public Object get(CameraEx.ParametersModifier m) { return m.isPictureControlExposureShiftSupported(); }
            }, supMod));
            log("sharpnessGainMode supported: " + safe(new Probe() {
                public Object get(CameraEx.ParametersModifier m) { return m.isSharpnessGainModeSupported(); }
            }, supMod));
            log("supported params: " + sup.flatten());
        } catch (Throwable t) {
            log("supported params failed: " + t);
        }

        try {
            Camera.Parameters cur = camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(cur);
            log("current params: " + cur.flatten());
            if (matrixSupported) {
                origMatrix = m.getRGBMatrix();
                log("current RGB matrix: " + join(origMatrix));
            }
            log("contrast " + m.getContrast() + " [" + m.getMinContrast() + ".." + m.getMaxContrast() + "]");
            log("saturation " + m.getSaturation() + " [" + m.getMinSaturation() + ".." + m.getMaxSaturation() + "]");
            if (wbShiftSupported) {
                origWbMode = m.getWhiteBalanceShiftMode();
                origWbLB = m.getWhiteBalanceShiftLB();
                origWbCC = m.getWhiteBalanceShiftCC();
                maxWbLB = m.getMaxWhiteBalanceShiftLB();
                log("WB shift mode " + origWbMode + " LB " + origWbLB + " [" + m.getMinWhiteBalanceShiftLB() + ".." + maxWbLB
                        + "] CC " + origWbCC + " [" + m.getMinWhiteBalanceShiftCC() + ".." + m.getMaxWhiteBalanceShiftCC() + "]");
            }
            for (int i = 0; i < COLOR_TYPES.length; i++) {
                if (hasDepth(i)) {
                    origDepth[i] = m.getColorDepth(COLOR_TYPES[i]);
                    minDepth[i] = m.getMinColorDepth(COLOR_TYPES[i]);
                    maxDepth[i] = m.getMaxColorDepth(COLOR_TYPES[i]);
                    log("colorDepth " + COLOR_TYPES[i] + " " + origDepth[i] + " [" + minDepth[i] + ".." + maxDepth[i] + "]");
                }
            }
        } catch (Throwable t) {
            log("current params failed: " + t);
        }

        if (gammaSupported) {
            try {
                CameraEx.GammaTable existing = camera.getExtendedGammaTable();
                log("existing gamma table: " + (existing == null ? "none" : "size " + existing.getSize()));
                CameraEx.GammaTable table = camera.createGammaTable();
                log("new gamma table: " + (table == null ? "null" : "size " + table.getSize()));
                if (table != null) {
                    table.release();
                }
            } catch (Throwable t) {
                log("gamma table probe failed: " + t);
            }
        }
    }

    private interface Probe {
        Object get(CameraEx.ParametersModifier m);
    }

    private Object safe(Probe p, CameraEx.ParametersModifier m) {
        try {
            return p.get(m);
        } catch (Throwable t) {
            return "error " + t;
        }
    }

    private boolean hasDepth(int i) {
        return colorDepthTypes != null && colorDepthTypes.contains(COLOR_TYPES[i]);
    }

    // ---------------- test steps ----------------

    private void showStep() {
        title.setText(STEP_NAMES[step] + "\n< > or dial: change test   Shutter: photo   MENU: exit");
        restoreAll();
        String result;
        try {
            result = applyStep(step);
        } catch (Throwable t) {
            result = "FAILED: " + t;
        }
        log("step " + step + ": " + result);
        status.setText(result);
    }

    private String applyStep(int s) throws IOException {
        switch (s) {
            case 0:
                return "Normal";
            case 1:
                if (!matrixSupported) return "matrix not supported";
                writeMatrix(new int[] {1024, 0, 0, 1024, 0, 0, 1024, 0, 0});
                return "Colour matrix applied";
            case 2: {
                if (!gammaSupported) return "gamma not supported";
                int[] g = new int[1024];
                for (int i = 0; i < 1024; i++) g[i] = 1023 - i;
                writeGamma(g);
                return "Negative curve applied";
            }
            case 3: {
                if (!gammaSupported) return "gamma not supported";
                int[] g = new int[1024];
                for (int i = 0; i < 1024; i++) g[i] = (i / 256) * 341;
                writeGamma(g);
                return "Posterize curve applied";
            }
            case 4:
                return applyFilm("gold200.bin", "Kodak Gold 200");
            case 5:
                return applyFilm("xpro.bin", "Lomo Cross-Process");
            case 6: {
                if (!wbShiftSupported) return "WB shift not supported";
                Camera.Parameters p = camera.createEmptyParameters();
                CameraEx.ParametersModifier m = camera.createParametersModifier(p);
                m.setWhiteBalanceShiftMode(true);
                m.setWhiteBalanceShiftLB(maxWbLB);
                m.setWhiteBalanceShiftCC(0);
                camera.getNormalCamera().setParameters(p);
                return "White balance shifted to amber (" + maxWbLB + ")";
            }
            case 7: {
                Camera.Parameters p = camera.createEmptyParameters();
                CameraEx.ParametersModifier m = camera.createParametersModifier(p);
                int n = 0;
                for (int i = 0; i < COLOR_TYPES.length; i++) {
                    if (hasDepth(i)) {
                        m.setColorDepth(COLOR_TYPES[i], i == 0 ? maxDepth[i] : minDepth[i]);
                        n++;
                    }
                }
                if (n == 0) return "colour depth not supported";
                camera.getNormalCamera().setParameters(p);
                return "Colour depth changed on " + n + " colours";
            }
        }
        return "?";
    }

    private String applyFilm(String asset, String name) throws IOException {
        if (!gammaSupported || !matrixSupported) return "gamma/matrix not supported";
        DataInputStream in = new DataInputStream(getAssets().open(asset));
        int[] g = new int[1024];
        int[] mtx = new int[9];
        try {
            if (in.readInt() != 1) throw new IOException("bad film file " + asset);
            for (int i = 0; i < 1024; i++) g[i] = in.readInt();
            for (int i = 0; i < 9; i++) mtx[i] = in.readInt();
        } finally {
            in.close();
        }
        writeGamma(g);
        writeMatrix(mtx);
        log(name + " matrix " + join(mtx));
        return name + " look applied";
    }

    private void writeMatrix(int[] mtx) {
        Camera.Parameters p = camera.createEmptyParameters();
        CameraEx.ParametersModifier m = camera.createParametersModifier(p);
        m.setRGBMatrix(mtx);
        camera.getNormalCamera().setParameters(p);
    }

    private void writeGamma(int[] g) {
        byte[] buf = new byte[2048];
        for (int i = 0; i < 1024; i++) {
            buf[2 * i] = (byte) (g[i] & 0xff);
            buf[2 * i + 1] = (byte) ((g[i] >> 8) & 0xff);
        }
        CameraEx.GammaTable table = camera.createGammaTable();
        table.setPictureEffectGammaForceOff(true);
        int written = table.write(new ByteArrayInputStream(buf));
        camera.setExtendedGammaTable(table);
        table.release();
        log("gamma bytes written: " + written);
    }

    /** Undoes everything the steps can change. Each part is independent so one failure doesn't block the rest. */
    private void restoreAll() {
        if (camera == null) return;
        if (gammaSupported) {
            try {
                camera.setExtendedGammaTable(null);
            } catch (Throwable t) {
                log("restore gamma failed: " + t);
            }
        }
        if (matrixSupported) {
            try {
                writeMatrix(origMatrix != null && origMatrix.length == 9 ? origMatrix : IDENTITY);
            } catch (Throwable t) {
                log("restore matrix failed: " + t);
            }
        }
        if (wbShiftSupported) {
            try {
                Camera.Parameters p = camera.createEmptyParameters();
                CameraEx.ParametersModifier m = camera.createParametersModifier(p);
                m.setWhiteBalanceShiftMode(origWbMode);
                m.setWhiteBalanceShiftLB(origWbLB);
                m.setWhiteBalanceShiftCC(origWbCC);
                camera.getNormalCamera().setParameters(p);
            } catch (Throwable t) {
                log("restore WB shift failed: " + t);
            }
        }
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            int n = 0;
            for (int i = 0; i < COLOR_TYPES.length; i++) {
                if (hasDepth(i)) {
                    m.setColorDepth(COLOR_TYPES[i], origDepth[i]);
                    n++;
                }
            }
            if (n > 0) camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("restore colour depth failed: " + t);
        }
    }

    // ---------------- preview ----------------

    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera.getNormalCamera().setPreviewDisplay(holder);
            camera.getNormalCamera().startPreview();
            log("preview started");
        } catch (Throwable t) {
            log("preview failed: " + t);
        }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    public void surfaceDestroyed(SurfaceHolder holder) {}

    // ---------------- keys ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_RIGHT:
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
                step = (step + 1) % STEP_NAMES.length;
                showStep();
                return true;
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                step = (step + STEP_NAMES.length - 1) % STEP_NAMES.length;
                showStep();
                return true;
            case ScalarInput.ISV_KEY_S1_1:
                if (camera != null) camera.getNormalCamera().autoFocus(null);
                return true;
            case ScalarInput.ISV_KEY_S2:
                if (camera != null && !takingPicture) {
                    log("photo started in step " + step);
                    takingPicture = true;
                    try {
                        camera.burstableTakePicture();
                    } catch (Throwable t) {
                        takingPicture = false;
                        log("burstableTakePicture failed: " + t);
                    }
                }
                return true;
            case ScalarInput.ISV_KEY_PLAY:
                status.setText("Exit with MENU first, then view photos");
                return true;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_S1_1:
                if (camera != null) camera.getNormalCamera().cancelAutoFocus();
                return true;
            case ScalarInput.ISV_KEY_S2:
            case ScalarInput.ISV_KEY_PLAY:
                return true;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
                exitApp();
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Called once the camera has finished the exposure; ends the burst after one frame. */
    public void onShutter(int shutterStatus, CameraEx cameraEx) {
        try {
            cameraEx.cancelTakePicture();
        } catch (Throwable t) {
            log("cancelTakePicture failed: " + t);
        }
        runOnUiThread(new Runnable() {
            public void run() {
                takingPicture = false;
                flushPendingLog();
            }
        });
        log("photo done, shutter status " + shutterStatus);
    }

    private void exitApp() {
        if (takingPicture) {
            status.setText("Wait for the photo to finish saving");
            return;
        }
        try {
            new DAConnectionManager(this).finish();
        } catch (Throwable t) {
            log("DAConnectionManager.finish failed: " + t);
        }
        finish();
    }

    private void notifyAppInfo() {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(intent);
    }

    // ---------------- log ----------------

    private void openLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "FILMPRB");
            dir.mkdirs();
            log = new FileWriter(new File(dir, "LOG.TXT"), true);
        } catch (IOException e) {
            log = null;
        }
        flushPendingLog();
    }

    private void closeLog() {
        if (log != null) {
            try {
                log.close();
            } catch (IOException ignored) {
            }
            log = null;
        }
    }

    private synchronized void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + msg + "\n";
        if (takingPicture || log == null) {
            pendingLog.add(line);
            return;
        }
        write(line);
    }

    private synchronized void flushPendingLog() {
        if (log == null) return;
        for (String line : pendingLog) {
            write(line);
        }
        pendingLog.clear();
    }

    private void write(String line) {
        try {
            log.write(line);
            log.flush();
        } catch (IOException ignored) {
        }
    }

    private static String join(int[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
