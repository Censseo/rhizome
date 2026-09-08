# Rhizome testnet faucet

A small, standalone HTTP service that hands out test-network coins. It is **not** a route on
`app-node` and **not** a page in the dashboard — see the docstring at the top of `faucet.py` for
why. It holds exactly one signing key and talks to a node the same way any other wallet does: by
shelling out to the `app-wallet` CLI's `send` command.

Stdlib-only Python 3 (`http.server`), no third-party dependency, no build step.

## Running it

```bash
./gradlew :app-wallet:installDist          # once, if app-wallet/build/install doesn't exist yet

python3 scripts/local-testnet/faucet/faucet.py \
  --key-file /etc/rhizome/faucet.key \
  --node-url https://staging.example.org \
  --state-file /var/lib/rhizome-faucet/state.json
```

`--key-file` is the only required flag. Everything else has a documented default; run
`faucet.py --help` to see them all, or read the table below. Every flag has an environment
variable equivalent (the flag wins if both are given), so it can be run as a systemd unit with
`Environment=` lines instead of a long command line.

## Where the key must live

**Never inside this git repository.** A real deployment generates the faucet's key through a
normal `app-wallet keygen` (or an offline ceremony, if the faucet funds itself from a cold
reserve rather than being pre-funded at genesis) and stores it outside any working tree the
faucet process's operator does not fully control — e.g. `/etc/rhizome/faucet.key`, **mode
`0600`**, owned by the service user. `faucet.py` checks the file's permission bits at startup
and prints a warning (not a refusal — it still starts) if the mode is readable or writable
beyond its owner.

If the key is encrypted (`app-wallet keygen` without `--plaintext`), point
`--passphrase-file`/`RHIZOME_FAUCET_PASSPHRASE_FILE` at a file holding the passphrase. Do not
rely on an interactive prompt: `faucet.py` runs the wallet CLI with `stdin` closed, so a
passphrase-protected key with no `--passphrase-file` configured fails every drip cleanly (503)
rather than hanging the process waiting for a prompt that will never come.

The local test battery (`suite-faucet.sh`) never touches a real key — see below.

## Environment variables / flags

| Flag | Env var | Default | Meaning |
|---|---|---|---|
| `--node-url` | `RHIZOME_FAUCET_NODE_URL` | `http://127.0.0.1:3000` | node the faucet submits transactions to |
| `--key-file` | `RHIZOME_FAUCET_KEY_FILE` | *(required)* | faucet's own wallet-CLI key file |
| `--passphrase-file` | `RHIZOME_FAUCET_PASSPHRASE_FILE` | *(none)* | passphrase file, if the key is encrypted |
| `--wallet-bin` | `RHIZOME_FAUCET_WALLET_BIN` | `app-wallet/build/install/app-wallet/bin/app-wallet` | the CLI binary to shell out to |
| `--state-file` | `RHIZOME_FAUCET_STATE_FILE` | `~/.rhizome-faucet/state.json` | persisted cooldowns/budget (JSON) |
| `--drip-pdn` | `RHIZOME_FAUCET_DRIP_PDN` | `1` | amount per successful drip, in PDN |
| `--fee-pdn` | `RHIZOME_FAUCET_FEE_PDN` | `0` | fee attached to each drip, in PDN |
| `--cooldown-seconds` | `RHIZOME_FAUCET_COOLDOWN_SECONDS` | `3600` | minimum time between two drips to the same address |
| `--ip-daily-cap` | `RHIZOME_FAUCET_IP_DAILY_CAP` | `5` | max successful drips per source IP per UTC day |
| `--daily-budget-pdn` | `RHIZOME_FAUCET_DAILY_BUDGET_PDN` | `500` | hard stop: total PDN handed out per UTC day |
| `--pow-difficulty-bits` | `RHIZOME_FAUCET_POW_DIFFICULTY_BITS` | `18` | hashcash-style PoW difficulty (bits of leading zero) |
| `--pow-ttl-seconds` | `RHIZOME_FAUCET_POW_TTL_SECONDS` | `120` | how long an issued challenge stays solvable |
| `--wallet-timeout-seconds` | `RHIZOME_FAUCET_WALLET_TIMEOUT_SECONDS` | `30` | timeout for one `app-wallet send` subprocess call |
| `--node-probe-timeout-seconds` | `RHIZOME_FAUCET_NODE_PROBE_TIMEOUT_SECONDS` | `5` | timeout for the `/stats` reachability check done before every drip |
| `--host` | `RHIZOME_FAUCET_HOST` | `127.0.0.1` | bind address — loopback by default; open it deliberately (e.g. behind a reverse proxy), not by accident |
| `--port` | `RHIZOME_FAUCET_PORT` | `8085` | listen port |

`--fee-pdn` matters per network: devnet's `minFee` is `0`, but a profile like `staging` inherits
mainnet's `minFee = 10` base units (`0.001` PDN) — a drip below that fee is admitted by the
faucet's own checks but rejected by the node, which `faucet.py` reports as a clean `503`. Check
`scripts/local-testnet/profiles/<network>.env`'s `MIN_FEE` before pointing this at a real node.

## Routes

- `GET /` — a minimal HTML form. The browser solves the proof-of-work challenge itself with the
  Web Crypto API (`crypto.subtle.digest`) and POSTs the result; no captcha, no third-party
  script, no build step.
- `GET /challenge` — `{"nonce", "difficultyBits", "expiresAt"}`. `nonce` is single-use: solving
  and submitting it consumes it, whether or not the drip that follows succeeds.
- `POST /drip` — `{"address", "nonce", "solution"}`. A solution is accepted when
  `sha256(nonce + ":" + solution)` has at least `difficultyBits` leading zero bits. Malformed
  input (bad JSON, invalid address, invalid/expired/already-used challenge) is rejected before
  the wallet CLI is ever invoked — no transaction is submitted for a request that fails these
  checks. Status codes: `200` success, `400` malformed/bad PoW, `429` cooldown or per-IP cap,
  `503` daily budget exhausted or the node/wallet CLI could not complete the transfer.
- `GET /status` — `{"nodeReachable", "faucetAddress", "dailyBudget*", "dripBaseUnits", ...}`. No
  secrets: no key path, no wallet binary path, no passphrase file path.

## The honest limitation

**None of the controls here stop a determined attacker with many IP addresses and some CPU.**
The proof-of-work makes a single request mildly expensive to produce and free to verify, and the
per-address cooldown and per-IP cap raise the cost of draining the faucet from *one* machine —
but an attacker who controls a botnet, a residential proxy pool, or just enough patience defeats
all three by construction: PoW is a rate-limit, not an identity system, and per-IP accounting is
exactly as strong as the assumption that one IP is one attacker.

The actual backstop is the **global daily budget** (`--daily-budget-pdn`), which caps the
faucet's total loss regardless of how the requests were distributed, combined with the fact that
**this is test-network money with no value outside the network it was minted on**. Do not point
this faucet's `--key-file` at a mainnet account, and do not treat the PoW/cooldown/per-IP
controls as anything stronger than friction.

## Testing

`scripts/local-testnet/suite-faucet.sh` exercises this service end to end against a local devnet
started by `scripts/local-testnet/start.sh`. It generates its own **throwaway** key with
`app-wallet keygen --plaintext`, funds it from the devnet's miners, and never touches a real
staging or mainnet key — see the suite's header comment for the hygiene check that pins this.
