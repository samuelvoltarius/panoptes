#!/system/bin/sh
# Panoptes Notzugang (0.1.14, NOTZUGANG_KONZEPT.md): eigener, minimaler tailscaled als Shell-Prozess für die
# Fernwartung über adb. Liegt außerhalb von Panoptes (Update/Deinstallation fassen ihn nicht an), eigener Knoten
# bzw. statedir, eigener Socket 8633, zufälliger UDP-Port. Kein Web. Einzige Weiterleitung (0.1.15): Tailnet-Port
# 5555 -> adb auf 127.0.0.1:5555 per `serve --tcp`. Der gebündelte tailscaled leitet bewusst nicht von selbst auf
# Loopback weiter (tools/patches/tailscale-no-local-forward.patch), ohne serve gibt es also kein adb.
#   notzugang.sh start   Aufpasser starten (läuft er schon: nichts)
#   notzugang.sh serve   adb-Weiterleitung prüfen und bei Bedarf setzen (idempotent): serve=ok|fehlt
#   notzugang.sh stop    Aufpasser und eigenen tailscaled beenden, Merker „aus“ setzen
#   notzugang.sh status  läuft? (Aufpasser, tailscaled, adb-Weiterleitung)
#   notzugang.sh neu     nach Skript-Erneuerung nur den Aufpasser neu starten (tailscaled läuft weiter)
#   notzugang.sh lauf    (intern) Aufpasser-Schleife
D=/data/local/tmp/panoptes-notzugang
B="$D/bin"
S="$D/state"
L="$D/log"
P="$D/aufpasser.pid"
T="$B/tailscale --socket 127.0.0.1:8633"

eigene() {
  for p in $(pidof tailscaled 2>/dev/null); do
    case "$p" in ''|*[!0-9]*) continue;; esac
    tr '\000' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -qF -- "--statedir $S" && printf '%s ' "$p"
  done
}

aufpasser_laeuft() {
  [ -f "$P" ] || return 1
  a="$(cat "$P" 2>/dev/null)"
  case "$a" in ''|*[!0-9]*) return 1;; esac
  tr '\000' ' ' < "/proc/$a/cmdline" 2>/dev/null | grep -qF "notzugang.sh lauf"
}

# adb-Weiterleitung gesetzt? serve status --json: {"TCP":{"5555":{"TCPForward":"127.0.0.1:5555"}}}
serve_da() {
  $T serve status --json 2>/dev/null | tr -d ' \n\t\r' | grep -qF '"5555":{"TCPForward":"127.0.0.1:5555"}'
}

# Idempotent: nur setzen, wenn sie fehlt, danach zurücklesen. Die serve-Konfiguration liegt im statedir und
# überlebt Neustarts. Nur dieser eine Eintrag wird gesetzt, nie alles zurückgesetzt (fremde Einträge bleiben).
serve_sichern() {
  serve_da && return 0
  $T serve --bg --tcp 5555 tcp://127.0.0.1:5555 > /dev/null 2>&1
  serve_da
}

log_kuerzen() {
  g=$(wc -c < "$L" 2>/dev/null || echo 0)
  [ "$g" -gt 1048576 ] 2>/dev/null && : > "$L"
}

case "$1" in
  start)
    rm -f "$D/aus"
    aufpasser_laeuft && { echo laeuft; exit 0; }
    nohup sh "$D/notzugang.sh" lauf > /dev/null 2>&1 &
    echo gestartet
    ;;
  stop)
    : > "$D/aus"
    if aufpasser_laeuft; then kill "$(cat "$P")" 2>/dev/null; fi
    for p in $(eigene); do kill "$p" 2>/dev/null; done
    rm -f "$P"
    echo gestoppt
    ;;
  status)
    a=nein; aufpasser_laeuft && a=ja
    t=nein; [ -n "$(eigene)" ] && t=ja
    s=nein; [ "$t" = ja ] && serve_da && s=ja
    echo "aufpasser=$a tailscaled=$t aus=$([ -f "$D/aus" ] && echo ja || echo nein) adb_serve=$s"
    ;;
  serve)
    if serve_sichern; then echo serve=ok; else echo serve=fehlt; exit 1; fi
    ;;
  neu)
    # 0.1.16: nach dem Erneuern dieses Skripts (App-Update) nur den Aufpasser mit dem neuen Code neu starten.
    # Der eigene tailscaled läuft weiter (keine Unterbrechung, kein neues up, kein Key).
    [ -f "$D/aus" ] && { echo aus; exit 0; }
    if aufpasser_laeuft; then kill "$(cat "$P")" 2>/dev/null; sleep 1; fi
    rm -f "$P"
    nohup sh "$D/notzugang.sh" lauf > /dev/null 2>&1 &
    echo neu
    ;;
  lauf)
    echo $$ > "$P"
    w=30
    sv=""   # PIDs des tailscaled, für den die adb-Weiterleitung schon steht (je tailscaled-Start einmal prüfen)
    sw=30; sn=0   # Fehlversuche (z. B. vor der Anmeldung) mit Backoff 30 s ... 10 min wiederholen
    while [ ! -f "$D/aus" ]; do
      e="$(eigene)"
      j=$(date +%s)
      if [ -n "$e" ] && [ "$e" != "$sv" ] && [ "$j" -ge "$sn" ] 2>/dev/null; then
        if serve_sichern; then
          sv="$e"; sw=30; sn=0
          echo "$(date '+%F %T') adb-Weiterleitung 5555 ok" >> "$L"
        else
          sn=$((j + sw)); sw=$((sw * 2)); [ "$sw" -gt 600 ] && sw=600
        fi
      fi
      if [ -z "$e" ]; then
        log_kuerzen
        echo "$(date '+%F %T') tailscaled starten" >> "$L"
        nohup "$B/tailscaled" --tun userspace-networking --statedir "$S" --socket 127.0.0.1:8633 --port 0 >> "$L" 2>&1 &
        sleep 5
        if [ -z "$(eigene)" ]; then
          w=$((w * 2)); [ "$w" -gt 600 ] && w=600
        else
          w=30
        fi
      fi
      sleep "$w"
    done
    rm -f "$P"
    ;;
  *)
    echo "notzugang.sh start|stop|status|serve|neu" >&2
    exit 2
    ;;
esac
