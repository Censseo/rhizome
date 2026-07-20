package rhizome.vm;

/**
 * One event a contract emitted during a call: an indexable {@code topic} and an
 * opaque {@code data} payload. Logs are the channel autonomous agents watch to
 * react to on-chain state; they are kept only when the call succeeds (a reverted
 * call emits nothing), and are never read back by contract code, so they carry no
 * consensus weight beyond the gas paid to emit them.
 */
public record LogEntry(byte[] topic, byte[] data) {

    public LogEntry {
        topic = topic.clone();
        data = data.clone();
    }

    @Override
    public byte[] topic() {
        return topic.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
