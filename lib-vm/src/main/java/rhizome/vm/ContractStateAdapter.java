package rhizome.vm;

import java.util.Arrays;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.StateKeys;
import rhizome.core.state.snapshot.StateSink;
import rhizome.core.state.snapshot.StateSource;

/**
 * Snapshot adapter for the two contract state domains, bridging a {@link ContractStore} to
 * the lib-core snapshot seam (lib-core cannot depend on the VM module). Keys mirror the
 * engine's state-root composition exactly: code is keyed by the contract address, storage by
 * {@code address(25) ‖ key}.
 */
public final class ContractStateAdapter implements StateSource, StateSink {

    private final ContractStore store;

    public ContractStateAdapter(ContractStore store) {
        this.store = store;
    }

    @Override
    public void forEach(byte domain, EntryConsumer out) {
        switch (domain) {
            case StateKeys.CONTRACT_CODE ->
                store.forEachCode((contract, code) -> out.accept(contract.toBytes(), code));
            case StateKeys.CONTRACT_STORAGE ->
                store.forEachStorage((contract, key, value) -> out.accept(concat(contract.toBytes(), key), value));
            default -> throw new IllegalArgumentException("not a contract domain: " + domain);
        }
    }

    @Override
    public void put(byte domain, byte[] key, byte[] value) {
        switch (domain) {
            case StateKeys.CONTRACT_CODE -> store.putCode(PublicAddress.of(key), value);
            case StateKeys.CONTRACT_STORAGE -> store.putStorage(
                PublicAddress.of(Arrays.copyOfRange(key, 0, PublicAddress.SIZE)),
                Arrays.copyOfRange(key, PublicAddress.SIZE, key.length), value);
            default -> throw new IllegalArgumentException("not a contract domain: " + domain);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
