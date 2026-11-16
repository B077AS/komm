package komm.ui.mention;

import javafx.application.Platform;
import javafx.scene.text.Font;
import komm.App;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User mentions inside message text.
 *
 * <p>A mention is stored in the message content as {@code <@uuid>} (the hub owns usernames and
 * they can change, so the id is what goes over the wire). The name is looked up through
 * {@link komm.ui.avatar.AvatarCache} whenever the text is shown.
 */
public final class Mentions {

    /** {@code <@xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx>} — group 1 is the user id. */
    public static final Pattern TOKEN = Pattern.compile(
            "<@([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})>");

    /** Shown when the mentioned user can't be resolved (left the server, deleted account). */
    public static final String UNKNOWN_NAME = "unknown-user";

    // Pill colours: {background, text}
    private static final String[] PILL_NORMAL = {"-color-accent-subtle", "-color-accent-3"};
    // Hover = the resting tint with one more faint accent layer on top, text a step brighter.
    private static final String[] PILL_HOVER = {"-color-accent-subtle, -color-accent-subtle", "-color-accent-1"};
    private static final String[] PILL_SELF_HOVER = {"-color-accent-muted, -color-accent-subtle", "-color-accent-0"};
    private static final String[] PILL_SELF = {"-color-accent-muted", "-color-accent-0"};
    private static final String[] PILL_UNKNOWN = {"-color-bg-middle", "-color-fg-subtle"};

    private Mentions() {
    }

    public static String token(UUID userId) {
        return "<@" + userId + ">";
    }

    /** Parses the id of a token match; null if the text isn't a valid UUID. */
    public static UUID idOf(Matcher m) {
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean containsAny(String text) {
        return text != null && text.indexOf("<@") >= 0 && TOKEN.matcher(text).find();
    }

    /** Distinct mentioned user ids, in order of appearance. */
    public static Set<UUID> ids(String text) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (text == null || text.indexOf("<@") < 0) return ids;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            UUID id = idOf(m);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    public static boolean mentions(String text, UUID userId) {
        return userId != null && ids(text).contains(userId);
    }

    /** True when the message mentions the logged-in user. */
    public static boolean mentionsMe(String text) {
        return App.getUser() != null && mentions(text, App.getUser().getUserId());
    }

    /** Display name for a mention ({@code "@name"}), from the cache only — never blocks. */
    public static String displayName(UUID userId) {
        String name = userId != null ? App.getAvatarCache().getUsername(userId) : null;
        return "@" + (name != null ? name : UNKNOWN_NAME);
    }

    /**
     * Replaces every token with {@code @name} (cached names only, unknown ones become
     * {@code @unknown-user}). Use for anything shown or copied as plain text: previews,
     * notifications, clipboard.
     */
    public static String toPlain(String text) {
        if (text == null || text.indexOf("<@") < 0) return text;
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(displayName(idOf(m))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code @word} at the start of the text or after whitespace; group 1 is the word. */
    private static final Pattern PLAIN_MENTION = Pattern.compile("(?<![^\\s])@([^\\s<>@]{1,64})");

    /**
     * The reverse of {@link #toPlain}: every {@code @name} whose name exactly matches (ignoring
     * case) one of {@code candidates}' cached usernames becomes a {@code <@uuid>} token. Trailing
     * punctuation that isn't part of a name is left alone ({@code "@marta,"} → token + {@code ","}).
     * Used for pasted text and on send, so a mention copied out of a message, or typed out in full,
     * stays a mention.
     */
    public static String fromPlain(String text, Collection<UUID> candidates) {
        if (text == null || text.indexOf('@') < 0 || candidates == null || candidates.isEmpty()) return text;
        Map<String, UUID> byName = new HashMap<>();
        for (UUID id : candidates) {
            String name = App.getAvatarCache().getUsername(id);
            if (name != null) byName.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
        }
        if (byName.isEmpty()) return text;

        Matcher m = PLAIN_MENTION.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            String word = m.group(1);
            // Longest prefix of the word that is a known name, dropping trailing punctuation only.
            UUID id = null;
            int len = word.length();
            while (len > 0) {
                id = byName.get(word.substring(0, len).toLowerCase(Locale.ROOT));
                if (id != null || Character.isLetterOrDigit(word.charAt(len - 1))) break;
                len--;
            }
            if (id == null) continue;
            sb.append(text, last, m.start()).append(token(id));
            last = m.start(1) + len;
        }
        if (last == 0) return text;
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** Fetches any mentioned names that aren't cached yet, then runs {@code onReady} on the FX thread. */
    public static void resolveNames(String text, Runnable onReady) {
        Set<UUID> ids = ids(text);
        ids.removeIf(id -> App.getAvatarCache().getUsername(id) != null);
        if (ids.isEmpty()) {
            if (onReady != null) onReady.run();
            return;
        }
        App.getAvatarCache().resolveAll(ids).whenComplete((v, err) -> {
            if (onReady != null) Platform.runLater(onReady);
        });
    }

    // ── Pill node ─────────────────────────────────────────────────────────────

    /**
     * An inline mention pill for a TextFlow. Shows the cached name right away, or a neutral
     * placeholder that updates once the name is fetched; {@code onNameChanged} (may be null) runs
     * after such a late update, for owners that need to re-measure.
     */
    public static MentionPill pill(UUID userId, Font font, Consumer<MentionPill> onNameChanged) {
        MentionPill pill = new MentionPill(userId, font);
        String cached = userId != null ? App.getAvatarCache().getUsername(userId) : null;
        if (cached != null || userId == null) {
            applyName(pill, cached);
            return pill;
        }
        pill.setLabel("@…");
        pill.setColors(PILL_NORMAL[0], PILL_NORMAL[1]);
        App.getAvatarCache().resolve(userId).whenComplete((u, err) -> {
            String name = u != null ? u.username() : null;
            Platform.runLater(() -> {
                applyName(pill, name);
                if (onNameChanged != null) onNameChanged.accept(pill);
            });
        });
        return pill;
    }

    public static void setPillHover(MentionPill pill, boolean hover) {
        if (!isResolved(pill)) return; // unknown users aren't clickable
        String[] c = colorsFor(pill.getUserId());
        if (hover) c = c == PILL_SELF ? PILL_SELF_HOVER : PILL_HOVER;
        pill.setColors(c[0], c[1]);
    }

    public static boolean isResolved(MentionPill pill) {
        return pill.getUserId() != null && App.getAvatarCache().getUsername(pill.getUserId()) != null;
    }

    private static void applyName(MentionPill pill, String name) {
        pill.setLabel("@" + (name != null ? name : UNKNOWN_NAME));
        String[] c = name == null ? PILL_UNKNOWN : colorsFor(pill.getUserId());
        pill.setColors(c[0], c[1]);
    }

    private static String[] colorsFor(UUID userId) {
        boolean self = App.getUser() != null && userId.equals(App.getUser().getUserId());
        return self ? PILL_SELF : PILL_NORMAL;
    }
}
