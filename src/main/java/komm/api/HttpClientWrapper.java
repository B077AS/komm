package komm.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import komm.api.json.GsonProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public class HttpClientWrapper {
    private final HttpClient httpClient;
    private final Gson gson = GsonProvider.get();
    private final String baseUrl;

    public HttpClientWrapper(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Execute a GET request
     */
    public <T> T get(String endpoint, String token, Class<T> responseClass) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .GET();
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        return gson.fromJson(response.body(), responseClass);
    }

    /**
     * Execute a GET request with Type (for generics like List<T>, Map<K,V>)
     */
    public <T> T getWithType(String endpoint, String token, Type type) throws Exception {
        //log.debug("Executing GET request {}", endpoint);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .GET();
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        return gson.fromJson(response.body(), type);
    }

    /**
     * Execute a GET request returning a List<T>
     */
    public <T> List<T> getList(String endpoint, String token, Class<T> elementClass) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .GET();

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        Type listType = TypeToken.getParameterized(List.class, elementClass).getType();
        return gson.fromJson(response.body(), listType);
    }

    /**
     * Execute a POST request
     */
    public <T> T post(String endpoint, Object body, String token, Class<T> responseClass, int expectedStatus) throws Exception {
        String jsonBody = body != null ? gson.toJson(body) : "";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != expectedStatus) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        if (responseClass == Void.class || response.body().isEmpty()) {
            return null;
        }

        return gson.fromJson(response.body(), responseClass);
    }

    /**
     * POST request expecting 200 response
     */
    public <T> T post(String endpoint, Object body, String token, Class<T> responseClass) throws Exception {
        return post(endpoint, body, token, responseClass, 200);
    }

    /**
     * POST request with Type support for generics
     */
    public <T> T postWithType(String endpoint, Object body, String token, Type type, int expectedStatus) throws Exception {
        String jsonBody = gson.toJson(body);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != expectedStatus) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        return gson.fromJson(response.body(), type);
    }

    /**
     * Execute a PUT request
     */
    public <T> T put(String endpoint, Object body, String token, Class<T> responseClass) throws Exception {
        String jsonBody = gson.toJson(body);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody));
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        if (responseClass == Void.class || response.body().isEmpty()) {
            return null;
        }

        return gson.fromJson(response.body(), responseClass);
    }

    /**
     * Execute a PATCH request
     */
    public <T> T patch(String endpoint, Object body, String token, Class<T> responseClass) throws Exception {
        String jsonBody = body != null ? gson.toJson(body) : "";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody));

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        if (responseClass == Void.class || response.body().isEmpty()) {
            return null;
        }

        return gson.fromJson(response.body(), responseClass);
    }

    /**
     * Execute a DELETE request
     */
    public void delete(String endpoint, String token) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .DELETE();
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }
    }

    /**
     * Upload a file as multipart/form-data.
     * Streams directly from disk — does not load the entire file into memory.
     */
    public <T> T postMultipart(String endpoint, String fieldName, File file,
                                String contentType, String token,
                                Class<T> responseClass, int expectedStatus) throws Exception {
        String boundary = "FormBoundary" + UUID.randomUUID().toString().replace("-", "");

        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + file.getName() + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return new SequenceInputStream(
                                new SequenceInputStream(
                                        new ByteArrayInputStream(header),
                                        Files.newInputStream(file.toPath())),
                                new ByteArrayInputStream(footer));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != expectedStatus) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        if (responseClass == Void.class || response.body().isEmpty()) return null;
        return gson.fromJson(response.body(), responseClass);
    }

    /**
     * Download binary data (like ZIP files)
     */
    public byte[] downloadBinary(String endpoint, String token, Consumer<Double> progressCallback) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .GET();
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<InputStream> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes());
            throw new HttpStatusException(response.statusCode(), errorBody);
        }

        // Get content length for progress tracking
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        
        InputStream inputStream = response.body();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        byte[] buffer = new byte[8192];
        int bytesRead;
        long totalBytesRead = 0;
        
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
            totalBytesRead += bytesRead;
            
            // Report progress if callback provided and content length known
            if (progressCallback != null && contentLength > 0) {
                double progress = (double) totalBytesRead / contentLength;
                progressCallback.accept(progress);
            }
        }
        
        inputStream.close();
        
        // Final progress update
        if (progressCallback != null) {
            progressCallback.accept(1.0);
        }
        
        return outputStream.toByteArray();
    }

    /**
     * Download text content (like properties file)
     */
    public String downloadText(String endpoint, String token) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .GET();
        
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(
            requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }

        return response.body();
    }
}