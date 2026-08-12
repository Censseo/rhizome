package rhizome.node;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;

import rhizome.core.blockchain.BlockProducer;
import rhizome.net.PeerDiscovery;

/**
 * Everything {@link RhizomeNode#start()} opened: the listening socket, the threads and the
 * schedules. Held whole rather than field-by-field so shutdown has one question to ask — did the
 * node ever start? — instead of eight independent null checks that could each be answered
 * differently by a start that failed halfway.
 *
 * @param producer null when no miner address is configured; the only optional component
 */
record NodeRuntime(
    Eventloop eventloop,
    Thread eventloopThread,
    HttpServer httpServer,
    ExecutorService apiWorkers,
    BlockProducer producer,
    ScheduledExecutorService syncScheduler,
    PeerDiscovery discovery) {
}
