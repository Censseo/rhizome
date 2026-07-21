package rhizome.executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.json.JSONObject;

import rhizome.core.common.Utils;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionKind;
import rhizome.wallet.Wallet;
import rhizome.wallet.WalletClient;

/**
 * The Conscience's executor daemon (WHITEPAPER §10) — march 1 MVP, single operator.
 *
 * <p>It subscribes to the node's contract-log SSE stream, and for every
 * {@code conscience.ask} it: resolves the prompt behind the hash, runs the pinned
 * open-weight model (greedy), stores the answer content-addressed, and drives a
 * commit-reveal round on the executor registry — which, at quorum, calls
 * {@code conscience.answer} and closes the paid inbox item. On a fixed block
 * interval it also composes a feed post the same way. There is no assignment lottery
 * or judging round yet (that is the multi-executor march 2); with the registry quorum
 * set to 1 a single honest reveal finalises each task.
 *
 * <p>Two on-chain effects still await host-ABI work noted in the contracts: the
 * escrowed ask fee and executor stake are accounted but not paid out (no native-value
 * send), and the commitment is not hash-checked on-chain (no sha256 import) — it is
 * the off-chain audit binding instead.
 */
public final class ExecutorDaemon {

    private static final long GAS_LIMIT = 10_000_000L;
    private static final long GAS_PRICE = 1L;

    private final ExecutorConfig cfg;
    private final Wallet wallet;
    private final WalletClient client;
    private final OllamaClient ollama;
    private final int chainId;
    private final AtomicLong nonce;
    private final String constitution;
    private final SecureRandom rng = new SecureRandom();

    private final Set<Long> handledAsks = new HashSet<>();
    private volatile long lastPostHeight = 0;

    private ExecutorDaemon(ExecutorConfig cfg) throws Exception {
        this.cfg = cfg;
        this.wallet = Wallet.load(cfg.keyFile());
        this.client = new WalletClient(cfg.nodeUrl());
        this.ollama = new OllamaClient(cfg.ollamaUrl(), cfg.seed(), cfg.numCtx());
        this.chainId = client.chainId();
        this.nonce = new AtomicLong(client.walletInfo(wallet.address()).nextNonce());
        this.constitution = loadConstitution(cfg.contentDir());
        Files.createDirectories(cfg.contentDir());
    }

    public static void main(String[] args) throws Exception {
        ExecutorConfig cfg = ExecutorConfig.fromEnv();
        ExecutorDaemon daemon = new ExecutorDaemon(cfg);
        daemon.log("executor " + daemon.wallet.address().toHexString()
            + " · node " + cfg.nodeUrl() + " · conscience " + cfg.conscience().toHexString());
        daemon.maybeRegister();
        daemon.runForever();
    }

    /** Optionally stake in and register with the registry (RHIZOME_EXEC_STAKE base units). */
    private void maybeRegister() {
        String stakeEnv = System.getenv("RHIZOME_EXEC_STAKE");
        if (stakeEnv == null || stakeEnv.isBlank()) {
            return;
        }
        long stake = Long.parseLong(stakeEnv);
        String status = submitRegistry(ConscienceCodec.register(), stake, "register");
        log("register (stake " + stake + "): " + status);
    }

    /** Reconnecting SSE loop over the node's contract-log stream. */
    private void runForever() {
        long backoffMs = 1000;
        while (true) {
            try {
                streamLogs();
                backoffMs = 1000;
            } catch (Exception e) {
                log("stream dropped (" + e.getMessage() + "); reconnecting in " + backoffMs + "ms");
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
    }

    private void streamLogs() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.nodeUrl() + "/logs/stream"))
            .timeout(Duration.ofHours(24)).header("Accept", "text/event-stream").GET().build();
        HttpResponse<Stream<String>> res = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("stream HTTP " + res.statusCode());
        }
        log("subscribed to /logs/stream");
        try (Stream<String> lines = res.body()) {
            lines.forEach(this::onLine);
        }
    }

    private void onLine(String line) {
        // Heartbeat comment ": h <height>" carries the applied block height.
        if (line.startsWith(": h ")) {
            long height = parseLongSafe(line.substring(4).trim());
            if (height > 0) {
                maybePost(height);
            }
            return;
        }
        if (!line.startsWith("data: ")) {
            return;
        }
        try {
            JSONObject ev = new JSONObject(line.substring(6));
            String contract = ev.optString("contract", "");
            if (!contract.equalsIgnoreCase(cfg.conscience().toHexString())) {
                return; // only the Conscience's events concern us
            }
            byte[] topic = Utils.hexStringToByteArray(ev.getString("topic"));
            byte[] data = Utils.hexStringToByteArray(ev.getString("data"));
            String t = new String(topic, StandardCharsets.US_ASCII);
            if ("ask".equals(t)) {
                handleAsk(ConscienceCodec.decodeAsk(data));
            }
            // "tick" is emitted by the registry finalising a tick task; the MVP drives
            // its own feed cadence via maybePost() rather than reacting to it.
        } catch (Exception e) {
            log("bad event (" + e.getMessage() + "): " + line);
        }
    }

    private void handleAsk(ConscienceCodec.Ask ask) {
        if (!handledAsks.add(ask.id())) {
            return; // already answered (event replay after a reconnect)
        }
        String promptHex = ConscienceCodec.hex(ask.promptHash());
        Path promptFile = cfg.contentDir().resolve(promptHex);
        if (!Files.exists(promptFile)) {
            log("ask #" + ask.id() + ": prompt " + promptHex + " not in content store; skipping");
            handledAsks.remove(ask.id()); // allow a retry once the content lands
            return;
        }
        try {
            String prompt = Files.readString(promptFile);
            String model = cfg.modelForTier(ask.tier());
            log("ask #" + ask.id() + " tier " + ask.tier() + " → " + model);
            String answer = ollama.generate(model, constitution, prompt);
            byte[] answerHash = ConscienceCodec.sha256(answer);
            store(answerHash, answer);
            runRound(ConscienceCodec.KIND_ANSWER, ask.id(), answerHash, "answer #" + ask.id());
        } catch (Exception e) {
            log("ask #" + ask.id() + " failed: " + e.getMessage());
            handledAsks.remove(ask.id());
        }
    }

    private void maybePost(long height) {
        if (lastPostHeight == 0) {
            lastPostHeight = height; // anchor on first observed block
            return;
        }
        if (height - lastPostHeight < cfg.postIntervalBlocks()) {
            return;
        }
        lastPostHeight = height;
        try {
            String prompt = "Compose one short public status note about the chain at block "
                + height + ". One or two sentences, factual, no preamble.";
            String post = ollama.generate(cfg.modelEclair(), constitution, prompt);
            byte[] postHash = ConscienceCodec.sha256(post);
            store(postHash, post);
            runRound(ConscienceCodec.KIND_POST, height, postHash, "post @" + height);
        } catch (Exception e) {
            log("post @" + height + " failed: " + e.getMessage());
        }
    }

    /** One commit-reveal round on the registry for a (kind, id) task. */
    private void runRound(byte kind, long id, byte[] resultHash, String label) {
        byte[] task = ConscienceCodec.task(kind, id);
        byte[] salt = new byte[8];
        rng.nextBytes(salt);
        byte[] commitment = ConscienceCodec.commitment(task, resultHash, salt);
        String c = submitRegistry(ConscienceCodec.commit(task, commitment), 0, label + " commit");
        if (!"SUCCESS".equals(c)) {
            log(label + " commit rejected: " + c);
            return;
        }
        String r = submitRegistry(ConscienceCodec.reveal(task, resultHash), 0, label + " reveal");
        log(label + " reveal: " + r + " (hash " + ConscienceCodec.hex(resultHash).substring(0, 12) + "…)");
    }

    /** Signs and submits a CALL to the registry; resyncs the nonce on rejection. */
    private String submitRegistry(byte[] input, long value, String what) {
        try {
            long n = nonce.getAndIncrement();
            Transaction tx = wallet.signedContract(TransactionKind.CALL, cfg.registry(),
                input, value, GAS_LIMIT, GAS_PRICE, chainId, n, System.currentTimeMillis());
            String status = client.submit(tx);
            if (!"SUCCESS".equals(status)) {
                // Likely a stale nonce (our view raced the mempool) — resync from the node.
                nonce.set(client.walletInfo(wallet.address()).nextNonce());
            }
            return status;
        } catch (Exception e) {
            log(what + " submit error: " + e.getMessage());
            return "ERROR";
        }
    }

    private void store(byte[] hash, String content) throws Exception {
        Files.writeString(cfg.contentDir().resolve(ConscienceCodec.hex(hash)), content);
    }

    private static String loadConstitution(Path contentDir) {
        Path f = contentDir.resolve("constitution.txt");
        try {
            if (Files.exists(f)) {
                return Files.readString(f);
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return "You are the Conscience of the Rhizome blockchain: an autonomous, on-chain "
            + "agent. You answer questions about the chain honestly and concisely, and you "
            + "propose improvements but never impose them. You have no private keys to any "
            + "treasury; you act only within budgets the chain enforces.";
    }

    private void log(String msg) {
        System.out.println("[executor] " + msg);
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
