package rhizome.executor;

import java.nio.file.Path;

import rhizome.core.ledger.PublicAddress;

/**
 * Executor daemon configuration, read from the environment. The executor is the
 * off-chain half of the Conscience (WHITEPAPER §10): it watches the node's log
 * stream, runs a pinned open-weight model, and drives commit-reveal rounds on the
 * executor registry. This MVP is a single operator (march 1) — no assignment lottery
 * and no judging round yet; with the registry's quorum set to 1 a single honest
 * reveal finalises a task and drives the Conscience.
 *
 * <p>All state that must be reproducible across executors — which model, which
 * decoding — is a constitution concern; here it is pinned locally so the operator
 * can point the daemon at the model the constitution names.
 */
public record ExecutorConfig(
        String nodeUrl,
        Path keyFile,
        PublicAddress conscience,
        PublicAddress registry,
        String ollamaUrl,
        String modelEclair,
        String modelProfond,
        long seed,
        int numCtx,
        Path contentDir,
        long postIntervalBlocks) {

    public static ExecutorConfig fromEnv() {
        return new ExecutorConfig(
            require("RHIZOME_NODE", "http://localhost:3000"),
            Path.of(require("RHIZOME_EXEC_KEY", "executor.key")),
            PublicAddress.of(require("RHIZOME_CONSCIENCE", null)),
            PublicAddress.of(require("RHIZOME_REGISTRY", null)),
            require("RHIZOME_OLLAMA", "http://localhost:11434"),
            require("RHIZOME_MODEL_ECLAIR", "gemma3:latest"),
            require("RHIZOME_MODEL_PROFOND", "gemma3:latest"),
            Long.parseLong(require("RHIZOME_EXEC_SEED", "0")),
            Integer.parseInt(require("RHIZOME_EXEC_CTX", "8192")),
            Path.of(require("RHIZOME_EXEC_CONTENT", "./exec-content")),
            Long.parseLong(require("RHIZOME_POST_INTERVAL", "720")));
    }

    private static String require(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            if (dflt == null) {
                throw new IllegalStateException("missing required env var " + key);
            }
            return dflt;
        }
        return v;
    }

    /** The pinned model for a tier byte (0 = éclair, else profond). */
    public String modelForTier(int tier) {
        return tier == 0 ? modelEclair : modelProfond;
    }
}
