package komm.ui.chat.virtual;

import komm.websocket.messages.payloads.MessageReceivedPayload;

import java.util.UUID;

/**
 * One row in the {@link VirtualMessageList} backing list — a wrapper around a
 * message payload.
 *
 * <p>Rows are compared by identity (the list is index-addressed, never searched by
 * {@code equals}), so no {@code equals}/{@code hashCode} override is needed. The
 * wrapper exists so a fresh instance can be dropped in via {@code list.set(i, …)} to
 * force Flowless to rebuild a cell after the payload is mutated in place.
 */
public final class MessageRow {

    private final MessageReceivedPayload payload;

    private MessageRow(MessageReceivedPayload payload) {
        this.payload = payload;
    }

    public static MessageRow message(MessageReceivedPayload payload) {
        return new MessageRow(payload);
    }

    public MessageReceivedPayload payload() {
        return payload;
    }

    public UUID id() {
        return payload.getMessageId();
    }
}
