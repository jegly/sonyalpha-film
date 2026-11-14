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
        double[] mono;             // black and white: how much each of R, G, B adds to the grey (film sensitivity)
        double[][] extra;          // extra colour matrix applied after the chroma op, before the gains

        Recipe bri(double v) { brightness = v; return this; }
        Recipe con(double v) { contrast = v; return this; }
        Recipe sho(double v) { shoulder = v; return this; }
        Recipe lift(double v) { lift = v; return this; }
        Recipe white(double v) { white = v; return this; }
        Recipe sat(double v) { saturation = v; return this; }
        Recipe axis(double angle, double alongGain, double acrossGain) { axis = angle; along = alongGain; across = acrossGain; return this; }
        Recipe hue(double v) { hue = v; return this; }
        Recipe gains(double rr, double gg, double bb) { r = rr; g = gg; b = bb; return this; }
        /** Black and white with the film's own colour sensitivity; the weights are scaled to add up to 1. */
        Recipe mono(double wr, double wg, double wb) { double t = wr + wg + wb; mono = new double[] {wr / t, wg / t, wb / t}; return this; }
        /** Any 3x3 colour matrix; rows that add up to 1 leave greys alone. Several calls stack. */
        Recipe mix(double[][] m) { extra = extra == null ? m : mul3(m, extra); return this; }
        /** Blues lean violet and greens get cleaner: red follows (blue - green). */
        Recipe violet(double k) { return mix(new double[][] {{1, -k, k}, {0, 1, 0}, {0, 0, 1}}); }
        /** Blues lean cyan and warm tones get a touch redder: green follows (blue - red). */
        Recipe cyan(double k) { return mix(new double[][] {{1, 0, 0}, {-k, 1, k}, {0, 0, 1}}); }
        /** Blues go darker and yellows brighter, like a polariser on slide film. */
        Recipe deepBlue(double k) {
            return mix(new double[][] {{1 + k / 2, k / 2, -k}, {k / 2, 1 + k / 2, -k}, {k / 2, k / 2, 1 - k}});
        }
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
        RECIPES.put("vision2_200t", r().con(1.08).sho(0.7).lift(0.05).sat(0.92).gains(0.98, 1.0, 1.02));
        RECIPES.put("vision2_500t", r().con(1.08).sho(0.68).lift(0.07).sat(0.88).hue(2).gains(0.97, 1.0, 1.03));
        RECIPES.put("vision_500t_5279", r().con(1.12).sho(0.7).lift(0.06).sat(0.9).hue(3).gains(1.0, 1.0, 1.02));
        RECIPES.put("exr_5248", r().con(1.15).sho(0.75).lift(0.04).sat(1.0).gains(1.02, 1.0, 0.97));
        RECIPES.put("ektachrome_100d", r().con(1.25).sho(0.85).lift(0.01).sat(1.2).gains(0.98, 1.0, 1.04));
        RECIPES.put("double_x_5222", r().con(1.35).sho(0.8).lift(0.03).sat(0));
        RECIPES.put("trix_7266", r().bri(1.05).con(1.5).sho(0.85).sat(0));
        RECIPES.put("orwo_un54", r().con(1.25).sho(0.75).lift(0.05).sat(0));
        RECIPES.put("eterna_500t", r().con(1.0).sho(0.65).lift(0.07).sat(0.75).hue(6).gains(0.97, 1.04, 1.05));
        RECIPES.put("eterna_vivid_160", r().con(1.12).sho(0.7).lift(0.04).sat(1.05).hue(4).gains(0.99, 1.03, 1.01));
        RECIPES.put("reala_500d", r().con(1.05).sho(0.7).lift(0.05).sat(0.95).hue(3).gains(1.0, 1.02, 1.0));
        RECIPES.put("technicolor_2strip", r().con(1.2).sho(0.85).lift(0.02).sat(1.1).axis(10, 1.3, 0.25).gains(1.02, 0.98, 0.98));
        RECIPES.put("kodak_2302", r().con(1.4).sho(0.9).sat(0));
        RECIPES.put("kodachrome_40_super8", r().con(1.3).sho(0.85).lift(0.01).sat(1.25).axis(0, 1.15, 0.95).hue(-2).gains(1.03, 1.0, 0.96));
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
        // More Kodak
        RECIPES.put("kodak_gold_100", r().con(1.15).sho(0.8).lift(0.02).sat(1.12).hue(3).gains(1.05, 1.02, 0.93));
        RECIPES.put("kodak_gold_400", r().con(1.18).sho(0.75).lift(0.035).sat(1.08).hue(4).gains(1.06, 1.02, 0.91));
        RECIPES.put("kodak_pro_image_100", r().con(1.05).sho(0.7).lift(0.03).sat(1.02).hue(2).gains(1.04, 1.01, 0.95));
        RECIPES.put("kodak_e100", r().con(1.22).sho(0.88).lift(0.005).sat(1.15).gains(0.98, 1.0, 1.03));
        // 1990s warm Ektachrome slide film, measured from reportage photos: true blacks, neutral greys, deep reds and ochres
        RECIPES.put("ektachrome_panther_100", r().bri(1.08).con(1.22).sho(0.8).sat(1.0).axis(25, 1.14, 0.96).gains(1.02, 1.0, 0.97));
        RECIPES.put("ektachrome_elite_100", r().bri(1.12).con(1.3).sho(0.82).sat(1.02).axis(15, 1.2, 0.93).hue(-2).gains(1.03, 1.0, 0.95));
        RECIPES.put("kodak_vericolor_iii", r().con(0.92).sho(0.6).lift(0.06).sat(0.82).hue(4).gains(1.04, 1.01, 0.95));
        RECIPES.put("kodak_plus_x", r().con(1.2).sho(0.8).lift(0.02).mono(0.28, 0.56, 0.16));
        RECIPES.put("kodak_panatomic_x", r().con(1.3).sho(0.85).lift(0.01).mono(0.25, 0.6, 0.15));
        RECIPES.put("kodak_technical_pan", r().con(1.6).sho(0.9).mono(0.5, 0.4, 0.1));
        // false-colour infrared: foliage turns red and pink, skies stay blue
        RECIPES.put("kodak_aerochrome", r().con(1.2).sho(0.85).lift(0.01)
                .mix(new double[][] {{0.2, 1.3, -0.5}, {0.6, 0.1, 0.3}, {0.1, 0.0, 0.9}}));
        // More Fujifilm
        RECIPES.put("fuji_velvia_100f", r().con(1.3).sho(0.88).sat(1.3).violet(0.05).gains(1.0, 1.0, 1.02));
        RECIPES.put("fuji_fortia_sp", r().con(1.35).sho(0.9).sat(1.5).axis(0, 1.1, 1.0).gains(1.02, 1.0, 1.0));
        RECIPES.put("fuji_natura_1600", r().con(1.0).sho(0.65).lift(0.05).sat(0.95).hue(-4).gains(0.98, 1.03, 1.02));
        RECIPES.put("fuji_pro_160ns", r().con(0.95).sho(0.62).lift(0.04).sat(0.88).hue(-2).gains(0.99, 1.02, 1.01));
        RECIPES.put("fuji_industrial_100", r().con(1.12).sho(0.78).lift(0.03).sat(1.0).hue(-5).cyan(0.06).gains(0.97, 1.03, 1.03));
        // 1980s Fuji colour negative, measured from a lab-printed landscape: soft, pale skies, a faint cool-magenta cast
        RECIPES.put("fujicolor_hr_100", r().con(0.95).sho(0.65).lift(0.04).sat(0.85).hue(3).gains(1.02, 0.99, 1.03));
        RECIPES.put("fujicolor_100", r().con(1.1).sho(0.8).lift(0.025).sat(1.05).hue(-3).gains(0.98, 1.02, 1.0));
        RECIPES.put("xtrans_classic_neg", r().con(1.3).sho(0.8).lift(0.02).sat(0.8).axis(30, 1.15, 0.8).cyan(0.08).gains(1.02, 1.0, 0.97));
        RECIPES.put("xtrans_nostalgic_neg", r().con(1.05).sho(0.7).lift(0.04).sat(1.0).hue(5).axis(40, 1.15, 0.9).gains(1.06, 1.02, 0.92));
        RECIPES.put("xtrans_eterna", r().con(0.88).sho(0.6).lift(0.05).sat(0.72).gains(0.99, 1.01, 1.02));
        RECIPES.put("xtrans_reala_ace", r().con(1.1).sho(0.78).lift(0.02).sat(1.0).hue(-1));
        // Fuji slide film on medium format: violet-leaning blues, deep skies, rich greens
        RECIPES.put("velvia_50_120", r().bri(1.08).con(1.32).sho(0.88).sat(1.32).violet(0.14).deepBlue(0.10).gains(1.02, 1.0, 1.04));
        // Measured from 6x7 slides with RDP III and RVP100F on the film edge: sky blue has red nearly level with green
        // (periwinkle, 120/130/200), greens are deep with no yellow in them, yellow fields go olive-gold, clouds lilac-pink.
        RECIPES.put("provia_100f_120", r().bri(1.06).con(1.2).sho(0.85).sat(1.05).violet(0.3).mix(new double[][] {{1, 0, 0}, {0.2, 0.8, 0}, {0, 0, 1}}).gains(1.0, 0.98, 1.04));
        RECIPES.put("velvia_100f_120", r().bri(1.08).con(1.26).sho(0.88).sat(1.12).violet(0.34).mix(new double[][] {{1, 0, 0}, {0.24, 0.76, 0}, {0, 0, 1}}).gains(1.02, 0.97, 1.05));
        RECIPES.put("velvia_polarised", r().con(1.3).sho(0.9).sat(1.35).violet(0.04).deepBlue(0.22));
        // Ilford, Agfa and others
        RECIPES.put("ilford_ortho_plus", r().con(1.25).sho(0.8).lift(0.02).mono(0.02, 0.58, 0.40));
        RECIPES.put("ilford_sfx_200", r().con(1.35).sho(0.85).lift(0.02).mono(0.7, 0.4, -0.1));
        RECIPES.put("kentmere_400", r().con(1.1).sho(0.7).lift(0.04).mono(0.3, 0.55, 0.15));
        RECIPES.put("agfa_scala_200x", r().con(1.45).sho(0.9).mono(0.33, 0.52, 0.15));
        RECIPES.put("agfa_optima_200", r().con(1.12).sho(0.78).lift(0.03).sat(1.08).axis(0, 1.12, 0.95).hue(2).gains(1.03, 1.0, 0.97));
        RECIPES.put("harman_phoenix_200", r().con(1.45).sho(0.92).lift(0.01).sat(1.2).hue(6).axis(30, 1.2, 0.85).gains(1.05, 1.0, 0.94));
        RECIPES.put("fomapan_100", r().con(1.15).sho(0.75).lift(0.03).mono(0.27, 0.55, 0.18));
        RECIPES.put("fomapan_400", r().con(1.25).sho(0.75).lift(0.05).mono(0.38, 0.5, 0.12));
        RECIPES.put("orwo_nc500", r().con(0.95).sho(0.65).lift(0.07).sat(0.75).hue(-6).gains(0.97, 1.04, 0.98));
        RECIPES.put("adox_color_mission", r().con(1.1).sho(0.75).lift(0.03).sat(1.1).axis(0, 1.12, 0.95).gains(1.03, 1.0, 0.98));
        RECIPES.put("adox_cms_20", r().con(1.7).sho(0.95).mono(0.3, 0.59, 0.11));
        RECIPES.put("jch_streetpan_400", r().con(1.45).sho(0.85).lift(0.01).mono(0.45, 0.47, 0.08));
        RECIPES.put("bergger_pancro_400", r().con(0.95).sho(0.6).lift(0.06).mono(0.3, 0.55, 0.15));
        // More cine
        RECIPES.put("cinestill_800t_push", r().bri(1.05).con(1.5).lift(0.04).sat(1.2).axis(30, 1.3, 0.8).hue(-8).gains(1.0, 1.05, 1.18));
        RECIPES.put("cinestill_redrum", r().con(1.3).lift(0.03).sat(0.8).axis(25, 1.2, 0.3).gains(1.2, 0.9, 0.55));
        RECIPES.put("eastman_5247", r().con(1.1).sho(0.7).lift(0.05).sat(0.92).hue(4).gains(1.04, 1.01, 0.94));
        RECIPES.put("eastman_5254", r().con(1.2).sho(0.75).lift(0.04).sat(0.85).hue(6).gains(1.07, 1.02, 0.88));
        RECIPES.put("vision3_500t_push", r().bri(1.05).con(1.25).sho(0.7).lift(0.06).sat(0.95).gains(0.95, 1.02, 1.08));
        RECIPES.put("nordic_noir", r().bri(1.1).con(1.2).lift(0.03).sat(0.55).gains(0.92, 1.0, 1.06));
        RECIPES.put("hong_kong_neon", r().con(1.3).lift(0.03).sat(1.2).hue(-6).axis(350, 1.25, 0.85).gains(1.02, 1.06, 0.92));
        RECIPES.put("paris_gold", r().con(1.15).sho(0.8).lift(0.03).sat(1.15).axis(15, 1.2, 0.8).gains(1.06, 1.04, 0.86));
        RECIPES.put("silver_screen", r().con(1.1).sho(0.6).lift(0.05).white(0.97).mono(0.2, 0.6, 0.2));
        // Moods
        RECIPES.put("pastel_sky", r().con(0.95).sho(0.65).lift(0.05).sat(1.0).axis(20, 1.1, 0.95).cyan(0.18).gains(1.04, 1.0, 0.98));
        RECIPES.put("airy_blue", r().bri(0.85).con(0.88).sho(0.6).lift(0.07).sat(0.85).cyan(0.12).gains(0.98, 1.01, 1.05));
        RECIPES.put("lilac_dusk", r().con(1.0).sho(0.7).lift(0.06).sat(1.05).violet(0.16).gains(1.05, 0.97, 1.06));
        RECIPES.put("nordic_night", r().bri(1.15).con(1.25).lift(0.02).sat(1.05).axis(30, 1.3, 0.75).cyan(0.06).gains(0.9, 1.0, 1.1));
        // measured from medium format colour negative portraits: teal-cyan pastel skies, cream whites, coral skin
        RECIPES.put("pastel_portrait", r().con(1.0).sho(0.7).lift(0.04).white(0.96)
                .mix(new double[][] {{0.94, 0, 0.06}, {-0.05, 0.83, 0.22}, {0, 0.12, 0.88}}).gains(1.0, 0.94, 0.87));
        RECIPES.put("deep_sky", r().con(1.2).sho(0.88).sat(1.2).violet(0.07).deepBlue(0.2));
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
        if (rc.mono != null) m = new double[][] {rc.mono.clone(), rc.mono.clone(), rc.mono.clone()};
        if (rc.extra != null) m = mul3(rc.extra, m);
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
