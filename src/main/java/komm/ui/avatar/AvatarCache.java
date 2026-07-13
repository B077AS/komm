package komm.ui.avatar;

import javafx.scene.image.Image;
import komm.App;
import komm.model.dto.response.UserBatchCacheResponse;
import komm.model.dto.response.UserCacheResponse;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AvatarCache {

    // Must match EmojiMessageItem.AVATAR_SIZE and MINI_SIZE
    private static final double CHAT_SIZE = 38.0;
    private static final double MINI_SIZE = 14.0;

    @Getter
    @Builder
    public static class CachedUser {
        private final String username;
        private final byte[] avatar;
        @Setter
        private volatile Image chatImage;
        @Setter
        private volatile Image miniImage;

        // Record-style accessors kept for compatibility with existing call sites
        public String username() {
            return username;
        }

        public byte[] avatar() {
            return avatar;
        }
    }

    private static final int MAX_SIZE = 200;

    private final Map<UUID, CachedUser> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedUser> eldest) {
                    if (size() <= MAX_SIZE) return false;
                    // Never evict the logged-in user's own avatar
                    UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
                    return !eldest.getKey().equals(me);
                }
            });

    /**
     * Resolves a user by UUID — returns from cache instantly if present,
     * otherwise fetches from the hub, caches, and returns asynchronously.
     */
    public CompletableFuture<CachedUser> resolve(UUID userId) {
        CachedUser cached = cache.get(userId);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return CompletableFuture.supplyAsync(() -> {
            try {
                UserCacheResponse resp = App.getServices()
                        .hub()
                        .getUserService()
                        .getUserAvatar(userId);

                if (resp == null) return null;

                byte[] bytes = (resp.getAvatar() != null && !resp.getAvatar().isBlank())
                        ? Base64.getDecoder().decode(resp.getAvatar())
                        : null;

                CachedUser user = CachedUser.builder()
                        .username(resp.getUsername())
                        .avatar(bytes)
                        .build();
                cache.put(userId, user);
                return user;

            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * Resolves all missing UUIDs in one batch hub call.
     * Already-cached entries are skipped. Safe to call from any thread;
     * callers on a background thread can .join() to wait for completion.
     */
    public CompletableFuture<Void> resolveAll(Collection<UUID> userIds) {
        List<UUID> missing = userIds.stream()
                .filter(id -> id != null && !cache.containsKey(id))
                .distinct()
                .toList();
        if (missing.isEmpty()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            try {
                List<UserBatchCacheResponse> results = App.getServices().hub()
                        .getUserService().getBatchAvatars(missing);
                if (results == null) return;
                for (UserBatchCacheResponse r : results) {
                    if (r.getUserId() == null) continue;
                    byte[] bytes = (r.getAvatar() != null && !r.getAvatar().isBlank())
                            ? Base64.getDecoder().decode(r.getAvatar()) : null;
                    cache.computeIfAbsent(r.getUserId(), k -> CachedUser.builder()
                            .username(r.getUsername())
                            .avatar(bytes)
                            .build());
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Manually populate the cache — useful when avatar bytes are already
     * available (e.g. from ConnectedUserCard's ChannelUserSummary).
     */
    public void populate(UUID userId, String username, byte[] avatarBytes) {
        cache.computeIfAbsent(userId, k -> CachedUser.builder()
                .username(username)
                .avatar(avatarBytes)
                .build());
    }

    /**
     * Unconditionally stores fresh avatar bytes for a user, replacing any
     * existing entry and its decoded images. Used when the bytes are already
     * known locally (e.g. the logged-in user just changed their own avatar).
     */
    public void put(UUID userId, String username, byte[] avatarBytes) {
        if (userId == null) return;
        cache.put(userId, CachedUser.builder()
                .username(username)
                .avatar(avatarBytes)
                .build());
    }

    /**
     * Drops a user's cached avatar (bytes + decoded images) so that the next
     * {@link #resolve(UUID)} refetches it. No network call happens here — the
     * refetch is lazy, triggered only when the avatar is next needed.
     */
    public void evict(UUID userId) {
        if (userId == null) return;
        cache.remove(userId);
    }

    /**
     * Decodes avatar bytes into chatImage and miniImage for each user.
     * Must be called from a background thread — never the FX thread.
     */
    public void preloadImages(Collection<UUID> userIds) {
        for (UUID uid : userIds) {
            if (uid == null) continue;
            CachedUser user = cache.get(uid);
            if (user == null || user.getAvatar() == null) continue;
            if (user.getChatImage() == null)
                user.setChatImage(toFxImage(user.getAvatar(), CHAT_SIZE));
            if (user.getMiniImage() == null)
                user.setMiniImage(toFxImage(user.getAvatar(), MINI_SIZE));
        }
    }

    /**
     * Ensures chatImage and miniImage are decoded for a single user.
     * Must be called from a background thread — never the FX thread.
     */
    public void ensureImages(CachedUser user) {
        if (user == null || user.getAvatar() == null) return;
        if (user.getChatImage() == null)
            user.setChatImage(toFxImage(user.getAvatar(), CHAT_SIZE));
        if (user.getMiniImage() == null)
            user.setMiniImage(toFxImage(user.getAvatar(), MINI_SIZE));
    }

    public CachedUser getIfPresent(UUID userId) {
        return cache.get(userId);
    }

    public Image getAvatarImage(UUID userId, double size) {
        CachedUser u = cache.get(userId);
        return u != null ? toFxImage(u.getAvatar(), size) : null;
    }

    public String getUsername(UUID userId) {
        CachedUser u = cache.get(userId);
        return u != null ? u.getUsername() : null;
    }

    public void invalidate() {
        cache.clear();
    }

    private static Image toFxImage(byte[] bytes, double size) {
        if (bytes == null || bytes.length == 0) return null;
        try (var stream = new ByteArrayInputStream(bytes)) {
            return new Image(stream, size, size, true, true);
        } catch (Exception e) {
            return null;
        }
    }
}
