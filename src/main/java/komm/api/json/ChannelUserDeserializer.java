package komm.api.json;

import com.google.gson.*;
import komm.model.dto.summary.ChannelUserSummary;
import komm.utils.KommUtils;

import java.lang.reflect.Type;
import java.util.Base64;
import java.util.UUID;

public class ChannelUserDeserializer implements JsonDeserializer<ChannelUserSummary> {

    @Override
    public ChannelUserSummary deserialize(JsonElement json, Type typeOfT,
                                          JsonDeserializationContext context) throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();

        String avatarBase64 = KommUtils.getStringOrNull(obj, "avatar");
        byte[] avatarBytes = null;
        if (avatarBase64 != null) {
            try {
                avatarBytes = Base64.getDecoder().decode(avatarBase64);
            } catch (IllegalArgumentException ignored) {
            }
        }

        ChannelUserSummary summary = new ChannelUserSummary();
        summary.setUserId(context.deserialize(obj.get("userId"), UUID.class));
        summary.setUsername(KommUtils.getStringOrNull(obj, "username"));
        summary.setAvatar(avatarBytes);
        summary.setAvatarImageFormat(KommUtils.getStringOrNull(obj, "avatarImageFormat"));
        summary.setMicEnabled(obj.has("micEnabled") && !obj.get("micEnabled").isJsonNull()
                && obj.get("micEnabled").getAsBoolean());
        summary.setSpeakerEnabled(obj.has("speakerEnabled") && !obj.get("speakerEnabled").isJsonNull()
                && obj.get("speakerEnabled").getAsBoolean());

        return summary;
    }
}