#!/system/bin/sh
# Panoptes Notzugang (0.1.14, NOTZUGANG_KONZEPT.md): eigener, minimaler tailscaled als Shell-Prozess für die
# Fernwartung über adb. Liegt außerhalb von Panoptes (Update/Deinstallation fassen ihn nicht an), eigener Knoten
# bzw. statedir, eigener Socket 8633, zufälliger UDP-Port. Kein Web, kein Serve.
#   notzugang.sh start   Aufpasser starten (läuft er schon: nichts)
#   notzugang.sh stop    Aufpasser und eigenen tailscaled beenden, Merker „aus“ setzen
#   notzugang.sh status  läuft? (Aufpasser, tailscaled)
#   notzugang.sh lauf    (intern) Aufpasser-Schleife
D=/data/local/tmp/panoptes-notzugang
B="$D/bin"
S="$D/state"
L="$D/log"
P="$D/aufpasser.pid"

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
    echo "aufpasser=$a tailscaled=$t aus=$([ -f "$D/aus" ] && echo ja || echo nein)"
    ;;
  lauf)
    echo $$ > "$P"
    w=30
    while [ ! -f "$D/aus" ]; do
      if [ -z "$(eigene)" ]; then
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
    echo "notzugang.sh start|stop|status" >&2
    exit 2
    ;;
esac
