"""Tuya-LAN-Wallbox-Simulator (Kern 6, WALLBOX_VERTRAG §4b) – nur zum Testen, nie gegen echte Geräte.

Spielt ein Tuya-Gerät mit Protokoll 3.3, 3.4 oder 3.5 auf 127.0.0.1. Rahmen und Verschlüsselung kommen aus
tinytuya (MIT, Jason Cox) als unabhängigem Orakel; die Geräteseite (Schlüsselaushandlung, Antworten) ist hier
nachgebaut. Gegenprobe: der tinytuya-Client muss mit diesem Simulator arbeiten (--selbsttest), dann wird die
Panoptes-Java-Umsetzung gegen denselben Simulator geprüft (WallboxTuyaSimTest).

Start:  python -I tuya_simulator.py --version 3.4 --port 16668 [--selbsttest]
Geräte-ID und local_key sind feste Testwerte (unten), DPs wie eine einfache Wallbox:
  1 = Laden ein/aus (bool), 4 = Ladestrom A (int), 3 = Status (enum), 9 = Leistung W (int)
Steuerbefehle schreiben die DPs; Status „charging“ + Leistung folgen dem Schalter.
"""
import argparse
import hashlib
import hmac
import json
import os
import socket
import struct
import threading
import time

import tinytuya

GERAET = "bf0123456789abcdefgh"
SCHLUESSEL = b"0123456789abcdef"
M = tinytuya.TuyaMessage


class Geraet:
    def __init__(self, version):
        self.version = version
        self.dps = {"1": False, "3": "connected", "4": 16, "9": 0}
        self.lock = threading.Lock()

    def setze(self, dps):
        with self.lock:
            for k, v in dps.items():
                if k in ("1", "4"):
                    self.dps[k] = v
            an = bool(self.dps["1"])
            self.dps["3"] = "charging" if an else "connected"
            self.dps["9"] = int(self.dps["4"]) * 230 if an else 0
            return dict(self.dps)


def lies_rahmen(sock):
    kopf = sock.recv(4, socket.MSG_WAITALL)
    if len(kopf) < 4:
        return None
    prefix = struct.unpack(">I", kopf)[0]
    if prefix == 0x55AA:
        rest = sock.recv(12, socket.MSG_WAITALL)
        laenge = struct.unpack(">I", rest[8:12])[0]
        return kopf + rest + sock.recv(laenge, socket.MSG_WAITALL)
    if prefix == 0x6699:
        rest = sock.recv(14, socket.MSG_WAITALL)
        laenge = struct.unpack(">I", rest[10:14])[0]
        return kopf + rest + sock.recv(laenge + 4, socket.MSG_WAITALL)
    raise ValueError("unbekannter Rahmen %08x" % prefix)


class Sitzung:
    def __init__(self, sock, geraet):
        self.sock, self.g, self.v = sock, geraet, geraet.version
        self.key = SCHLUESSEL
        self.seq = 100
        self.lokal = None
        self.fern = None

    def sende(self, cmd, klar):
        self.seq += 1
        if self.v == "3.5":
            msg = M(self.seq, cmd, 0, klar, 0, True, 0x6699, os.urandom(12))
            self.sock.sendall(tinytuya.pack_message(msg, hmac_key=self.key))
        elif self.v == "3.4":
            # 55AA: der Rückgabecode (4 Byte, 0) steht unverschlüsselt vor den Nutzdaten
            enc = tinytuya.AESCipher(self.key).encrypt(klar, False) if klar else b""
            self.sock.sendall(tinytuya.pack_message(M(self.seq, cmd, 0, bytes(4) + enc, 0, True, 0x55AA, None), hmac_key=self.key))
        else:
            self.sock.sendall(tinytuya.pack_message(M(self.seq, cmd, 0, bytes(4) + klar, 0, True, 0x55AA, None)))

    def entschluessle(self, msg):
        p = msg.payload
        if self.v == "3.3":
            if p[:3] == b"3.3":
                p = p[15:]
            return tinytuya.AESCipher(self.key).decrypt(p, False, decode_text=False) if p else b""
        if self.v == "3.4":
            p = tinytuya.AESCipher(self.key).decrypt(p, False, decode_text=False) if p else b""
        if p[:3] in (b"3.4", b"3.5"):
            p = p[15:]
        return p

    def antwort_json(self, obj, mit_kopf):
        roh = json.dumps(obj, separators=(",", ":")).encode()
        if self.v == "3.3":
            enc = tinytuya.AESCipher(self.key).encrypt(roh, False)
            return (b"3.3" + b"\0" * 12 + enc) if mit_kopf else enc
        return ((self.v.encode() + b"\0" * 12) if mit_kopf else b"") + roh

    def los(self):
        while True:
            daten = lies_rahmen(self.sock)
            if daten is None:
                return
            hk = None if self.v == "3.3" else self.key
            msg = tinytuya.unpack_message(daten, hmac_key=hk, no_retcode=True)
            if not msg.crc_good:
                print("SIM: Prüfsumme falsch, verwerfe")
                return
            cmd = msg.cmd
            if cmd == 3:   # Schlüsselaushandlung Schritt 1
                self.lokal = self.entschluessle(msg)[:16]
                self.fern = os.urandom(16)
                self.sende(4, self.fern + hmac.new(SCHLUESSEL, self.lokal, hashlib.sha256).digest())
                continue
            if cmd == 5:
                if self.entschluessle(msg)[:32] != hmac.new(SCHLUESSEL, self.fern, hashlib.sha256).digest():
                    print("SIM: Aushandlung HMAC falsch")
                    return
                x = bytes(a ^ b for a, b in zip(self.lokal, self.fern))
                if self.v == "3.4":
                    self.key = tinytuya.AESCipher(SCHLUESSEL).encrypt(x, False, pad=False)
                else:
                    self.key = tinytuya.AESCipher(SCHLUESSEL).encrypt(x, False, pad=False, iv=self.lokal[:12])[12:28]
                continue
            klar = self.entschluessle(msg)
            anfrage = json.loads(klar) if klar[:1] == b"{" else {}
            if cmd in (0x0A, 0x10):
                with self.g.lock:
                    dps = dict(self.g.dps)
                if self.v == "3.3":
                    self.sende(cmd, self.antwort_json({"devId": GERAET, "dps": dps, "t": int(time.time())}, False))
                else:
                    self.sende(cmd, self.antwort_json({"dps": dps}, False))
            elif cmd in (0x07, 0x0D):
                setz = anfrage.get("dps") or (anfrage.get("data") or {}).get("dps") or {}
                neu = self.g.setze(setz)
                self.sende(cmd, b"")
                aenderung = {k: neu[k] for k in neu}
                if self.v == "3.3":
                    self.sende(0x08, self.antwort_json({"devId": GERAET, "dps": aenderung, "t": int(time.time())}, True))
                else:
                    self.sende(0x08, self.antwort_json({"protocol": 4, "t": int(time.time()), "data": {"dps": aenderung}}, True))
            elif cmd == 0x09:
                self.sende(0x09, b"")
            else:
                print("SIM: unbekannter Befehl", cmd)


def server(version, port, bereit=None):
    g = Geraet(version)
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(5)
    if bereit:
        bereit.set()
    while True:
        c, _ = s.accept()
        threading.Thread(target=lambda: _sicher(c, g), daemon=True).start()


def _sicher(c, g):
    try:
        Sitzung(c, g).los()
    except Exception as e:  # Testwerkzeug: Fehler melden, weiterlaufen
        print("SIM Fehler:", repr(e))
    finally:
        c.close()


def selbsttest(version, port):
    d = tinytuya.Device(GERAET, "127.0.0.1", SCHLUESSEL.decode(), version=float(version), port=port)
    d.set_socketPersistent(False)
    s1 = d.status()
    r = d.set_value(4, 10)
    r2 = d.set_status(True, 1)
    s2 = d.status()
    ok = s1.get("dps", {}).get("1") is False and s2.get("dps", {}).get("1") is True and s2["dps"].get("4") == 10
    print(json.dumps({"version": version, "vorher": s1, "nachher": s2, "ok": ok}))
    return ok


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("--version", default="3.3", choices=["3.3", "3.4", "3.5"])
    a.add_argument("--port", type=int, default=16668)
    a.add_argument("--selbsttest", action="store_true")
    o = a.parse_args()
    if o.selbsttest:
        ev = threading.Event()
        threading.Thread(target=server, args=(o.version, o.port, ev), daemon=True).start()
        ev.wait()
        raise SystemExit(0 if selbsttest(o.version, o.port) else 1)
    print("Tuya-Simulator %s auf 127.0.0.1:%d" % (o.version, o.port), flush=True)
    server(o.version, o.port)
