#!/system/bin/sh
# Rückfall auf den alten Stand — läuft AUF DEM AUTO.
# Stoppt SurfMate samt Daemons und Tailscale, aktiviert die alte App wieder
# und startet den alten Verbindungswächter. Die alten Dateien wurden beim
# Umzug nie verändert, also ist nichts zurückzukopieren.

OLD=/data/local/tmp
NEW=/data/user_de/0/com.android.shell/surfmate
OLDPKG=com.overdrive.app
NEWPKG=at.greenchili.surfmate
LOG=/sdcard/surfmate-migration/migrate.log
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') ROLLBACK: $1" | tee -a "$LOG"; }

log "startet"
am force-stop "$NEWPKG"
pkill -f "$NEW/start_" 2>/dev/null
sleep 1
pkill -9 -f "$NEWPKG" 2>/dev/null
pkill -f "statedir $NEW/.tailscale" 2>/dev/null
pkill -f "$NEW/" 2>/dev/null
pkill -f sm-watchdog.sh 2>/dev/null
log "pm disable-user --user 0: $(pm disable-user --user 0 "$NEWPKG" 2>&1)"
sleep 2

log "pm enable: $(pm enable "$OLDPKG" 2>&1)"
monkey -p "$OLDPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
# alter Wächter startet auch den alten tailscaled, falls die App ihn nicht hochzieht
[ -f "$OLD/ts-watchdog.sh" ] && nohup sh "$OLD/ts-watchdog.sh" </dev/null >/dev/null 2>&1 &
sleep 20
log "alt: tailscaled=$(ps -A -o ARGS | grep -c "[s]tatedir $OLD/.tailscale") web=$(curl -s -m 4 http://127.0.0.1:8080/api/surveillance/status 2>/dev/null | grep -c '"success":true')"
