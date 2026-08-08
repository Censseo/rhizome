#!/usr/bin/env python3
"""Verdict de chaîne pour le testnet local.

Détermine si les tips observés (fichiers `stats-<i>.json` = /stats d'un nœud) sont sur
UNE seule chaîne — « même chaîne, hauteurs différentes » est un simple retard de gossip,
sain — ou sur des branches réellement distinctes (scission).

Pourquoi c'est nécessaire : sur un réseau à blocs rapides (devnet, plancher de
difficulté), un balayage séquentiel lit le nœud 15 ~2 s après le nœud 0. Chaque bloc
arrivé entre les deux lectures apparaît comme un « tip distinct » — les camps
fantômes suivent l'ordre de scrutation, pas une topologie. La question décisive est
l'appartenance à une chaîne : le tip d'un nœud en retard est un bloc ANCÊTRE du tip du
nœud en avance, donc présent dans sa chaîne.

Usage: chaincheck.py <stats_dir> <base_port>
Sortie : "uni" | "same-chain:<écart max>" | "split" | "unknown" (indisponible)
"""
import hashlib
import json
import os
import struct
import sys
import urllib.request

EMPTY = "0" * 64  # stateRoot absent = SHA256Hash.empty()


def decode_header(data: bytes) -> dict:
    """Décode un en-tête (HeaderCodec, big-endian) et renvoie les champs du préimage."""
    return {
        "id": int.from_bytes(data[0:4], "big"),
        "ts": int.from_bytes(data[4:12], "big"),
        "diff": int.from_bytes(data[12:16], "big"),
        "ntx": int.from_bytes(data[16:20], "big"),
        "prev": data[20:52].hex(),
        "merkle": data[52:84].hex(),
        "nonce": data[84:116].hex(),
        "state": data[116:148].hex(),
        "vote": int.from_bytes(data[148:152], "big"),
        "uncles": data[156:],  # (hash 32 ‖ miner 25 ‖ diff 4)*, non décodé ici
    }


def header_hash(h: dict) -> str:
    """SHA256 du préimage canonique (BlockHeader.hash), hex MAJUSCULE.

    Préimage : merkleRoot ‖ lastBlockHash ‖ id ‖ difficulty ‖ numTransactions ‖
    timestamp (big-endian), puis stateRoot (si non vide), vote (si non nul), et les
    oncles : hash ‖ miner de chacun, suivis de toutes les difficultés.
    """
    pre = bytearray()
    pre += bytes.fromhex(h["merkle"])
    pre += bytes.fromhex(h["prev"])
    pre += struct.pack(">i", h["id"])
    pre += struct.pack(">i", h["diff"])
    pre += struct.pack(">i", h["ntx"])
    pre += struct.pack(">q", h["ts"])
    if h["state"] != EMPTY:
        pre += bytes.fromhex(h["state"])
    if h["vote"] != 0:
        pre += struct.pack(">i", h["vote"])
    if h["uncles"]:
        diffs = b""
        off = 0
        while off + 61 <= len(h["uncles"]):
            # Fil wire : hash(32) ‖ difficulty(4) ‖ mineur(25) ; préimage : hash ‖ mineur,
            # puis toutes les difficultés (HeaderCodec.readFrom vs BlockHeader.hash).
            pre += h["uncles"][off:off + 32]       # hash de l'oncle
            diffs += h["uncles"][off + 32:off + 36]
            pre += h["uncles"][off + 36:off + 61]  # mineur (25 octets)
            off += 61
        pre += diffs
    return hashlib.sha256(bytes(pre)).hexdigest().upper()


def header_at(node_port: int, h: int):
    """En-tête à la hauteur h servie par le nœud, None si indisponible (reorg/503)."""
    url = f"http://127.0.0.1:{node_port}/headers?start={h}&end={h}"
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return decode_header(r.read())
    except Exception:
        return None


def main() -> int:
    stats_dir, base_port = sys.argv[1], int(sys.argv[2])
    nodes = {}
    for name in os.listdir(stats_dir):
        if not name.startswith("stats-") or not name.endswith(".json"):
            continue
        idx = int(name[len("stats-"):-len(".json")])
        with open(os.path.join(stats_dir, name)) as fh:
            data = json.load(fh)
        nodes[idx] = (int(data["height"]), str(data["tipHash"]))
    if not nodes:
        print("unknown")
        return 0
    tips = {tip for _, tip in nodes.values()}
    if len(tips) == 1:
        print("uni")
        return 0
    # Chaîne de référence : celle du nœud le plus haut — tout tip en retard doit y être
    # présent comme en-tête à sa hauteur. Le nœud peut reorg entre-temps : "unknown",
    # jamais un faux "split" (le cycle suivant re-vérifie).
    ref_idx, (ref_height, ref_tip) = max(nodes.items(), key=lambda kv: kv[1][0])
    mismatches = []
    for tip in sorted(tips - {ref_tip}):
        h = next(ht for (ht, t) in nodes.values() if t == tip)
        hdr = header_at(base_port + ref_idx, h)
        if hdr is None:
            print("unknown")
            return 0
        if header_hash(hdr) != tip:
            mismatches.append(tip)
    max_h = max(h for h, _ in nodes.values())
    min_h = min(h for h, _ in nodes.values())
    if mismatches:
        print(f"split")
    else:
        print(f"same-chain:{max_h - min_h}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
