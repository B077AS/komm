package komm.websocket;

import komm.utils.PingHistory;
import komm.utils.PingLossTracker;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.PingPayload;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PingService {

    private static final int INITIAL_DELAY_SECONDS = 2;
    private static final int INTERVAL_SECONDS = 3;

    private ScheduledExecutorService scheduler;

    public void start(InstallationWsClient wsClient) {
        stop();
        PingLossTracker.reset();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ping-service");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long timestamp = System.currentTimeMillis();
                PingLossTracker.onPingSent(timestamp);
                List<Integer> hist = PingHistory.getHistory();
                Integer lastRtt = hist.isEmpty() ? null : hist.get(hist.size() - 1);
                wsClient.send(WsMessageType.PING, PingPayload.builder()
                        .timestamp(timestamp)
                        .lastRttMs(lastRtt)
                        .lastLossPct(PingLossTracker.getLossPercent())
                        .build());
            } catch (Exception e) {
                log.warn("Failed to send ping: {}", e.getMessage());
            }
        }, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Ping service started");
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = null;
    }
}
