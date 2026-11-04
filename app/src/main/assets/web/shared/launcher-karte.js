/**
 * Panoptes — Karte „Panoptes Launcher“ auf der System-Seite (0.1.37 Stufe 4, docs/LAUNCHER_SPEC.md §1).
 *
 *   GET  /api/appupdate/launcher   Stand: installiert, verfügbar (Version, Größe), installierbar, nichtWeil, mobilFrage
 *   POST /api/appupdate/launcher   {aktion: installieren|oeffnen|aktivieren|entfernen, mobil}
 *
 * Die Launcher-APK kommt aus dem Panoptes-Release (sha256 + gleiche Signatur prüft der Kern). „Öffnen“ startet sie
 * nur als App. Panoptes setzt sie nie als Standard-Startbildschirm; das macht nur der Nutzer selbst in Android.
 * Ohne WLAN fragt die Karte vorher mit Größe, die Einwilligung gilt nur für diesen einen Download. Fehlt die API
 * (älteres Panoptes) oder gibt es 403, bleibt die Karte unsichtbar. ES5, deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    function t(k, f, v) { return window.PanoptesShell ? PanoptesShell.tr(k, f, v) : f; }
    function $(id) { return document.getElementById(id); }
    function toast(m, k) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, k); }
    function lang() { try { return BYD.i18n.getLang(); } catch (e) { return 'de'; } }
    function mb(b) { return b ? (Math.round(b / 104857.6) / 10).toLocaleString(lang()) + ' MB' : ''; }

    // 0.1.38: In der Handy-App (pn_einbettung=handy) gibt der Kern mit Recht „launcher“ nur Stand und „Öffnen“ frei;
    // Installieren/Entfernen bleiben am Auto bzw. beim vollen Login (AppUpdateApiHandler.nurOeffnen).
    function fern() {
        try {
            return document.documentElement.getAttribute('data-einbettung') === 'handy'
                || window.sessionStorage.getItem('pn_einbettung') === 'handy';
        } catch (e) { return false; }
    }

    var L = {
        stand: null, timer: null,

        laden: function () {
            var self = this;
            return fetch('/api/appupdate/launcher', { cache: 'no-store' }).then(function (r) {
                if (r.status === 403) {
                    // ohne Recht „launcher“: den Satz des Kerns zeigen, sonst Karte weglassen
                    return r.json().then(function (j) { return j && j.recht === 'launcher' ? { ohneRecht: j.text || '' } : null; },
                        function () { return null; });
                }
                if (r.status === 404 || r.status === 503) return null;
                return r.ok ? r.json() : null;
            }).then(function (d) {
                $('launcherKarte').style.display = d ? '' : 'none';
                if (d && d.ohneRecht != null) self.ohneRecht(d.ohneRecht);
                else if (d) self.zeige(d);
                return d;
            }, function () { return null; });
        },

        ohneRecht: function (text) {
            $('lncState').textContent = text || t('system.lnc_ohne_recht', 'Für diese Funktion braucht dein Handy das Recht „Launcher“. Einschalten am Bildschirm im Auto unter Gekoppelte Geräte.');
            ['lncInstall', 'lncUpdate', 'lncOpen', 'lncRemove', 'lncEnable', 'lncDeaktiviertHinweis', 'lncOpenHint'].forEach(function (id) { if ($(id)) $(id).style.display = 'none'; });
        },

        grundText: function (g) {
            return g === 'faehrt' || g === 'nicht_in_p' ? t('system.lnc_why_driving', 'Geht nur im Stand (Gang P).')
                : g === 'wartet_wlan' ? t('system.lnc_why_wlan', 'Kein WLAN. Panoptes fragt vorher, ob über mobile Daten geladen werden darf.')
                : g === 'kein_launcher' ? t('system.lnc_why_none', 'Im Panoptes-Release ist gerade kein Launcher zu finden. Bitte später noch einmal.')
                : g === 'laeuft_schon' ? t('system.lnc_why_running', 'Wird gerade geladen oder installiert.')
                : g === 'pruefsumme' || g === 'signatur' ? t('system.lnc_err_check', 'Die Launcher-Datei hat die Prüfung nicht bestanden und wird nicht installiert.')
                : g === 'nicht_erreichbar' ? t('system.lnc_err_net', 'Update-Server nicht erreichbar.')
                : g === 'nicht_installiert' ? t('system.lnc_not_installed', 'Nicht installiert')
                : g && g.indexOf('start:') === 0 ? t('system.lnc_open_fail', 'Der Launcher ließ sich am Bildschirm im Auto nicht öffnen. Bitte dort einmal selbst antippen.')
                : t('system.req_fail', 'Das hat nicht geklappt.');
        },

        zeige: function (d) {
            this.stand = d;
            var v = d.verfuegbar, inst = d.installiert, laeuft = !!d.phase;
            $('lncNow').textContent = inst ? t('system.lnc_build', 'Build {n}', { n: inst }) : t('system.lnc_not_installed', 'Nicht installiert');
            $('lncNew').textContent = v ? v.version + (v.groesseBytes ? ' · ' + mb(v.groesseBytes) : '') : '–';
            $('lncBadge').textContent = laeuft ? t('system.upd_badge_running', 'Läuft')
                : inst && d.neuer ? t('system.upd_badge_new', 'Neue Version')
                : inst ? t('system.lnc_badge_installed', 'Installiert') : '--';
            var txt = '';
            if (d.phase === 'laedt') txt = t('system.lnc_ph_load', 'Lädt den Launcher … {p} %', { p: d.prozent || 0 });
            else if (d.phase === 'prueft') txt = t('system.upd_ph_check', 'Prüft Prüfsumme und Signatur …');
            else if (d.phase === 'installiert') txt = t('system.lnc_ph_install', 'Installiert …');
            else if (d.fehler) txt = this.grundText(d.fehler);
            else if (d.neuer && !d.installierbar && !d.mobilFrage) txt = this.grundText(d.nichtWeil);
            $('lncState').textContent = txt;
            var kannLaden = d.neuer && !laeuft && (d.installierbar || d.mobilFrage || !v);
            var amAuto = !fern();   // aus der Ferne nur „Öffnen“, nie Installieren/Entfernen
            $('lncInstall').style.display = amAuto && !inst && !laeuft ? '' : 'none';
            $('lncUpdate').style.display = amAuto && inst && d.neuer && kannLaden ? '' : 'none';
            // 0.1.38 (Befund am Auto): „Öffnen“ immer, wenn installiert. „phase“ ist nur der Installationsstand
            // (laedt/prueft/installiert, im Kern danach geleert), nicht „Launcher-Prozess läuft“ oder „ist vorne“.
            $('lncOpen').style.display = inst ? '' : 'none';
            if ($('lncOpenHint')) $('lncOpenHint').style.display = inst ? '' : 'none';
            $('lncRemove').style.display = amAuto && inst && !laeuft ? '' : 'none';
            // 0.1.38.7: Rückfall-Stufe 2 (Paket ausgeschaltet) ohne adb wieder einschalten
            if ($('lncEnable')) $('lncEnable').style.display = inst && d.deaktiviert ? '' : 'none';
            if ($('lncDeaktiviertHinweis')) $('lncDeaktiviertHinweis').style.display = inst && d.deaktiviert ? '' : 'none';
            var self = this;
            clearTimeout(this.timer);
            if (laeuft) this.timer = setTimeout(function () { self.laden(); }, 2000);
        },

        senden: function (aktion, mobil) {
            var self = this;
            return fetch('/api/appupdate/launcher', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ aktion: aktion, mobil: !!mobil })
            }).then(function (r) { return r.json(); }).then(function (d) {
                if (d && d.ok === false) toast(self.grundText(d.grund), 'error');
                if (d) self.zeige(d);
                return d;
            }, function () { toast(t('system.req_fail', 'Das hat nicht geklappt.'), 'error'); });
        },

        installieren: function () {
            var d = this.stand || {}, v = d.verfuegbar;
            if (d.mobilFrage) {
                var groesse = v && v.groesseBytes ? mb(v.groesseBytes) : t('system.lnc_size_unknown', 'unbekannt');
                if (!window.confirm(t('system.lnc_mobil_frage', 'Kein WLAN. Den Launcher jetzt über mobile Daten laden ({g})? Das gilt nur für diesen einen Download.', { g: groesse }))) return;
                return this.senden('installieren', true);
            }
            return this.senden('installieren', false);
        },

        entfernen: function () {
            if (!window.confirm(t('system.lnc_remove_confirm', 'Panoptes Launcher entfernen? Der BYD-Startbildschirm bleibt, wie er ist; dein Launcher-Layout geht dabei verloren.'))) return;
            var self = this;
            return this.senden('entfernen').then(function (d) {
                if (d && d.ok) toast(t('system.lnc_removed', 'Panoptes Launcher entfernt.'), 'success');
                return self.laden();
            });
        },

        init: function () {
            var self = this;
            if (!$('launcherKarte')) return;
            $('lncInstall').onclick = function () { self.installieren(); };
            $('lncUpdate').onclick = function () { self.installieren(); };
            $('lncOpen').onclick = function () {
                // 0.1.38: Rückmeldung nach dem Tipp, ok oder abgelehnt in Klartext (senden() meldet Fehler selbst)
                $('lncOpen').disabled = true;
                self.senden('oeffnen').then(function (d) {
                    $('lncOpen').disabled = false;
                    if (d && d.ok) toast(t('system.lnc_opened', 'Der Launcher ist jetzt am Bildschirm im Auto offen.'), 'success');
                });
            };
            $('lncRemove').onclick = function () { self.entfernen(); };
            if ($('lncEnable')) $('lncEnable').onclick = function () {
                $('lncEnable').disabled = true;
                self.senden('aktivieren').then(function () { $('lncEnable').disabled = false; });
            };
            this.laden();
        }
    };

    window.PanoptesLauncherKarte = L;
})();
