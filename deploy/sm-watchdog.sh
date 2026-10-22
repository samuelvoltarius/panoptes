#!/system/bin/sh
# SurfMate-Verbindungswächter — läuft AUF DEM AUTO (nohup, PPID=1).
# Nachfolger von ts-watchdog.sh. Hält am Leben, was die Verbindung braucht,
# auch wenn von außen gerade niemand ans Auto kommt:
#   1. Herzschlag-Ping hält die Mobilfunk-Datensitzung offen
#   2. Shizuku
#   3. SurfMate funktional (Weboberfläche antwortet), sonst App neu starten –
#      aber NIE während eines Updates und nie in der Anlaufphase.
#   4. App-Neustart auf Anforderung des Gesundheitswächters (.gesundheit-app-neustart).
#
# Fix 21.10.2026: Der alte Wächter riss die App 3 min nach einem Update per
# force-stop ab und brach damit den langen Daemon-Start (adb 127.0.0.1:5555) ab
# – Ursache des Ausfalls 00:51–00:57. Jetzt:
#   - Pause, solange $NEW/.deploy.lock existiert oder ein Update-/Umzugsskript läuft
#   - Schonfrist: Paket jünger als 15 min oder App-Prozess jünger als 10 min
#   - App lebt, Daemons fehlen noch, aber Schonfrist → warten
#   - erst nach 5 Fehlversuchen (je 1 min), danach mindestens 15 min Abstand
#   - jeder Eingriff mit Grund im Protokoll

NEW=/data/user_de/0/com.android.shell/surfmate
DD=/data/local/tmp/surfmate-deploy
PKG=at.greenchili.surfmate
TS=$NEW/.tailscale
LOG=$NEW/sm-watchdog.log
N=0
FAIL=0
LAST_RESTART=0
MAX_FAIL=5
PKG_GRACE=900
APP_GRACE=600
RESTART_GAP=900

notiz() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG"; }
now() { date +%s; }

update_running() {
    [ -d "$NEW/.deploy.lock" ] && return 0
    ps -A -o ARGS 2>/dev/null | grep -q "^sh $DD/update-on-car\.sh" && return 0
    if ps -A -o ARGS 2>/dev/null | grep -q "^sh $DD/migrate-on-car\.sh"; then
        [ -e "$NEW/.migration-ok" ] || return 0   # bestätigter Umzug zählt nicht (Rest-Unter-Shell)
    fi
    return 1
}

# Alter des installierten APKs (s) – mtime von base.apk = Installationszeit
pkg_age() {
    apk=$(pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)
    [ -n "$apk" ] || { echo 999999; return; }
    echo $(( $(now) - $(stat -c %Y "$apk" 2>/dev/null || echo 0) ))
}

# Alter des App-Prozesses (s), 999999 wenn er nicht läuft
app_age() {
    p=$(pidof "$PKG" 2>/dev/null | awk '{print $1}')
    [ -n "$p" ] || { echo 999999; return; }
    echo $(( $(now) - $(stat -c %Y "/proc/$p" 2>/dev/null || echo 0) ))
}

notiz "Wächter gestartet (Fassung 21.10.: Update-Pause, Schonfrist, $MAX_FAIL Versuche, Abstand ${RESTART_GAP}s)"

while true; do
    # Nach einem Rückfall ist SurfMate deaktiviert: nichts anfassen und beenden.
    pm list packages -e 2>/dev/null | grep -q "package:$PKG\$" || { notiz "$PKG deaktiviert -> Wächter beendet"; exit 0; }

    if [ -f "$LOG" ]; then
        SZ=$(wc -c < "$LOG" 2>/dev/null || echo 0)
        [ "$SZ" -gt 2000000 ] && tail -c 400000 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi

    # 1) Herzschlag
    ping -c 1 -W 3 1.1.1.1 >/dev/null 2>&1 || ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1

    # 2) Shizuku
    if ! ps -A 2>/dev/null | grep -q '[s]hizuku_server'; then
        APKDIR=$(pm path moe.shizuku.privileged.api 2>/dev/null | sed -n 's#package:\(.*\)/base.apk#\1#p' | head -1)
        if [ -n "$APKDIR" ] && [ -x "$APKDIR/lib/arm64/libshizuku.so" ]; then
            "$APKDIR/lib/arm64/libshizuku.so" >/dev/null 2>&1 &
            notiz "shizuku neu gestartet"
        fi
    fi

    # 3) SurfMate funktional
    if update_running; then
        [ "$FAIL" -gt 0 ] && notiz "Update läuft – Prüfung pausiert (Zähler zurückgesetzt)"
        FAIL=0
    elif curl -s -m 5 http://127.0.0.1:8180/api/surveillance/status 2>/dev/null | grep -q '"success":true'; then
        [ "$FAIL" -ge 2 ] && notiz "SurfMate antwortet wieder"
        FAIL=0
    else
        PA=$(pkg_age); AA=$(app_age)
        if [ "$PA" -lt "$PKG_GRACE" ]; then
            notiz "SurfMate antwortet nicht – Schonfrist nach Installation (${PA}s < ${PKG_GRACE}s), warte"
            FAIL=0
        elif [ "$AA" -lt "$APP_GRACE" ]; then
            notiz "SurfMate antwortet nicht – App läuft erst ${AA}s, Daemons starten noch, warte"
            FAIL=0
        else
            FAIL=$((FAIL + 1))
            notiz "SurfMate antwortet nicht ($FAIL/$MAX_FAIL; App-Alter ${AA}s, Paket-Alter ${PA}s)"
            if [ "$FAIL" -ge "$MAX_FAIL" ]; then
                SINCE=$(( $(now) - LAST_RESTART ))
                if [ "$SINCE" -lt "$RESTART_GAP" ]; then
                    notiz "Neustart fällig, aber letzter Neustart erst vor ${SINCE}s – warte (Abstand ${RESTART_GAP}s)"
                else
                    notiz "EINGRIFF: SurfMate neu gestartet (Grund: Web ${MAX_FAIL} min ohne Antwort, App-Alter ${AA}s)"
                    am force-stop "$PKG" >/dev/null 2>&1; sleep 3
                    monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
                    LAST_RESTART=$(now)
                    FAIL=0
                fi
            fi
        fi
    fi

    # 4) Gesundheitswächter der App fordert die letzte Stufe an (App neu starten). Er hat neu scharf
    #    geschaltet und den Daemon neu gestartet, ohne Erfolg. Ausführen tut nur dieser Wächter:
    #    nie während eines Updates, mit demselben Mindestabstand wie oben, Grund ins Protokoll.
    REQ=$NEW/.gesundheit-app-neustart
    if [ -f "$REQ" ] && ! update_running; then
        SINCE=$(( $(now) - LAST_RESTART ))
        if [ "$SINCE" -lt "$RESTART_GAP" ]; then
            notiz "Gesundheit fordert App-Neustart, letzter Neustart erst vor ${SINCE}s – warte"
        else
            notiz "EINGRIFF: SurfMate neu gestartet auf Anforderung des Gesundheitswächters ($(head -c 200 "$REQ" 2>/dev/null))"
            rm -f "$REQ"
            am force-stop "$PKG" >/dev/null 2>&1; sleep 3
            monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
            LAST_RESTART=$(now)
            FAIL=0
        fi
    fi

    # Notnagel: tailscaled ganz weg und SurfMate holt ihn nicht zurück (nie während eines Updates)
    if ! update_running && [ "$FAIL" -ge 2 ] && ! ps -A -o ARGS 2>/dev/null | grep -q "[s]tatedir $TS"; then
        if [ -x "$TS/tailscaled" ]; then
            "$TS/tailscaled" --tun userspace-networking --statedir "$TS" \
                --socket 127.0.0.1:8632 --socks5-server 127.0.0.1:8639 >> "$TS/tailscale.log" 2>&1 &
            notiz "EINGRIFF: tailscaled per Notnagel gestartet"
        fi
    fi

    [ "$ONCE" = "1" ] && { notiz "Testlauf (ONCE=1) beendet"; exit 0; }
    N=$((N + 1))
    [ $((N % 60)) -eq 0 ] && notiz "Wächter läuft"
    sleep 60
done
