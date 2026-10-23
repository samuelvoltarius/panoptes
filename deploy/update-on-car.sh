#!/system/bin/sh
# SurfMate-Update — läuft AUF DEM AUTO (als shell, per nohup), mit Totmannschalter
# und Sperre gegen gleichzeitige Updates.
#
#   sh update-on-car.sh <neues.apk auf dem Auto> [sitzung] [commit]
#
# Ablauf:
#   0. Sperre $NEW/.deploy.lock (mkdir = atomar). Läuft schon ein Update oder ein
#      Umzug, bricht das Skript ab. Eine Sperre älter als 2 h wird nur mit
#      Protokolleintrag übernommen, nie stillschweigend.
#   1. laufendes APK sichern nach /data/local/tmp/surfmate-deploy/ (pm kann nicht
#      von /sdcard installieren: system_server darf sdcardfs nicht lesen)
#   2. neues APK installieren, App + Daemons neu starten (tailscaled bleibt)
#   3. Totmannschalter: liegt nach 30 min keine Datei $NEW/.update-ok vor, wird
#      das gesicherte APK zurückgespielt. Danach (oder nach Bestätigung) fällt
#      die Sperre.
#
# Bestätigen (nach Prüfung von außen):  touch /data/user_de/0/com.android.shell/surfmate/.update-ok
#
# In-App-Updater (Panoptes, appupdate/AppUpdateDienst): startet dieses Skript selbst mit
#   LOCK_KEY=<schlüssel> AUTO_OK=1 EXPECT_VC=<versionCode> [DD=<arbeitsordner>]
# AUTO_OK=1 bestätigt selbst, wenn 3 Prüfungen hintereinander (alle 2 min) gesund sind:
# installierter versionCode = EXPECT_VC, mindestens so viele Daemons wie vorher (≥ 1) und
# Web 200, falls es vorher 200 war. Sonst greift der Totmannschalter wie immer. Rückfall bzw.
# gescheiterte Installation hinterlassen $NEW/.appupdate-rueckfall („<versionCode> <grund>“),
# damit der Updater dieselbe Version nicht automatisch erneut versucht.
# Ohne AUTO_OK (Deploy-Sitzung) ist der Ablauf unverändert.
#
# Fallen (20./21.10.2026): pm-Ausgaben nie in eine /sdcard-Datei umleiten
# ("Failed transaction"); pm installiert nichts von /sdcard.

PKG=at.greenchili.surfmate
NEW=/data/user_de/0/com.android.shell/surfmate
DD=${DD:-/data/local/tmp/surfmate-deploy}
APK="$1"
WHO="${2:-unbekannt}"
COMMIT="${3:-?}"
STAMP=$(date +%Y%m%d-%H%M%S)
LOG=/sdcard/surfmate-migration/update.log
LOCK="$NEW/.deploy.lock"
DEADLINE_MIN=${DEADLINE_MIN:-30}
RUECKFALL_MARKE="$NEW/.appupdate-rueckfall"
STALE_SEC=7200

mkdir -p /sdcard/surfmate-migration "$DD"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" | tee -a "$LOG"; }

release_lock() { rm -f "$LOCK/info" 2>/dev/null; rmdir "$LOCK" 2>/dev/null; case "$APK" in "$DD"/neu-*.apk) rm -f "$APK";; esac; }

# --- 0. Sperre ------------------------------------------------------------------
# Laufende Updates: nur echte Skriptprozesse zählen (nicht die adb-"sh -c"-Hülle,
# nicht sich selbst). Ein Umzug zählt nur, solange er nicht bestätigt ist: nach der
# Bestätigung bleibt eine Unter-Shell mit gleicher Befehlszeile stehen, die nur auf
# den sm-watchdog wartet (am 21.10. festgestellt).
# Eigene Kinder (die Unter-Shells dieser Prüfung tragen dieselbe Befehlszeile)
# und der Elternprozess werden übersprungen.
ME=$$
others=$(ps -A -o PID,PPID,ARGS | while read pid ppid a1 a2 rest; do
    [ "$pid" = "$ME" ] || [ "$ppid" = "$ME" ] || [ "$pid" = "$PPID" ] && continue
    case "$a1 $a2" in
        "sh $DD/update-on-car.sh") echo "$pid" ;;
        "sh $DD/migrate-on-car.sh") [ -e "$NEW/.migration-ok" ] || echo "$pid" ;;
    esac
done)
if [ -n "$others" ]; then
    log "ABGEWIESEN ($WHO, $COMMIT): es läuft schon ein Update/Umzug (PID $(echo $others))"
    # eine von run-update.sh für diesen Lauf belegte Sperre wieder freigeben
    if [ -n "$LOCK_KEY" ] && grep -q "schluessel=$LOCK_KEY" "$LOCK/info" 2>/dev/null; then rm -f "$LOCK/info"; rmdir "$LOCK"; rm -f "$APK"; fi
    exit 2
fi
if [ "$DRY_RUN" = "1" ]; then echo "TROCKENLAUF: keine anderen Updates gefunden"; exit 0; fi
if [ -n "$LOCK_KEY" ] && grep -q "schluessel=$LOCK_KEY" "$LOCK/info" 2>/dev/null; then
    : # Sperre wurde von run-update.sh für genau diesen Lauf belegt
elif ! mkdir "$LOCK" 2>/dev/null; then
    born=$(stat -c %Y "$LOCK" 2>/dev/null || echo 0)
    age=$(( $(date +%s) - born ))
    if [ "$age" -lt "$STALE_SEC" ]; then
        log "ABGEWIESEN ($WHO, $COMMIT): Sperre aktiv seit ${age}s — $(tr '\n' ' ' < "$LOCK/info" 2>/dev/null)"
        exit 2
    fi
    log "Sperre veraltet (${age}s, $(tr '\n' ' ' < "$LOCK/info" 2>/dev/null)) — übernommen von $WHO"
    release_lock
    mkdir "$LOCK" 2>/dev/null || { log "ABGEWIESEN ($WHO): Sperre konnte nicht übernommen werden"; exit 2; }
fi
grep -q "schluessel=" "$LOCK/info" 2>/dev/null || printf 'sitzung=%s\ncommit=%s\napk=%s\nstart=%s\n' "$WHO" "$COMMIT" "$APK" "$STAMP" > "$LOCK/info"

[ -f "$APK" ] || { log "ABBRUCH: APK $APK fehlt"; release_lock; exit 1; }
OLDAPK=$(pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)
[ -n "$OLDAPK" ] && [ -f "$OLDAPK" ] || { log "ABBRUCH: $PKG nicht installiert"; release_lock; exit 1; }

# Daemons und ihre Startschleifen gezielt über den Paketpfad beenden (nie
# "pkill -f <paketname>": die eigene Befehlszeile enthält den Namen).
stop_daemons() {
    pkill -f "$NEW/start_" 2>/dev/null
    sleep 1
    for p in $(ls /proc | grep -E '^[0-9]+$'); do
        tr '\0' '\n' < /proc/$p/environ 2>/dev/null | grep -q "^CLASSPATH=.*/data/app/$PKG-" && kill -9 "$p" 2>/dev/null
    done
    am force-stop "$PKG"
}
# 0.1.16 (Deploy, Koordination 23.10.): App MINIMIERT starten – derselbe Start, den BootReceiver bei
# MY_PACKAGE_REPLACED vorsieht (post_update + minimize_on_start). MainActivity fährt die Daemons hoch und geht
# sofort in den Hintergrund; am Display bleibt, was vorher vorn war (kein Vordergrund-Start, keine hängende
# Fehlerseite, wenn 8180 noch nicht läuft). Danach bis START_WARTE_S (180 s; DaemonStartup wartet selbst 45 s) auf web=200 warten; kommt es nicht,
# Rückfall wie bis 0.1.15: Vordergrund-Start per monkey, damit das Update nie ohne laufende Daemons endet.
START_WARTE_S=${START_WARTE_S:-180}
start_app() {
    am start -n "$PKG/.ui.MainActivity" --ez post_update true --ez minimize_on_start true >/dev/null 2>&1
    w=0
    while [ "$w" -lt "$START_WARTE_S" ]; do
        if [ "$(web_code)" = "200" ]; then log "App minimiert gestartet, web=200 nach ${w} s"; return 0; fi
        sleep 5; w=$((w + 5))
    done
    log "App minimiert gestartet, aber web nach ${START_WARTE_S} s nicht 200 — Rückfall: Vordergrund-Start (monkey)"
    monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
}
daemon_zahl() { for n in byd_cam_daemon sentry_daemon acc_sentry_daemon; do pidof "$n" >/dev/null && echo x; done | wc -l; }
web_code() { curl -s -m 4 -o /dev/null -w '%{http_code}' http://127.0.0.1:8180/ 2>/dev/null; }
installierter_vc() { dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
rueckfall_merken() { [ -n "$EXPECT_VC" ] && echo "$EXPECT_VC $1" > "$RUECKFALL_MARKE"; }

log "=== Update $STAMP startet ($WHO, $COMMIT, $APK) ==="
BKAPK="$DD/vorher-$STAMP.apk"
cp "$OLDAPK" "$BKAPK" || { log "ABBRUCH: Sicherung fehlgeschlagen"; release_lock; exit 1; }
ls -t "$DD"/vorher-*.apk 2>/dev/null | tail -n +3 | while read f; do rm -f "$f"; done   # nur die 2 neuesten behalten
log "Sicherung: $(ls -la "$BKAPK" | awk '{print $5}') Bytes in $BKAPK"

rm -f "$NEW/.update-ok"
DM_VORHER=$(daemon_zahl); WEB_VORHER=$(web_code)
[ "$AUTO_OK" = "1" ] && log "Selbstbestätigung an (vorher: daemons=$DM_VORHER/3 web=$WEB_VORHER, erwartet versionCode $EXPECT_VC)"
stop_daemons
OUT=$(pm install -r -g "$APK" 2>&1)
log "pm install: $OUT"
case "$OUT" in
    *Success*) ;;
    *) log "Installation fehlgeschlagen — altes APK läuft weiter"; rueckfall_merken pm; start_app; release_lock; exit 1 ;;
esac
start_app
GESUND=0
log "neue Version gestartet — warte auf Bestätigung (max. $DEADLINE_MIN min)"

i=0
while [ $i -lt $((DEADLINE_MIN * 2)) ]; do
    if [ -e "$NEW/.update-ok" ]; then
        log "BESTÄTIGT — Update abgeschlossen"
        release_lock
        exit 0
    fi
    if [ $((i % 4)) -eq 0 ]; then
        TS=$(ps -A -o ARGS | grep -c "[s]tatedir $NEW/.tailscale")
        WEB=$(web_code)
        DM=$(daemon_zahl)
        log "Stand nach $((i / 2)) min: tailscaled=$TS web=$WEB daemons=$DM/3"
        if [ "$AUTO_OK" = "1" ]; then
            VC=$(installierter_vc)
            if [ "$VC" = "$EXPECT_VC" ] && [ "$DM" -ge 1 ] && [ "$DM" -ge "$DM_VORHER" ] \
               && { [ "$WEB_VORHER" != "200" ] || [ "$WEB" = "200" ]; }; then
                GESUND=$((GESUND + 1))
            else
                GESUND=0
            fi
            if [ "$GESUND" -ge 3 ]; then
                log "SELBST BESTÄTIGT (versionCode $VC, daemons=$DM/3, web=$WEB, 3 Prüfungen gesund)"
                rm -f "$RUECKFALL_MARKE"
                touch "$NEW/.update-ok"
                continue
            fi
        fi
    fi
    i=$((i + 1))
    sleep 30
done

log "KEINE Bestätigung nach $DEADLINE_MIN min — spiele vorherige Version zurück"
stop_daemons
log "pm install (zurück): $(pm install -r -d -g "$BKAPK" 2>&1)"
rueckfall_merken totmann
start_app
release_lock
log "Rückfall abgeschlossen"
