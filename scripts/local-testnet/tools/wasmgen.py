#!/usr/bin/env python3
"""Génère les modules WASM adverses de la batterie contrats.

Les fixtures `.wasm` du dépôt sont des contrats LÉGITIMES (compilés depuis lib-vm/contracts) :
aucune ne porte les formes que la famille VM du catalogue décrit — flottants, import hors ABI,
compteurs déclarés démesurés, mémoire au-delà du cap. Elles vivent dans les tests JUnit, qui les
assemblent en mémoire ; pour les POSTER sur un nœud vivant il faut les mêmes octets sur disque.
Ce générateur les émet à la main, section par section, pour que chaque module isole EXACTEMENT
une violation (un module cassé au hasard serait refusé pour une raison qu'on ne contrôle pas).

    wasmgen.py <répertoire de sortie>
"""
import sys, pathlib

def leb(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def vec(items) -> bytes:
    return leb(len(items)) + b"".join(items)

def section(sid: int, payload: bytes) -> bytes:
    return bytes([sid]) + leb(len(payload)) + payload

def functype(params=b"", results=b"") -> bytes:
    return b"\x60" + leb(len(params)) + params + leb(len(results)) + results

def name(s: str) -> bytes:
    b = s.encode()
    return leb(len(b)) + b

def module(*sections) -> bytes:
    return b"\x00asm\x01\x00\x00\x00" + b"".join(sections)

# Sections courantes : un seul type () -> (), une seule fonction, exportée sous "call".
TYPE_VOID = section(1, vec([functype()]))
FUNC_ONE = section(3, vec([leb(0)]))
EXPORT_CALL = section(7, vec([name("call") + b"\x00" + leb(0)]))

def code(body: bytes, locals_groups=b"\x00") -> bytes:
    fn = locals_groups + body + b"\x0b"
    return section(10, vec([leb(len(fn)) + fn]))

EMPTY_BODY = code(b"")

MODULES = {}

# Contrôle : module valide minimal — prouve que la batterie sait déployer, donc qu'un refus
# ailleurs vient bien de la violation testée.
MODULES["noop"] = module(TYPE_VOID, FUNC_ONE, EXPORT_CALL, EMPTY_BODY)

# VM-01 — arithmétique flottante : résultat dépendant de la plate-forme, donc fork.
# f64.const 1.5 ; f64.const 2.5 ; f64.add ; drop
MODULES["float"] = module(
    TYPE_VOID, FUNC_ONE, EXPORT_CALL,
    code(b"\x44" + (1.5).hex().encode()[:0] + b"\x00\x00\x00\x00\x00\x00\xf8\x3f"
         + b"\x44" + b"\x00\x00\x00\x00\x00\x00\x04\x40"
         + b"\xa0" + b"\x1a"))

# VM-21 — pas d'export `call` : du code mort installé sur la chaîne.
MODULES["nocall"] = module(
    TYPE_VOID, FUNC_ONE, section(7, vec([name("run") + b"\x00" + leb(0)])), EMPTY_BODY)

# VM-08 — import hors de l'ABI hôte déclarée : évasion du bac à sable.
MODULES["badimport"] = module(
    TYPE_VOID,
    section(2, vec([name("env") + name("system") + b"\x00" + leb(0)])),
    section(3, vec([leb(0)])),
    section(7, vec([name("call") + b"\x00" + leb(1)])),
    EMPTY_BODY)

# VM-21 — mémoire IMPORTÉE sous un nom d'hôte : contourne la fabrique de mémoire bornée.
MODULES["memimport"] = module(
    TYPE_VOID,
    section(2, vec([name("env") + name("memory") + b"\x02" + b"\x00" + leb(1)])),
    FUNC_ONE, EXPORT_CALL, EMPTY_BODY)

# VM-18 — type de fonction à 1001 paramètres (cap = 1000) : allocation de frame non mesurée.
MODULES["manyparams"] = module(
    section(1, vec([functype(), functype(b"\x7f" * 1001)])),
    section(3, vec([leb(0)])), EXPORT_CALL, EMPTY_BODY)

# Borne inférieure du même cap : 1000 paramètres, doit être ACCEPTÉ (le cap borne, il n'interdit pas).
MODULES["paramscap"] = module(
    section(1, vec([functype(), functype(b"\x7f" * 1000)])),
    section(3, vec([leb(0)])), EXPORT_CALL, EMPTY_BODY)

# VM-06 — mémoire déclarée à 2048 pages (cap 1024) : 128 Mio par instance.
MODULES["hugemem"] = module(
    TYPE_VOID, FUNC_ONE, section(5, vec([b"\x00" + leb(2048)])), EXPORT_CALL, EMPTY_BODY)

# VM-19 — 4097 globals (cap 4096) : matérialisés avant toute mesure de gaz.
MODULES["manyglobals"] = module(
    TYPE_VOID, FUNC_ONE,
    section(6, vec([b"\x7f\x00\x41\x00\x0b"] * 4097)),
    EXPORT_CALL, EMPTY_BODY)

# VM-19 — 20 000 fonctions déclarées (cap 16 384).
MODULES["manyfuncs"] = module(
    TYPE_VOID, section(3, vec([leb(0)] * 20000)),
    section(7, vec([name("call") + b"\x00" + leb(0)])),
    section(10, vec([leb(len(b"\x00\x0b")) + b"\x00\x0b"] * 20000)))

# V1 — locals agrégés au-delà du cap (65 536) : la parade est PRÉ-parse (WasmPreScan).
MODULES["manylocals"] = module(
    TYPE_VOID, FUNC_ONE, EXPORT_CALL,
    code(b"", locals_groups=leb(4) + b"".join(leb(50000) + b"\x7f" for _ in range(4))))

# VM-03 — compteur déclaré démesuré face aux octets présents : bombe d'allocation au parse.
MODULES["hugecount"] = module(
    TYPE_VOID, section(3, leb(0xFFFFFFF) + b"\x00"), EXPORT_CALL, EMPTY_BODY)

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    for n, b in MODULES.items():
        (out / f"{n}.wasm").write_bytes(b)
        print(f"{n}.wasm {len(b)} octets")
