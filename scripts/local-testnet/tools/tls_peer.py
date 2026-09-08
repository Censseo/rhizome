#!/usr/bin/env python3
"""Pair TLS hostile, sur le modèle de hostile_peer.py : un vrai serveur HTTP, mais celui-ci en
TLS, avec un certificat auto-signé DÉLIBÉRÉMENT distinct de celui du relais (tls_proxy.py) — un
nœud honnête n'a aucune raison de lui faire confiance, quel que soit le canal par lequel il en
entend parler.

Sert des réponses plausibles de nœud devnet (même forme que hostile_peer.py, sans les corps
démesurés — ce n'est pas son terrain ici) et journalise CHAQUE requête reçue, avec la valeur
EXACTE de son en-tête Authorization ("<absent>" si aucun), dans <journal> : c'est la preuve
qu'on relit après coup pour établir un négatif — « ce pair n'a JAMAIS reçu le jeton porteur »
(NET-08 du catalogue : lib-net/src/test/java/rhizome/net/PeerTokenPolicyTest.java —
`gossipLearnedPeerNeverReceivesTheToken`). suite-tls.sh lance deux instances de ce script avec
le MÊME certificat sur deux ports : l'une en pair CONFIGURÉ (RHIZOME_PEERS), l'autre en pair
APPRIS PAR GOSSIP (/add_peer) — seule la première doit jamais voir le jeton.

    tls_peer.py <port> <cert.pem> <clé.pem> <journal>
"""
import http.server
import ssl
import sys
import time

PORT = int(sys.argv[1])
CERT = sys.argv[2]
KEY = sys.argv[3]
LOG = sys.argv[4]

FAKE_INFO = (b'{"chainId":3,"network":"rhizome-devnet","height":1,'
             b'"difficulty":6,"totalWork":"1","mempool":0,"prunedBelow":0}')
FAKE_PEERS = b'{"peers":[]}'


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _log(self):
        auth = self.headers.get("Authorization", "<absent>")
        # Une ligne par requête, horodatée : suite-tls.sh compte à la fois les lignes totales
        # (pour prouver que le nœud a bien essayé de le joindre — sans quoi un journal vide ne
        # prouverait rien) et les lignes portant "Authorization=Bearer" (qui doivent rester à
        # zéro pour le pair appris par gossip).
        with open(LOG, "a") as f:
            f.write(f"{time.time():.3f}\t{self.command}\t{self.path}\tAuthorization={auth}\n")

    def _send(self, body):
        self._log()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_GET(self):
        if self.path.startswith("/peers"):
            self._send(FAKE_PEERS)
        else:
            self._send(FAKE_INFO)

    def do_POST(self):
        self._send(b'{"status":"OK"}')

    def log_message(self, *args):
        pass  # le journal applicatif ci-dessus suffit ; pas de bruit sur stderr


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(certfile=CERT, keyfile=KEY)

httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
httpd.serve_forever()
