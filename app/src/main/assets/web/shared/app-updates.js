/**
 * Panoptes — Karte „App-Updates“ auf der System-Seite (NEUBAU2_API_VORSCHLAG.md §1).
 *
 *   GET  /api/appupdate/status          Stand: Kanal, Modus, installiert, verfügbar, letzte Suche, installierbar
 *   POST /api/appupdate/suchen          sofort suchen (Server begrenzt auf 1× je 60 s)
 *   POST /api/appupdate/installieren    {versionCode} – nur mit vollem Login, Server prüft „steht in P“ (0.1.38.5: auch bei Zündung an)
 *   POST /api/appupdate/hinweis         {aktion:"zuruecknehmen"} – vorgemerktes Update (Karte am Display) doch nicht
 *
 * Kanal und Modus kommen aus dem Einstellungs-Schema (Gruppe „update“) und werden mit einstellungen.js
 * gezeichnet – so stehen sie auch im EV-Cockpit. Fehlt die API (älteres Panoptes) oder gibt es 403
 * (gekoppeltes Gerät ohne Recht), bleibt die Karte unsichtbar.
 *
 * Regeln: Beim Öffnen wird nichts gesucht und nichts installiert. Installieren nur nach Rückfrage.
 * Der Knopf erscheint nur, wenn der Server „installierbar“ meldet (Auto steht sicher in P oder ist aus).
 * Es geht nie ein Befehl ans Auto, nur an die App selbst. ES5, deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    function t(k, f, v) { return window.PanoptesShell ? PanoptesShell.tr(k, f, v) : f; }
    function $(id) { return document.getElementById(id); }
    function toast(m, k) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, k); }
    function lang() { try { return BYD.i18n.getLang(); } catch (e) { return 'de'; } }

    function datum(ms) {
        if (!ms) return t('system.upd_never', 'noch nie');
        var d = Date.now() - ms;
        if (d >= 0 && d < 60000) return t('system.upd_just_now', 'gerade eben');
        if (d >= 0 && d < 3600000) return t('system.upd_min_ago', 'vor {n} min', { n: Math.round(d / 60000) });
        if (d >= 0 && d < 86400000) return t('system.upd_h_ago', 'vor {n} h', { n: Math.round(d / 3600000) });
        try { return new Date(ms).toLocaleString(lang(), { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }); }
        catch (e) { return new Date(ms).toLocaleString(); }
    }

    function mb(b) { return b ? (Math.round(b / 104857.6) / 10).toLocaleString(lang()) + ' MB' : ''; }

    var U = {
        stand: null, timer: null, warteNeustart: false, alteVersion: null,

        laden: function () {
            var self = this;
            return fetch('/api/appupdate/status?lang=' + encodeURIComponent(lang()), { cache: 'no-store' }).then(function (r) {
                if (r.status === 404 || r.status === 403) return null;
                return r.ok ? r.json() : null;
            }).then(function (d) {
                $('updates').style.display = d ? '' : 'none';
                if (d) self.zeige(d);
                return d;
            }, function () { return null; });
        },

        grundText: function (g) {
            return g === 'faehrt' ? t('system.upd_why_driving', 'Während der Fahrt geht kein Update. Bitte anhalten und in P stellen.')
                : g === 'zuendung_an' ? t('system.upd_why_ignition', 'Installieren geht erst, wenn das Auto sicher in P steht.')
                : g === 'nicht_in_p' ? t('system.upd_why_not_p', 'Installieren geht nur, wenn das Auto in P steht.')
                : g === 'waechter_aktiv' ? t('system.upd_why_guard', 'Der Wächter nimmt gerade etwas auf. Bitte warten, bis er fertig ist.')
                : g === 'laeuft_schon' ? t('system.upd_why_running', 'Ein Update läuft gerade.')
                : g === 'anderer_stand' ? t('system.upd_why_changed', 'Inzwischen gibt es einen anderen Stand. Die Karte zeigt jetzt den neuen.')
                : g === 'ratenlimit' ? t('system.upd_why_rate', 'Gerade erst gesucht. Bitte in einer Minute noch einmal.')
                : g === 'kein_update' ? t('system.upd_why_none', 'Es gibt nichts Neueres.')
                : g === 'zustand_unbekannt' ? t('system.upd_why_unknown', 'Panoptes weiß gerade nicht sicher, ob das Auto steht. Installieren geht, sobald das wieder feststeht.')
                : g === 'zu_alt' ? t('system.upd_why_too_old', 'Dieses Update braucht eine neuere Ausgangsversion. Bitte zuerst die Version davor installieren.')
                : g === 'wartet_wlan' ? t('system.upd_why_wlan', 'Lädt erst im WLAN, weil Updates über Mobilfunk ausgeschaltet sind.')
                : g === 'pruefen_aus' ? t('system.upd_why_check_off', 'Die automatische Suche nach Updates ist ausgeschaltet.')
                : t('system.req_fail', 'Das hat nicht geklappt.');
        },

        fehlerText: function (f) {
            return f === 'nicht_erreichbar' ? t('system.upd_err_net', 'Update-Server nicht erreichbar. Panoptes versucht es später wieder.')
                : f === 'kein_zugang' ? t('system.upd_err_access', 'Kein Zugriff auf die Update-Quelle.')
                : f === 'pruefsumme' || f === 'signatur' ? t('system.upd_err_check', 'Die neue Version hat die Prüfung nicht bestanden und wird nicht installiert.')
                : t('system.upd_err', 'Die Suche hat nicht geklappt.');
        },

        // 0.1.9 (Kern 4): phase „pausiert“ = Download unterbrochen, geht beim nächsten Parken weiter.
        // Das ist kein laufendes Update: kein schnelles Nachfragen, Suchen bleibt möglich.
        laeuft: function (d) { return !!(d && d.phase && d.phase !== 'pausiert'); },

        zeige: function (d) {
            this.stand = d;
            var v = d.verfuegbar, badge = $('updBadge'), laeuft = this.laeuft(d);
            var aktuell = d.aktuell || {}, aktuellKanal = aktuell.kanal;
            var aktuellText = aktuell.label || aktuell.version || '--';
            if (aktuellKanal === 'test') aktuellText += ' · ' + t('system.upd_test_tag', 'Test');
            else if (aktuellKanal === 'stabil') aktuellText += ' · ' + t('system.upd_stable_tag', 'Stabil');
            if (aktuell.versionCode) aktuellText += ' · ' + t('system.upd_build_tag', 'Build {n}', { n: aktuell.versionCode });
            $('updNow').textContent = aktuellText;
            $('updNew').textContent = v ? v.version + (v.kanal === 'test' ? ' · ' + t('system.upd_test_tag', 'Test') : '') : '–';
            $('updLast').textContent = datum(d.letztePruefung)
                + (d.netz === 'wlan' ? ' · ' + t('system.upd_net_wlan', 'WLAN')
                    : d.netz === 'mobil' ? ' · ' + t('system.upd_net_mobile', 'Mobilfunk') : '');
            badge.textContent = d.phase === 'pausiert' ? t('system.upd_badge_paused', 'Pausiert')
                : laeuft ? t('system.upd_badge_running', 'Läuft')
                : d.ergebnis === 'fehler' ? t('system.upd_badge_error', 'Fehler')
                : v ? t('system.upd_badge_new', 'Neue Version')
                : d.ergebnis === 'nie' ? '--' : t('system.upd_badge_current', 'Aktuell');

            var notes = $('updNotes');
            // 0.1.38.5: Art (Korrektur / neue Funktion) gleich im Titel, Text kommt fertig übersetzt vom Server
            if (v && ((v.notizen && v.notizen.length) || v.artText)) {
                notes.style.display = '';
                $('updNotesTitle').textContent = t('system.upd_notes', 'Neu in {v}', { v: v.version })
                    + (v.artText ? ' · ' + v.artText : '') + (v.groesseBytes ? ' · ' + mb(v.groesseBytes) : '');
                var ul = $('updNotesList');
                ul.innerHTML = '';
                (v.notizen || []).forEach(function (z) { var li = document.createElement('li'); li.textContent = z; ul.appendChild(li); });
            } else notes.style.display = 'none';

            // 0.1.38.9 (04.11.): der Launcher steht dort, wo man Panoptes updatet
            var L = d.launcher || {}, li = L.installiert, lv = L.verfuegbar;
            $('updLauncherStat').style.display = li || lv ? '' : 'none';
            $('updLauncher').textContent = li
                ? (li.version || t('system.upd_build_tag', 'Build {n}', { n: li.versionCode })) + (L.neuer && lv ? ' → ' + lv.version : '')
                : t('system.upd_launcher_none', 'nicht installiert');
            $('updLauncherHinweis').style.display = !li && lv ? '' : 'none';
            $('updLauncherHinweisText').textContent = t('system.upd_launcher_hinweis',
                'Der Panoptes Launcher ist nicht installiert. Das ist kein Fehler. Einrichten kannst du ihn unter Apps & Display → Launcher einrichten.');
            if (L.mitPanoptes && lv && notes.style.display !== 'none') {
                var lm = document.createElement('li');
                lm.textContent = t('system.upd_launcher_mit', 'Der Panoptes Launcher {v} kommt mit.', { v: lv.version });
                $('updNotesList').appendChild(lm);
            }

            var st = $('updState'), txt = '';
            if (d.phase) {
                txt = d.phase === 'pausiert' ? t('system.upd_ph_paused', 'Download unterbrochen. Er geht beim nächsten Parken weiter.')
                    : d.phase === 'laedt' ? t('system.upd_ph_load', 'Lädt die neue Version …')
                    : d.phase === 'prueft' ? t('system.upd_ph_check', 'Prüft Prüfsumme und Signatur …')
                    : d.phase === 'installiert' ? t('system.upd_ph_install', 'Installiert. Panoptes startet gleich neu …')
                    : d.phase === 'beobachtet' ? t('system.upd_ph_watch', 'Neue Version läuft. Panoptes beobachtet sie noch ein paar Minuten.')
                    : d.phase === 'rueckfall' ? t('system.upd_ph_back', 'Die neue Version lief nicht sauber. Panoptes ist zur vorigen zurückgekehrt.') : '';
            } else if (d.ergebnis === 'fehler') {
                txt = this.fehlerText(d.fehler);
            } else if (v && v.zurueckgerollt) {
                txt = t('system.upd_rolled_back', 'Diese Version lief hier schon einmal nicht sauber; Panoptes ist damals zurückgekehrt. Installieren nur, wenn du es noch einmal versuchen willst.');
            } else if (v && d.vorgemerkt) {
                txt = t('system.upd_vorgemerkt', 'Vorgemerkt: Panoptes installiert das Update etwa 10 Minuten, nachdem du das Auto abgestellt hast. Der Wächter ist dabei ein paar Minuten aus.');
            } else if (v && d.automatischGeplant) {
                txt = t('system.upd_auto_planned', 'Kommt von selbst, sobald das Auto mindestens 10 Minuten abgestellt ist.');
            } else if (v && d.mobilFrage) {
                txt = t('system.upd_mobil_hinweis', 'Kein WLAN. Du kannst über mobile Daten laden, Panoptes fragt vorher.');
            } else if (v && !d.installierbar) {
                txt = this.grundText(d.nichtWeil);
            } else if (v) {
                txt = d.modus === 'automatisch' ? t('system.upd_auto_soon', 'Wird gleich automatisch installiert. Du kannst auch selbst auf Installieren tippen.')
                    : t('system.upd_ready', 'Bereit. Panoptes startet dabei kurz neu, das Display kann flackern. Nur im Stand.');
            } else if (d.pruefen === false) {
                txt = t('system.upd_why_check_off', 'Die automatische Suche nach Updates ist ausgeschaltet.');
            } else if (d.kanal === 'test') {
                txt = t('system.upd_test_note', 'Kanal Test: Neue Versionen kommen früher, sind aber weniger geprüft.');
            } else if (aktuellKanal === 'test' && d.kanal === 'stabil') {
                txt = t('system.upd_stable_wait', 'Kanal Stabil ist gewählt. Diese Testversion bleibt installiert, bis eine stabile Version mit einer höheren Buildnummer verfügbar ist.');
            }
            if (!txt && !v && li && L.neuer && lv) txt = t('system.upd_launcher_neu', 'Neuer Launcher {v}. Panoptes selbst ist aktuell.', { v: lv.version });
            st.textContent = txt;

            var prog = $('updProgress');
            var gross = v && Number(v.groesseBytes), geladen = Number(d.geladenBytes);
            var prozent = d.phase === 'pausiert' && gross > 0 && geladen > 0
                ? Math.min(100, Math.round(geladen / gross * 100)) : (Number(d.prozent) || 0);
            prog.style.display = d.phase === 'laedt' || (d.phase === 'pausiert' && prozent > 0) ? '' : 'none';
            $('updBar').style.width = prozent + '%';

            var inst = $('updInstall');
            inst.style.display = v && (d.installierbar || d.mobilFrage) && !laeuft && d.phase !== 'pausiert' ? '' : 'none';
            $('updSearch').disabled = laeuft;
            $('updCancel').style.display = v && d.vorgemerkt && !laeuft ? '' : 'none';
            // nur der Launcher ist neuer: eigener Knopf; kommt er mit Panoptes mit, reicht „Installieren“
            $('updLauncherInstall').style.display = li && L.neuer && !L.mitPanoptes && !laeuft ? '' : 'none';

            var self = this;
            clearTimeout(this.timer);
            if (laeuft) this.timer = setTimeout(function () { self.nachInstallation(); }, 1500);
        },

        // Während der Installation stirbt der Daemon. Dann /status abfragen, bis eine neue Version antwortet.
        nachInstallation: function () {
            var self = this;
            fetch('/api/appupdate/status?lang=' + encodeURIComponent(lang()), { cache: 'no-store' })
                .then(function (r) { if (!r.ok) throw new Error('weg'); return r.json(); })
                .then(function (d) { self.zeige(d); }, function () { self.warteAufNeustart(); });
        },

        warteAufNeustart: function () {
            var self = this;
            $('updState').textContent = t('system.upd_restarting', 'Panoptes startet neu … Diese Seite lädt von selbst, sobald es wieder da ist.');
            $('updProgress').style.display = 'none';
            fetch('/status', { cache: 'no-store' }).then(function (r) { if (!r.ok) throw new Error('weg'); return r.json(); })
                .then(function (s) {
                    if (s && s.appVersion && s.appVersion !== self.alteVersion) { location.reload(); return; }
                    self.timer = setTimeout(function () { self.nachInstallation(); }, 3000);
                }, function () { self.timer = setTimeout(function () { self.warteAufNeustart(); }, 3000); });
        },

        suchen: function () {
            var self = this, b = $('updSearch');
            b.disabled = true;
            fetch('/api/appupdate/suchen?lang=' + encodeURIComponent(lang()), { method: 'POST' })
                .then(function (r) { return r.json().then(function (d) { return d || {}; }, function () { return {}; }); })
                .then(function (d) {
                    if (d.ok === false) { toast(self.grundText(d.grund), 'warning'); return self.laden(); }
                    self.zeige(d);
                    toast(d.ergebnis === 'fehler' ? self.fehlerText(d.fehler)
                        : d.verfuegbar ? t('system.upd_found', 'Neue Version {v} gefunden.', { v: d.verfuegbar.version })
                        : t('system.upd_none', 'Panoptes ist aktuell.'), d.ergebnis === 'fehler' ? 'error' : 'success');
                }, function () { toast(t('system.upd_err', 'Die Suche hat nicht geklappt.'), 'error'); })
                .then(function () { b.disabled = self.laeuft(self.stand); });
        },

        installieren: function () {
            var self = this, d = this.stand, v = d && d.verfuegbar;
            if (!v) return;
            // 0.1.26.1: gleiche Rückfrage wie in der Handy-App (HANDY_API_VERTRAG §8)
            var mitL = d.launcher && d.launcher.mitPanoptes && d.launcher.verfuegbar
                ? ' ' + t('system.upd_launcher_mit', 'Der Panoptes Launcher {v} kommt mit.', { v: d.launcher.verfuegbar.version }) : '';
            if (!window.confirm(t('system.upd_confirm_fern', 'Panoptes wird jetzt auf {v} aktualisiert. Wächter und Verbindung sind ca. 3–8 Minuten weg. Läuft die neue Version nicht sauber, kehrt Panoptes von selbst zur jetzigen zurück.', { v: v.version }) + mitL)) return;
            // 0.1.32.1: ohne WLAN wie die Karte: Größe nennen, gilt nur für diesen Download
            var mobil = !!d.mobilFrage;
            if (mobil && !window.confirm(t('system.upd_mobil_frage', 'Das Auto hat gerade kein WLAN. Das Update braucht etwa {g} mobile Daten. Nur für diese Version laden?',
                { g: v.groesseBytes > 0 ? Math.round(v.groesseBytes / 1000000) + ' MB' : '70 MB' }))) return;
            var b = $('updInstall');
            b.disabled = true;
            this.alteVersion = d.aktuell && d.aktuell.version;
            fetch('/status', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
                .then(function (s) { if (s && s.appVersion) self.alteVersion = s.appVersion; }, function () {})
                .then(function () {
                    return fetch('/api/appupdate/installieren', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ versionCode: v.versionCode, version: v.version, bestaetigt: true, mobil: mobil }) });
                })
                .then(function (r) {
                    if (r.status === 403) return { ok: false, grund: 'kein_recht' };
                    return r.json().then(function (x) { return x || {}; }, function () { return {}; });
                })
                .then(function (r) {
                    if (r.ok) { toast(t('system.upd_started', 'Update gestartet.'), 'success'); self.nachInstallation(); return; }
                    toast(r.grund === 'kein_recht' ? t('system.upd_no_right', 'Nur mit Anmeldung an Panoptes möglich, nicht von einem gekoppelten Gerät.')
                        : r.text || self.grundText(r.grund), 'warning');
                    self.laden();
                }, function () { toast(t('system.req_fail', 'Das hat nicht geklappt.'), 'error'); })
                .then(function () { b.disabled = false; });
        },

        /** 0.1.38.9: nur den Launcher aktualisieren (Panoptes ist aktuell); ohne WLAN erst fragen. */
        launcherInstallieren: function (mobil) {
            var self = this, b = $('updLauncherInstall');
            b.disabled = true;
            fetch('/api/appupdate/launcher', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ aktion: 'installieren', mobil: !!mobil }) })
                .then(function (r) { return r.json().then(function (x) { return x || {}; }, function () { return {}; }); })
                .then(function (r) {
                    if (r.ok) { toast(t('system.upd_started', 'Update gestartet.'), 'success'); return; }
                    if (r.grund === 'wartet_wlan' && !mobil) {
                        var g = r.verfuegbar && r.verfuegbar.groesseBytes;
                        if (window.confirm(t('system.upd_mobil_frage', 'Das Auto hat gerade kein WLAN. Das Update braucht etwa {g} mobile Daten. Nur für diese Version laden?',
                            { g: g > 0 ? Math.round(g / 1000000) + ' MB' : '10 MB' }))) { self.launcherInstallieren(true); }
                        return;
                    }
                    toast(self.grundText(r.grund), 'warning');
                }, function () { toast(t('system.req_fail', 'Das hat nicht geklappt.'), 'error'); })
                .then(function () { b.disabled = false; setTimeout(function () { self.laden(); }, 5000); });
        },

        zuruecknehmen: function () {
            var self = this;
            fetch('/api/appupdate/hinweis', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ aktion: 'zuruecknehmen' }) })
                .then(function (r) { if (!r.ok) throw new Error('nein'); return self.laden(); })
                .then(null, function () { toast(t('system.req_fail', 'Das hat nicht geklappt.'), 'error'); });
        },

        einstellungen: function () {
            var self = this, box = $('updSettings');
            if (!window.PanoptesEinstellungen) return;
            PanoptesEinstellungen.laden().then(function (store) {
                if (!store || !store.feld('update.kanal') && !store.feld('update.modus')) {
                    box.innerHTML = '';
                    return;
                }
                store.zeichne(box, { gruppe: 'update', nachSpeichern: function () { self.laden(); } });
            }, function () {});
        },

        init: function () {
            var self = this;
            $('updSearch').onclick = function () { self.suchen(); };
            $('updInstall').onclick = function () { self.installieren(); };
            $('updCancel').onclick = function () { self.zuruecknehmen(); };
            $('updLauncherInstall').onclick = function () { self.launcherInstallieren(false); };
            this.laden().then(function (d) {
                if (!d) return;
                self.einstellungen();
                if (location.hash === '#updates') $('updates').scrollIntoView();
            });
            // Ruhig nachsehen, solange die Seite offen ist (nur lesen, kein Suchen).
            setInterval(function () { if (!document.hidden && !self.laeuft(self.stand)) self.laden(); }, 60000);
        }
    };

    window.PanoptesUpdates = U;
})();
