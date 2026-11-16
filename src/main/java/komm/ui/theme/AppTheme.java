package komm.ui.theme;

import lombok.Getter;

/**
 * Accent colour choices. Each accent has a dark-mode palette (the bright original swatch and its
 * 0-9 ramp) and a light-mode accent: the same hue darkened until it reaches at least 4.7:1 on the
 * light chat background (#f5f6f8, "Mist"), so accent text and white-on-accent buttons stay readable on the light theme. The light
 * ramp is derived from that colour (see {@link #buildLightOverride}).
 * <p>
 * Also compiled into the launcher (which only uses the dark palette): keep it free of client-only
 * dependencies.
 */
@Getter
public enum AppTheme {

    PURPLE("Purple", "#9580ff",
            "#e5dfff", "#d5ccff", "#c5b9ff", "#b5a6ff", "#a593ff",
            "#9580ff", "#7f6dd9", "#685ab3", "#52468c", "#3c3366",
            149, 128, 255, "#44475a", "#6a4dff"),

    RED("Red", "#ff3355",
            "#ffe5ea", "#ffccd5", "#ffb3bf", "#ff99aa", "#ff6680",
            "#ff3355", "#d92244", "#b31133", "#8c0022", "#660011",
            255, 51, 85, "#5a4449", "#dc0025"),

    ORANGE("Orange", "#ff8c42",
            "#fff0e5", "#ffe2cc", "#ffd4b3", "#ffc699", "#ffb880",
            "#ff8c42", "#d97538", "#b35e2d", "#8c4722", "#663017",
            255, 140, 66, "#5a4c44", "#bc4a00"),

    YELLOW("Yellow", "#f0c040",
            "#fff9e5", "#fff3cc", "#ffedb3", "#ffe799", "#ffde6a",
            "#f0c040", "#cca030", "#a88020", "#846010", "#604800",
            240, 192, 64, "#5a5444", "#8a670b"),

    GREEN("Green", "#50d090",
            "#e5fff4", "#ccffe9", "#b3ffde", "#80ffc8", "#65e0a8",
            "#50d090", "#40b078", "#328c5e", "#246844", "#16442c",
            80, 208, 144, "#445a50", "#217c4f"),

    LIGHT_GREEN("Light Green", "#a0ff60",
            "#f0ffe5", "#e2ffcc", "#d4ffb3", "#c6ff99", "#b8ff80",
            "#a0ff60", "#80d948", "#60b330", "#448c1a", "#2c6606",
            160, 255, 96, "#4b5a44", "#327d00"),

    TEAL("Teal", "#00c8d8",
            "#e0fafc", "#c0f5f9", "#a0f0f6", "#60e6f0", "#30d6e8",
            "#00c8d8", "#00a8b5", "#008890", "#00686c", "#004848",
            0, 200, 216, "#44595a", "#007982"),

    LIGHT_BLUE("Light Blue", "#7dd3fc",
            "#e5f6ff", "#ccedff", "#b3e4ff", "#99dbff", "#80d2ff",
            "#7dd3fc", "#5cb0d4", "#3d8dac", "#1f6a84", "#09485c",
            125, 211, 252, "#44535a", "#0474a9"),

    BLUE("Blue", "#5b9cf6",
            "#e5eeff", "#ccdcff", "#b3cbff", "#99b9ff", "#80a8ff",
            "#5b9cf6", "#4478cc", "#2d56a2", "#183578", "#09194e",
            91, 156, 246, "#444d5a", "#0d67e5"),

    PINK("Pink", "#ff79c6",
            "#ffe5f5", "#ffcce9", "#ffb3de", "#ff99d2", "#ff80c7",
            "#ff79c6", "#d95ea0", "#b3437c", "#8c2858", "#660c34",
            255, 121, 198, "#5a4452", "#d5007a"),

    MAGENTA("Magenta", "#e040fb",
            "#fae5ff", "#f5ccff", "#f0b3ff", "#eb99ff", "#e680ff",
            "#e040fb", "#bc30d4", "#9820ac", "#741084", "#50005c",
            224, 64, 251, "#56445a", "#ba05d9");

    private final String displayName;
    /** Dark-mode accent (the original, bright swatch). */
    private final String swatchColor;
    /** Light-mode accent: same hue, contrast-tuned for white backgrounds. */
    private final String lightSwatchColor;
    /** Dark-mode {@code .root} override (also what the launcher uses). */
    private final String cssOverride;
    private final String lightCssOverride;

    AppTheme(String displayName, String swatchColor,
             String c0, String c1, String c2, String c3, String c4,
             String c5, String c6, String c7, String c8, String c9,
             int r, int g, int b, String highlight, String lightAccent) {
        this.displayName = displayName;
        this.swatchColor = swatchColor;
        this.lightSwatchColor = lightAccent;
        this.cssOverride = buildOverride(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, r, g, b, highlight);
        this.lightCssOverride = buildLightOverride(lightAccent);
    }

    /** The accent swatch for the given mode. */
    public String swatchColor(boolean dark) {
        return dark ? swatchColor : lightSwatchColor;
    }

    /** The {@code .root} looked-up colour overrides for this accent in the given mode. */
    public String getCssOverride(boolean dark) {
        return dark ? cssOverride : lightCssOverride;
    }

    private static String buildOverride(
            String c0, String c1, String c2, String c3, String c4,
            String c5, String c6, String c7, String c8, String c9,
            int r, int g, int b, String highlight) {
        return "-color-accent-0:" + c0 + ";" +
               "-color-accent-1:" + c1 + ";" +
               "-color-accent-2:" + c2 + ";" +
               "-color-accent-3:" + c3 + ";" +
               "-color-accent-4:" + c4 + ";" +
               "-color-accent-5:" + c5 + ";" +
               "-color-accent-6:" + c6 + ";" +
               "-color-accent-7:" + c7 + ";" +
               "-color-accent-8:" + c8 + ";" +
               "-color-accent-9:" + c9 + ";" +
               "-color-accent-fg:" + c5 + ";" +
               "-color-accent-emphasis:" + c5 + ";" +
               "-color-accent-muted:rgba(" + r + "," + g + "," + b + ",0.4);" +
               "-color-accent-subtle:rgba(" + r + "," + g + "," + b + ",0.1);" +
               "-color-highlight:" + highlight + ";" +
               "-color-fg-self:" + c1 + ";";
    }

    /**
     * Light-mode ramp. The dark ramp runs light (0, readable on a dark background) to dark
     * (9, a subtle fill/border on a dark background); the light ramp keeps those ROLES, so it runs
     * dark (0, readable on white) to light (9, a subtle tint on white). Code that uses
     * {@code -color-accent-4} for link text or {@code -color-accent-8} for a stripe works in both modes.
     */
    private static String buildLightOverride(String accent) {
        double[] hsl = hexToHsl(accent);
        double h = hsl[0], s = hsl[1], l = hsl[2];
        String[] ramp = {
                hsl(h, s * 0.85, 0.20),
                hsl(h, s * 0.85, 0.26),
                hsl(h, s, Math.max(0.22, l - 0.10)),
                hsl(h, s, Math.max(0.24, l - 0.05)),
                accent,
                accent,
                hsl(h, s, l + (0.97 - l) * 0.35),
                hsl(h, s, l + (0.97 - l) * 0.55),
                hsl(h, s, l + (0.97 - l) * 0.72),
                hsl(h, s, l + (0.97 - l) * 0.85),
        };
        int[] rgb = hexToRgb(accent);
        String rgba = "rgba(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + ",";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ramp.length; i++) {
            sb.append("-color-accent-").append(i).append(':').append(ramp[i]).append(';');
        }
        return sb +
               "-color-accent-fg:" + accent + ";" +
               "-color-accent-emphasis:" + accent + ";" +
               "-color-accent-muted:" + rgba + "0.30);" +
               "-color-accent-subtle:" + rgba + "0.10);" +
               "-color-highlight:" + hsl(h, s * 0.85, 0.915) + ";" +
               "-color-fg-self:" + ramp[1] + ";";
    }

    // ── small colour helpers (no JavaFX dependency: the launcher compiles this class) ──

    private static int[] hexToRgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new int[]{(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff};
    }

    private static double[] hexToHsl(String hex) {
        int[] c = hexToRgb(hex);
        double r = c[0] / 255.0, g = c[1] / 255.0, b = c[2] / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double h = 0, s = 0, l = (max + min) / 2;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    private static String hsl(double h, double s, double l) {
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        double a = s * Math.min(l, 1 - l);
        int[] out = new int[3];
        int[] ns = {0, 8, 4};
        for (int i = 0; i < 3; i++) {
            double k = (ns[i] + h * 12) % 12;
            double v = l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
            out[i] = (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
        }
        return String.format("#%02x%02x%02x", out[0], out[1], out[2]);
    }
}
