package rhizome.services.blockchain;

import org.jetbrains.annotations.NotNull;
import lombok.Getter;
import lombok.Setter;
import rhizome.core.blockchain.AbstractBlockchain;
import io.activej.async.service.ReactiveService;
import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;

@Getter
@Setter
public class BlockchainService extends AbstractBlockchain implements ReactiveService {

    private Eventloop eventloop;

    public BlockchainService(Eventloop eventloop) {
        this.eventloop = eventloop;
    }

    @Override
    public @NotNull Promise<?> start() {
        throw new UnsupportedOperationException("Unimplemented method 'start'");
    }

    @Override
    public @NotNull Promise<?> stop() {
        throw new UnsupportedOperationException("Unimplemented method 'stop'");
    }

    @Override
    public @NotNull Reactor getReactor() {
        return eventloop;
    }
}
