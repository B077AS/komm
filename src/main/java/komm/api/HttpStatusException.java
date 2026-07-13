package komm.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;

@Getter
public class HttpStatusException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public HttpStatusException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** Extracts the "message" field from the JSON error body. */
    public String getApiMessage() {
        try {
            JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
            if (obj.has("message")) return obj.get("message").getAsString();
        } catch (Exception ignored) {}
        return "Request failed (HTTP " + statusCode + ")";
    }

    /** Extracts the api message from any Throwable, handling HttpStatusException specially. */
    public static String extractMessage(Throwable t) {
        if (t instanceof HttpStatusException hse) return hse.getApiMessage();
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    public boolean isExpiredToken() {
        return statusCode == 401 && 
               responseBody != null &&
               (responseBody.contains("Expired token") || 
                responseBody.toLowerCase().contains("expired"));
    }
}