package komm.api.json;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import komm.model.dto.summary.BadgeSummary;
import komm.model.dto.summary.MainUserSummary;
import komm.utils.KommUtils;

import java.lang.reflect.Type;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class UserDeserializer implements JsonDeserializer<MainUserSummary> {

    @Override
    public MainUserSummary deserialize(JsonElement json, Type typeOfT,
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

        MainUserSummary user = new MainUserSummary();
        user.setUserId(context.deserialize(obj.get("userId"), UUID.class));
        user.setUsername(KommUtils.getStringOrNull(obj, "username"));
        user.setEmail(KommUtils.getStringOrNull(obj, "email"));
        user.setAvatar(avatarBytes);
        user.setAvatarImageFormat(KommUtils.getStringOrNull(obj, "avatarImageFormat"));
        user.setStatusMessage(KommUtils.getStringOrNull(obj, "statusMessage"));
        user.setStatusEmoji(KommUtils.getStringOrNull(obj, "statusEmoji"));
        user.setStatus(
                obj.has("status") && !obj.get("status").isJsonNull()
                        ? MainUserSummary.UserStatus.valueOf(obj.get("status").getAsString())
                        : MainUserSummary.UserStatus.UNKNOWN
        );
        user.setMicEnabled(obj.has("micEnabled") && !obj.get("micEnabled").isJsonNull()
                && obj.get("micEnabled").getAsBoolean());
        user.setSpeakerEnabled(obj.has("speakerEnabled") && !obj.get("speakerEnabled").isJsonNull()
                && obj.get("speakerEnabled").getAsBoolean());

        if (obj.has("badges") && obj.get("badges").isJsonArray()) {
            Type badgeListType = new TypeToken<List<BadgeSummary>>() {}.getType();
            user.setBadges(context.deserialize(obj.get("badges"), badgeListType));
        }

        return user;
    }
}