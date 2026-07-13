package komm.ui.emojis;

import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

import java.util.function.IntPredicate;

public class EmojiFilterTextField extends TextField {

    // Allows only ASCII letters, digits, underscore, and hyphen
    private static final IntPredicate USERNAME_ALLOWED = cp ->
            (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') ||
            (cp >= '0' && cp <= '9') || cp == '_' || cp == '-';

    // Allows printable ASCII only (no spaces, no control chars, no non-ASCII/emoji)
    private static final IntPredicate EMAIL_ALLOWED = cp -> cp >= 0x21 && cp <= 0x7E;

    private final IntPredicate allowFilter;
    private final int maxLength;

    public EmojiFilterTextField() {
        this("", null, 0);
    }

    public EmojiFilterTextField(String text) {
        this(text, null, 0);
    }

    private EmojiFilterTextField(String text, IntPredicate allowFilter, int maxLength) {
        super(prepareText(text, allowFilter, maxLength));
        this.allowFilter = allowFilter;
        this.maxLength = maxLength;
        installFilter();
    }

    /** Factory for a plain field with a hard character cap and emoji blocked. */
    public static EmojiFilterTextField maxLength(int maxLength) {
        return new EmojiFilterTextField("", null, maxLength);
    }

    /** Factory for a username field: only [a-zA-Z0-9_-] is accepted. */
    public static EmojiFilterTextField username() {
        return new EmojiFilterTextField("", USERNAME_ALLOWED, 0);
    }

    /** Factory for a username field with a character limit. */
    public static EmojiFilterTextField username(int maxLength) {
        return new EmojiFilterTextField("", USERNAME_ALLOWED, maxLength);
    }

    /** Factory for an email field: printable ASCII only, no spaces. */
    public static EmojiFilterTextField email() {
        return new EmojiFilterTextField("", EMAIL_ALLOWED, 0);
    }

    /**
     * Installs an emoji-stripping listener on any TextField (e.g. PasswordTextField)
     * that already has its own TextFormatter and cannot use setTextFormatter again.
     */
    public static void installEmojiFilterListener(TextField field) {
        field.textProperty().addListener((obs, old, newVal) -> {
            String stripped = stripEmoji(newVal);
            if (!stripped.equals(newVal)) field.setText(stripped);
        });
    }

    public static boolean isEmojiCodePoint(int cp) {
        return cp > 0xFFFF
                || Character.getType(cp) == Character.OTHER_SYMBOL
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0x1F000 && cp <= 0x1FFFF);
    }

    public static String stripEmoji(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints()
                .filter(cp -> !isEmojiCodePoint(cp))
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String prepareText(String text, IntPredicate allowFilter, int maxLength) {
        String stripped = stripEmoji(text);
        if (allowFilter != null) {
            StringBuilder sb = new StringBuilder(stripped.length());
            stripped.codePoints().filter(allowFilter).forEach(sb::appendCodePoint);
            stripped = sb.toString();
        }
        if (maxLength > 0 && stripped.length() > maxLength) {
            stripped = stripped.substring(0, maxLength);
        }
        return stripped;
    }

    private void installFilter() {
        setTextFormatter(new TextFormatter<>(change -> {
            String incoming = change.getText();
            if (incoming == null || incoming.isEmpty()) {
                return change; // deletions / caret moves — always allow
            }
            if (allowFilter != null) {
                // Strict mode: every code point must match the allowlist
                if (!incoming.codePoints().allMatch(allowFilter)) return null;
            } else {
                // Default mode: reject emoji
                if (incoming.codePoints().anyMatch(EmojiFilterTextField::isEmojiCodePoint)) return null;
            }
            if (maxLength > 0 && change.getControlNewText().length() > maxLength) return null;
            return change;
        }));
    }
}