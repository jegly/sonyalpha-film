package com.filmsim.app;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
import android.widget.ImageView;
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
import com.sony.scalar.sysutil.didep.Gpelibrary;
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
 * Controls (A7 II): rear dial = shutter (S/M) or exposure comp (P/A), front dial = ISO (both can be changed in the menu),
 * control wheel = film, Fn = menu (brand, film, strength, white balance, Kelvin, halation, grain, light leak:
 * up/down picks a row, left/right or the wheel changes it, C2 marks a favourite), centre = focus magnifier, C1 = compare with no look,
 * C2 = strength (favourite while in the menu), C3 = white balance, DISP/up = info on/off, Play = review photos and try films on them (up/down = film, centre = save a copy), MOVIE = record video, MENU = exit.
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
    private static final String[] MENU_ROWS = {"Brand", "Film", "Strength", "WB", "Kelvin", "D-Range", "Halation", "Grain",
            "Light leak", "Copy size", "Front dial", "Rear dial", "RAW format"};
    private static final int ROW_BRAND = 0, ROW_FILM = 1, ROW_STRENGTH = 2, ROW_WB = 3, ROW_KELVIN = 4, ROW_DRO = 5,
            ROW_HALATION = 6, ROW_GRAIN = 7, ROW_LEAK = 8, ROW_SIZE = 9, ROW_FRONT = 10, ROW_REAR = 11, ROW_RAW = 12;
    /** Rows of the menu shown at once, so most of the picture stays visible while changing films. */
    private static final int MENU_WINDOW = 5;
    // What a dial does. Default: front = ISO, rear = shutter speed in S/M and exposure compensation in P/A.
    private static final String[] TURN_NAMES = {"Default", "Aperture", "Shutter speed", "ISO", "Exposure comp", "Film"};
    private static final int TURN_DEFAULT = 0, TURN_APERTURE = 1, TURN_SHUTTER = 2, TURN_ISO = 3, TURN_EV = 4, TURN_FILM = 5;
    private int frontDial, rearDial;
    /** RAW recording modes the camera offers (compress / uncompress) and the one chosen; the app's own shooting session
     *  did not inherit the camera menu's uncompressed setting, so it is set here. */
    private final List<String> rawModes = new ArrayList<String>();
    private String rawMode = "";
    /** Keys that toggle something ignore auto-repeat and a second press within this many ms. */
    private static final long TOGGLE_GAP_MS = 250;
    private long lastToggleTime;
    private int lastToggleKey;
    private static final String[] GRAIN_NAMES = {"Off", "Fine", "Medium", "Heavy", "Coarse", "Auto"};
    /** Grain setting that picks the level from the film's speed, like the real stock. */
    private static final int GRAIN_AUTO = 5;
    private static final int[] FILM_SPEEDS = {20, 25, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1600, 3000, 3200};
    private static final String FAVOURITES = "Favourites";
    /** These looks come from spektrafilm's film models; its licence asks for this credit wherever they are used. */
    private static final String SPEKTRAFILM = "Spektrafilm";
    private static final String SPEKTRAFILM_CREDIT = "Derived from spektrafilm by Andrea Volpato, CC BY-SA 4.0";
    private boolean menuOpen;
    private int menuRow = ROW_FILM;
    private final Set<String> favourites = new LinkedHashSet<String>();
    // After-shot develop effects; any of them makes a developed copy in FILMSIM/DEV
    private boolean halation;
    private int grain;
    private boolean lightLeak;
    private boolean largeCopy;
    // Sony's D-Range Optimizer: 0 off, 1 auto, 2.. level 1..maxDro
    private int dro;
    private int maxDro = 5;
    private List<String> droModes = new ArrayList<String>();

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
    /** F-number x100 from the lens; 0 with a manual lens, which doesn't report one. */
    private int aperture;
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
    /** Shows the developed copy (halation, grain, leaks) over the original when one exists. */
    private ImageView devView;
    private Bitmap devBitmap;
    private boolean showDeveloped = true;
    /** The developed copy decoded by Sony's viewer, which shows full colour (the app's overlay is 16 levels per colour). */
    private OptimizedImage devImage;

    // Darkroom: trying films on photos already on the card, from playback
    /** The film being previewed on the photo on screen, or null for plain playback. */
    private FilmLibrary.Film darkFilm;
    /** A RAW shown from the preview stored inside it, with no darkroom film. */
    private boolean plainPreview;
    private FilmLibrary.Look darkLook;
    private int darkIndex;
    private int darkStrength = 100;
    /** Fn in playback: a small Brand / Film / Strength menu for the darkroom. */
    private boolean darkMenuOpen;
    private int darkRow = 1, darkBrand, darkBrandFilm;
    private static final String[] DARK_ROWS = {"Brand", "Film", "Strength"};
    /** The photo on screen: its real file (the JPEG when there is one, else the RAW), name and rotation. */
    private File playFile;
    private String playName = "";
    private int playOrientation = 1;
    private String playExif = "";
    private boolean playHasDev, playDevShown;
    private PreviewWorker previewWorker;
    /** Goes up whenever the photo or film on screen changes, so late previews are dropped. */
    private int previewGen;

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
    /** Newest files before the next shot. The camera's clock can't be trusted (it reads 1970), so new files are
     * the ones that weren't there before. */
    private File knownPhoto, knownJpeg;
    /** The saved film when it's one of the user's LUTs, which only exist once they've been loaded. */
    private String pendingFilmKey;
    private Thread customLoader;
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
                                if (playback) refreshPlayInfo();
                            }
                        });
                    }

                    public void onDone(final String source, final File result, final long millis) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                log("developed " + source + " -> " + result.getName() + " in " + millis + " ms: " + developer.lastTiming + " (" + developer.memNote + ")");
                                showMessage("Developed copy saved: " + result.getName() + " (" + (millis / 1000) + " s)");
                                if (playback) {
                                    if (darkFilm == null) showPhoto();
                                    else {
                                        playHasDev = true;
                                        refreshPlayInfo();
                                    }
                                }
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
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        knownPhoto = newestPhoto(dcim);
        knownJpeg = newestJpeg(dcim);
        if (!openCamera()) return;
        readCapabilities();
        startShooting();
        loadCustomLuts();
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
        applyRawMode();
        applyDro();
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
            try {
                camera.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
                    public void onApertureChange(final CameraEx.ApertureInfo info, CameraEx c) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                aperture = info.currentAperture;
                                refreshExposureBar();
                            }
                        });
                    }
                });
            } catch (Throwable t) {
                log("aperture listener failed: " + t);
            }
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
            try {
                List modes = m.getSupportedDROModes();
                droModes.clear();
                if (modes != null) for (Object o : modes) droModes.add(o.toString());
                maxDro = Math.max(1, m.getMaxDROLevel());
                log("dro modes " + droModes + " max level " + maxDro);
            } catch (Throwable t) {
                log("dro capabilities failed: " + t);
            }
            try {
                rawModes.clear();
                List rm = m.getSupportedRawRecModes();
                if (rm != null) for (Object o : rm) rawModes.add(o.toString());
                CameraEx.ParametersModifier cur = camera.createParametersModifier(camera.getNormalCamera().getParameters());
                log("raw rec modes " + rawModes + " current " + cur.getRawRecMode());
            } catch (Throwable t) {
                log("raw rec modes failed: " + t);
            }
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

    private void applyRawMode() {
        if (camera == null || rawModes.isEmpty()) return;
        if (!rawModes.contains(rawMode)) {
            // first run: uncompressed if the camera offers it
            rawMode = rawModes.contains(CameraEx.ParametersModifier.RAW_REC_MODE_UNCOMPRESS)
                    ? CameraEx.ParametersModifier.RAW_REC_MODE_UNCOMPRESS : rawModes.get(0);
        }
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setRawRecMode(rawMode);
            camera.getNormalCamera().setParameters(p);
            log("raw rec mode set to " + rawMode);
        } catch (Throwable t) {
            log("setRawRecMode failed: " + t);
        }
    }

    private static String rawLabel(String mode) {
        return CameraEx.ParametersModifier.RAW_REC_MODE_UNCOMPRESS.equals(mode) ? "Uncompressed  (about 48 MB)"
                : CameraEx.ParametersModifier.RAW_REC_MODE_COMPRESS.equals(mode) ? "Compressed  (about 24 MB)" : mode;
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
        if (f != null && SPEKTRAFILM.equals(f.brand) && !menuOpen) {
            showMessage(SPEKTRAFILM_CREDIT);
        } else if (f != null && f.name.contains("800T")) {
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
            case ROW_DRO:
                if (!droModes.isEmpty()) {
                    int count = maxDro + 2;
                    dro = (dro + dir + count) % count;
                    applyDro();
                }
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
            case ROW_SIZE:
                largeCopy = !largeCopy;
                showMessage(largeCopy ? "Large copies: 3000 px wide, slow (1-2 min each)" : "Small copies: 1500 px wide, much faster");
                break;
            case ROW_FRONT:
                frontDial = (frontDial + dir + TURN_NAMES.length) % TURN_NAMES.length;
                break;
            case ROW_REAR:
                rearDial = (rearDial + dir + TURN_NAMES.length) % TURN_NAMES.length;
                break;
            case ROW_RAW:
                if (!rawModes.isEmpty()) {
                    int i = Math.max(0, rawModes.indexOf(rawMode));
                    rawMode = rawModes.get((i + dir + rawModes.size()) % rawModes.size());
                    applyRawMode();
                }
                break;
        }
        refreshMenu();
    }

    /** Reads the user's own LUTs from the LUTS folder, converting new ones in the background. */
    private void loadCustomLuts() {
        if (customLoader != null && customLoader.isAlive()) return;
        final File card = Environment.getExternalStorageDirectory();
        customLoader = new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                final List<FilmLibrary.Film> custom = new CustomLuts(card).load(new CustomLuts.Listener() {
                    public void onConverting(final int index, final int total, final String name) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                showMessage("Converting your LUT " + index + "/" + total + ": " + name);
                            }
                        });
                    }

                    public void onFailed(final String name, final String reason) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                log("custom LUT " + name + " failed: " + reason);
                                showMessage("Couldn't use LUT " + name + ": " + reason);
                            }
                        });
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (pausing) return;
                        library.setCustom(custom);
                        if (!custom.isEmpty()) log("loaded " + custom.size() + " custom LUTs");
                        if (pendingFilmKey != null) {
                            FilmLibrary.Film f = library.find(pendingFilmKey);
                            pendingFilmKey = null;
                            if (f != null) selectFilm(f);
                        }
                        refreshMenu();
                        refreshFilmBar();
                    }
                });
            }
        }, "custom-luts");
        customLoader.start();
    }

    /**
     * The grain level to use for a film. Auto reads the film's speed from its name (Portra 160, Tri-X 400,
     * Delta 3200...): slow films get fine grain, fast ones coarse. Films with no speed in the name get Medium.
     */
    private int grainFor(FilmLibrary.Film f) {
        if (grain != GRAIN_AUTO) return grain;
        int speed = 0;
        if (f != null) {
            String name = f.name;
            int i = 0;
            while (i < name.length()) {
                if (!Character.isDigit(name.charAt(i))) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < name.length() && Character.isDigit(name.charAt(j))) j++;
                if (j - i <= 4) {
                    int v = Integer.parseInt(name.substring(i, j));
                    for (int known : FILM_SPEEDS) {
                        if (v == known) speed = Math.max(speed, v);
                    }
                }
                i = j;
            }
        }
        if (speed == 0) return 2;
        return speed <= 100 ? 1 : speed <= 400 ? 2 : speed <= 800 ? 3 : 4;
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
            WB_KELVIN.equals(wbMode) ? kelvin + "K" : "off  (turn to use Kelvin)",
            droLabel(),
            halation ? "On" : "Off",
            grain == GRAIN_AUTO ? "Auto  (by film speed: " + GRAIN_NAMES[grainFor(film)] + ")" : GRAIN_NAMES[grain],
            lightLeak ? "On  (random each shot)" : "Off",
            largeCopy ? "Large  (3000 px, slow)" : "Small  (1500 px, fast)",
            frontDial == TURN_DEFAULT ? "Default  (ISO)" : TURN_NAMES[frontDial],
            rearDial == TURN_DEFAULT ? "Default  (shutter in S/M, EV in P/A)" : TURN_NAMES[rearDial],
            rawModes.isEmpty() ? "n/a" : rawLabel(rawMode.length() > 0 ? rawMode : rawModes.get(0)),
        };
        StringBuilder sb = new StringBuilder();
        // a short window of rows around the highlighted one keeps the picture in view
        int first = Math.max(0, Math.min(menuRow - MENU_WINDOW / 2, MENU_ROWS.length - MENU_WINDOW));
        for (int i = first; i < first + MENU_WINDOW; i++) {
            String label = MENU_ROWS[i];
            sb.append(i == menuRow ? "> " : "  ").append(label);
            for (int k = label.length(); k < 10; k++) sb.append(' ');
            sb.append(i == menuRow ? "<  " + values[i] + "  >" : "   " + values[i]).append('\n');
        }
        sb.append(menuRow + 1).append('/').append(MENU_ROWS.length).append("  up/down: row   left/right: change   Fn: close");
        if ((menuRow == ROW_FILM || menuRow == ROW_BRAND) && SPEKTRAFILM.equals(brand)) sb.append("\n").append(SPEKTRAFILM_CREDIT);
        else if (menuRow == ROW_FILM) sb.append("\nC2: favourite   Delete: no film");
        else if (menuRow >= ROW_HALATION && menuRow <= ROW_SIZE) sb.append("\nAdded after the shot to a copy in FILMSIM/DEV");
        else if (menuRow == ROW_FRONT || menuRow == ROW_REAR) sb.append("\nAperture needs a lens the camera can control");
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

    private void applyDro() {
        if (camera == null || droModes.isEmpty()) return;
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            if (dro == 0) {
                m.setDROMode(CameraEx.ParametersModifier.DRO_MODE_OFF);
            } else if (dro == 1 && droModes.contains(CameraEx.ParametersModifier.DRO_MODE_AUTO)) {
                m.setDROMode(CameraEx.ParametersModifier.DRO_MODE_AUTO);
            } else {
                m.setDROMode(CameraEx.ParametersModifier.DRO_MODE_ON);
                m.setDROLevel(Math.max(1, Math.min(maxDro, dro - 1)));
            }
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("applyDro failed: " + t);
            showMessage("D-Range failed: " + t);
        }
    }

    private String droLabel() {
        if (droModes.isEmpty()) return "n/a";
        return dro == 0 ? "Off" : dro == 1 ? "Auto" : "Level " + (dro - 1) + "  (more highlight and shadow detail)";
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

    private void stepAperture(int dir) {
        if (aperture <= 0) {
            showMessage("This lens has no aperture control from the camera");
            return;
        }
        try {
            if (dir > 0) camera.incrementAperture();
            else camera.decrementAperture();
        } catch (Throwable t) {
            log("aperture step failed: " + t);
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                readExposure();
            }
        }, 100);
    }

    /** A front or rear dial click, doing whatever that dial is set to in the menu. */
    private void turnDial(int action, int dir, boolean rear) {
        switch (action) {
            case TURN_APERTURE:
                stepAperture(dir);
                break;
            case TURN_SHUTTER:
                stepShutter(dir);
                break;
            case TURN_ISO:
                stepIso(dir);
                break;
            case TURN_EV:
                stepEv(dir);
                break;
            case TURN_FILM:
                if (menuOpen) menuChange(dir);
                else stepFilm(dir);
                break;
            default:
                if (!rear) stepIso(dir);
                else if (shutterControlsShutter()) stepShutter(dir);
                else stepEv(dir);
        }
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
            try {
                aperture = m.getAperture();
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
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        File newest = newestPhoto(dcim);
        boolean newPhoto = newest != null && !newest.equals(knownPhoto);
        String name = newPhoto ? newest.getName() : "?";
        knownPhoto = newest;
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
        if (!developOn()) knownJpeg = newestJpeg(dcim);
        if (developOn()) {
            File jpeg = newestJpeg(dcim);
            boolean fresh = jpeg != null && !jpeg.equals(knownJpeg);
            knownJpeg = jpeg;
            // RAW only: develop the 1616 px preview stored inside the RAW file instead
            File source = fresh ? jpeg : newPhoto && newest.getName().toUpperCase().endsWith(".ARW") ? newest : null;
            if (source != null) {
                Develop.Options opt = new Develop.Options();
                opt.halation = halation;
                opt.grain = grainFor(shotFilm);
                opt.lightLeak = lightLeak;
                opt.sample = !fresh ? 1 : largeCopy ? 2 : 4;
                opt.monochrome = shotFilm != null && "Black & white".equals(shotFilm.type);
                opt.seed = source.getName().hashCode() * 31L + shotTime;
                developer.add(source, opt);
            } else {
                showMessage("Couldn't find the new photo to develop");
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
        darkFilm = null;
        darkLook = null;
        darkMenuOpen = false;
        surfaceView.setVisibility(View.GONE);
        filmBar.setVisibility(View.GONE);
        exposureBar.setVisibility(View.GONE);
        setOverlayColour(true);
        playView = new OptimizedImageView(this);
        // just above the live view surface, below the info line
        root.addView(playView, 1, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        devView = new ImageView(this);
        devView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        devView.setBackgroundColor(0xFF000000);
        devView.setVisibility(View.GONE);
        root.addView(devView, 2, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playInfo.setVisibility(View.VISIBLE);
        showPhoto();
    }

    /** Leaves playback; with reopen, takes the camera back and restores the film look. */
    private void closePlayback(boolean reopen) {
        if (!playback) return;
        playback = false;
        previewGen++;
        if (previewWorker != null) {
            previewWorker.quit();
            previewWorker = null;
        }
        darkFilm = null;
        darkLook = null;
        darkMenuOpen = false;
        pickerPanel.setVisibility(View.GONE);
        try {
            if (playView != null) playView.setOptimizedImage(null);
        } catch (Throwable t) {
            log("clear image failed: " + t);
        }
        releasePlayImage();
        repaintSoon();
        if (devView != null) {
            devView.setImageBitmap(null);
            root.removeView(devView);
            devView = null;
        }
        releaseDevBitmap();
        releaseDevImage();
        setOverlayColour(false);
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

    private void releaseDevImage() {
        if (devImage != null) {
            try {
                devImage.release();
            } catch (Throwable ignored) {
            }
            devImage = null;
        }
    }

    /** Full colour (8 bits per channel) or the camera's usual 4-bit overlay. */
    private void setOverlayColour(boolean full) {
        try {
            Gpelibrary.changeFrameBufferPixel(full ? Gpelibrary.GS_FRAMEBUFFER_TYPE.ABGR8888 : Gpelibrary.GS_FRAMEBUFFER_TYPE.RGBA4444);
        } catch (Throwable t) {
            log("overlay colour change failed: " + t);
        }
    }

    private void releaseDevBitmap() {
        if (devBitmap != null) {
            devBitmap.recycle();
            devBitmap = null;
        }
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
        if (darkMenuOpen) {
            switch (scan) {
                case ScalarInput.ISV_KEY_UP:
                    darkRow = (darkRow + DARK_ROWS.length - 1) % DARK_ROWS.length;
                    refreshDarkMenu();
                    return true;
                case ScalarInput.ISV_KEY_DOWN:
                    darkRow = (darkRow + 1) % DARK_ROWS.length;
                    refreshDarkMenu();
                    return true;
                case ScalarInput.ISV_KEY_RIGHT:
                case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
                case ScalarInput.ISV_DIAL_3_CLOCKWISE:
                case ScalarInput.ISV_DIAL_1_CLOCKWISE:
                case ScalarInput.ISV_DIAL_2_CLOCKWISE:
                    darkMenuChange(1);
                    return true;
                case ScalarInput.ISV_KEY_LEFT:
                case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
                case ScalarInput.ISV_DIAL_3_COUNTERCW:
                case ScalarInput.ISV_DIAL_1_COUNTERCW:
                case ScalarInput.ISV_DIAL_2_COUNTERCW:
                    darkMenuChange(-1);
                    return true;
                case ScalarInput.ISV_KEY_FN:
                    closeDarkMenu();
                    return true;
            }
        }
        switch (scan) {
            case ScalarInput.ISV_KEY_RIGHT:
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
            case ScalarInput.ISV_DIAL_3_CLOCKWISE:
                stepPhoto(1);
                return true;
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
            case ScalarInput.ISV_DIAL_3_COUNTERCW:
                stepPhoto(-1);
                return true;
            // darkroom: up/down or the front/rear dials try films on the photo on screen
            case ScalarInput.ISV_KEY_DOWN:
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
                stepDarkFilm(1);
                return true;
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                stepDarkFilm(-1);
                return true;
            case ScalarInput.ISV_KEY_FN:
                openDarkMenu();
                return true;
            case ScalarInput.ISV_KEY_CUSTOM2:
                if (darkFilm != null) {
                    int i = 0;
                    while (i < STRENGTHS.length && STRENGTHS[i] != darkStrength) i++;
                    darkStrength = STRENGTHS[(i + 1) % STRENGTHS.length];
                    showDeveloped = true;
                    requestPreview();
                    refreshPlayInfo();
                    refreshDarkMenu();
                }
                return true;
            case ScalarInput.ISV_KEY_ENTER:
                saveDarkroomCopy();
                return true;
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
                if (darkFilm != null) {
                    darkFilm = null;
                    darkLook = null;
                    closeDarkMenu();
                    showPhoto();
                }
                return true;
            case ScalarInput.ISV_KEY_PLAY:
            case ScalarInput.ISV_KEY_S1_1:
            case ScalarInput.ISV_KEY_S2:
                closePlayback(true);
                return true;
            case ScalarInput.ISV_KEY_CUSTOM1:
                showDeveloped = !showDeveloped;
                showPhoto();
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
        previewGen++;
        String data = playFiles.get(playIndex);
        OptimizedImage img = null;
        String info = "";
        int orientation = 1;
        String name = null;
        try {
            OptimizedImageFactory.Options o = new OptimizedImageFactory.Options();
            o.imageType = 3;
            // without a colour type the picture is shown untagged, which looks lighter than Sony's own playback
            o.colorType = OptimizedImageFactory.Options.SRGB;
            o.bBasicInfo = true;
            o.bCamInfo = true;
            img = OptimizedImageFactory.decodeImage(data, o);
            AvindexContentInfo ci = o.outContentInfo;
            if (ci != null) {
                double t = ci.getAttributeDouble(AvindexContentInfo.TAG_EXPOSURE_TIME, 0);
                int iso = ci.getAttributeInt(AvindexContentInfo.TAG_ISO, 0);
                if (t > 0) info += (t < 1 ? "1/" + Math.round(1 / t) : String.format("%.1f\"", t)) + "   ";
                if (iso > 0) info += "ISO " + iso + "   ";
                orientation = ci.getAttributeInt(AvindexContentInfo.TAG_ORIENTATION, 1);
                // playback hands out avindex:// ids; the file name comes from the photo's info
                name = ci.getAttribute(AvindexContentInfo.TAG_DCF_TBL_FILE_NAME);
            }
            playView.setDisplayType(OptimizedImageView.DisplayType.DISPLAY_TYPE_CENTER_INNER);
            playView.setOptimizedImage(img);
        } catch (Throwable t) {
            log("showPhoto failed: " + t);
        }
        releasePlayImage();
        playImage = img;
        if (name == null || name.length() == 0) {
            try {
                AvindexContentInfo full = AvindexStore.Images.Media.getImageInfo(data);
                if (full != null) name = full.getAttribute(AvindexContentInfo.TAG_DCF_TBL_FILE_NAME);
            } catch (Throwable t) {
                log("file name lookup failed: " + t);
            }
        }
        if (name == null || name.length() == 0) name = new File(data).getName();
        File devDir = new File(new File(Environment.getExternalStorageDirectory(), "FILMSIM"), "DEV");
        File dev = Developer.newestCopy(devDir, name);
        boolean hasDev = dev != null;
        if (dev == null) dev = new File(devDir, Developer.outName(name) + ".JPG");
        playName = name;
        playOrientation = orientation;
        playExif = info;
        playFile = findPhoto(name);
        // while a film is being tried the original shows first, then the preview replaces it
        boolean previewing = darkFilm != null && showDeveloped;
        if (!hasDev || !showDeveloped || previewing) {
            devView.setImageBitmap(null);
            releaseDevBitmap();
            devView.setVisibility(View.GONE);
        }
        boolean devShown = false;
        String via = "";
        releaseDevImage();
        if (hasDev && showDeveloped && !previewing) {
            // Sony's viewer shows full colour; try it first
            try {
                OptimizedImageFactory.Options o = new OptimizedImageFactory.Options();
                o.imageType = 3;
                o.colorType = OptimizedImageFactory.Options.SRGB;
                devImage = OptimizedImageFactory.decodeImage(dev.getAbsolutePath(), o);
            } catch (Throwable t) {
                log("viewer decode of copy failed: " + t);
                devImage = null;
            }
            if (devImage != null) {
                devView.setImageBitmap(null);
                releaseDevBitmap();
                devView.setVisibility(View.GONE);
                playView.setVisibility(View.VISIBLE);
                try {
                    playView.setOptimizedImage(devImage);
                    devShown = true;
                    via = "viewer";
                } catch (Throwable t) {
                    log("viewer show of copy failed: " + t);
                    releaseDevImage();
                }
            }
            if (!devShown && showDevCopy(dev, orientation)) {
                // Sony's viewer draws on its own layer above the app's views, so hide it under the overlay copy
                playView.setVisibility(View.INVISIBLE);
                devShown = true;
                via = "overlay";
            }
        }
        if (!devShown) {
            playView.setVisibility(View.VISIBLE);
            if (img != null) {
                try {
                    playView.setOptimizedImage(img);
                } catch (Throwable t) {
                    log("re-show original failed: " + t);
                }
            }
        }
        // Sony's viewer seems to render a RAW afresh, without the film look; the saved preview inside the ARW has it
        plainPreview = !devShown && darkFilm == null && playFile != null && playFile.getName().toUpperCase().endsWith(".ARW");
        if (plainPreview) {
            if (previewWorker == null) {
                previewWorker = new PreviewWorker();
                previewWorker.start();
            }
            int[] ramp = new int[1024];
            for (int i = 0; i < 1024; i++) ramp[i] = i;
            previewWorker.request(playFile, playOrientation, new FilmLook(ramp, IDENTITY, 100), ++previewGen);
            via = "embedded preview";
        }
        log("playback " + name + " | copy " + dev.getName() + " exists " + hasDev + " shown " + devShown + " " + via);
        playHasDev = hasDev;
        playDevShown = devShown;
        refreshPlayInfo();
        if (img == null && !devShown) playInfo.append("\n(could not show this file)");
        if (previewing) requestPreview();
    }

    /** The two lines under the photo: what is on screen, and what the buttons do. */
    private void refreshPlayInfo() {
        if (!playback) return;
        String head;
        if (darkFilm != null) {
            head = showDeveloped ? "PREVIEW  " + darkFilm.brand + " " + darkFilm.name + "  " + darkStrength + "%"
                    : "ORIGINAL (C1: preview)";
        } else {
            head = !playHasDev ? "" : playDevShown ? "COPY (C1: original)" : "ORIGINAL (C1: copy)";
        }
        String filmUsed = shotFilms.get(playName.toUpperCase());
        StringBuilder sb = new StringBuilder();
        if (head.length() > 0) sb.append(head).append("   ");
        sb.append(playName).append("   ");
        if (filmUsed != null) sb.append("shot on ").append(filmUsed).append("   ");
        sb.append(playExif).append(playIndex + 1).append('/').append(playFiles.size());
        if (developing > 0) sb.append("   saving ").append(developing).append("...");
        // one line while the darkroom menu is open: the menu sits just above and has its own key hints
        if (!darkMenuOpen) {
            sb.append('\n');
            if (darkFilm != null) sb.append("up/down: film   Fn: brands   C2: strength   centre: save copy   Delete: no film");
            else sb.append("< > browse   up/down: try a film   Fn: film menu   Play: back");
        }
        playInfo.setText(sb.toString());
    }

    // ---------------- darkroom ----------------

    /** Finds a photo's file under DCIM. A RAW with a JPEG beside it gives the JPEG, which has the full picture. */
    private static File findPhoto(String name) {
        File[] dirs = new File(Environment.getExternalStorageDirectory(), "DCIM").listFiles();
        if (dirs == null || name == null) return null;
        for (File d : dirs) {
            File f = new File(d, name);
            if (!f.isFile()) continue;
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.toUpperCase().endsWith(".ARW")) {
                File jpeg = new File(d, name.substring(0, dot) + ".JPG");
                if (jpeg.isFile()) return jpeg;
            }
            return f;
        }
        return null;
    }

    /** Films in darkroom order: favourites first, then every film by brand. Fills starts with where each group begins. */
    private List<FilmLibrary.Film> darkFilms(List<Integer> starts) {
        List<FilmLibrary.Film> out = new ArrayList<FilmLibrary.Film>();
        for (String brand : menuBrands()) {
            starts.add(out.size());
            out.addAll(menuFilms(brand));
        }
        return out;
    }

    private void stepDarkFilm(int dir) {
        List<FilmLibrary.Film> list = darkFilms(new ArrayList<Integer>());
        if (list.isEmpty()) return;
        if (darkFilm == null) {
            // start from the film selected for shooting
            darkIndex = film != null ? Math.max(0, list.indexOf(film)) : 0;
        } else {
            darkIndex = (Math.min(darkIndex, list.size() - 1) + dir + list.size()) % list.size();
        }
        setDarkFilm(list.get(darkIndex));
    }

    /** Fn in playback: opens the darkroom menu on the film being previewed (or the shooting film to start with). */
    private void openDarkMenu() {
        List<String> brands = menuBrands();
        FilmLibrary.Film cur = darkFilm != null ? darkFilm : film;
        darkBrand = 0;
        darkBrandFilm = 0;
        if (cur != null) {
            // stay in Favourites if that is where the film was picked from, else go to its own brand
            List<Integer> starts = new ArrayList<Integer>();
            darkFilms(starts);
            boolean inFavourites = darkFilm != null && !favourites.isEmpty() && starts.size() > 1 && darkIndex < starts.get(1);
            int b = inFavourites ? 0 : brands.indexOf(cur.brand);
            if (b >= 0) {
                darkBrand = b;
                darkBrandFilm = Math.max(0, menuFilms(brands.get(b)).indexOf(cur));
            }
        }
        darkMenuOpen = true;
        darkRow = 1;
        applyDarkMenuFilm();
    }

    private void closeDarkMenu() {
        if (!darkMenuOpen) return;
        darkMenuOpen = false;
        pickerPanel.setVisibility(View.GONE);
        refreshPlayInfo();
        repaintSoon();
    }

    private void darkMenuChange(int dir) {
        List<String> brands = menuBrands();
        switch (darkRow) {
            case 0:
                darkBrand = (Math.min(darkBrand, brands.size() - 1) + dir + brands.size()) % brands.size();
                darkBrandFilm = 0;
                applyDarkMenuFilm();
                break;
            case 1: {
                int n = menuFilms(brands.get(Math.min(darkBrand, brands.size() - 1))).size();
                if (n > 0) {
                    darkBrandFilm = (Math.min(darkBrandFilm, n - 1) + dir + n) % n;
                    applyDarkMenuFilm();
                }
                break;
            }
            default:
                darkStrength = Math.max(5, Math.min(100, darkStrength + dir * 5));
                showDeveloped = true;
                requestPreview();
                refreshPlayInfo();
                refreshDarkMenu();
        }
    }

    /** Previews the film highlighted in the darkroom menu. */
    private void applyDarkMenuFilm() {
        List<Integer> starts = new ArrayList<Integer>();
        List<FilmLibrary.Film> all = darkFilms(starts);
        if (all.isEmpty()) return;
        darkBrand = Math.min(darkBrand, starts.size() - 1);
        int end = darkBrand + 1 < starts.size() ? starts.get(darkBrand + 1) : all.size();
        darkIndex = Math.min(starts.get(darkBrand) + darkBrandFilm, end - 1);
        darkBrandFilm = darkIndex - starts.get(darkBrand);
        setDarkFilm(all.get(darkIndex));
    }

    private void refreshDarkMenu() {
        if (!playback || !darkMenuOpen) return;
        List<String> brands = menuBrands();
        darkBrand = Math.min(darkBrand, brands.size() - 1);
        List<FilmLibrary.Film> list = menuFilms(brands.get(darkBrand));
        String filmValue = "-";
        if (!list.isEmpty()) {
            int k = Math.min(darkBrandFilm, list.size() - 1);
            FilmLibrary.Film cur = list.get(k);
            filmValue = cur.name + "  " + stars(cur) + (isFavourite(cur) ? " \u2665" : "") + "   (" + (k + 1) + "/" + list.size() + ")";
        }
        String[] values = {brands.get(darkBrand) + "   (" + (darkBrand + 1) + "/" + brands.size() + ")", filmValue, darkStrength + "%"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DARK_ROWS.length; i++) {
            String label = DARK_ROWS[i];
            sb.append(i == darkRow ? "> " : "  ").append(label);
            for (int k = label.length(); k < 10; k++) sb.append(' ');
            sb.append(i == darkRow ? "<  " + values[i] + "  >" : "   " + values[i]).append('\n');
        }
        sb.append("up/down: row   left/right: change   centre: save copy   Fn: close");
        picker.setText(sb.toString());
        pickerPanel.setVisibility(View.VISIBLE);
    }

    private void setDarkFilm(FilmLibrary.Film f) {
        try {
            darkLook = FilmLibrary.load(getAssets(), f);
            darkFilm = f;
        } catch (Throwable t) {
            log("darkroom film load failed: " + t);
            showMessage("Could not load " + f.name);
            return;
        }
        showDeveloped = true;
        requestPreview();
        refreshPlayInfo();
        refreshDarkMenu();
    }

    private FilmLook darkFilmLook() {
        return darkFilm == null || darkLook == null ? null : new FilmLook(darkLook.gamma, darkLook.matrix, darkStrength);
    }

    /** Asks the background worker for the photo on screen with the darkroom film on it. */
    private void requestPreview() {
        FilmLook fl = darkFilmLook();
        if (fl == null) return;
        if (playFile == null) {
            showMessage("Can't find " + playName + " on the card");
            return;
        }
        if (previewWorker == null) {
            previewWorker = new PreviewWorker();
            previewWorker.start();
        }
        previewWorker.request(playFile, playOrientation, fl, ++previewGen);
    }

    private void showPreview(Bitmap b, int gen) {
        if (!playback || gen != previewGen || devView == null || (darkFilm == null && !plainPreview) || !showDeveloped) {
            b.recycle();
            return;
        }
        Bitmap old = devBitmap;
        devBitmap = b;
        devView.setImageBitmap(b);
        devView.setVisibility(View.VISIBLE);
        // Sony's viewer draws on its own layer above the app's views, so hide it under the preview
        if (playView != null) playView.setVisibility(View.INVISIBLE);
        if (old != null && old != b) old.recycle();
    }

    /** Centre button in playback: saves a copy of the photo with the previewed film (and any develop effects). */
    private void saveDarkroomCopy() {
        if (playFile == null) {
            showMessage("Can't find " + playName + " on the card");
            return;
        }
        if (darkFilm == null && !developOn()) {
            showMessage("Pick a film first: up/down");
            return;
        }
        if (developer.pending() >= 6) {
            showMessage("Still saving " + developer.pending() + " copies, try again in a moment");
            return;
        }
        boolean raw = playFile.getName().toUpperCase().endsWith(".ARW");
        Develop.Options opt = new Develop.Options();
        opt.look = darkFilmLook();
        opt.halation = halation;
        opt.grain = grainFor(darkFilm != null ? darkFilm : film);
        opt.lightLeak = lightLeak;
        // a RAW is developed from the 1616 px preview inside it, which is already small
        opt.sample = raw ? 1 : largeCopy ? 2 : 4;
        opt.monochrome = darkFilm != null && "Black & white".equals(darkFilm.type);
        opt.seed = playFile.getName().hashCode() * 31L + System.currentTimeMillis();
        String out = developer.addCopy(playFile, opt);
        log("darkroom copy " + playFile.getName() + " -> " + out + " film " + (darkFilm == null ? "none" : darkFilm.name) + " " + darkStrength + "%");
        showMessage("Saving " + out + ".JPG" + (darkFilm != null ? " with " + darkFilm.name : "")
                + (raw ? " (from the RAW's preview)" : ""));
    }

    /**
     * Makes film previews off the main thread: decodes the photo once at about screen size, then puts each
     * requested film on a copy of those pixels. Only the newest request matters; older ones are skipped.
     */
    private class PreviewWorker extends Thread {
        private File wantFile;
        private int wantOrientation, wantGen;
        private FilmLook wantLook;
        private boolean pending, quit;
        // the photo on screen, decoded once
        private File baseFile;
        private int[] base, out;
        private int bw, bh;

        PreviewWorker() {
            super("film-preview");
        }

        synchronized void request(File f, int orientation, FilmLook look, int gen) {
            wantFile = f;
            wantOrientation = orientation;
            wantLook = look;
            wantGen = gen;
            pending = true;
            notifyAll();
        }

        synchronized void quit() {
            quit = true;
            notifyAll();
        }

        private synchronized boolean superseded() {
            return pending || quit;
        }

        @Override
        public void run() {
            while (true) {
                File f;
                int orientation;
                final int gen;
                FilmLook look;
                synchronized (this) {
                    while (!pending && !quit) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (quit) return;
                    pending = false;
                    f = wantFile;
                    orientation = wantOrientation;
                    look = wantLook;
                    gen = wantGen;
                }
                try {
                    final long t0 = System.currentTimeMillis();
                    final boolean fresh = !f.equals(baseFile);
                    if (fresh) {
                        baseFile = null;
                        base = null;
                        load(f, orientation);
                        baseFile = f;
                        if (superseded()) continue;
                    }
                    final long t1 = System.currentTimeMillis();
                    if (out == null || out.length != base.length) out = new int[base.length];
                    System.arraycopy(base, 0, out, 0, base.length);
                    if (!look.isIdentity()) look.apply(out, 0, out.length);
                    final Bitmap b = Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888);
                    final long t2 = System.currentTimeMillis();
                    final String size = bw + "x" + bh, name = f.getName();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            log("preview " + name + " " + size + (fresh ? " decode " + (t1 - t0) + " ms," : "") + " film " + (t2 - t1) + " ms");
                            showPreview(b, gen);
                        }
                    });
                } catch (final Throwable t) {
                    out = null;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            log("film preview failed: " + t);
                            if (playback && gen == previewGen) {
                                showMessage(t instanceof OutOfMemoryError ? "Not enough memory for a preview right now"
                                        : "Could not preview this photo: " + t.getMessage());
                            }
                        }
                    });
                }
            }
        }

        /** Decodes the photo (or the preview inside a RAW) at roughly screen size into base. */
        private void load(File f, int orientation) throws IOException {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            byte[] jpeg = null;
            if (f.getName().toUpperCase().endsWith(".ARW")) {
                jpeg = ArwPreview.read(f);
                if (jpeg == null) throw new IOException("no preview inside the RAW file");
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            } else {
                BitmapFactory.decodeFile(f.getPath(), o);
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 640) o.inSampleSize *= 2;
            Bitmap b = jpeg != null ? BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o) : BitmapFactory.decodeFile(f.getPath(), o);
            jpeg = null;
            if (b == null) throw new IOException("cannot decode " + f.getName());
            int deg = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            bw = b.getWidth();
            bh = b.getHeight();
            int[] px = new int[bw * bh];
            b.getPixels(px, 0, bw, 0, 0, bw, bh);
            b.recycle();
            base = px;
        }
    }

    /** Decodes the developed copy at screen size and shows it over the original. */
    private boolean showDevCopy(File dev, int orientation) {
        devView.setImageBitmap(null);
        releaseDevBitmap();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(dev.getPath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 800) o.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(dev.getPath(), o);
            if (b == null) {
                devView.setVisibility(View.GONE);
                return false;
            }
            int deg = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            devBitmap = b;
            devView.setImageBitmap(b);
            devView.setVisibility(View.VISIBLE);
            return true;
        } catch (Throwable t) {
            log("showDevCopy failed: " + t);
            devView.setVisibility(View.GONE);
            return false;
        }
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
        if (isToggleKey(scan)) {
            // holding a button a moment too long repeats it, which closed and reopened the menu
            long now = event.getEventTime();
            if (event.getRepeatCount() > 0 || (scan == lastToggleKey && now - lastToggleTime < TOGGLE_GAP_MS)) return true;
            lastToggleKey = scan;
            lastToggleTime = now;
        }
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
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                turnDial(rearDial, scan == ScalarInput.ISV_DIAL_2_CLOCKWISE ? 1 : -1, true);
                return true;
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
                turnDial(frontDial, scan == ScalarInput.ISV_DIAL_1_CLOCKWISE ? 1 : -1, false);
                return true;
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

    private static boolean isToggleKey(int scan) {
        switch (scan) {
            case ScalarInput.ISV_KEY_FN:
            case ScalarInput.ISV_KEY_ENTER:
            case ScalarInput.ISV_KEY_WB:
            case ScalarInput.ISV_KEY_CUSTOM:
            case ScalarInput.ISV_KEY_CUSTOM1:
            case ScalarInput.ISV_KEY_CUSTOM2:
            case ScalarInput.ISV_KEY_DISP:
            case ScalarInput.ISV_KEY_PLAY:
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
            case ScalarInput.ISV_KEY_STASTOP:
            case ScalarInput.ISV_KEY_2ND_STASTOP:
                return true;
        }
        return false;
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
        if (aperture > 0) sb.append(formatAperture()).append("    ");
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

    private String formatAperture() {
        float f = aperture / 100f;
        return "F" + (f >= 10 || f == Math.floor(f) && f >= 4 ? String.valueOf(Math.round(f)) : String.format("%.1f", f));
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
        // films are remembered by brand and name: the bundled file numbers change when films are added
        String filmKey = prefs.getString("filmname", "");
        film = null;
        if (library != null) {
            for (FilmLibrary.Film f : library.all()) {
                if (filmKey.equals(f.external != null ? f.file : favKey(f))) film = f;
            }
        }
        pendingFilmKey = film == null && filmKey.startsWith("custom:") ? filmKey : null;
        frontDial = Math.max(0, Math.min(TURN_NAMES.length - 1, prefs.getInt("frontdial", 0)));
        rawMode = prefs.getString("rawmode", "");
        rearDial = Math.max(0, Math.min(TURN_NAMES.length - 1, prefs.getInt("reardial", 0)));
        look = null;
        bypass = false;
        savedWb = prefs.getString("wb", null);
        halation = prefs.getBoolean("halation", false);
        grain = Math.max(0, Math.min(GRAIN_NAMES.length - 1, prefs.getInt("grain", 0)));
        lightLeak = prefs.getBoolean("leak", false);
        largeCopy = prefs.getBoolean("largecopy", false);
        dro = Math.max(0, prefs.getInt("dro", 0));
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
                .putString("filmname", film == null ? "" : film.external != null ? film.file : favKey(film))
                .putInt("frontdial", frontDial)
                .putInt("reardial", rearDial)
                .putString("rawmode", rawMode)
                .putString("wb", savedWb)
                .putInt("kelvin", savedKelvin)
                .putBoolean("halation", halation)
                .putInt("grain", grain)
                .putBoolean("leak", lightLeak)
                .putBoolean("largecopy", largeCopy)
                .putInt("dro", dro)
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
