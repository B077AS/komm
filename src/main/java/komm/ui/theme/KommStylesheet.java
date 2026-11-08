package komm.ui.theme;

import atlantafx.base.theme.Dracula;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the application's user-agent stylesheet: AtlantaFX's stock Dracula theme straight from
 * the jar, patched IN PLACE by {@code komm-theme.css} (the rules Komm changes, adds or drops).
 *
 * <p>Two deliberate choices:
 * <ul>
 *   <li><b>One user-agent file.</b> JavaFX gives every user-agent rule lower priority than any
 *       author stylesheet regardless of specificity, so loading the overrides as a scene
 *       stylesheet would let a low-specificity override beat the theme's own {@code :hover} /
 *       {@code :focused} rules.</li>
 *   <li><b>Patch, don't append.</b> Equal-specificity rules are decided by file order. Appending
 *       Komm's changed rules at the end flipped such ties (a {@code .text-input} override started
 *       beating the stock {@code .text-area} rule and padded every TextArea). Each override
 *       therefore replaces / follows its stock rule so the original order is preserved.</li>
 * </ul>
 * Directive syntax is documented at the top of {@code komm-theme.css}. Upgrading AtlantaFX now
 * means bumping the dependency (and reviewing overrides that mask a changed stock rule).
 *
 * <p>If building the patched sheet fails for any reason the plain stock theme is used, so the app
 * still starts (just without Komm's customisations).
 */
@Slf4j
public final class KommStylesheet {

    private static final Pattern ROOT_URL = Pattern.compile("url\\(\"(/[^\"]+)\"\\)");
    private static final Pattern FONT_FACE = Pattern.compile("@font-face\\s*\\{[^}]*\\}");
    private static final Pattern COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern DIRECTIVE =
            Pattern.compile("/\\*@(replace|after|remove|first)(?:\\s+(.*?)\\s+#(\\d+))?\\*/");

    private KommStylesheet() {}

    /** URL to pass to {@code Application.setUserAgentStylesheet}. */
    public static String userAgentUrl() {
        try {
            String theme = read(new URL(resolveThemeUrl()));
            String patch = absolutizeResourceUrls(read(resource("/komm-theme.css")));

            // JavaFX's CSS parser only accepts @font-face before the first rule, so hoist them.
            StringBuilder fontFaces = new StringBuilder();
            Matcher ff = FONT_FACE.matcher(patch);
            while (ff.find()) fontFaces.append(ff.group()).append('\n');
            patch = ff.replaceAll("");

            Path file = Files.createTempFile("komm-ua-", ".css");
            file.toFile().deleteOnExit();
            Files.writeString(file, fontFaces + patch(theme, patch), StandardCharsets.UTF_8);
            return file.toUri().toString();
        } catch (Exception e) {
            log.error("[Theme] Failed to build the patched stylesheet — falling back to the stock theme", e);
            return resolveThemeUrl();
        }
    }

    // ── Patch engine ──────────────────────────────────────────────────────────

    private record Rule(String selector, String text) {}

    /** One stock rule plus whatever the patch inserts after it. */
    private static final class Slot {
        String text;                       // null = removed
        final List<String> after = new ArrayList<>();
        Slot(String text) { this.text = text; }
    }

    static String patch(String stock, String patch) {
        List<Rule> rules = parseRules(COMMENT.matcher(stock).replaceAll(""));
        List<Slot> slots = new ArrayList<>();
        // (selector -> its stock slots in order), so "#n" addresses the n-th occurrence
        Map<String, List<Slot>> bySelector = new HashMap<>();
        for (Rule r : rules) {
            Slot s = new Slot(r.text());
            slots.add(s);
            bySelector.computeIfAbsent(r.selector(), k -> new ArrayList<>()).add(s);
        }
        List<String> first = new ArrayList<>();
        List<String> appended = new ArrayList<>();
        // inserts that share an anchor keep their file order
        Map<Slot, Slot> cursor = new HashMap<>();

        int unresolved = 0;
        Matcher dm = DIRECTIVE.matcher(patch);
        int pos = 0;
        List<int[]> spans = new ArrayList<>();           // [directiveStart, directiveEnd] in 'patch'
        List<String[]> directives = new ArrayList<>();   // kind, selector, n
        while (dm.find()) {
            spans.add(new int[]{dm.start(), dm.end()});
            directives.add(new String[]{dm.group(1), dm.group(2), dm.group(3)});
        }
        // Walk the patch: each directive is followed by one rule (except @remove); rules before the first
        // directive or between directives without one are appended.
        for (int i = 0; i <= spans.size(); i++) {
            int segStart = i == 0 ? 0 : spans.get(i - 1)[1];
            int segEnd = i == spans.size() ? patch.length() : spans.get(i)[0];
            String seg = COMMENT.matcher(patch.substring(segStart, segEnd)).replaceAll("");
            List<Rule> segRules = parseRules(seg);
            String[] d = i == 0 ? null : directives.get(i - 1);
            int k = 0;
            if (d != null && !d[0].equals("remove") && !segRules.isEmpty()) {
                Rule r = segRules.get(k++);
                if (!apply(d, r.text(), bySelector, first, cursor)) {
                    appended.add(r.text());
                    unresolved++;
                }
            } else if (d != null && d[0].equals("remove")) {
                Slot s = lookup(bySelector, d[1], d[2]);
                if (s != null) s.text = null;
            }
            for (; k < segRules.size(); k++) appended.add(segRules.get(k).text());
        }

        StringBuilder out = new StringBuilder(stock.length() + patch.length());
        for (String t : first) out.append(t).append('\n');
        for (Slot s : slots) {
            if (s.text != null) out.append(s.text).append('\n');
            for (String t : s.after) out.append(t).append('\n');
        }
        for (String t : appended) out.append(t).append('\n');
        if (unresolved > 0) log.warn("[Theme] {} komm-theme.css rule(s) had no matching stock anchor and were appended", unresolved);
        return out.toString();
    }

    private static boolean apply(String[] d, String text, Map<String, List<Slot>> bySelector,
                                 List<String> first, Map<Slot, Slot> cursor) {
        switch (d[0]) {
            case "first" -> { first.add(text); return true; }
            case "replace" -> {
                Slot s = lookup(bySelector, d[1], d[2]);
                if (s == null) return false;
                s.text = text;
                return true;
            }
            case "after" -> {
                Slot s = lookup(bySelector, d[1], d[2]);
                if (s == null) return false;
                s.after.add(text);
                return true;
            }
            default -> { return false; }
        }
    }

    private static Slot lookup(Map<String, List<Slot>> bySelector, String selector, String n) {
        List<Slot> list = bySelector.get(normalize(selector));
        int idx = Integer.parseInt(n);
        return list != null && idx < list.size() ? list.get(idx) : null;
    }

    /** Flat CSS only (no nesting): "selector { body }" blocks. Returns normalised selector + original text. */
    private static List<Rule> parseRules(String css) {
        List<Rule> out = new ArrayList<>();
        int i = 0;
        while (i < css.length()) {
            int open = css.indexOf('{', i);
            if (open < 0) break;
            int depth = 1, j = open + 1;
            while (j < css.length() && depth > 0) {
                char c = css.charAt(j++);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            String selector = css.substring(i, open).trim();
            out.add(new Rule(normalize(selector), selector + " " + css.substring(open, j)));
            i = j;
        }
        return out;
    }

    /** Must match the normalisation used when komm-theme.css was generated. */
    private static String normalize(String selector) {
        return selector.replaceAll("\\s*>\\s*", ">").replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ", ").trim();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String resolveThemeUrl() {
        String ss = new Dracula().getUserAgentStylesheet();
        if (ss.contains(":")) return ss;                       // already a full URL
        URL url = Dracula.class.getResource(ss.startsWith("/") ? ss : "/" + ss);
        if (url == null) throw new IllegalStateException("Theme stylesheet not found: " + ss);
        return url.toExternalForm();
    }

    /** Resource URLs written as url("/x") are classpath-absolute; in a temp file they would resolve against the file system root. */
    private static String absolutizeResourceUrls(String css) {
        Matcher m = ROOT_URL.matcher(css);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            URL u = KommStylesheet.class.getResource(m.group(1));
            String replacement = u != null ? "url(\"" + u.toExternalForm() + "\")" : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static URL resource(String path) {
        URL u = KommStylesheet.class.getResource(path);
        if (u == null) throw new IllegalStateException("Missing resource " + path);
        return u;
    }

    private static String read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
