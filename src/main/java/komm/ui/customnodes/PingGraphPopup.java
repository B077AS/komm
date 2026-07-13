package komm.ui.customnodes;

import komm.utils.PingHistory;
import komm.utils.PingLossTracker;

import java.util.List;
import java.util.function.Consumer;

/**
 * Real-time latency graph popup for the local connection.
 */
public class PingGraphPopup extends PingGraphPopupBase {

    public PingGraphPopup() {
        super("LATENCY");
    }

    @Override
    protected List<Integer> currentHistory() {
        return PingHistory.getHistory();
    }

    @Override
    protected void subscribe(Consumer<Integer> listener) {
        PingHistory.addListener(listener);
    }

    @Override
    protected void unsubscribe(Consumer<Integer> listener) {
        PingHistory.removeListener(listener);
    }

    @Override
    protected int currentLossPercent() {
        return PingLossTracker.getLossPercent();
    }
}
