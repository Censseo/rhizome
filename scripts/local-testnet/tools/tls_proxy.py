#!/usr/bin/env python3
"""Relais TLS minimal pour la batterie suite-tls.sh.

Aucun de nginx/caddy/socat/stunnel n'est installé sur cette machine (vérifié par
suite-tls.sh via `command -v` avant d'invoquer ce script) : ce module tient lieu du relais
« réel » que la batterie a besoin de mettre devant UN nœud Rhizome en clair. Il ne fait
qu'UNE chose, celle qui compte pour prouver TLS + jeton porteur de bout en bout : négocier
TLS sur le port d'écoute puis relayer les octets déchiffrés, TELS QUELS, vers l'amont HTTP
en clair (un nœud lié à la boucle locale) — et la réponse dans l'autre sens.

Relais au niveau OCTET, pas un analyseur HTTP : robuste à n'importe quel verbe/encodage que
les appels curl de la batterie utilisent, au prix de ne pas être un relais HTTP « intelligent »
(pas d'injection d'en-tête, pas de réécriture de X-Forwarded-For). C'EST le point : un vrai
relais nginx/Caddy réécrit ou complète X-Forwarded-For dans sa configuration ; celui-ci ne le
fait PAS, si bien que l'en-tête envoyé par le client curl atteint le nœud tel quel — exactement
ce qui rend RHIZOME_TRUST_XFF dangereux tant qu'on ne connaît pas le comportement XFF exact de
son propre relais en production (voir les cas XFF-* de suite-tls.sh, qui exploitent précisément
cette transparence).

Écart avec un relais de production, à noter pour une campagne multi-VM : celui-ci ne journalise
rien, ne fait pas d'équilibrage de charge, ne réutilise pas les connexions amont au-delà d'une
paire de sockets par connexion cliente, et son certificat est auto-signé jetable (généré par
suite-tls.sh dans un sous-répertoire de $BASE_DIR, jamais commité). Un vrai déploiement a besoin
d'un certificat émis par une AC publique (ou interne, distribuée aux clients) et d'une politique
de renouvellement — hors de portée d'une seule machine de développement.

    tls_proxy.py <port_écoute> <port_amont> <cert.pem> <clé.pem>
"""
import socket
import ssl
import sys
import threading

LISTEN_PORT = int(sys.argv[1])
UPSTREAM_PORT = int(sys.argv[2])
CERT = sys.argv[3]
KEY = sys.argv[4]
UPSTREAM_HOST = "127.0.0.1"
# Le nœud amont est TOUJOURS en boucle locale (voir tls_proxy.py appelé par suite-tls.sh) :
# c'est le relais, pas le nœud, qui est censé être le point d'entrée réseau-exposé.


def relay(src, dst):
    """Copie src -> dst jusqu'à EOF ou erreur, puis referme dst en écriture (moitié de fermeture
    propre : l'autre thread du couple finit de vider ce qu'il a encore à transmettre)."""
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass  # connexion coupée d'un côté ou de l'autre — rien à journaliser, ce n'est pas hostile
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(tls_conn):
    try:
        upstream = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=30)
    except OSError:
        tls_conn.close()
        return
    t1 = threading.Thread(target=relay, args=(tls_conn, upstream), daemon=True)
    t2 = threading.Thread(target=relay, args=(upstream, tls_conn), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    tls_conn.close()
    upstream.close()


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(certfile=CERT, keyfile=KEY)

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", LISTEN_PORT))
listener.listen(64)
print(f"tls_proxy: 127.0.0.1:{LISTEN_PORT} -> {UPSTREAM_HOST}:{UPSTREAM_PORT}", flush=True)

while True:
    raw_conn, _addr = listener.accept()
    try:
        tls_conn = ctx.wrap_socket(raw_conn, server_side=True)
    except ssl.SSLError:
        # Un client qui échoue la poignée de main (cas TLS-02 : aucune AC de confiance) atterrit
        # ici — c'est le comportement attendu, pas une erreur du relais.
        raw_conn.close()
        continue
    threading.Thread(target=handle, args=(tls_conn,), daemon=True).start()
