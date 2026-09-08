#!/usr/bin/env python3
"""Pair HOSTILE pour la batterie réseau : un vrai serveur HTTP, pas un double de test.

Il se présente comme un nœud devnet d'une hauteur et d'un travail absurdes (pour être choisi
comme source de synchronisation), puis répond des corps démesurés — 50 Mo sur les routes de
données, un million d'entrées sur /peers. C'est la forme NET-03 (OOM par corps géant sur un
round de découverte automatique) et NET-04 (occuper le fil de synchronisation).

    hostile_peer.py <port>
"""
import sys, http.server

PORT = int(sys.argv[1])
GIANT_PEERS = b'["http://127.0.0.1:1"' + b',"http://127.0.0.1:1"' * 1_000_000 + b"]"
GIANT_BODY = b"A" * (50 * 1024 * 1024)
FAKE_INFO = (b'{"chainId":3,"network":"rhizome-devnet","height":999999999,'
             b'"difficulty":6,"totalWork":"999999999999999","mempool":0}')


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, body):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass  # le nœud coupe dès que son cap de corps mord — c'est le comportement attendu

    def do_GET(self):
        if self.path.startswith("/peers"):
            self._send(GIANT_PEERS)
        elif self.path.startswith(("/info", "/stats", "/block_count", "/total_work")):
            self._send(FAKE_INFO)
        else:
            self._send(GIANT_BODY)

    def do_POST(self):
        self._send(b'{"status":"SUCCESS"}')

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
