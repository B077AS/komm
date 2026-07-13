package komm.api.json;

import com.google.gson.*;
import komm.model.dto.summary.ChannelUserSummary;
import komm.utils.KommUtils;

import java.lang.reflect.Type;
import java.util.Base64;
import java.util.UUID;

public class ChannelUserSummaryDeserializer implements JsonDeserializer<ChannelUserSummary> {

    @Override
    public ChannelUserSummary deserialize(JsonElement json, Type typeOfT,
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

        return ChannelUserSummary.builder()
                .userId(context.deserialize(obj.get("userId"), UUID.class))
                .username(KommUtils.getStringOrNull(obj, "username"))
                .avatar(avatarBytes)
                .avatarImageFormat(KommUtils.getStringOrNull(obj, "avatarImageFormat"))
                .micEnabled(obj.has("micEnabled") && obj.get("micEnabled").getAsBoolean())
                .speakerEnabled(obj.has("speakerEnabled") && obj.get("speakerEnabled").getAsBoolean())
                .serverMicEnabled(true)
                .serverSpeakerEnabled(true)
                .pokesEnabled(true)
                .build();
    }
}