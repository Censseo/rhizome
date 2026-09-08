#!/usr/bin/env python3
"""Rhizome testnet faucet — a standalone service, stdlib-only (no third-party dependency,
matching scripts/local-testnet/tools/hostile_peer.py and friends: http.server, nothing else).

Deliberately NOT a node route and NOT a dashboard page:

  * A node route would mean every mainnet binary forever carries the code path and the surface
    for holding a signing key, for a capability only a test network needs.
  * A dashboard page would mean shipping the faucet's private key to every browser that loads
    it — the dashboard's whole design property is that the node (and any page it serves) never
    sees a private key; wallet keys live and are used only in the browser or in this CLI-style
    process, never on a server that also does something else.

So this is a small, separate process that holds exactly one signing key, talks to a node the
same way any other client does (by shelling out to the wallet CLI's `send`, the same command a
human operator would type), and persists its own rate-limit state to a JSON file so a restart
cannot be used to reset a cooldown or refill the daily budget.

Routes:
    GET  /            minimal HTML form (address input, solves the PoW challenge client-side
                       with the browser's Web Crypto API, then POSTs /drip)
    GET  /challenge    issues a fresh hashcash-style proof-of-work challenge: a random nonce and
                       a difficulty (bits of leading zero required of sha256(nonce + ":" + solution))
    POST /drip         {"address": "...", "nonce": "...", "solution": "..."} -> drips the
                       configured amount to `address`, subject to PoW, per-address cooldown,
                       per-IP daily cap and the global daily budget, in that order
    GET  /status       daily budget remaining, node reachability — no secrets

Every setting has an environment variable AND a CLI flag (flag wins if both given); see
README.md in this directory, or run `faucet.py --help`.

See README.md for the honest limitation of the abuse controls here: none of them stop a
determined attacker with many IPs and CPU. The real backstop is the global daily budget and the
fact that this money is worthless outside a test network.
"""
from __future__ import annotations

import argparse
import hashlib
import http.server
import json
import os
import secrets
import stat
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

# Mirrors rhizome.core.common.Constants.DECIMAL_SCALE_FACTOR — this process is stdlib-only
# Python and cannot import the Java constant, so the value is copied here instead (same
# category of checked mirror as scripts/local-testnet/profiles/*.env). 1 PDN == 10000 base
# units; PDN amounts finer than that are rejected rather than silently truncated.
DECIMAL_SCALE_FACTOR = 10000

MAX_DRIP_BODY_BYTES = 4096
MAX_SOLUTION_LEN = 64
MAX_NONCE_LEN = 64
MAX_ADDRESS_LEN = 64  # the wire format is 50 hex chars; a little slack before the hard reject


def today() -> str:
    return datetime.now(timezone.utc).date().isoformat()


def is_valid_address(addr: str) -> bool:
    """Local, offline replica of PublicAddress.isValidChecksum(): version(1) || body(20) ||
    checksum(4), checksum = SHA256(SHA256(version||body))[0:4]. Doing this check here — instead
    of letting a malformed address reach the wallet CLI and fail there — is what keeps a
    malformed request from ever invoking a subprocess or touching the node."""
    if not isinstance(addr, str) or len(addr) != 50:
        return False
    try:
        raw = bytes.fromhex(addr)
    except ValueError:
        return False
    if len(raw) != 25:
        return False
    body, checksum = raw[:21], raw[21:]
    expected = hashlib.sha256(hashlib.sha256(body).digest()).digest()[:4]
    return checksum == expected


def leading_zero_bits(digest: bytes) -> int:
    bits = 0
    for b in digest:
        if b == 0:
            bits += 8
            continue
        bits += 8 - b.bit_length()
        break
    return bits


def pow_digest(nonce: str, solution: str) -> bytes:
    return hashlib.sha256(f"{nonce}:{solution}".encode("utf-8")).digest()


def pdn_to_base_units(pdn_text: str, flag_name: str) -> int:
    try:
        amount = Decimal(pdn_text)
    except InvalidOperation:
        raise SystemExit(f"faucet: invalid {flag_name} PDN amount: {pdn_text!r}")
    if amount < 0:
        raise SystemExit(f"faucet: {flag_name} must not be negative: {pdn_text!r}")
    scaled = amount * DECIMAL_SCALE_FACTOR
    if scaled != scaled.to_integral_value():
        raise SystemExit(
            f"faucet: {flag_name} is finer than a base unit (scale {DECIMAL_SCALE_FACTOR}): "
            f"{pdn_text!r}")
    return int(scaled)


def sanitize(text: str | None, limit: int = 300) -> str:
    """The wallet CLI's stdout/stderr is logged and echoed in /drip error bodies; strip control
    characters (log/terminal injection from whatever the node happened to say) and cap the
    length rather than trust it."""
    if not text:
        return ""
    cleaned = "".join(ch if ch.isprintable() else " " for ch in text)
    return " ".join(cleaned.split())[:limit]


# --------------------------------------------------------------------------------------------
# Persisted state: per-address cooldown, per-IP daily count, global daily budget spend. Written
# with a temp-file-then-rename so a crash mid-write can never leave a half-written file that a
# restart would fail to parse or (worse) silently treat as empty and hand out a fresh budget.
# --------------------------------------------------------------------------------------------
class FaucetState:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._data = self._load()

    def _load(self) -> dict:
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, ValueError) as e:
                raise SystemExit(f"faucet: cannot read state file {self.path}: {e}")
            if not isinstance(data, dict):
                raise SystemExit(f"faucet: state file {self.path} is not a JSON object")
        else:
            data = {}
        data.setdefault("version", 1)
        data.setdefault("dailyBudget", {"date": today(), "spentBaseUnits": 0})
        data.setdefault("cooldowns", {})
        data.setdefault("ipUsage", {})
        return data

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".tmp")
        with tmp.open("w", encoding="utf-8") as f:
            json.dump(self._data, f, indent=2, sort_keys=True)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)  # atomic on POSIX: readers never see a partial file

    def _roll_daily_locked(self) -> None:
        budget = self._data["dailyBudget"]
        if budget.get("date") != today():
            budget["date"] = today()
            budget["spentBaseUnits"] = 0

    def snapshot(self) -> dict:
        with self._lock:
            self._roll_daily_locked()
            return dict(self._data["dailyBudget"])

    def cooldown_remaining_seconds(self, address: str, cooldown_seconds: int) -> float:
        with self._lock:
            last = self._data["cooldowns"].get(address)
            if last is None:
                return 0.0
            return max(0.0, cooldown_seconds - (time.time() - last))

    def ip_remaining_today(self, ip: str, ip_daily_cap: int) -> int:
        with self._lock:
            self._roll_daily_locked()
            usage = self._data["ipUsage"].get(ip)
            if usage is None or usage.get("date") != today():
                return ip_daily_cap
            return max(0, ip_daily_cap - int(usage.get("count", 0)))

    def budget_remaining_base_units(self, daily_budget_base_units: int) -> int:
        with self._lock:
            self._roll_daily_locked()
            return max(0, daily_budget_base_units - int(self._data["dailyBudget"]["spentBaseUnits"]))

    def record_drip(self, address: str, ip: str, amount_base_units: int) -> None:
        """Called ONLY after the wallet CLI has reported SUCCESS — a drip that never reached the
        chain must never start a cooldown or spend budget. Cooldown, per-IP count and the daily
        spend move together in one locked, persisted write, so a crash right after the on-chain
        transfer costs at worst one ungoverned drip, never a torn state file."""
        with self._lock:
            self._roll_daily_locked()
            self._data["cooldowns"][address] = time.time()
            usage = self._data["ipUsage"].setdefault(ip, {"date": today(), "count": 0})
            if usage.get("date") != today():
                usage["date"] = today()
                usage["count"] = 0
            usage["count"] = int(usage.get("count", 0)) + 1
            self._data["dailyBudget"]["spentBaseUnits"] = (
                int(self._data["dailyBudget"]["spentBaseUnits"]) + amount_base_units)
            self._save_locked()


# --------------------------------------------------------------------------------------------
# Proof-of-work challenges: in-memory only, deliberately not persisted (a TTL of a couple of
# minutes means a restart losing outstanding challenges is a non-event — the client just asks
# for a new one). Single-use: consume() deletes the entry, so a solved challenge cannot fund two
# drips no matter how many times the same solution is replayed.
# --------------------------------------------------------------------------------------------
class ChallengeStore:
    def __init__(self, difficulty_bits: int, ttl_seconds: int):
        self.difficulty_bits = difficulty_bits
        self.ttl_seconds = ttl_seconds
        self._lock = threading.Lock()
        self._issued: dict[str, float] = {}  # nonce -> expires_at

    def _purge_expired_locked(self) -> None:
        now = time.time()
        for nonce in [n for n, exp in self._issued.items() if exp <= now]:
            del self._issued[nonce]

    def issue(self) -> tuple[str, float]:
        with self._lock:
            self._purge_expired_locked()
            nonce = secrets.token_hex(16)
            expires_at = time.time() + self.ttl_seconds
            self._issued[nonce] = expires_at
            return nonce, expires_at

    def consume(self, nonce: object, solution: object) -> bool:
        if not isinstance(nonce, str) or not (0 < len(nonce) <= MAX_NONCE_LEN):
            return False
        if not isinstance(solution, str) or not (0 < len(solution) <= MAX_SOLUTION_LEN):
            return False
        with self._lock:
            self._purge_expired_locked()
            if nonce not in self._issued:
                return False
            if leading_zero_bits(pow_digest(nonce, solution)) < self.difficulty_bits:
                return False
            del self._issued[nonce]  # single-use
            return True


@dataclass(frozen=True)
class Config:
    node_url: str
    key_file: Path
    passphrase_file: Path | None
    wallet_bin: Path
    state_file: Path
    drip_pdn: str
    drip_base_units: int
    fee_pdn: str
    cooldown_seconds: int
    ip_daily_cap: int
    daily_budget_base_units: int
    pow_difficulty_bits: int
    pow_ttl_seconds: int
    wallet_timeout_seconds: int
    node_probe_timeout_seconds: float
    host: str
    port: int


class Faucet:
    def __init__(self, config: Config):
        self.config = config
        self.state = FaucetState(config.state_file)
        self.challenges = ChallengeStore(config.pow_difficulty_bits, config.pow_ttl_seconds)
        # Serializes the whole check-then-submit-then-persist sequence: the wallet CLI reads the
        # faucet account's next nonce from the node and signs against it, so two drips racing
        # each other could sign the same nonce twice. One lock, held for the full drip(), makes
        # every drip strictly sequential — acceptable for a faucet, not for a busy node.
        self._drip_lock = threading.Lock()
        self.faucet_address = self._probe_faucet_address()

    def _probe_faucet_address(self) -> str | None:
        argv = [str(self.config.wallet_bin), "address", str(self.config.key_file)]
        if self.config.passphrase_file:
            argv += ["--passphrase-file", str(self.config.passphrase_file)]
        try:
            proc = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                                   text=True, timeout=self.config.wallet_timeout_seconds)
        except (OSError, subprocess.TimeoutExpired) as e:
            print(f"faucet: WARNING could not determine the faucet's own address at startup: {e}",
                  file=sys.stderr)
            return None
        if proc.returncode != 0:
            print("faucet: WARNING `wallet address` failed at startup: "
                  f"{sanitize(proc.stderr or proc.stdout)}", file=sys.stderr)
            return None
        return proc.stdout.strip() or None

    def _node_reachable(self) -> bool:
        try:
            req = urllib.request.Request(self.config.node_url.rstrip("/") + "/stats",
                                          headers={"X-Rhizome-Request": "1"})
            with urllib.request.urlopen(req, timeout=self.config.node_probe_timeout_seconds) as resp:
                return 200 <= resp.status < 300
        except (urllib.error.URLError, OSError, ValueError):
            return False

    def _wallet_send(self, address: str) -> tuple[bool, str]:
        argv = [str(self.config.wallet_bin), "send", self.config.node_url,
                str(self.config.key_file), address, self.config.drip_pdn, self.config.fee_pdn]
        if self.config.passphrase_file:
            argv += ["--passphrase-file", str(self.config.passphrase_file)]
        try:
            # stdin=DEVNULL is load-bearing: an encrypted key file with no --passphrase-file
            # would otherwise make the wallet CLI block on an interactive console prompt that
            # will never come, hanging the request (and, with the drip lock held, every request
            # after it) instead of failing cleanly.
            proc = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                                   text=True, timeout=self.config.wallet_timeout_seconds)
        except subprocess.TimeoutExpired:
            return False, "wallet CLI timed out"
        except OSError as e:
            return False, f"wallet CLI could not be started: {e}"
        if proc.returncode != 0:
            return False, sanitize(proc.stderr or proc.stdout) or f"wallet CLI exited {proc.returncode}"
        if "status: SUCCESS" not in proc.stdout:
            return False, sanitize(proc.stdout) or "wallet CLI did not report SUCCESS"
        return True, sanitize(proc.stdout)

    def drip(self, address: str, ip: str) -> tuple[int, dict]:
        with self._drip_lock:
            cooldown_left = self.state.cooldown_remaining_seconds(address, self.config.cooldown_seconds)
            if cooldown_left > 0:
                return 429, {"error": "address in cooldown",
                             "retryAfterSeconds": int(cooldown_left) + 1}
            if self.state.ip_remaining_today(ip, self.config.ip_daily_cap) <= 0:
                return 429, {"error": "per-IP daily cap reached"}
            remaining = self.state.budget_remaining_base_units(self.config.daily_budget_base_units)
            if remaining < self.config.drip_base_units:
                return 503, {"error": "daily faucet budget exhausted",
                             "dailyBudgetRemainingBaseUnits": remaining}
            if not self._node_reachable():
                return 503, {"error": "faucet's node is unreachable"}
            ok, detail = self._wallet_send(address)
            if not ok:
                return 503, {"error": "transfer failed", "detail": detail}
            self.state.record_drip(address, ip, self.config.drip_base_units)
            return 200, {"status": "SUCCESS", "amountBaseUnits": self.config.drip_base_units,
                         "detail": detail}

    def status(self) -> dict:
        snap = self.state.snapshot()
        spent = int(snap.get("spentBaseUnits", 0))
        budget = self.config.daily_budget_base_units
        return {
            "nodeReachable": self._node_reachable(),
            "faucetAddress": self.faucet_address,
            "date": snap.get("date"),
            "dailyBudgetBaseUnits": budget,
            "dailyBudgetSpentBaseUnits": spent,
            "dailyBudgetRemainingBaseUnits": max(0, budget - spent),
            "dripBaseUnits": self.config.drip_base_units,
            "cooldownSeconds": self.config.cooldown_seconds,
            "ipDailyCap": self.config.ip_daily_cap,
            "powDifficultyBits": self.config.pow_difficulty_bits,
        }


INDEX_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Rhizome testnet faucet</title>
<style>
body { font-family: sans-serif; max-width: 34em; margin: 3em auto; padding: 0 1em; color: #222; }
input { width: 100%; box-sizing: border-box; padding: .5em; font-family: monospace; font-size: 1em; }
button { margin-top: .6em; padding: .6em 1.2em; font-size: 1em; }
#out { white-space: pre-wrap; margin-top: 1em; font-family: monospace; font-size: .9em; }
.warn { color: #a33; }
</style>
</head>
<body>
<h1>Rhizome testnet faucet</h1>
<p class="warn">Test-network coins only. They have no value outside this network.</p>
<p>Paste a Rhizome address below. Your browser solves a small proof-of-work puzzle before the
request is sent — this takes a few seconds and needs no account, captcha, or third party.</p>
<input id="addr" placeholder="recipient address (50 hex characters)" autocomplete="off">
<button id="go">Solve challenge &amp; request coins</button>
<div id="out"></div>
<script>
function leadingZeroBits(bytes) {
  let bits = 0;
  for (const b of bytes) {
    if (b === 0) { bits += 8; continue; }
    let n = b;
    while ((n & 0x80) === 0) { n = (n << 1) & 0xff; bits++; }
    break;
  }
  return bits;
}
async function sha256(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return new Uint8Array(buf);
}
document.getElementById('go').addEventListener('click', async () => {
  const out = document.getElementById('out');
  const button = document.getElementById('go');
  const address = document.getElementById('addr').value.trim();
  if (!address) { out.textContent = 'enter an address first'; return; }
  button.disabled = true;
  try {
    out.textContent = 'fetching challenge...';
    const challenge = await (await fetch('/challenge')).json();
    out.textContent = `solving proof of work (${challenge.difficultyBits} bits)...`;
    let solution = 0, digest, bits;
    const started = performance.now();
    do {
      solution++;
      digest = await sha256(challenge.nonce + ':' + solution);
      bits = leadingZeroBits(digest);
    } while (bits < challenge.difficultyBits);
    out.textContent = `solved in ${((performance.now() - started) / 1000).toFixed(1)}s (${solution} tries), submitting...`;
    const resp = await fetch('/drip', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ address: address, nonce: challenge.nonce, solution: String(solution) }),
    });
    const body = await resp.json().catch(() => ({}));
    out.textContent = `HTTP ${resp.status}: ${JSON.stringify(body, null, 2)}`;
  } catch (e) {
    out.textContent = 'error: ' + e;
  } finally {
    button.disabled = false;
  }
});
</script>
</body>
</html>
"""


def make_handler(faucet: Faucet):
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "RhizomeFaucet/1"
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # noqa: A003 - stdlib override signature
            sys.stderr.write(f"{self.address_string()} - {fmt % args}\n")

        def _send_json(self, code: int, obj: dict) -> None:
            body = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # client hung up; nothing left to do

        def _send_html(self) -> None:
            body = INDEX_HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):  # noqa: A003 - stdlib override signature
            path = urllib.parse.urlsplit(self.path).path
            if path == "/":
                self._send_html()
            elif path == "/challenge":
                nonce, expires_at = faucet.challenges.issue()
                self._send_json(200, {
                    "nonce": nonce,
                    "difficultyBits": faucet.config.pow_difficulty_bits,
                    "expiresAt": int(expires_at),
                })
            elif path == "/status":
                self._send_json(200, faucet.status())
            else:
                self._send_json(404, {"error": "not found"})

        def do_POST(self):  # noqa: A003 - stdlib override signature
            path = urllib.parse.urlsplit(self.path).path
            if path != "/drip":
                self._send_json(404, {"error": "not found"})
                return

            # Malformed-input rejections below all return before touching Faucet.drip(), which
            # is the one method that can invoke the wallet CLI: a bad request must cost this
            # process nothing beyond parsing.
            length_header = self.headers.get("Content-Length")
            try:
                length = int(length_header)
            except (TypeError, ValueError):
                self._send_json(400, {"error": "missing or invalid Content-Length"})
                return
            if length < 0 or length > MAX_DRIP_BODY_BYTES:
                self._send_json(400, {"error": "body too large"})
                return
            raw = self.rfile.read(length)
            try:
                payload = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                self._send_json(400, {"error": "malformed JSON"})
                return
            if not isinstance(payload, dict):
                self._send_json(400, {"error": "request body must be a JSON object"})
                return

            address = payload.get("address")
            nonce = payload.get("nonce")
            solution = payload.get("solution")
            if not isinstance(address, str) or len(address) > MAX_ADDRESS_LEN:
                self._send_json(400, {"error": "address is required"})
                return
            address = address.strip().upper()
            if not is_valid_address(address):
                self._send_json(400, {"error": "invalid address"})
                return
            # PoW is verified before anything that touches the node or the wallet CLI: a bad,
            # expired or already-used challenge must cost this process one sha256 call, nothing
            # more (and consume() is what makes a solved challenge single-use).
            if not faucet.challenges.consume(nonce, solution):
                self._send_json(400, {"error": "invalid, expired or already-used challenge"})
                return

            code, body = faucet.drip(address, self.client_address[0])
            self._send_json(code, body)

    return Handler


def build_config(args: argparse.Namespace) -> Config:
    key_file = Path(args.key_file).expanduser()
    if not key_file.is_file():
        raise SystemExit(f"faucet: --key-file {key_file} does not exist or is not a file")
    mode = stat.S_IMODE(key_file.stat().st_mode)
    if mode & (stat.S_IRWXG | stat.S_IRWXO):
        print(f"faucet: WARNING key file {key_file} is readable/writable beyond its owner "
              f"(mode {oct(mode)}) — chmod 600 it", file=sys.stderr)

    wallet_bin = Path(args.wallet_bin).expanduser()
    if not (wallet_bin.is_file() and os.access(wallet_bin, os.X_OK)):
        raise SystemExit(f"faucet: --wallet-bin {wallet_bin} is not an executable file "
                          "(build it with `./gradlew :app-wallet:installDist`)")

    passphrase_file = Path(args.passphrase_file).expanduser() if args.passphrase_file else None
    if passphrase_file and not passphrase_file.is_file():
        raise SystemExit(f"faucet: --passphrase-file {passphrase_file} does not exist")

    if args.pow_difficulty_bits < 0 or args.pow_difficulty_bits > 64:
        raise SystemExit("faucet: --pow-difficulty-bits must be between 0 and 64")
    if args.cooldown_seconds < 0:
        raise SystemExit("faucet: --cooldown-seconds must not be negative")
    if args.ip_daily_cap < 0:
        raise SystemExit("faucet: --ip-daily-cap must not be negative")
    if args.pow_ttl_seconds <= 0:
        raise SystemExit("faucet: --pow-ttl-seconds must be positive")

    drip_base_units = pdn_to_base_units(args.drip_pdn, "--drip-pdn")
    if drip_base_units <= 0:
        raise SystemExit("faucet: --drip-pdn must be positive")
    fee_base_units = pdn_to_base_units(args.fee_pdn, "--fee-pdn")
    daily_budget_base_units = pdn_to_base_units(args.daily_budget_pdn, "--daily-budget-pdn")
    if daily_budget_base_units < drip_base_units:
        raise SystemExit("faucet: --daily-budget-pdn is smaller than a single --drip-pdn — "
                          "the faucet could never complete one drip")
    del fee_base_units  # validated only; the wallet CLI is given the PDN text directly

    return Config(
        node_url=args.node_url.rstrip("/"),
        key_file=key_file,
        passphrase_file=passphrase_file,
        wallet_bin=wallet_bin,
        state_file=Path(args.state_file).expanduser(),
        drip_pdn=args.drip_pdn,
        drip_base_units=drip_base_units,
        fee_pdn=args.fee_pdn,
        cooldown_seconds=args.cooldown_seconds,
        ip_daily_cap=args.ip_daily_cap,
        daily_budget_base_units=daily_budget_base_units,
        pow_difficulty_bits=args.pow_difficulty_bits,
        pow_ttl_seconds=args.pow_ttl_seconds,
        wallet_timeout_seconds=args.wallet_timeout_seconds,
        node_probe_timeout_seconds=args.node_probe_timeout_seconds,
        host=args.host,
        port=args.port,
    )


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    root = Path(__file__).resolve().parents[3]  # .../faucet/faucet.py -> repo root
    default_wallet_bin = root / "app-wallet" / "build" / "install" / "app-wallet" / "bin" / "app-wallet"
    default_state_file = Path.home() / ".rhizome-faucet" / "state.json"

    def env(name: str, default: str) -> str:
        return os.environ.get(name, default)

    p = argparse.ArgumentParser(
        description="Standalone faucet service for a Rhizome test network.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--node-url", default=env("RHIZOME_FAUCET_NODE_URL", "http://127.0.0.1:3000"),
                   help="node this faucet submits transactions to")
    p.add_argument("--key-file", default=env("RHIZOME_FAUCET_KEY_FILE", ""),
                   help="the faucet's own wallet-CLI key file (required; never inside the git "
                        "repo — see README.md)")
    p.add_argument("--passphrase-file", default=env("RHIZOME_FAUCET_PASSPHRASE_FILE", ""),
                   help="passphrase file for --key-file, if it is encrypted (optional)")
    p.add_argument("--wallet-bin", default=env("RHIZOME_FAUCET_WALLET_BIN", str(default_wallet_bin)),
                   help="path to the app-wallet CLI binary")
    p.add_argument("--state-file", default=env("RHIZOME_FAUCET_STATE_FILE", str(default_state_file)),
                   help="JSON file the faucet persists cooldowns/budget to (read at startup, "
                        "written after every successful drip)")
    p.add_argument("--drip-pdn", default=env("RHIZOME_FAUCET_DRIP_PDN", "1"),
                   help="amount handed out per successful drip, in PDN")
    p.add_argument("--fee-pdn", default=env("RHIZOME_FAUCET_FEE_PDN", "0"),
                   help="transaction fee attached to each drip, in PDN (devnet's minFee is 0; "
                        "a network with a nonzero minFee needs this raised, e.g. staging "
                        "needs at least 0.001)")
    p.add_argument("--cooldown-seconds", type=int,
                   default=int(env("RHIZOME_FAUCET_COOLDOWN_SECONDS", "3600")),
                   help="minimum time between two drips to the same address")
    p.add_argument("--ip-daily-cap", type=int, default=int(env("RHIZOME_FAUCET_IP_DAILY_CAP", "5")),
                   help="maximum successful drips per source IP per UTC day")
    p.add_argument("--daily-budget-pdn", default=env("RHIZOME_FAUCET_DAILY_BUDGET_PDN", "500"),
                   help="hard stop: total PDN this faucet will hand out per UTC day")
    p.add_argument("--pow-difficulty-bits", type=int,
                   default=int(env("RHIZOME_FAUCET_POW_DIFFICULTY_BITS", "18")),
                   help="hashcash-style difficulty, in bits of required leading zeros "
                        "(~2^bits average tries; cheap to verify, mildly costly to satisfy)")
    p.add_argument("--pow-ttl-seconds", type=int,
                   default=int(env("RHIZOME_FAUCET_POW_TTL_SECONDS", "120")),
                   help="how long an issued challenge stays solvable")
    p.add_argument("--wallet-timeout-seconds", type=float,
                   default=float(env("RHIZOME_FAUCET_WALLET_TIMEOUT_SECONDS", "30")),
                   help="timeout for one wallet-CLI subprocess call")
    p.add_argument("--node-probe-timeout-seconds", type=float,
                   default=float(env("RHIZOME_FAUCET_NODE_PROBE_TIMEOUT_SECONDS", "5")),
                   help="timeout for the /stats reachability probe done before every drip")
    p.add_argument("--host", default=env("RHIZOME_FAUCET_HOST", "127.0.0.1"),
                   help="bind address (127.0.0.1 by default: a service holding a signing key "
                        "should be exposed deliberately, e.g. behind a reverse proxy, not by "
                        "accident)")
    p.add_argument("--port", type=int, default=int(env("RHIZOME_FAUCET_PORT", "8085")))
    args = p.parse_args(argv)
    if not args.key_file:
        p.error("--key-file is required (or set RHIZOME_FAUCET_KEY_FILE)")
    return args


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    config = build_config(args)
    faucet = Faucet(config)
    httpd = http.server.ThreadingHTTPServer((config.host, config.port), make_handler(faucet))
    print(f"rhizome-faucet: listening on http://{config.host}:{config.port}  "
          f"node={config.node_url}  state={config.state_file}  "
          f"faucetAddress={faucet.faucet_address}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        # No special SIGTERM handling beyond this: every successful drip is already fsynced and
        # atomically renamed into place before the HTTP response is sent, so an abrupt SIGTERM
        # or SIGKILL loses at most the one in-flight request, never previously-committed state.
        httpd.server_close()


if __name__ == "__main__":
    main()
