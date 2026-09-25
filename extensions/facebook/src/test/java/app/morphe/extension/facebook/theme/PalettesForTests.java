/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

/**
 * Wallpaper palettes of other hues, standing in for phones' own. Each is the palette Material's
 * colour utilities build from a seed colour, darkest tone first, the way TonePalette holds them:
 * accent, neutral, neutral variant.
 */
public final class PalettesForTests {
    private PalettesForTests() {}

    /** From #B3261E, a red wallpaper (hue 26), tonal spot: accent chroma 36. */
    public static final int[][] RED = parse(
            "000000 3B0906 561E18 73342C 904A42 AD6258 CC7B70 EA9589 FFB4AA FFDAD5 FFEDEA FFFBFF FFFFFF",
            "000000 231918 392E2C 504442 685B5A 827472 9C8D8B B8A8A5 D4C3C0 F1DEDC FFEDEA FFFBFF FFFFFF",
            "000000 251917 3B2D2B 534341 6B5A58 857370 A08C89 BBA7A4 D8C2BE F5DDDA FFEDEA FFFBFF FFFFFF");

    /** From #FFD600, a yellow wallpaper (hue 96), with a stronger accent: chroma 48. */
    public static final int[][] YELLOW = parse(
            "000000 221B00 3A3000 544600 705D00 8D7500 AA8F12 C6A930 E3C54A FFE170 FFF0C4 FFFBFF FFFFFF",
            "000000 1E1B13 333027 4A463D 625E53 7B776B 969084 B1AB9E CCC6B9 E9E2D4 F7F0E2 FFFBFF FFFFFF",
            "000000 1F1B10 343024 4B4639 635E50 7C7767 979080 B2AB9A CEC6B4 EAE2CF F9F0DD FFFBFF FFFFFF");

    /** From #386A20, a green wallpaper (hue 138), tonal spot. */
    public static final int[][] GREEN = parse(
            "000000 072100 173807 2D4F1C 446732 5C8148 759B60 8FB678 A9D291 C5EFAB D3FDB8 F7FFEC FFFFFF",
            "000000 191D16 2E312B 444840 5C5F58 757870 8F9289 A9ACA3 C5C8BE E1E4D9 F0F2E7 FBFDF3 FFFFFF",
            "000000 181D15 2D3229 43483E 5B6055 74796D 8D9286 A8ADA0 C3C8BB DFE4D7 EEF3E5 F9FEF0 FFFFFF");

    /** A palette from one of the arrays above, as a phone's own. */
    public static TonePalette palette(int[][] families) {
        return new TonePalette(families, true);
    }

    private static int[][] parse(String... families) {
        int[][] tones = new int[families.length][];
        for (int f = 0; f < families.length; f++) {
            String[] hex = families[f].split(" ");
            tones[f] = new int[hex.length];
            for (int i = 0; i < hex.length; i++) tones[f][i] = 0xFF000000 | Integer.parseInt(hex[i], 16);
        }
        return tones;
    }
}
