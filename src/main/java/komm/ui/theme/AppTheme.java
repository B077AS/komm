package komm.ui.theme;

import lombok.Getter;

@Getter
public enum AppTheme {

    PURPLE("Purple", "#9580ff",
            "#e5dfff", "#d5ccff", "#c5b9ff", "#b5a6ff", "#a593ff",
            "#9580ff", "#7f6dd9", "#685ab3", "#52468c", "#3c3366",
            149, 128, 255, "#44475a"),

    RED("Red", "#ff3355",
            "#ffe5ea", "#ffccd5", "#ffb3bf", "#ff99aa", "#ff6680",
            "#ff3355", "#d92244", "#b31133", "#8c0022", "#660011",
            255, 51, 85, "#5a4449"),

    ORANGE("Orange", "#ff8c42",
            "#fff0e5", "#ffe2cc", "#ffd4b3", "#ffc699", "#ffb880",
            "#ff8c42", "#d97538", "#b35e2d", "#8c4722", "#663017",
            255, 140, 66, "#5a4c44"),

    YELLOW("Yellow", "#f0c040",
            "#fff9e5", "#fff3cc", "#ffedb3", "#ffe799", "#ffde6a",
            "#f0c040", "#cca030", "#a88020", "#846010", "#604800",
            240, 192, 64, "#5a5444"),

    GREEN("Green", "#50d090",
            "#e5fff4", "#ccffe9", "#b3ffde", "#80ffc8", "#65e0a8",
            "#50d090", "#40b078", "#328c5e", "#246844", "#16442c",
            80, 208, 144, "#445a50"),

    LIGHT_GREEN("Light Green", "#a0ff60",
            "#f0ffe5", "#e2ffcc", "#d4ffb3", "#c6ff99", "#b8ff80",
            "#a0ff60", "#80d948", "#60b330", "#448c1a", "#2c6606",
            160, 255, 96, "#4b5a44"),

    TEAL("Teal", "#00c8d8",
            "#e0fafc", "#c0f5f9", "#a0f0f6", "#60e6f0", "#30d6e8",
            "#00c8d8", "#00a8b5", "#008890", "#00686c", "#004848",
            0, 200, 216, "#44595a"),

    LIGHT_BLUE("Light Blue", "#7dd3fc",
            "#e5f6ff", "#ccedff", "#b3e4ff", "#99dbff", "#80d2ff",
            "#7dd3fc", "#5cb0d4", "#3d8dac", "#1f6a84", "#09485c",
            125, 211, 252, "#44535a"),

    BLUE("Blue", "#5b9cf6",
            "#e5eeff", "#ccdcff", "#b3cbff", "#99b9ff", "#80a8ff",
            "#5b9cf6", "#4478cc", "#2d56a2", "#183578", "#09194e",
            91, 156, 246, "#444d5a"),

    PINK("Pink", "#ff79c6",
            "#ffe5f5", "#ffcce9", "#ffb3de", "#ff99d2", "#ff80c7",
            "#ff79c6", "#d95ea0", "#b3437c", "#8c2858", "#660c34",
            255, 121, 198, "#5a4452"),

    MAGENTA("Magenta", "#e040fb",
            "#fae5ff", "#f5ccff", "#f0b3ff", "#eb99ff", "#e680ff",
            "#e040fb", "#bc30d4", "#9820ac", "#741084", "#50005c",
            224, 64, 251, "#56445a");

    private final String displayName;
    private final String swatchColor;
    private final String cssOverride;

    AppTheme(String displayName, String swatchColor,
             String c0, String c1, String c2, String c3, String c4,
             String c5, String c6, String c7, String c8, String c9,
             int r, int g, int b, String highlight) {
        this.displayName = displayName;
        this.swatchColor = swatchColor;
        this.cssOverride = buildOverride(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, r, g, b, highlight);
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
}
