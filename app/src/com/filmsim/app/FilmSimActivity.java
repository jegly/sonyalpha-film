package com.filmsim.app;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.sony.scalar.graphics.OptimizedImage;
import com.sony.scalar.graphics.OptimizedImageFactory;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.media.AvindexContentInfo;
import com.sony.scalar.media.MediaRecorder;
import com.sony.scalar.provider.AvindexStore;
import com.sony.scalar.sysutil.KeyStatus;
import com.sony.scalar.sysutil.ScalarInput;
import com.sony.scalar.widget.OptimizedImageView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Film Sim: applies film looks through the camera's own image pipeline (tone curve + colour matrix),
 * so the viewfinder, JPEGs and anything else the ISP renders carry the look.
 *
 * Controls (A7 II): rear dial = shutter (S/M) or exposure comp (P/A), front dial = ISO,
 * control wheel = film, Fn = menu (brand, film, strength, white balance, Kelvin, halation, grain, light leak:
 * up/down picks a row, left/right or the wheel changes it, C2 marks a favourite), centre = focus magnifier, C1 = compare with no look,
 * C2 = strength (favourite while in the menu), C3 = white balance, DISP/up = info on/off, Play = review photos, MOVIE = record video, MENU = exit.
 */
public class FilmSimActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener {
    private static final int[] IDENTITY = {1024, 0, 0, 0, 1024, 0, 0, 0, 1024};
    private static final int[] STRENGTHS = {100, 75, 50, 25};
    // Mode dial positions as reported through ScalarInput (same values as the ISV_KEY_MODE_* scan codes).
    private static final int DIAL_AUTO = 535, DIAL_SUPERIOR_AUTO = 536, DIAL_P = 537, DIAL_S = 538, DIAL_A = 539, DIAL_M = 540;
    private static final int EV_DIAL = 620;
    private static final int MAG_STEP = 100, MAG_LIMIT = 800;

    private final Handler handler = new Handler();

    private FilmLibrary library;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady;
    private TextView filmBar, exposureBar, picker, message, keyInfo;
    private View pickerPanel;

    private CameraEx camera;
    private boolean takingPicture;
    private boolean pausing;

    // Look state
    private FilmLibrary.Film film;
    private FilmLibrary.Look look;
    private int strength = 100;
    private boolean bypass;
    private boolean overlay = true;

    // Fn menu: one row per setting
    private static final String[] MENU_ROWS = {"Brand", "Film", "Strength", "WB", "Kelvin", "Halation", "Grain", "Light leak"};
    private static final int ROW_BRAND = 0, ROW_FILM = 1, ROW_STRENGTH = 2, ROW_WB = 3, ROW_KELVIN = 4, ROW_HALATION = 5,
            ROW_GRAIN = 6, ROW_LEAK = 7;
    private static final String[] GRAIN_NAMES = {"Off", "Fine", "Medium", "Heavy"};
    private static final String FAVOURITES = "Favourites";
    private boolean menuOpen;
    private int menuRow = ROW_FILM;
    private final Set<String> favourites = new LinkedHashSet<String>();
    // After-shot develop effects; any of them makes a developed copy in FILMSIM/DEV
    private boolean halation;
    private int grain;
    private boolean lightLeak;

    // White balance panel
    private static final String[] WB_ORDER = {"auto", "daylight", "shade", "cloudy-daylight", "incandescent",
            "warm-fluorescent", "fluorescent-coolwhite", "fluorescent-daywhite", "fluorescent-daylight", "flash", "color-temp"};
    private static final String WB_KELVIN = "color-temp";
    private final List<String> wbModes = new ArrayList<String>();
    private String wbMode = "auto";
    private int kelvin = 5500, minKelvin = 2500, maxKelvin = 9900;
    private String savedWb;
    private int savedKelvin;
    private int menuBrand;
    private int menuFilm;

    // Exposure state
    private String sceneMode = "";
    private List<String> supportedSceneModes = new ArrayList<String>();
    private List<Integer> isoValues = new ArrayList<Integer>();
    private int minEv, maxEv;
    private float evStep;
    private int shutterN, shutterD;
    private int autoIso;
    private int meterLevel = Integer.MIN_VALUE;

    // Focus magnifier
    private List<Integer> magLevels = new ArrayList<Integer>();
    private int magIndex = -1;
    private int magX, magY;

    // In-app playback
    /** Created only while reviewing photos: kept in the layout it stopped hidden overlays from clearing. */
    private OptimizedImageView playView;
    private RelativeLayout root;
    private TextView playInfo;
    private boolean playback;
    private final List<String> playFiles = new ArrayList<String>();
    private int playIndex;
    private OptimizedImage playImage;
    private Map<String, String> shotFilms = new HashMap<String, String>();

    // Video: the camera is reopened in movie mode for recording, then back in photo mode afterwards.
    private MediaRecorder recorder;
    private boolean movieMode;
    private boolean recording;
    private boolean writingVideo;
    private boolean startWhenReady;
    private int recTime = -1;

    // Developed copies, made in the background after each shot
    private Developer developer;
    private int developing;
    private FilmLibrary.Film shotFilm;
    private long exitConfirmUntil;

    private FileWriter log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        surfaceHolder.addCallback(this);
        filmBar = (TextView) findViewById(R.id.filmBar);
        exposureBar = (TextView) findViewById(R.id.exposureBar);
        pickerPanel = findViewById(R.id.pickerPanel);
        picker = (TextView) findViewById(R.id.picker);
        message = (TextView) findViewById(R.id.message);
        keyInfo = (TextView) findViewById(R.id.keyInfo);
        root = (RelativeLayout) findViewById(R.id.root);
        playInfo = (TextView) findViewById(R.id.playInfo);
        developer = new Developer(new File(new File(Environment.getExternalStorageDirectory(), "FILMSIM"), "DEV"),
                new Developer.Listener() {
                    public boolean cameraBusy() {
                        return takingPicture || recording || writingVideo;
                    }

                    public void onStatus(final int queued) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                developing = queued;
                                refreshFilmBar();
                            }
                        });
                    }

                    public void onDone(final String source, final File result, final long millis) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                log("developed " + source + " -> " + result.getName() + " in " + millis + " ms");
                                showMessage("Developed copy saved: " + result.getName() + " (" + (millis / 1000) + " s)");
                            }
                        });
                    }

                    public void onFailed(final String source, final Throwable error) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                log("develop " + source + " failed: " + error);
                                showMessage("Developing failed: " + error.getMessage());
                            }
                        });
                    }
                });
        try {
            library = new FilmLibrary(getAssets());
        } catch (IOException e) {
            message.setText("Could not load films: " + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pausing = false;
        openLog();
        notifyAppInfo();
        restorePrefs();
        if (!openCamera()) return;
        readCapabilities();
        startShooting();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausing = true;
        developer.cancel();
        handler.removeCallbacksAndMessages(null);
        savePrefs();
        closePlayback(false);
        closeCamera();
        movieMode = false;
        writingVideo = false;
        takingPicture = false;
        log("exit");
        closeLog();
    }

    /** Settings that have to be (re)applied every time the camera is opened. */
    private void startShooting() {
        if (savedWb != null) setWhiteBalance(savedWb, savedKelvin, false);
        applyModeDial();
        applyEvDial();
        applyLook();
        refreshAll();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 1000);
    }

    private boolean openCamera() {
        try {
            if (movieMode) {
                CameraEx.OpenOptions opts = new CameraEx.OpenOptions();
                opts.setRecordingMode(1);
                camera = CameraEx.open(0, opts);
            } else {
                camera = CameraEx.open(0, null);
            }
            camera.setShutterListener(this);
            camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
                public void onShutterSpeedChange(final CameraEx.ShutterSpeedInfo info, CameraEx c) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            shutterN = info.currentShutterSpeed_n;
                            shutterD = info.currentShutterSpeed_d;
                            refreshExposureBar();
                        }
                    });
                }
            });
            camera.setAutoISOSensitivityListener(new CameraEx.AutoISOSensitivityListener() {
                public void onChanged(final int iso, CameraEx c) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            autoIso = iso;
                            refreshExposureBar();
                        }
                    });
                }
            });
            camera.setProperExposureLevelCallback(new CameraEx.ProperExposureLevelCallback() {
                public void onControlLevelNotify(final int level, CameraEx c) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            meterLevel = level;
                            refreshExposureBar();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            log("CameraEx.open failed: " + t);
            message.setText("Camera open failed: " + t);
            camera = null;
            return false;
        }
        if (movieMode && !openRecorder()) {
            movieMode = false;
            closeCamera();
            return openCamera();
        }
        if (surfaceReady) startPreview();
        return true;
    }

    private boolean openRecorder() {
        try {
            recorder = new MediaRecorder();
            recorder.setVideoSource(1);
            recorder.setOutputMedia(AvindexStore.getExternalMediaIds()[0]);
            recorder.setCamera(camera);
            recorder.setOnRecordListener(new MediaRecorder.OnRecordListener() {
                public void onStarted(final boolean ok, MediaRecorder mr) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            recording = ok;
                            recTime = 0;
                            if (!ok) showMessage("Could not start recording");
                            log("recording started: " + ok);
                            refreshFilmBar();
                        }
                    });
                }

                public void onStopped(MediaRecorder mr) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            recording = false;
                            log("recording stopped, last time " + recTime);
                            refreshFilmBar();
                            backToPhotosWhenWritten(0);
                        }
                    });
                }
            });
            recorder.setOnStreamWriteListener(new MediaRecorder.OnStreamWriteListener() {
                public void onStarted(MediaRecorder mr) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            writingVideo = true;
                        }
                    });
                }

                public void onCompleted(MediaRecorder mr) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            writingVideo = false;
                            refreshFilmBar();
                        }
                    });
                }
            });
            recorder.setOnRecTimeListener(new MediaRecorder.OnRecTimeListener() {
                public void onChanged(final int time, MediaRecorder mr) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            recTime = time;
                            refreshFilmBar();
                        }
                    });
                }
            });
            recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                public void onError(final int code, MediaRecorder mr) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            log("recorder error " + code);
                            showMessage("Video error " + code);
                            recording = false;
                            refreshFilmBar();
                        }
                    });
                }
            });
            recorder.prepare();
            log("recorder prepared");
            return true;
        } catch (Throwable t) {
            log("recorder setup failed: " + t);
            showMessage("Video not available: " + t);
            releaseRecorder();
            return false;
        }
    }

    private void releaseRecorder() {
        if (recorder == null) return;
        if (recording) {
            try {
                recorder.stop();
            } catch (Throwable t) {
                log("recorder stop failed: " + t);
            }
            recording = false;
        }
        try {
            recorder.release();
        } catch (Throwable t) {
            log("recorder release failed: " + t);
        }
        recorder = null;
    }

    /** Reopens the camera in photo or movie mode, keeping the film look. */
    private void switchMode(boolean movie) {
        if (movie == movieMode && camera != null) return;
        closeCamera();
        movieMode = movie;
        if (openCamera()) startShooting();
        refreshFilmBar();
    }

    private void toggleRecording() {
        if (recording) {
            try {
                recorder.stop();
            } catch (Throwable t) {
                log("stop recording failed: " + t);
                showMessage("Could not stop recording: " + t);
            }
            return;
        }
        if (takingPicture || writingVideo) {
            showMessage("Wait for saving to finish");
            return;
        }
        if (!movieMode) {
            showMessage("Switching to video...");
            switchMode(true);
            if (!movieMode) return;
            startWhenReady = true;
            handler.postDelayed(startRecording, 1500);
            return;
        }
        startRecording.run();
    }

    private final Runnable startRecording = new Runnable() {
        public void run() {
            startWhenReady = false;
            if (recorder == null || recording) return;
            try {
                recorder.start();
            } catch (Throwable t) {
                log("start recording failed: " + t);
                showMessage("Could not start recording: " + t);
            }
        }
    };

    /** After a clip stops, wait for the file to finish writing, then go back to photo mode. */
    private void backToPhotosWhenWritten(final int waited) {
        handler.postDelayed(new Runnable() {
            public void run() {
                if (recording || startWhenReady) return;
                if (writingVideo && waited < 60) {
                    backToPhotosWhenWritten(waited + 1);
                    return;
                }
                if (movieMode) switchMode(false);
            }
        }, 500);
    }

    /** Undoes the look and hands the camera back (on exit, and while reviewing photos, like Sony's apps). */
    private void closeCamera() {
        if (camera == null) return;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(startRecording);
        startWhenReady = false;
        releaseRecorder();
        stopMagnifier();
        clearLook();
        try {
            camera.getNormalCamera().stopPreview();
        } catch (Throwable t) {
            log("stopPreview failed: " + t);
        }
        try {
            camera.release();
        } catch (Throwable t) {
            log("release failed: " + t);
        }
        camera = null;
    }

    // ---------------- capabilities ----------------

    @SuppressWarnings("rawtypes")
    private void readCapabilities() {
        log("==== Film Sim on " + Build.MODEL + " sdk " + Build.VERSION.SDK_INT);
        try {
            Camera.Parameters sup = camera.getSupportedParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(sup);
            supportedSceneModes = sup.getSupportedSceneModes();
            if (supportedSceneModes == null) supportedSceneModes = new ArrayList<String>();
            isoValues.clear();
            List iso = m.getSupportedISOSensitivities();
            if (iso != null) {
                for (Object o : iso) {
                    try {
                        isoValues.add(Integer.parseInt(o.toString()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Collections.sort(isoValues);
            magLevels.clear();
            List mag = m.getSupportedPreviewMagnification();
            if (mag != null) {
                for (Object o : mag) {
                    int v = Integer.parseInt(o.toString());
                    if (v > 0) magLevels.add(v);
                }
            }
            Collections.sort(magLevels);
            wbModes.clear();
            List<String> wb = sup.getSupportedWhiteBalance();
            for (String mode : WB_ORDER) {
                if (wb == null || wb.contains(mode)) wbModes.add(mode);
            }
            try {
                minKelvin = m.getMinColorTemperatureForWhiteBalance();
                maxKelvin = m.getMaxColorTemperatureForWhiteBalance();
            } catch (Throwable ignored) {
            }
            log("scene modes " + supportedSceneModes + " | iso " + isoValues + " | magnifier " + magLevels
                    + " | wb " + wb + " kelvin " + minKelvin + ".." + maxKelvin);
        } catch (Throwable t) {
            log("readCapabilities failed: " + t);
        }
        try {
            Camera.Parameters p = camera.getNormalCamera().getParameters();
            minEv = p.getMinExposureCompensation();
            maxEv = p.getMaxExposureCompensation();
            evStep = p.getExposureCompensationStep();
            log("ev " + minEv + ".." + maxEv + " step " + evStep);
        } catch (Throwable t) {
            log("ev range failed: " + t);
        }
    }

    // ---------------- film look ----------------

    /** Writes the current film (at the current strength) into the pipeline, or clears it. */
    private void applyLook() {
        if (camera == null || pausing) return;
        if (takingPicture) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    applyLook();
                }
            }, 300);
            return;
        }
        if (film == null || bypass) {
            clearLook();
            return;
        }
        try {
            if (look == null) look = FilmLibrary.load(getAssets(), film);
            double a = strength / 100.0;
            int[] g = new int[1024];
            for (int i = 0; i < 1024; i++) g[i] = (int) Math.round((1 - a) * i + a * look.gamma[i]);
            int[] mtx = new int[9];
            for (int i = 0; i < 9; i++) mtx[i] = (int) Math.round((1 - a) * IDENTITY[i] + a * look.matrix[i]);
            writeGamma(g);
            writeMatrix(mtx);
        } catch (Throwable t) {
            log("applyLook failed: " + t);
            showMessage("Could not apply film: " + t);
        }
    }

    private void clearLook() {
        if (camera == null) return;
        try {
            camera.setExtendedGammaTable(null);
        } catch (Throwable t) {
            log("clear gamma failed: " + t);
        }
        try {
            writeMatrix(IDENTITY);
        } catch (Throwable t) {
            log("clear matrix failed: " + t);
        }
    }

    private void writeGamma(int[] g) {
        byte[] buf = new byte[2048];
        for (int i = 0; i < 1024; i++) {
            buf[2 * i] = (byte) (g[i] & 0xff);
            buf[2 * i + 1] = (byte) ((g[i] >> 8) & 0xff);
        }
        CameraEx.GammaTable table = camera.createGammaTable();
        table.setPictureEffectGammaForceOff(true);
        table.write(new ByteArrayInputStream(buf));
        camera.setExtendedGammaTable(table);
        table.release();
    }

    private void writeMatrix(int[] mtx) {
        Camera.Parameters p = camera.createEmptyParameters();
        camera.createParametersModifier(p).setRGBMatrix(mtx);
        camera.getNormalCamera().setParameters(p);
    }

    private void selectFilm(FilmLibrary.Film f) {
        if (f == film) return;
        film = f;
        look = null;
        bypass = false;
        applyLook();
        refreshFilmBar();
        if (f != null && f.name.contains("800T")) {
            showMessage("Tip: Tungsten or ~3200K WB (right button) for night");
        }
    }

    /** Next/previous film across the whole library (brand order), used by the control wheel. */
    private void stepFilm(int dir) {
        List<FilmLibrary.Film> all = library.all();
        if (all.isEmpty()) return;
        int i = film == null ? (dir > 0 ? -1 : 0) : all.indexOf(film);
        i = (i + dir + all.size()) % all.size();
        selectFilm(all.get(i));
    }

    private void cycleStrength() {
        int i = 0;
        for (int k = 0; k < STRENGTHS.length; k++) if (STRENGTHS[k] == strength) i = k;
        strength = STRENGTHS[(i + 1) % STRENGTHS.length];
        applyLook();
        refreshFilmBar();
        showMessage("Strength " + strength + "%");
    }

    // ---------------- picker ----------------

    private static String favKey(FilmLibrary.Film f) {
        return f.brand + "|" + f.name;
    }

    private boolean isFavourite(FilmLibrary.Film f) {
        return f != null && favourites.contains(favKey(f));
    }

    /** Brands in menu order; "Favourites" comes first once anything is marked. */
    private List<String> menuBrands() {
        List<String> out = new ArrayList<String>();
        if (!favourites.isEmpty()) out.add(FAVOURITES);
        out.addAll(library.brands());
        return out;
    }

    private List<FilmLibrary.Film> menuFilms(String brand) {
        if (!FAVOURITES.equals(brand)) return library.byBrand(brand);
        List<FilmLibrary.Film> out = new ArrayList<FilmLibrary.Film>();
        for (FilmLibrary.Film f : library.all()) {
            if (isFavourite(f)) out.add(f);
        }
        return out;
    }

    private void openMenu(int row) {
        menuOpen = true;
        menuRow = row;
        List<String> brands = menuBrands();
        menuBrand = film != null ? Math.max(0, brands.indexOf(film.brand)) : 0;
        List<FilmLibrary.Film> list = menuFilms(brands.get(menuBrand));
        menuFilm = film != null ? Math.max(0, list.indexOf(film)) : 0;
        refreshMenu();
    }

    private void closeMenu() {
        menuOpen = false;
        refreshMenu();
    }

    private void menuMove(int dir) {
        menuRow = (menuRow + dir + MENU_ROWS.length) % MENU_ROWS.length;
        refreshMenu();
    }

    /** Left/right or the wheel: change the value on the highlighted row. */
    private void menuChange(int dir) {
        List<String> brands = menuBrands();
        switch (menuRow) {
            case ROW_BRAND:
                menuBrand = (menuBrand + dir + brands.size()) % brands.size();
                menuFilm = 0;
                scheduleMenuFilm();
                break;
            case ROW_FILM: {
                List<FilmLibrary.Film> list = menuFilms(brands.get(Math.min(menuBrand, brands.size() - 1)));
                if (!list.isEmpty()) {
                    menuFilm = (menuFilm + dir + list.size()) % list.size();
                    scheduleMenuFilm();
                }
                break;
            }
            case ROW_STRENGTH:
                strength = Math.max(0, Math.min(100, strength + dir * 5));
                applyLook();
                refreshFilmBar();
                break;
            case ROW_WB:
                if (!wbModes.isEmpty()) {
                    int i = Math.max(0, wbModes.indexOf(wbMode));
                    i = (i + dir + wbModes.size()) % wbModes.size();
                    setWhiteBalance(wbModes.get(i), kelvin, true);
                }
                break;
            case ROW_KELVIN:
                if (wbModes.contains(WB_KELVIN)) setWhiteBalance(WB_KELVIN, kelvin + dir * 100, true);
                break;
            case ROW_HALATION:
                halation = !halation;
                developHint();
                break;
            case ROW_GRAIN:
                grain = (grain + dir + GRAIN_NAMES.length) % GRAIN_NAMES.length;
                developHint();
                break;
            case ROW_LEAK:
                lightLeak = !lightLeak;
                developHint();
                break;
        }
        refreshMenu();
    }

    private boolean developOn() {
        return halation || grain > 0 || lightLeak;
    }

    private void developHint() {
        if (developOn()) showMessage("After each shot a developed copy goes to FILMSIM/DEV");
        refreshFilmBar();
    }

    /** C2 in the menu: add or remove the highlighted film from Favourites. */
    private void toggleFavourite() {
        List<String> brands = menuBrands();
        String brand = brands.get(Math.min(menuBrand, brands.size() - 1));
        List<FilmLibrary.Film> list = menuFilms(brand);
        if (list.isEmpty()) return;
        FilmLibrary.Film f = list.get(Math.min(menuFilm, list.size() - 1));
        boolean wasEmpty = favourites.isEmpty();
        if (!favourites.remove(favKey(f))) favourites.add(favKey(f));
        showMessage(isFavourite(f) ? f.name + " added to Favourites" : f.name + " removed from Favourites");
        // keep the same brand and film highlighted as the Favourites entry appears or disappears
        List<String> now = menuBrands();
        menuBrand = Math.max(0, now.indexOf(brand));
        if (FAVOURITES.equals(brand)) {
            if (favourites.isEmpty()) {
                menuBrand = Math.max(0, now.indexOf(f.brand));
                menuFilm = Math.max(0, library.byBrand(f.brand).indexOf(f));
            } else {
                menuFilm = Math.min(menuFilm, menuFilms(FAVOURITES).size() - 1);
            }
        } else if (wasEmpty != favourites.isEmpty()) {
            menuBrand = Math.max(0, now.indexOf(brand));
        }
        savePrefs();
        refreshMenu();
    }

    /** Applies the highlighted film after a short pause, so scrolling quickly doesn't flood the pipeline. */
    private void scheduleMenuFilm() {
        handler.removeCallbacks(menuApply);
        handler.postDelayed(menuApply, 250);
    }

    private final Runnable menuApply = new Runnable() {
        public void run() {
            if (!menuOpen) return;
            List<String> brands = menuBrands();
            List<FilmLibrary.Film> list = menuFilms(brands.get(Math.min(menuBrand, brands.size() - 1)));
            if (menuFilm < list.size()) selectFilm(list.get(menuFilm));
        }
    };

    private void refreshMenu() {
        if (!menuOpen && pickerPanel.getVisibility() != View.GONE) repaintSoon();
        pickerPanel.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
        if (!menuOpen) return;
        List<String> brands = menuBrands();
        menuBrand = Math.min(menuBrand, brands.size() - 1);
        String brand = brands.get(menuBrand);
        List<FilmLibrary.Film> list = menuFilms(brand);
        String filmValue;
        if (list.isEmpty()) {
            filmValue = "-";
        } else {
            FilmLibrary.Film cur = list.get(Math.min(menuFilm, list.size() - 1));
            filmValue = cur.name + "  " + stars(cur) + (isFavourite(cur) ? " \u2665" : "")
                    + "   (" + (Math.min(menuFilm, list.size() - 1) + 1) + "/" + list.size() + ")";
        }
        String[] values = {
            brand + "   (" + (menuBrand + 1) + "/" + brands.size() + ")",
            filmValue,
            strength + "%",
            wbModes.isEmpty() ? "n/a" : wbLabel(wbMode, kelvin),
            kelvin + "K" + (WB_KELVIN.equals(wbMode) ? "" : "  (sets WB)"),
            halation ? "On" : "Off",
            GRAIN_NAMES[grain],
            lightLeak ? "On  (random each shot)" : "Off",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MENU_ROWS.length; i++) {
            String label = MENU_ROWS[i];
            sb.append(i == menuRow ? "> " : "  ").append(label);
            for (int k = label.length(); k < 10; k++) sb.append(' ');
            sb.append(i == menuRow ? "<  " + values[i] + "  >" : "   " + values[i]).append('\n');
        }
        sb.append("\nup/down: row   left/right: change   Fn: close");
        if (menuRow == ROW_FILM) sb.append("\nC2: favourite   Delete: no film");
        if (menuRow >= ROW_HALATION) sb.append("\nAdded after the shot to a copy in FILMSIM/DEV (needs JPEG)");
        picker.setText(sb.toString());
    }

    private static String stars(FilmLibrary.Film f) {
        if (f.designed()) return "\u25c6";
        int s = f.stars();
        return s == 3 ? "★★★" : s == 2 ? "★★☆" : "★☆☆";
    }

    // ---------------- white balance ----------------

    private void setWhiteBalance(String mode, int k, boolean remember) {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            p.setWhiteBalance(mode);
            if (WB_KELVIN.equals(mode)) {
                k = Math.max(minKelvin, Math.min(maxKelvin, k));
                camera.createParametersModifier(p).setColorTemperatureForWhiteBalance(k);
            }
            camera.getNormalCamera().setParameters(p);
            wbMode = mode;
            if (WB_KELVIN.equals(mode)) kelvin = k;
            if (remember) {
                savedWb = mode;
                savedKelvin = kelvin;
            }
        } catch (Throwable t) {
            log("setWhiteBalance " + mode + " failed: " + t);
            showMessage("White balance failed: " + t);
        }
        refreshExposureBar();
        refreshMenu();
    }

    private static String wbLabel(String mode, int k) {
        if ("auto".equals(mode)) return "AWB";
        if ("daylight".equals(mode)) return "Daylight";
        if ("shade".equals(mode)) return "Shade";
        if ("cloudy-daylight".equals(mode)) return "Cloudy";
        if ("incandescent".equals(mode)) return "Tungsten";
        if ("warm-fluorescent".equals(mode)) return "Fluor. warm";
        if ("fluorescent-coolwhite".equals(mode)) return "Fluor. cool";
        if ("fluorescent-daywhite".equals(mode)) return "Fluor. day white";
        if ("fluorescent-daylight".equals(mode)) return "Fluor. daylight";
        if ("flash".equals(mode)) return "Flash";
        if (WB_KELVIN.equals(mode)) return k + "K";
        return mode;
    }

    // ---------------- exposure ----------------

    private void applyModeDial() {
        try {
            KeyStatus ks = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_MODE_DIAL);
            if (ks == null || ks.valid != 1) return;
            String mode = null;
            switch (ks.status) {
                case DIAL_P: mode = "program-auto"; break;
                case DIAL_S: mode = "shutter-speed"; break;
                case DIAL_A: mode = "aperture-priority"; break;
                case DIAL_M: mode = "manual-exposure"; break;
                case DIAL_AUTO:
                case DIAL_SUPERIOR_AUTO: mode = supportedSceneModes.contains("auto") ? "auto" : "program-auto"; break;
            }
            if (mode == null) {
                showMessage("Set the mode dial to P, A, S or M");
                return;
            }
            if (!supportedSceneModes.isEmpty() && !supportedSceneModes.contains(mode)) {
                log("scene mode " + mode + " not supported");
                return;
            }
            Camera.Parameters p = camera.createEmptyParameters();
            p.setSceneMode(mode);
            camera.getNormalCamera().setParameters(p);
            log("mode dial " + ks.status + " -> " + mode);
        } catch (Throwable t) {
            log("applyModeDial failed: " + t);
        }
        readExposure();
    }

    private void applyEvDial() {
        try {
            KeyStatus ks = ScalarInput.getKeyStatus(EV_DIAL);
            if (ks == null || ks.valid != 1) return;
            int pos = ks.status;
            if ((pos & 512) == 512) pos = -(pos - 512);
            setEvIndex(pos);
        } catch (Throwable t) {
            log("applyEvDial failed: " + t);
        }
    }

    private void setEvIndex(int index) {
        index = Math.max(minEv, Math.min(maxEv, index));
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            p.setExposureCompensation(index);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("setExposureCompensation failed: " + t);
        }
        readExposure();
    }

    private void stepEv(int dir) {
        try {
            setEvIndex(camera.getNormalCamera().getParameters().getExposureCompensation() + dir);
        } catch (Throwable t) {
            log("stepEv failed: " + t);
        }
    }

    private void stepShutter(int dir) {
        try {
            if (dir > 0) camera.incrementShutterSpeed();
            else camera.decrementShutterSpeed();
        } catch (Throwable t) {
            log("shutter step failed: " + t);
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                readExposure();
            }
        }, 100);
    }

    private void stepIso(int dir) {
        if (isoValues.isEmpty()) return;
        try {
            int cur = camera.createParametersModifier(camera.getNormalCamera().getParameters()).getISOSensitivity();
            int i = isoValues.indexOf(cur);
            if (i < 0) {
                i = 0;
                while (i < isoValues.size() - 1 && isoValues.get(i) < cur) i++;
            }
            i = Math.max(0, Math.min(isoValues.size() - 1, i + dir));
            Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setISOSensitivity(isoValues.get(i));
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("stepIso failed: " + t);
        }
        readExposure();
    }

    private boolean shutterControlsShutter() {
        return "shutter-speed".equals(sceneMode) || "manual-exposure".equals(sceneMode);
    }

    private int iso;
    private int evIndex;

    @SuppressWarnings("unchecked")
    private void readExposure() {
        if (camera == null || takingPicture) return;
        try {
            Camera.Parameters p = camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            sceneMode = p.getSceneMode() == null ? "" : p.getSceneMode();
            iso = m.getISOSensitivity();
            evIndex = p.getExposureCompensation();
            if (p.getWhiteBalance() != null) wbMode = p.getWhiteBalance();
            try {
                int k = m.getColorTemperatureForWhiteBalance();
                if (k > 0) kelvin = k;
            } catch (Throwable ignored) {
            }
            Pair<Integer, Integer> ss = m.getShutterSpeed();
            if (ss != null) {
                shutterN = ss.first;
                shutterD = ss.second;
            }
            if ("manual-exposure".equals(sceneMode)) camera.getProperExposureLevel();
        } catch (Throwable t) {
            log("readExposure failed: " + t);
        }
        refreshExposureBar();
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            readExposure();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------- focus magnifier ----------------

    private void cycleMagnifier() {
        if (magLevels.isEmpty()) {
            showMessage("Focus magnifier not available");
            return;
        }
        magIndex++;
        if (magIndex >= magLevels.size()) {
            stopMagnifier();
        } else {
            moveMagnifier(0, 0);
        }
        refreshExposureBar();
    }

    private void moveMagnifier(int dx, int dy) {
        magX = Math.max(-MAG_LIMIT, Math.min(MAG_LIMIT, magX + dx));
        magY = Math.max(-MAG_LIMIT, Math.min(MAG_LIMIT, magY + dy));
        try {
            camera.setPreviewMagnification(magLevels.get(magIndex), new Pair<Integer, Integer>(magX, magY));
        } catch (Throwable t) {
            log("magnifier failed: " + t);
            showMessage("Magnifier failed: " + t);
            magIndex = -1;
        }
    }

    private void stopMagnifier() {
        if (magIndex < 0) return;
        magIndex = -1;
        magX = magY = 0;
        try {
            camera.stopPreviewMagnification();
        } catch (Throwable t) {
            log("stop magnifier failed: " + t);
        }
        refreshExposureBar();
    }

    // ---------------- capture ----------------

    private void shoot() {
        if (camera == null || takingPicture) return;
        stopMagnifier();
        takingPicture = true;
        try {
            camera.burstableTakePicture();
        } catch (Throwable t) {
            takingPicture = false;
            log("burstableTakePicture failed: " + t);
        }
    }

    /** The exposure is finished: end the burst after one frame. */
    public void onShutter(int status, CameraEx cameraEx) {
        try {
            cameraEx.cancelTakePicture();
        } catch (Throwable t) {
            log("cancelTakePicture failed: " + t);
        }
        final String label = film == null || bypass ? "No film" : film.brand + " " + film.name + " " + strength + "%";
        shotFilm = bypass ? null : film;
        final long shotTime = System.currentTimeMillis();
        runOnUiThread(new Runnable() {
            public void run() {
                takingPicture = false;
                handler.postDelayed(new Runnable() {
                    public void run() {
                        recordShot(label, shotTime);
                    }
                }, 3000);
            }
        });
    }

    /** Appends "photo file, film, time" to FILMSIM/SHOTS.TXT once the photo has been written. */
    private void recordShot(String label, long shotTime) {
        if (takingPicture) {
            final String l = label;
            final long t = shotTime;
            handler.postDelayed(new Runnable() {
                public void run() {
                    recordShot(l, t);
                }
            }, 1000);
            return;
        }
        File newest = newestPhoto(new File(Environment.getExternalStorageDirectory(), "DCIM"));
        String name = newest != null && newest.lastModified() > shotTime - 60000 ? newest.getName() : "?";
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "FILMSIM");
            dir.mkdirs();
            FileWriter w = new FileWriter(new File(dir, "SHOTS.TXT"), true);
            try {
                w.write(name + "\t" + label + "\t" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(shotTime)) + "\n");
            } finally {
                w.close();
            }
        } catch (IOException e) {
            log("shot log failed: " + e);
        }
        if (developOn()) {
            File jpeg = newestJpeg(new File(Environment.getExternalStorageDirectory(), "DCIM"));
            if (jpeg != null && jpeg.lastModified() > shotTime - 60000) {
                Develop.Options opt = new Develop.Options();
                opt.halation = halation;
                opt.grain = grain;
                opt.lightLeak = lightLeak;
                opt.monochrome = shotFilm != null && "Black & white".equals(shotFilm.type);
                opt.seed = jpeg.getName().hashCode() * 31L + shotTime;
                developer.add(jpeg, opt);
            } else {
                showMessage("Developing needs JPEG: set Quality to RAW & JPEG");
            }
        }
    }

    private static File newestJpeg(File dcim) {
        File best = null;
        File[] dirs = dcim.listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().toUpperCase().endsWith(".JPG") && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
        }
        return best;
    }

    private static File newestPhoto(File dcim) {
        File best = null;
        File[] dirs = dcim.listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toUpperCase();
                if ((n.endsWith(".JPG") || n.endsWith(".ARW")) && (best == null || f.lastModified() > best.lastModified())) {
                    best = f;
                }
            }
        }
        return best;
    }

    // ---------------- playback ----------------

    private void openPlayback() {
        if (movieMode) switchMode(false);
        if (takingPicture) {
            showMessage("Wait for the photo to finish saving");
            return;
        }
        playFiles.clear();
        Cursor c = null;
        try {
            c = getContentResolver().query(AvindexStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] {AvindexStore.Images.Media.DATA}, null, null,
                    AvindexStore.Images.Media.CONTENT_CREATED_UTC_DATE_TIME + " DESC");
            while (c != null && c.moveToNext()) playFiles.add(c.getString(0));
        } catch (Throwable t) {
            log("playback query failed: " + t);
        } finally {
            if (c != null) c.close();
        }
        if (playFiles.isEmpty()) {
            showMessage("No photos on the card");
            return;
        }
        closeMenu();
        closeCamera();
        shotFilms = readShotLog();
        playback = true;
        playIndex = 0;
        surfaceView.setVisibility(View.GONE);
        filmBar.setVisibility(View.GONE);
        exposureBar.setVisibility(View.GONE);
        playView = new OptimizedImageView(this);
        // just above the live view surface, below the info line
        root.addView(playView, 1, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playInfo.setVisibility(View.VISIBLE);
        showPhoto();
    }

    /** Leaves playback; with reopen, takes the camera back and restores the film look. */
    private void closePlayback(boolean reopen) {
        if (!playback) return;
        playback = false;
        try {
            if (playView != null) playView.setOptimizedImage(null);
        } catch (Throwable t) {
            log("clear image failed: " + t);
        }
        releasePlayImage();
        repaintSoon();
        if (playView != null) {
            root.removeView(playView);
            try {
                playView.release();
            } catch (Throwable t) {
                log("release image view failed: " + t);
            }
            playView = null;
        }
        playInfo.setVisibility(View.GONE);
        surfaceView.setVisibility(View.VISIBLE);
        if (reopen && openCamera()) startShooting();
    }

    private void releasePlayImage() {
        if (playImage != null) {
            try {
                playImage.release();
            } catch (Throwable ignored) {
            }
            playImage = null;
        }
    }

    private boolean onPlaybackKey(int scan) {
        switch (scan) {
            case ScalarInput.ISV_KEY_RIGHT:
            case ScalarInput.ISV_KEY_DOWN:
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
            case ScalarInput.ISV_DIAL_3_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
                stepPhoto(1);
                return true;
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
            case ScalarInput.ISV_DIAL_3_COUNTERCW:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                stepPhoto(-1);
                return true;
            case ScalarInput.ISV_KEY_PLAY:
            case ScalarInput.ISV_KEY_S1_1:
            case ScalarInput.ISV_KEY_S2:
                closePlayback(true);
                return true;
        }
        return true;
    }

    /** dir 1 = older photo, -1 = newer. */
    private void stepPhoto(int dir) {
        int i = playIndex + dir;
        if (i < 0 || i >= playFiles.size()) return;
        playIndex = i;
        showPhoto();
    }

    private void showPhoto() {
        String data = playFiles.get(playIndex);
        OptimizedImage img = null;
        String info = "";
        try {
            OptimizedImageFactory.Options o = new OptimizedImageFactory.Options();
            o.imageType = 3;
            o.bBasicInfo = true;
            o.bCamInfo = true;
            img = OptimizedImageFactory.decodeImage(data, o);
            AvindexContentInfo ci = o.outContentInfo;
            if (ci != null) {
                double t = ci.getAttributeDouble(AvindexContentInfo.TAG_EXPOSURE_TIME, 0);
                int iso = ci.getAttributeInt(AvindexContentInfo.TAG_ISO, 0);
                if (t > 0) info += (t < 1 ? "1/" + Math.round(1 / t) : String.format("%.1f\"", t)) + "   ";
                if (iso > 0) info += "ISO " + iso + "   ";
            }
            playView.setDisplayType(OptimizedImageView.DisplayType.DISPLAY_TYPE_CENTER_INNER);
            playView.setOptimizedImage(img);
        } catch (Throwable t) {
            log("showPhoto failed: " + t);
        }
        releasePlayImage();
        playImage = img;
        String name = new File(data).getName();
        String filmUsed = shotFilms.get(name.toUpperCase());
        playInfo.setText(name + "   " + (filmUsed != null ? filmUsed + "   " : "") + info
                + (playIndex + 1) + "/" + playFiles.size() + "      < > browse   Play: back");
        if (img == null) playInfo.append("\n(could not show this file)");
    }

    /** Photo file name -> film it was shot with, from FILMSIM/SHOTS.TXT. */
    private static Map<String, String> readShotLog() {
        Map<String, String> map = new HashMap<String, String>();
        File f = new File(new File(Environment.getExternalStorageDirectory(), "FILMSIM"), "SHOTS.TXT");
        if (!f.isFile()) return map;
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) map.put(parts[0].toUpperCase(), parts[1]);
                }
            } finally {
                br.close();
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    // ---------------- preview ----------------

    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (camera != null) startPreview();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    private void startPreview() {
        try {
            camera.getNormalCamera().setPreviewDisplay(surfaceHolder);
            camera.getNormalCamera().startPreview();
        } catch (Throwable t) {
            log("preview failed: " + t);
        }
    }

    // ---------------- keys ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        log("down " + scan + " code " + keyCode + " rep " + event.getRepeatCount() + " t " + event.getEventTime() + " menu " + menuOpen);
        if (playback) return onPlaybackKey(scan);
        if (camera == null) return super.onKeyDown(keyCode, event);
        if (scan == ScalarInput.ISV_KEY_MODE_DIAL || (scan >= 534 && scan <= 551)) {
            applyModeDial();
            return true;
        }
        switch (scan) {
            case ScalarInput.ISV_KEY_S1_1:
                closeMenu();
                stopMagnifier();
                try {
                    camera.getNormalCamera().autoFocus(null);
                } catch (Throwable t) {
                    log("autoFocus failed: " + t);
                }
                return true;
            case ScalarInput.ISV_KEY_S1_2:
                return true;
            case ScalarInput.ISV_KEY_S2:
                closeMenu();
                if (recording) return true;
                if (movieMode) {
                    switchMode(false);
                    showMessage("Photo mode");
                    return true;
                }
                shoot();
                return true;
            case ScalarInput.ISV_KEY_STASTOP:
            case ScalarInput.ISV_KEY_2ND_STASTOP:
                toggleRecording();
                return true;
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_COUNTERCW: {
                int dir = scan == ScalarInput.ISV_DIAL_2_CLOCKWISE ? 1 : -1;
                if (shutterControlsShutter()) stepShutter(dir);
                else stepEv(dir);
                return true;
            }
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_COUNTERCW: {
                stepIso(scan == ScalarInput.ISV_DIAL_1_CLOCKWISE ? 1 : -1);
                return true;
            }
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
            case ScalarInput.ISV_DIAL_3_CLOCKWISE:
            case ScalarInput.ISV_DIAL_3_COUNTERCW: {
                int dir = (scan == ScalarInput.ISV_DIAL_KURU_CLOCKWISE || scan == ScalarInput.ISV_DIAL_3_CLOCKWISE) ? 1 : -1;
                if (menuOpen) menuChange(dir);
                else stepFilm(dir);
                return true;
            }
            case EV_DIAL:
                applyEvDial();
                return true;
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_DOWN: {
                int dir = scan == ScalarInput.ISV_KEY_DOWN ? 1 : -1;
                if (menuOpen) menuMove(dir);
                else if (magIndex >= 0) moveMagnifier(0, dir * MAG_STEP);
                else if (scan == ScalarInput.ISV_KEY_UP) toggleOverlay();
                return true;
            }
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_KEY_RIGHT: {
                int dir = scan == ScalarInput.ISV_KEY_RIGHT ? 1 : -1;
                if (menuOpen) menuChange(dir);
                else if (magIndex >= 0) moveMagnifier(dir * MAG_STEP, 0);
                return true;
            }
            case ScalarInput.ISV_KEY_WB:
            case ScalarInput.ISV_KEY_CUSTOM:
                if (menuOpen) closeMenu();
                else openMenu(ROW_WB);
                return true;
            case ScalarInput.ISV_KEY_ENTER:
                if (menuOpen) closeMenu();
                else cycleMagnifier();
                return true;
            case ScalarInput.ISV_KEY_FN:
                if (menuOpen) closeMenu();
                else openMenu(menuRow);
                return true;
            case ScalarInput.ISV_KEY_CUSTOM1:
                if (film != null) {
                    bypass = !bypass;
                    applyLook();
                    refreshFilmBar();
                    showMessage(bypass ? "Film off (C1 to compare)" : film.name);
                }
                return true;
            case ScalarInput.ISV_KEY_CUSTOM2:
                if (menuOpen) toggleFavourite();
                else cycleStrength();
                return true;
            case ScalarInput.ISV_KEY_DISP:
                toggleOverlay();
                return true;
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
                if (menuOpen && menuRow == ROW_FILM) {
                    selectFilm(null);
                    showMessage("No film");
                }
                return true;
            case ScalarInput.ISV_KEY_PLAY:
                if (recording || writingVideo) showMessage("Stop recording first");
                else openPlayback();
                return true;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
                return true;
        }
        showKey(scan);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        log("up " + scan + " code " + keyCode + " t " + event.getEventTime() + " menu " + menuOpen);
        switch (scan) {
            case ScalarInput.ISV_KEY_S1_1:
                if (camera != null) {
                    try {
                        camera.getNormalCamera().cancelAutoFocus();
                    } catch (Throwable t) {
                        log("cancelAutoFocus failed: " + t);
                    }
                }
                return true;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
                if (playback) closePlayback(true);
                else if (menuOpen) closeMenu();
                else exitApp();
                return true;
        }
        return true;
    }

    private void exitApp() {
        if (recording || writingVideo) {
            showMessage(recording ? "Stop recording first (MOVIE)" : "Wait for the video to finish saving");
            return;
        }
        if (takingPicture) {
            showMessage("Wait for the photo to finish saving");
            return;
        }
        if (developer.pending() > 0 && System.currentTimeMillis() > exitConfirmUntil) {
            exitConfirmUntil = System.currentTimeMillis() + 3000;
            showMessage("Still developing a copy. Press MENU again to stop it and exit");
            return;
        }
        developer.cancel();
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

    // ---------------- display ----------------

    private void toggleOverlay() {
        overlay = !overlay;
        refreshAll();
        repaintSoon();
    }

    private void refreshAll() {
        refreshFilmBar();
        refreshExposureBar();
        refreshMenu();
    }

    private void refreshFilmBar() {
        filmBar.setVisibility(overlay || recording ? View.VISIBLE : View.GONE);
        String prefix = "";
        if (recording) {
            int sec = recTime > 100000 ? recTime / 1000 : Math.max(0, recTime);
            prefix = "\u25cf REC " + (sec / 60) + ":" + String.format("%02d", sec % 60) + "    ";
        } else if (writingVideo) {
            prefix = "Saving video...    ";
        } else if (movieMode) {
            prefix = "VIDEO (MOVIE to record)    ";
        }
        if (developing > 0) prefix += "Developing " + developing + "...    ";
        if (film == null) {
            filmBar.setText(prefix + "No film   (Fn: menu)");
        } else {
            filmBar.setText(prefix + film.brand + "  " + film.name + "   " + stars(film) + "   "
                    + (bypass ? "OFF (C1)" : strength + "%"));
        }
        filmBar.setTextColor(recording ? 0xFFFF5050 : 0xFFF2C46B);
    }

    private void refreshExposureBar() {
        exposureBar.setVisibility(overlay ? View.VISIBLE : View.GONE);
        StringBuilder sb = new StringBuilder();
        sb.append(modeLetter()).append("    ").append(formatShutter()).append("    ");
        if (iso == 0) {
            sb.append("ISO AUTO");
            if (autoIso > 0) sb.append(" (").append(autoIso).append(')');
        } else {
            sb.append("ISO ").append(iso);
        }
        sb.append("    ").append(wbLabel(wbMode, kelvin));
        float ev = evIndex * evStep;
        sb.append("    ").append(ev >= 0 ? "+" : "").append(String.format("%.1f", ev)).append(" EV");
        if ("manual-exposure".equals(sceneMode) && meterLevel != Integer.MIN_VALUE) {
            sb.append("    meter ").append(meterLevel);
        }
        if (magIndex >= 0) {
            sb.append("    zoom x").append(String.format("%.1f", magLevels.get(magIndex) / 100f));
        }
        exposureBar.setText(sb.toString());
    }

    private String modeLetter() {
        if ("manual-exposure".equals(sceneMode)) return "M";
        if ("shutter-speed".equals(sceneMode)) return "S";
        if ("aperture-priority".equals(sceneMode)) return "A";
        if ("program-auto".equals(sceneMode)) return "P";
        if ("auto".equals(sceneMode)) return "AUTO";
        return sceneMode;
    }

    private String formatShutter() {
        if (shutterD <= 0) return "--";
        if (shutterN == 1 && shutterD > 1) return "1/" + shutterD;
        float s = shutterN / (float) shutterD;
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.format("%.1f", s)) + "\"";
    }

    private final Runnable hideMessage = new Runnable() {
        public void run() {
            message.setVisibility(View.GONE);
            repaintSoon();
        }
    };

    /** Full repaints over the next few frames, so every screen buffer drops whatever was just hidden. */
    private void repaintSoon() {
        root.invalidate();
        for (int delay : new int[] {40, 100, 200, 350}) {
            handler.postDelayed(repaint, delay);
        }
    }

    private final Runnable repaint = new Runnable() {
        public void run() {
            root.invalidate();
        }
    };

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 2000);
    }

    private final Runnable hideKey = new Runnable() {
        public void run() {
            keyInfo.setVisibility(View.GONE);
        }
    };

    /** Shows the scan code of a button the app doesn't use yet, so it can be mapped later. */
    private void showKey(int scan) {
        keyInfo.setText("button " + scan);
        keyInfo.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideKey);
        handler.postDelayed(hideKey, 3000);
        log("unmapped button " + scan);
    }

    // ---------------- settings ----------------

    private void restorePrefs() {
        SharedPreferences prefs = getSharedPreferences("filmsim", MODE_PRIVATE);
        strength = prefs.getInt("strength", 100);
        overlay = prefs.getBoolean("overlay", true);
        film = library == null ? null : library.find(prefs.getString("film", ""));
        look = null;
        bypass = false;
        savedWb = prefs.getString("wb", null);
        halation = prefs.getBoolean("halation", false);
        grain = Math.max(0, Math.min(GRAIN_NAMES.length - 1, prefs.getInt("grain", 0)));
        lightLeak = prefs.getBoolean("leak", false);
        favourites.clear();
        for (String key : prefs.getString("favourites", "").split("\n")) {
            if (key.length() > 0) favourites.add(key);
        }
        savedKelvin = prefs.getInt("kelvin", 5500);
    }

    private void savePrefs() {
        getSharedPreferences("filmsim", MODE_PRIVATE).edit()
                .putInt("strength", strength)
                .putBoolean("overlay", overlay)
                .putString("film", film == null ? "" : film.file)
                .putString("wb", savedWb)
                .putInt("kelvin", savedKelvin)
                .putBoolean("halation", halation)
                .putInt("grain", grain)
                .putBoolean("leak", lightLeak)
                .putString("favourites", joinLines(favourites))
                .commit();
    }

    private static String joinLines(Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k);
        }
        return sb.toString();
    }

    // ---------------- log ----------------

    private void openLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "FILMSIM");
            dir.mkdirs();
            log = new FileWriter(new File(dir, "LOG.TXT"), true);
        } catch (IOException e) {
            log = null;
        }
    }

    private void closeLog() {
        if (log == null) return;
        try {
            log.close();
        } catch (IOException ignored) {
        }
        log = null;
    }

    /** Writes to FILMSIM/LOG.TXT, but never while a photo is being saved. */
    private void log(String msg) {
        if (log == null || takingPicture) return;
        try {
            log.write(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + msg + "\n");
            log.flush();
        } catch (IOException ignored) {
        }
    }
}
