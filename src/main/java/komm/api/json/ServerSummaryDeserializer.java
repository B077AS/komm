package komm.api.json;

import com.google.gson.*;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;
import komm.utils.KommUtils;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class ServerSummaryDeserializer implements JsonDeserializer<ServerSummary> {

    @Override
    public ServerSummary deserialize(JsonElement json, Type typeOfT,
                                     JsonDeserializationContext context) throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();

        String avatar = KommUtils.getStringOrNull(obj, "avatar");
        byte[] avatarBytes = null;
        if (avatar != null) {
            try {
                avatarBytes = Base64.getDecoder().decode(avatar);
            } catch (IllegalArgumentException ignored) {
            }
        }

        return ServerSummary.builder()
                .serverId(context.deserialize(obj.get("serverId"), UUID.class))
                .serverName(KommUtils.getStringOrNull(obj, "serverName"))
                .description(KommUtils.getStringOrNull(obj, "description"))
                .installationId(context.deserialize(obj.get("installationId"), UUID.class))
                .ipAddress(KommUtils.getStringOrNull(obj, "ipAddress"))
                .port(obj.has("port") && !obj.get("port").isJsonNull() ? obj.get("port").getAsInt() : null)
                .signalPort(obj.has("signalPort") && !obj.get("signalPort").isJsonNull() ? obj.get("signalPort").getAsInt() : null)
                .tlsEnabled(obj.has("tlsEnabled") && !obj.get("tlsEnabled").isJsonNull()
                        && obj.get("tlsEnabled").getAsBoolean())
                .avatar(avatar)
                .avatarImageFormat(KommUtils.getStringOrNull(obj, "avatarImageFormat"))
                .avatarBytes(avatarBytes)
                .role(obj.has("role") && !obj.get("role").isJsonNull()
                        ? ServerSummary.Role.valueOf(obj.get("role").getAsString()) : null)
                .joinedAt(context.deserialize(obj.get("joinedAt"), LocalDateTime.class))
                .displayOrder(obj.has("displayOrder") && !obj.get("displayOrder").isJsonNull()
                        ? obj.get("displayOrder").getAsInt() : null)
                .totalMembers(obj.get("totalMembers").getAsInt())
                .ownerId(context.deserialize(obj.get("ownerId"), UUID.class))
                .ownerUsername(KommUtils.getStringOrNull(obj, "ownerUsername"))
                .activeUsers(obj.get("activeUsers").getAsInt())
                .textChannelCount(obj.has("textChannelCount") ? obj.get("textChannelCount").getAsInt() : 0)
                .voiceChannelCount(obj.has("voiceChannelCount") ? obj.get("voiceChannelCount").getAsInt() : 0)
                .status(
                        obj.has("status") && !obj.get("status").isJsonNull()
                                ? InstallationSummary.InstallationStatus.valueOf(obj.get("status").getAsString())
                                : InstallationSummary.InstallationStatus.UNKNOWN
                )
                .defaultChannelPanelWidth(
                        obj.has("defaultChannelPanelWidth") && !obj.get("defaultChannelPanelWidth").isJsonNull()
                                ? obj.get("defaultChannelPanelWidth").getAsInt() : null
                )
                .channelNotificationsEnabled(
                        !obj.has("channelNotificationsEnabled") || obj.get("channelNotificationsEnabled").isJsonNull()
                                || obj.get("channelNotificationsEnabled").getAsBoolean()
                )
                .effectivePermissions(deserializePermissions(obj))
                .build();
    }

    private List<String> deserializePermissions(JsonObject obj) {
        if (!obj.has("effectivePermissions") || obj.get("effectivePermissions").isJsonNull()) return null;
        JsonArray arr = obj.getAsJsonArray("effectivePermissions");
        List<String> perms = new ArrayList<>(arr.size());
        arr.forEach(e -> perms.add(e.getAsString()));
        return perms;
    }
}