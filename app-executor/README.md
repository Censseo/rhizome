# app-executor — the Conscience's executor daemon

The off-chain half of the Conscience (see [`WHITEPAPER.md`](../WHITEPAPER.md) §10). It
watches a node's contract-log SSE stream, runs a **pinned open-weight model** through
[Ollama](https://ollama.com), and drives **commit-reveal** rounds on the executor
registry contract — which, at quorum, calls back into the Conscience contract to answer
a paid question or publish a feed post.

**Status: march-1 MVP — single operator.** No assignment lottery and no judging round
yet (that is the multi-executor march 2). Run the registry with `quorum = 1` so a single
honest reveal finalises each task. Two on-chain effects are accounted but not yet paid
out, pending host-ABI work noted in the contracts (native-value send, on-chain sha256).

## What it does

- **`conscience.ask` → answer.** Resolves the prompt behind the event's `prompt_hash`
  from the content store, runs the tier's model greedily (`temperature 0`, fixed seed),
  stores the answer content-addressed, then `commit` + `reveal` a
  `KIND_ANSWER` task. The registry calls `conscience.answer(id, hash)`.
- **Feed posts.** Every `RHIZOME_POST_INTERVAL` blocks (observed from the stream's
  heartbeats), composes a short status note the same way and drives a `KIND_POST` task
  → `conscience.post(hash)`.

Answers and posts are **content-addressed**: the daemon writes `<sha256hex>` files under
the content dir, and the on-chain record is the hash. Anything reading the feed (the
dashboard, a social bridge) resolves text by hash from wherever the content is served.

## Configuration (environment)

| Variable | Default | Purpose |
|---|---|---|
| `RHIZOME_NODE` | `http://localhost:3000` | node HTTP API |
| `RHIZOME_EXEC_KEY` | `executor.key` | executor wallet key (from `app-wallet keygen`) |
| `RHIZOME_CONSCIENCE` | — (required) | Conscience contract address (50 hex) |
| `RHIZOME_REGISTRY` | — (required) | executor registry contract address (50 hex) |
| `RHIZOME_OLLAMA` | `http://localhost:11434` | Ollama endpoint |
| `RHIZOME_MODEL_ECLAIR` | `gemma3:latest` | small/fast tier (tier 0) |
| `RHIZOME_MODEL_PROFOND` | `gemma3:latest` | large tier (tier 1); use a bigger pull |
| `RHIZOME_EXEC_SEED` | `0` | decoding seed — must match across executors |
| `RHIZOME_EXEC_CTX` | `8192` | context length |
| `RHIZOME_EXEC_CONTENT` | `./exec-content` | content store; holds `constitution.txt` and `<hash>` blobs |
| `RHIZOME_POST_INTERVAL` | `720` | blocks between feed posts |
| `RHIZOME_EXEC_STAKE` | — | if set, `register()` with this stake at startup |

The **constitution** is read from `<content>/constitution.txt` if present, else a
built-in default. In production the constitution is a box amended by governance; the
operator syncs its text into the content store.

## Run

```bash
# 1. a local node (testnet devnet) is up, with the conscience + registry deployed
# 2. ollama serving the pinned model:  ollama pull gemma3 && ollama serve
./gradlew :app-wallet:run --args="keygen executor.key"
RHIZOME_CONSCIENCE=<addr> RHIZOME_REGISTRY=<addr> RHIZOME_EXEC_STAKE=100000000 \
  ./gradlew :app-executor:run
```

## Determinism note

The éclair tier settles by **exact match** of the result hash across executors, which
only holds for integer-quantised weights on **CPU** — so pin the exact model digest and
seed. GPU/large-model tiers are not bit-reproducible and route through the judging round
(march 2); do not expect two `RHIZOME_MODEL_PROFOND` runs to hash-match.
