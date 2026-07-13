package komm.model.permissions;

import komm.model.dto.summary.ServerSummary;

import java.util.*;

public enum Permission {
    DELETE_SERVER       ("Delete Server",           "Permanently delete this server"),
    EDIT_SERVER_INFO    ("Edit Server Info",        "Edit the server name, description and avatar"),
    EDIT_SERVER_PERMS   ("Edit Server Permissions", "Manage role permissions and custom roles"),
    VIEW_SERVER_SETTINGS("View Server Settings",    "Access the server settings panel"),
    CREATE_CHANNELS     ("Create Channels",         "Create new text and voice channels"),
    DELETE_CHANNELS     ("Delete Channels",         "Delete existing channels"),
    EDIT_CHANNELS       ("Edit Channels",           "Edit channel names and settings"),
    EDIT_CHANNEL_PERMS  ("Edit Channel Permissions","Override permissions for specific channels"),
    DELETE_INVITES      ("Delete Invites",          "View and delete the server's invite links"),
    BAN_USERS           ("Ban Users",               "Permanently ban members from the server"),
    KICK_USERS          ("Kick Users",              "Remove members from the server"),
    MUTE_USERS          ("Mute Users",              "Mute members in voice channels"),
    DEAFEN_USERS        ("Deafen Users",            "Deafen members in voice channels"),
    INVITE_USERS        ("Invite Users",            "Invite new members to the server"),
    POKE_USERS          ("Poke Users",              "Send poke notifications to members"),
    CHECK_PING          ("Check Ping",              "View ping and connection info of members"),
    SEND_MESSAGES       ("Send Messages",           "Send text messages in channels"),
    SEND_GIFS           ("Send GIFs",               "Send GIF images in channels"),
    SEND_ATTACHMENTS    ("Send Attachments",        "Send files and images in channels"),
    ADD_REACTIONS       ("Add Reactions",           "Add emoji reactions to messages"),
    DELETE_OTHERS_MSGS  ("Delete Others' Messages", "Delete messages sent by other members"),
    JOIN_VOICE          ("Join Voice Channels",     "Connect to voice channels"),
    SCREEN_SHARE        ("Screen Share",            "Share your screen in voice channels"),
    USE_SOUNDBOARD          ("Use Soundboard",            "Play soundboard sounds in voice channels"),
    MANAGE_SERVER_SOUNDBOARD("Manage Server Soundboards", "Add, edit and remove server-wide soundboard sounds"),
    MOVE_MEMBERS            ("Move Members",              "Move members between voice channels"),
    VIEW_CHANNEL            ("View Channel",              "See this channel in the channel list and access its content");

    private final String displayName;
    private final String description;

    Permission(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public static EnumSet<Permission> fromNames(List<String> names) {
        if (names == null || names.isEmpty()) return EnumSet.noneOf(Permission.class);
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        for (String name : names) {
            try { result.add(Permission.valueOf(name)); }
            catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    public static List<String> toNames(Set<Permission> permissions) {
        if (permissions == null || permissions.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(permissions.size());
        for (Permission p : values()) {
            if (permissions.contains(p)) names.add(p.name());
        }
        return names;
    }

    public static EnumSet<Permission> defaultFor(ServerSummary.Role role) {
        return switch (role) {
            case OWNER -> EnumSet.allOf(Permission.class);
            case ADMIN -> EnumSet.complementOf(EnumSet.of(DELETE_SERVER));
            case MODERATOR -> EnumSet.complementOf(EnumSet.of(
                    DELETE_SERVER, EDIT_SERVER_INFO, EDIT_SERVER_PERMS,
                    CREATE_CHANNELS, DELETE_CHANNELS, EDIT_CHANNELS, EDIT_CHANNEL_PERMS,
                    DELETE_INVITES));
            case MEMBER -> EnumSet.of(INVITE_USERS, POKE_USERS, CHECK_PING,
                    SEND_MESSAGES, SEND_GIFS, SEND_ATTACHMENTS, ADD_REACTIONS,
                    JOIN_VOICE, SCREEN_SHARE, USE_SOUNDBOARD, VIEW_CHANNEL);
        };
    }
}
