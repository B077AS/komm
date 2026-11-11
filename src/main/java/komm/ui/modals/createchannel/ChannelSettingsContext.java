package komm.ui.modals.createchannel;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Shared state and shell callbacks handed to every {@link ChannelSettingsTab}. Owned by the
 * {@code CreateChannelModal} shell; tabs read from it and call back into the shell through it.
 */
public final class ChannelSettingsContext {

    public static final List<Permission> ALL_CHANNEL_PERMS = List.of(
            Permission.VIEW_CHANNEL,
            Permission.JOIN_VOICE,
            Permission.SCREEN_SHARE,
            Permission.USE_SOUNDBOARD,
            Permission.MANAGE_SERVER_SOUNDBOARD,
            Permission.SEND_MESSAGES,
            Permission.SEND_GIFS,
            Permission.SEND_ATTACHMENTS,
            Permission.ADD_REACTIONS,
            Permission.DELETE_OTHERS_MSGS
    );

    public static final Set<Permission> VOICE_ONLY_PERMS = Set.of(
            Permission.JOIN_VOICE, Permission.SCREEN_SHARE,
            Permission.USE_SOUNDBOARD, Permission.MANAGE_SERVER_SOUNDBOARD
    );

    private final ServerSummary server;
    private final boolean editMode;
    private final ChannelSummary editChannel;

    // Owned by DetailsChannelTab. Observable so the modal's sidebar block can mirror them live;
    // voice is also read by the Permissions/UserExceptions tabs to know which permissions apply
    // (voice-only perms are hidden for text channels).
    private final BooleanProperty voice = new SimpleBooleanProperty(true);
    private final StringProperty draftName = new SimpleStringProperty("");
    private final ObjectProperty<Ikon> draftIcon = new SimpleObjectProperty<>();
    private final ObservableValue<Ikon> shownIcon = Bindings.createObjectBinding(
            () -> draftIcon.get() != null ? draftIcon.get()
                    : voice.get() ? MaterialDesignM.MICROPHONE : Feather.HASH,
            draftIcon, voice);

    // Edit-mode "last saved" values — each tab needs the OTHER tab's original value when it
    // saves, since a channel update always sends the full name+icon pair.
    private String origName;
    private String origIcon;

    private Runnable saveButtonRefresher;
    private Consumer<Boolean> savingStateHandler;

    public ChannelSettingsContext(ServerSummary server, boolean editMode, ChannelSummary editChannel) {
        this.server = server;
        this.editMode = editMode;
        this.editChannel = editChannel;
        if (editChannel != null) {
            this.origName = editChannel.getChannelName();
            this.origIcon = editChannel.getIcon();
            this.draftName.set(origName == null ? "" : origName);
            if (origIcon != null && !origIcon.isBlank()) {
                try { this.draftIcon.set(new FontIcon(origIcon).getIconCode()); } catch (Exception ignored) { }
            }
        }
    }

    // ── Shared state ─────────────────────────────────────────────────────────────
    public ServerSummary server()       { return server; }
    public boolean isEditMode()         { return editMode; }
    public ChannelSummary editChannel() { return editChannel; }

    public boolean isVoice()            { return voice.get(); }
    public void setVoice(boolean v)     { this.voice.set(v); }
    public BooleanProperty voiceProperty() { return voice; }

    public String getDraftName()        { return draftName.get(); }
    public void setDraftName(String n)  { draftName.set(n); }
    public StringProperty draftNameProperty() { return draftName; }

    /** The icon picked by the user, or null for the type's default glyph. */
    public Ikon getDraftIcon()          { return draftIcon.get(); }
    public void setDraftIcon(Ikon i)    { draftIcon.set(i); }
    public ObjectProperty<Ikon> draftIconProperty() { return draftIcon; }

    /** The icon to display right now: the picked one, else the voice/text default. */
    public ObservableValue<Ikon> shownIcon() { return shownIcon; }

    public String getOrigName()         { return origName; }
    public void setOrigName(String n)   { this.origName = n; }

    public String getOrigIcon()         { return origIcon; }
    public void setOrigIcon(String i)   { this.origIcon = i; }

    /** Channel permissions applicable given the (fixed or in-progress) channel type. */
    public List<Permission> getActiveChannelPerms() {
        boolean isTextChannel = editChannel != null
                ? editChannel.getChannelType() == ChannelSummary.ChannelType.TEXT
                : !voice.get();
        if (isTextChannel) {
            return ALL_CHANNEL_PERMS.stream()
                    .filter(p -> !VOICE_ONLY_PERMS.contains(p))
                    .collect(Collectors.toList());
        }
        return ALL_CHANNEL_PERMS;
    }

    // ── Shell callbacks (wired by the shell) ────────────────────────────────────
    public void setSaveButtonRefresher(Runnable r)          { this.saveButtonRefresher = r; }
    public void setSavingStateHandler(Consumer<Boolean> h)  { this.savingStateHandler = h; }

    /** Ask the shell to re-evaluate the footer Save button (text + enabled state). */
    public void refreshSaveButton() { if (saveButtonRefresher != null) saveButtonRefresher.run(); }

    /** Toggle the modal's "saving" state (disables cancel/close, marks the modal persistent). */
    public void setSaving(boolean saving) { if (savingStateHandler != null) savingStateHandler.accept(saving); }
}
