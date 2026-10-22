package com.filmsim.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-designed looks built directly in the camera's own model: out = clip(M · g(x)).
 * Because they are expressed in exactly what the ISP can do, they reproduce 1:1 on the camera.
 *
 * Curve g: brightness exponent, symmetric S-contrast, highlight shoulder, then black lift / white point.
 * Matrix M = gains · A⁻¹ · chroma-op · A, where A maps RGB to (luma, u, v) and the chroma op applies
 * saturation, an anisotropic axis boost (e.g. orange/teal) and a hue rotation. Hue angles: red 0,
 * yellow 60, green 120, cyan 180, blue 240, magenta 300. Gains above 1 tint shadows but clip back to
 * neutral white, which gives tinted shadows with clean highlights.
 */
public class Designer {
    public static class Recipe {
        double brightness = 1.0;   // exponent on input; >1 darker
        double contrast = 1.0;     // S-curve power; >1 more contrast, <1 flatter
        double shoulder = 1.0;     // where highlight rolloff starts (1 = none)
        double lift = 0.0;         // output black level
        double white = 1.0;        // output white level
        double saturation = 1.0;
        double axis = 30, along = 1.0, across = 1.0; // anisotropic chroma scaling
        double hue = 0;            // hue rotation in degrees
        double r = 1, g = 1, b = 1; // channel gains applied last

        Recipe bri(double v) { brightness = v; return this; }
        Recipe con(double v) { contrast = v; return this; }
        Recipe sho(double v) { shoulder = v; return this; }
        Recipe lift(double v) { lift = v; return this; }
        Recipe white(double v) { white = v; return this; }
        Recipe sat(double v) { saturation = v; return this; }
        Recipe axis(double angle, double alongGain, double acrossGain) { axis = angle; along = alongGain; across = acrossGain; return this; }
        Recipe hue(double v) { hue = v; return this; }
        Recipe gains(double rr, double gg, double bb) { r = rr; g = gg; b = bb; return this; }
    }

    private static Recipe r() {
        return new Recipe();
    }

    /** Recipe id -> recipe. Ids are referenced from films.csv as "design:<id>". */
    public static final Map<String, Recipe> RECIPES = new LinkedHashMap<String, Recipe>();
    static {
        // CineStill
        RECIPES.put("cinestill_800t", r().con(1.3).lift(0.06).sat(1.1).axis(30, 1.25, 0.8).hue(-8).gains(1.0, 1.06, 1.16));
        RECIPES.put("cinestill_800t_cool", r().con(1.15).lift(0.03).sat(0.92).axis(30, 1.1, 0.9).gains(0.84, 0.96, 1.14));
        RECIPES.put("cinestill_50d", r().con(1.2).sho(0.8).lift(0.015).sat(1.2).axis(30, 1.05, 0.95).gains(0.97, 1.0, 1.05));
        RECIPES.put("cinestill_400d", r().con(1.25).sho(0.8).lift(0.03).sat(1.2).hue(3).gains(1.07, 1.01, 0.92));
        RECIPES.put("cinestill_bwxx", r().con(1.3).sho(0.75).lift(0.03).sat(0));
        // Kodak Vision3 / Fuji Eterna motion picture negative, scanned flat-ish
        RECIPES.put("vision3_50d", r().con(1.15).sho(0.75).lift(0.015).sat(1.05));
        RECIPES.put("vision3_250d", r().con(0.95).sho(0.65).lift(0.06).sat(0.9).hue(3).gains(1.04, 1.0, 0.95));
        RECIPES.put("vision3_200t", r().con(1.0).sho(0.7).lift(0.05).sat(0.9).gains(0.95, 1.0, 1.06));
        RECIPES.put("vision3_500t", r().bri(1.08).con(0.95).sho(0.65).lift(0.08).sat(0.82).gains(0.96, 1.02, 1.05));
        RECIPES.put("eterna_250d", r().con(0.95).sho(0.65).lift(0.06).sat(0.7).hue(8).gains(0.97, 1.05, 1.05));
        RECIPES.put("eterna_bleach", r().con(1.45).sho(0.8).lift(0.02).sat(0.45));
        // Cinematic grades
        RECIPES.put("teal_orange", r().con(1.2).lift(0.03).sat(1.05).axis(30, 1.35, 0.7).gains(1.0, 1.02, 1.06));
        RECIPES.put("bleach_bypass", r().con(1.5).sho(0.85).sat(0.5));
        RECIPES.put("golden_hour", r().con(1.1).sho(0.8).lift(0.03).sat(1.1).hue(3).gains(1.08, 1.02, 0.90));
        RECIPES.put("blue_hour", r().bri(1.15).con(1.25).lift(0.02).sat(0.9).gains(0.88, 0.96, 1.10));
        RECIPES.put("day_for_night", r().bri(1.6).con(1.2).lift(0.01).sat(0.5).gains(0.75, 0.9, 1.15));
        RECIPES.put("neon_noir", r().bri(1.2).con(1.35).lift(0.02).sat(1.12).axis(270, 1.3, 0.8).gains(1.0, 0.98, 1.06));
        RECIPES.put("technicolor", r().con(1.25).sho(0.85).lift(0.02).sat(1.4).axis(0, 1.15, 1.0));
        RECIPES.put("faded_70s", r().con(0.9).sho(0.7).lift(0.10).white(0.92).sat(0.8).hue(6).gains(1.06, 1.02, 0.92));
        RECIPES.put("super8", r().con(1.3).sho(0.8).lift(0.05).white(0.96).sat(1.15).gains(1.06, 1.0, 0.92));
        RECIPES.put("matrix_green", r().con(1.3).lift(0.02).sat(0.7).gains(0.92, 1.06, 0.92));
        RECIPES.put("pastel_symmetry", r().con(0.95).sho(0.7).lift(0.08).sat(0.9).hue(8).gains(1.04, 1.0, 0.98));
        RECIPES.put("desert", r().con(1.15).sho(0.8).lift(0.03).sat(0.85).gains(1.08, 1.0, 0.85));
        // Consumer and pro colour negatives
        RECIPES.put("kodak_ultramax_400", r().con(1.2).sho(0.75).lift(0.025).sat(1.18).hue(3).gains(1.05, 1.01, 0.94));
        RECIPES.put("kodak_colorplus_200", r().con(1.1).sho(0.7).lift(0.04).sat(0.95).hue(5).gains(1.06, 1.02, 0.92));
        RECIPES.put("kodak_gold_200_warm", r().con(1.12).sho(0.75).lift(0.03).sat(1.1).hue(4).gains(1.07, 1.03, 0.90));
        RECIPES.put("kodak_portra_160", r().con(0.98).sho(0.65).lift(0.03).sat(0.88).axis(30, 1.05, 0.9).gains(1.02, 1.0, 0.98));
        RECIPES.put("kodak_portra_800", r().con(1.08).sho(0.7).lift(0.04).sat(0.95).gains(1.03, 1.0, 0.97));
        RECIPES.put("fuji_c200", r().con(1.05).sho(0.75).lift(0.03).sat(1.0).hue(-4).gains(0.97, 1.03, 1.01));
        RECIPES.put("fuji_superia_xtra_400", r().con(1.18).sho(0.8).lift(0.025).sat(1.12).hue(-3).gains(0.98, 1.03, 1.0));
        RECIPES.put("fuji_pro_400h", r().con(0.95).sho(0.6).lift(0.05).sat(0.85).gains(0.98, 1.02, 1.03));
    }

    private static final double[] LUMA = {0.299, 0.587, 0.114};

    public static LutParams build(String id) {
        Recipe rc = RECIPES.get(id);
        if (rc == null) throw new IllegalArgumentException("unknown recipe " + id);
        LutParams p = new LutParams();
        for (int i = 0; i < LutParams.KNOTS; i++) {
            double x = i / (double) (LutParams.KNOTS - 1);
            p.gamma[i] = (int) Math.round(curve(rc, x) * 1023);
        }
        for (int i = 1; i < LutParams.KNOTS; i++) {
            if (p.gamma[i] < p.gamma[i - 1]) p.gamma[i] = p.gamma[i - 1];
        }
        double[][] m = matrix(rc);
        for (int j = 0; j < 3; j++) {
            for (int c = 0; c < 3; c++) {
                p.matrix[j * 3 + c] = (int) Math.round(m[j][c] * LutParams.MATRIX_SCALE);
            }
        }
        return p;
    }

    static double curve(Recipe rc, double x) {
        double y = Math.pow(x, rc.brightness);
        if (rc.contrast != 1.0) {
            double a = Math.pow(y, rc.contrast), b = Math.pow(1 - y, rc.contrast);
            y = a + b == 0 ? y : a / (a + b);
        }
        double s = rc.shoulder;
        if (s < 1.0 && y > s) {
            double k = 1 - s;
            y = s + k * (1 - Math.exp(-(y - s) / k)) / (1 - Math.exp(-1));
        }
        return rc.lift + (rc.white - rc.lift) * Math.max(0, Math.min(1, y));
    }

    static double[][] matrix(Recipe rc) {
        double[] e1 = {2 / Math.sqrt(6), -1 / Math.sqrt(6), -1 / Math.sqrt(6)};
        double[] e2 = {0, 1 / Math.sqrt(2), -1 / Math.sqrt(2)};
        double[][] a = {LUMA, e1, e2};
        double[][] ai = inv3(a);

        // chroma op in (u, v): rotate to axis, scale, rotate back, global saturation, then hue rotation
        double t = Math.toRadians(rc.axis);
        double[][] toAxis = {{Math.cos(t), Math.sin(t)}, {-Math.sin(t), Math.cos(t)}};
        double[][] scale = {{rc.along, 0}, {0, rc.across}};
        double[][] fromAxis = {{Math.cos(t), -Math.sin(t)}, {Math.sin(t), Math.cos(t)}};
        double h = Math.toRadians(rc.hue);
        double[][] rot = {{Math.cos(h), -Math.sin(h)}, {Math.sin(h), Math.cos(h)}};
        double[][] op2 = mul2(rot, mul2(fromAxis, mul2(scale, toAxis)));
        double[][] op = {
            {1, 0, 0},
            {0, op2[0][0] * rc.saturation, op2[0][1] * rc.saturation},
            {0, op2[1][0] * rc.saturation, op2[1][1] * rc.saturation},
        };
        double[][] m = mul3(ai, mul3(op, a));
        double[] gains = {rc.r, rc.g, rc.b};
        for (int j = 0; j < 3; j++) {
            for (int c = 0; c < 3; c++) m[j][c] *= gains[j];
        }
        return m;
    }

    private static double[][] mul2(double[][] x, double[][] y) {
        double[][] o = new double[2][2];
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) o[i][j] = x[i][0] * y[0][j] + x[i][1] * y[1][j];
        return o;
    }

    private static double[][] mul3(double[][] x, double[][] y) {
        double[][] o = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) o[i][j] = x[i][0] * y[0][j] + x[i][1] * y[1][j] + x[i][2] * y[2][j];
        return o;
    }

    private static double[][] inv3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        return new double[][] {
            {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det},
            {(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det},
            {(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det},
        };
    }
}
