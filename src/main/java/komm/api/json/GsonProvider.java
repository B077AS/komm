package komm.api.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import komm.model.dto.summary.ChannelUserSummary;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.ServerSummary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class GsonProvider {

    private static final Gson INSTANCE = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonSerializer<LocalDateTime>) (src, typeOfSrc, context) ->
                            context.serialize(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonDeserializer<LocalDateTime>) (json, typeOfT, context) ->
                            LocalDateTime.parse(json.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .registerTypeAdapter(ServerSummary.class, new ServerSummaryDeserializer())
            .registerTypeAdapter(MainUserSummary.class, new UserDeserializer())
            .registerTypeAdapter(ChannelUserDeserializer.class, new ChannelUserDeserializer())
            .registerTypeAdapter(ChannelUserSummary.class, new ChannelUserSummaryDeserializer())
            .create();

    private GsonProvider() {}

    public static Gson get() {
        return INSTANCE;
    }
}