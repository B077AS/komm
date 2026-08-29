package komm.ui.bots;

import komm.model.dto.summary.BotSummary;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side roster of the current server's bots, keyed by botId. Populated from the WS bot
 * roster (fetched on server join, kept in sync via {@code BOTS_UPDATED}). Unlike {@link
 * komm.ui.avatar.AvatarCache}, bots are never Hub users, so this never round-trips to the Hub —
 * it's just a local reflection of what the installation already pushed.
 */
public class BotRoster {

    private final Map<UUID, BotSummary> bots = new ConcurrentHashMap<>();

    public void reset(Collection<BotSummary> newBots) {
        bots.clear();
        if (newBots != null) {
            newBots.forEach(b -> bots.put(b.getBotId(), b));
        }
    }

    public boolean isBot(UUID id) {
        return id != null && bots.containsKey(id);
    }

    public BotSummary get(UUID botId) {
        return botId != null ? bots.get(botId) : null;
    }

    public Collection<BotSummary> all() {
        return bots.values();
    }

    public void clear() {
        bots.clear();
    }
}
