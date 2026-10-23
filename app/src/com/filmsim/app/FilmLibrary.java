package com.filmsim.app;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** The film stocks bundled in assets/films.txt, grouped by brand in file order. */
public class FilmLibrary {
    public static class Film {
        public final String file;
        public final String name;
        public final String brand;
        public final String type;
        /** Mean error (0-255) between the original LUT and what the camera can reproduce. */
        public final float error;
        /** Set for the user's own LUTs: the converted look on the card, instead of an app asset. */
        public final File external;

        Film(String file, String name, String brand, String type, float error) {
            this(file, name, brand, type, error, null);
        }

        Film(String file, String name, String brand, String type, float error, File external) {
            this.file = file;
            this.name = name;
            this.brand = brand;
            this.type = type;
            this.error = error;
            this.external = external;
        }

        /** Designed directly in the camera's model, so it reproduces exactly (error stored as -1). */
        public boolean designed() {
            return error < 0;
        }

        /** 3 stars = very close to the original LUT, 1 star = rough approximation. */
        public int stars() {
            return designed() || error < 4 ? 3 : error < 10 ? 2 : 1;
        }
    }

    /** Tone curve (1024 points, 0-1023) and 3x3 matrix (x1024, row-major) for one film. */
    public static class Look {
        public final int[] gamma = new int[1024];
        public final int[] matrix = new int[9];
    }

    private final List<Film> films = new ArrayList<Film>();
    private final List<String> brands = new ArrayList<String>();

    public FilmLibrary(AssetManager assets) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(assets.open("films.txt"), "UTF-8"));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                String[] f = line.split("\\|");
                if (f.length < 5) continue;
                films.add(new Film(f[0], f[1], f[2], f[3], Float.parseFloat(f[4])));
                if (!brands.contains(f[2])) brands.add(f[2]);
            }
        } finally {
            br.close();
        }
    }

    public List<Film> all() {
        return films;
    }

    public List<String> brands() {
        return brands;
    }

    public List<Film> byBrand(String brand) {
        List<Film> out = new ArrayList<Film>();
        for (Film f : films) {
            if (f.brand.equals(brand)) out.add(f);
        }
        return out;
    }

    public Film find(String file) {
        for (Film f : films) {
            if (f.file.equals(file)) return f;
        }
        return null;
    }

    /** Puts the user's LUTs in their own brand at the front of the list (replacing any from before). */
    public void setCustom(List<Film> custom) {
        for (int i = films.size() - 1; i >= 0; i--) {
            if (CustomLuts.BRAND.equals(films.get(i).brand)) films.remove(i);
        }
        brands.remove(CustomLuts.BRAND);
        if (custom.isEmpty()) return;
        films.addAll(0, custom);
        brands.add(0, CustomLuts.BRAND);
    }

    public static Look load(AssetManager assets, Film film) throws IOException {
        DataInputStream in = new DataInputStream(film.external != null
                ? new FileInputStream(film.external) : assets.open("films/" + film.file));
        try {
            if (in.readInt() != 1) throw new IOException("bad film file " + film.file);
            Look look = new Look();
            for (int i = 0; i < 1024; i++) look.gamma[i] = in.readInt();
            for (int i = 0; i < 9; i++) look.matrix[i] = in.readInt();
            return look;
        } finally {
            in.close();
        }
    }
}
