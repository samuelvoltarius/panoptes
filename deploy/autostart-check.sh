#!/bin/bash
# BYD-Autostart nach einer Panoptes-Installation prüfen bzw. freigeben.
# Läuft von außen auf einem Rechner mit adb-Zugang zum Auto, nur nach bestätigtem Update.
#
#   ./autostart-check.sh             nur prüfen (Standard, ändert nichts): ist die Freigabe
#                                    für DIESE Installation bestätigt? (App-Marke bzw. settings)
#   ./autostart-check.sh --freigeben Schalter im BYD-Dialog umlegen lassen, falls nötig,
#                                    und für diese Installation bestätigen
#
# Hintergrund: BYD setzt „Deaktiver Autostart“ bei jeder Installation zurück; ohne Freigabe
# startet Panoptes nach dem nächsten Hochfahren nicht (21.10.: „App läuft nicht“).
# --freigeben schickt AUTOSTART_ENABLE an die App; die legt den Schalter mit ihrem bestehenden
# AutoStartEnabler (Bedienungshilfe) um und liest ihn zur Bestätigung erneut. Dabei geht der
# BYD-Dialog kurz am Bildschirm auf – nur bei geparktem Auto und eingeschaltetem Bildschirm.
# Kein Fahrzeugbefehl (Regel A unberührt), kein Eingriff in Tailscale/adb (Regel B unberührt).
#
# Ergebnis (letzte Zeile): AUTOSTART=frei | AUTOSTART=gesperrt | AUTOSTART=unbekannt
# Bei frei wird `settings global panoptes_autostart_ok` auf den lastUpdateTime gesetzt;
# der Einrichtungsdialog der App erscheint dann nicht mehr wegen Autostart.
set -u
MODE="${1:-}"
PKG=at.greenchili.surfmate
TAG=PanoptesAutostart

DEV=""
for D in ${PANOPTES_ADB:-127.0.0.1:5555}; do   # Adresse(n) des Autos, z. B. PANOPTES_ADB="100.x.y.z:5555"
  adb connect "$D" >/dev/null 2>&1 || true
  if timeout 10 adb -s "$D" shell echo ok 2>/dev/null | grep -q ok; then DEV="$D"; break; fi
done
[ -n "$DEV" ] || { echo "Auto nicht erreichbar"; echo "AUTOSTART=unbekannt"; exit 1; }
A() { adb -s "$DEV" shell "$@" | tr -d '\r'; }
echo "Auto über $DEV"

INST=$(A "dumpsys package $PKG | grep -m1 lastUpdateTime=" | sed 's/.*lastUpdateTime=//')
[ -n "$INST" ] || { echo "Panoptes nicht installiert?"; echo "AUTOSTART=unbekannt"; exit 1; }
echo "Installation: $INST"
GLOBAL=$(A "settings get global panoptes_autostart_ok")
echo "Bestätigung bisher: $GLOBAL"

SINCE=$(A "date '+%m-%d %H:%M:%S.000'")
lines() { A "logcat -d -T '$SINCE' -s $TAG:I" | grep "$TAG" ; }

if [ "$MODE" = "--freigeben" ]; then
  A "am broadcast -a $PKG.action.AUTOSTART_ENABLE -n $PKG/.receiver.AutoStartCheckReceiver" >/dev/null
  for i in $(seq 1 30); do
    sleep 1
    lines | grep -q "nachher\|ergebnis=NO_SERVICE" && break
  done
else
  A "am broadcast -a $PKG.action.AUTOSTART_STATUS -n $PKG/.receiver.AutoStartCheckReceiver" >/dev/null
  for i in $(seq 1 10); do
    sleep 1
    lines | grep -q " status " && break
  done
fi

OUT=$(lines)
echo "$OUT" | sed 's/^/  /'
RES=$(echo "$OUT" | grep -o "ergebnis=[A-Z_]*" | tail -1 | cut -d= -f2)
LAST=$(echo "$OUT" | grep -E "status|nachher" | tail -1)

if [ "$MODE" = "--freigeben" ]; then
  case "$RES" in
    SUCCESS|ALREADY_OFF)
      A "settings put global panoptes_autostart_ok '$INST'"
      echo "Bestätigung gesetzt: $(A "settings get global panoptes_autostart_ok")"
      echo "AUTOSTART=frei"; exit 0;;
    "") echo "Keine Antwort der App (läuft sie? Bedienungshilfe an?)"; echo "AUTOSTART=unbekannt"; exit 1;;
    *)  echo "Freigabe nicht bestätigt ($RES) – bitte im BYD-Dialog von Hand prüfen."
        echo "AUTOSTART=gesperrt"; exit 2;;
  esac
fi

case "$LAST" in
  *autostartMissing=false*) echo "AUTOSTART=frei";;
  *autostartMissing=true*)  echo "AUTOSTART=gesperrt"; exit 2;;
  *) echo "Keine Antwort der App"; echo "AUTOSTART=unbekannt"; exit 1;;
esac
