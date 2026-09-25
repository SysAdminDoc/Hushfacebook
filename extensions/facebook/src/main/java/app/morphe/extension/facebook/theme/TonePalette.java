/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.Arrays;

/**
 * A wallpaper palette in the shape Android 12 and newer publish it: three families of thirteen
 * tones each, from black to white. The accent family carries the wallpaper's colour, the neutral
 * family is grey with a trace of it, and the neutral variant has a little more.
 *
 * <p>The tone of a step is its CIE lightness, L*, so two colours of the same tone have the same
 * luminance whatever their hue. {@link #sameLightness} leans on that: it answers the palette's
 * colour at the lightness of the colour it's given, so a grey keeps how light it is and text keeps
 * the contrast Facebook gave it against its background.
 */
public final class TonePalette {
    public static final int ACCENT = 0;
    public static final int NEUTRAL = 1;
    public static final int NEUTRAL_VARIANT = 2;

    /** The tone of each step, darkest first: system_*_1000, system_*_900 ... system_*_10, system_*_0. */
    public static final int[] TONES = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100};

    /**
     * The palette on Android 11, which has no wallpaper palette. It is the tonal spot palette of
     * Facebook's blue, #0866FF (hue 271.5), as Material's colour utilities build it: accent at
     * chroma 36, neutral at 6 and neutral variant at 8, darkest first. It is kept as text so that
     * no colour sweep of the APK mistakes one of these for a colour Facebook wrote.
     */
    static final String FALLBACK =
            "000000 00184A 192E60 324578 4A5C92 6375AC 7C8FC8 97AAE4 B3C5FF DBE1FF EEF0FF FEFBFF FFFFFF;"
                    + "000000 1A1B21 2F3036 46464C 5D5E64 76767D 909097 ABAAB1 C6C6CD E3E2E9 F1F0F7 FEFBFF FFFFFF;"
                    + "000000 191B23 2E3038 45464F 5C5E67 757680 8F909A AAAAB4 C5C6D0 E2E2EC F0F0FA FEFBFF FFFFFF";

    /** How many steps of L* a lookup table holds: one per tenth, 0 to 100. */
    private static final int STEPS = 1000;

    /** sRGB to linear light, one entry per channel value. */
    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    // D65 white, as sRGB defines it.
    private static final double XN = 0.95047;
    private static final double ZN = 1.08883;

    /** Opaque colours, [family][step], darkest first. */
    private final int[][] tones;

    /** Whether the tones came from the phone's wallpaper palette rather than {@link #FALLBACK}. */
    public final boolean dynamic;

    /** [family][L* in tenths]: built on first use, since the settings screen needs only the tones. */
    private volatile int[][] byLightness;

    TonePalette(int[][] tones, boolean dynamic) {
        if (tones.length != 3) throw new IllegalArgumentException("three families, not " + tones.length);
        for (int[] family : tones) {
            if (family.length != TONES.length) throw new IllegalArgumentException("a family has " + family.length + " tones");
        }
        this.tones = new int[3][];
        for (int f = 0; f < 3; f++) {
            this.tones[f] = new int[TONES.length];
            for (int i = 0; i < TONES.length; i++) this.tones[f][i] = tones[f][i] | 0xFF000000;
        }
        this.dynamic = dynamic;
    }

    /** The fixed palette Android 11 gets. */
    public static TonePalette fallback() {
        String[] families = FALLBACK.split(";");
        int[][] tones = new int[3][TONES.length];
        for (int f = 0; f < 3; f++) {
            String[] hex = families[f].trim().split(" ");
            for (int i = 0; i < TONES.length; i++) tones[f][i] = 0xFF000000 | Integer.parseInt(hex[i], 16);
        }
        return new TonePalette(tones, false);
    }

    /** The phone's wallpaper palette from Android 12, and {@link #fallback()} before it or when it can't be read. */
    public static TonePalette of(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return system(context);
            } catch (RuntimeException unreadable) {
                return fallback();
            }
        }
        return fallback();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static TonePalette system(Context context) {
        int[][] ids = {
                {android.R.color.system_accent1_1000, android.R.color.system_accent1_900, android.R.color.system_accent1_800,
                        android.R.color.system_accent1_700, android.R.color.system_accent1_600, android.R.color.system_accent1_500,
                        android.R.color.system_accent1_400, android.R.color.system_accent1_300, android.R.color.system_accent1_200,
                        android.R.color.system_accent1_100, android.R.color.system_accent1_50, android.R.color.system_accent1_10,
                        android.R.color.system_accent1_0},
                {android.R.color.system_neutral1_1000, android.R.color.system_neutral1_900, android.R.color.system_neutral1_800,
                        android.R.color.system_neutral1_700, android.R.color.system_neutral1_600, android.R.color.system_neutral1_500,
                        android.R.color.system_neutral1_400, android.R.color.system_neutral1_300, android.R.color.system_neutral1_200,
                        android.R.color.system_neutral1_100, android.R.color.system_neutral1_50, android.R.color.system_neutral1_10,
                        android.R.color.system_neutral1_0},
                {android.R.color.system_neutral2_1000, android.R.color.system_neutral2_900, android.R.color.system_neutral2_800,
                        android.R.color.system_neutral2_700, android.R.color.system_neutral2_600, android.R.color.system_neutral2_500,
                        android.R.color.system_neutral2_400, android.R.color.system_neutral2_300, android.R.color.system_neutral2_200,
                        android.R.color.system_neutral2_100, android.R.color.system_neutral2_50, android.R.color.system_neutral2_10,
                        android.R.color.system_neutral2_0},
        };
        int[][] tones = new int[3][TONES.length];
        for (int f = 0; f < 3; f++) {
            for (int i = 0; i < TONES.length; i++) tones[f][i] = context.getColor(ids[f][i]);
        }
        return new TonePalette(tones, true);
    }

    /** The palette's colour for one of {@link #TONES} in a family. */
    public int tone(int family, int tone) {
        for (int i = 0; i < TONES.length; i++) {
            if (TONES[i] == tone) return tones[family][i];
        }
        throw new IllegalArgumentException("no tone " + tone);
    }

    /**
     * The colour of this family at the lightness of {@code color}, with {@code color}'s alpha.
     * Between two of the palette's steps it takes the hue and chroma between theirs, in CIELAB.
     */
    public int sameLightness(int family, int color) {
        int[][] tables = byLightness;
        if (tables == null) tables = buildTables();
        int step = (int) Math.round(lstar(color) * (STEPS / 100.0));
        if (step < 0) step = 0;
        if (step > STEPS) step = STEPS;
        return (color & 0xFF000000) | (tables[family][step] & 0x00FFFFFF);
    }

    private synchronized int[][] buildTables() {
        if (byLightness != null) return byLightness;
        int[][] tables = new int[3][STEPS + 1];
        for (int f = 0; f < 3; f++) {
            double[][] labs = new double[TONES.length][];
            for (int i = 0; i < TONES.length; i++) labs[i] = lab(tones[f][i]);
            // A phone's palette is darkest first too, but its lightness decides, not its position.
            Arrays.sort(labs, (x, y) -> Double.compare(x[0], y[0]));
            int k = 0;
            for (int step = 0; step <= STEPS; step++) {
                double l = step * (100.0 / STEPS);
                while (k < labs.length - 2 && l > labs[k + 1][0]) k++;
                double[] low = labs[k];
                double[] high = labs[k + 1];
                double span = high[0] - low[0];
                double w = span <= 0 ? 0 : Math.max(0, Math.min(1, (l - low[0]) / span));
                tables[f][step] = fromLab(l, low[1] + (high[1] - low[1]) * w, low[2] + (high[2] - low[2]) * w);
            }
        }
        byLightness = tables;
        return tables;
    }

    /** CIE L* of an sRGB colour, 0 to 100. The alpha is ignored. */
    public static double lstar(int color) {
        return yToLstar(luminance(color));
    }

    /** The relative luminance WCAG uses: linear light, weighted for the eye, 0 to 1. */
    public static double luminance(int color) {
        return 0.2126 * LINEAR[(color >> 16) & 0xFF] + 0.7152 * LINEAR[(color >> 8) & 0xFF] + 0.0722 * LINEAR[color & 0xFF];
    }

    private static double yToLstar(double y) {
        return y <= 216.0 / 24389.0 ? y * 24389.0 / 27.0 : 116.0 * Math.cbrt(y) - 16.0;
    }

    private static double[] lab(int color) {
        double r = LINEAR[(color >> 16) & 0xFF];
        double g = LINEAR[(color >> 8) & 0xFF];
        double b = LINEAR[color & 0xFF];
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / XN;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / ZN;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double labF(double t) {
        return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16) / 116;
    }

    private static double labInverse(double f) {
        double cube = f * f * f;
        return cube > 216.0 / 24389.0 ? cube : (116 * f - 16) * 27.0 / 24389.0;
    }

    private static int fromLab(double l, double a, double b) {
        double fy = (l + 16) / 116;
        double x = labInverse(fy + a / 500) * XN;
        double y = labInverse(fy);
        double z = labInverse(fy - b / 200) * ZN;
        double r = 3.2406 * x - 1.5372 * y - 0.4986 * z;
        double g = -0.9689 * x + 1.8758 * y + 0.0415 * z;
        double bl = 0.0557 * x - 0.2040 * y + 1.0570 * z;
        return 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(bl);
    }

    private static int channel(double linear) {
        double c = linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, c)) * 255);
    }
}
