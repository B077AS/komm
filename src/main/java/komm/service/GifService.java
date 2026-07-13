package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.summary.GifPage;
import komm.model.dto.summary.GifResult;

import java.util.List;

public class GifService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public GifService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public GifPage getTrending(int page) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get(
                        "/api/gifs/trending?limit=20&page=" + page,
                        tokenManager.getAccessToken(),
                        GifPage.class)
        );
    }

    public GifPage search(String query, int page) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get(
                        "/api/gifs/search?q=" + query + "&limit=20&page=" + page,
                        tokenManager.getAccessToken(),
                        GifPage.class)
        );
    }

    public List<GifResult> getFavorites() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/gifs/favorites", tokenManager.getAccessToken(), GifResult.class)
        );
    }

    public void addFavorite(GifResult gif) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.post("/api/gifs/favorites", gif, tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void removeFavorite(String gifId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/gifs/favorites/" + gifId, tokenManager.getAccessToken());
            return null;
        });
    }
}
