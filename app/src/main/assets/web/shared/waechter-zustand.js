/**
 * Panoptes – EIN Wächter-Zustand mit Grund für alle Wächter-Seiten (0.1.38.11, W1).
 *
 *   PanoptesWaechterZustand.zeile(status, t)  → { grund, txt, dot, hinweis, kurz }
 *   PanoptesWaechterZustand.unbekannt(t)      → dasselbe, wenn kein Status kam (Dienst fehlt/antwortet nicht)
 *
 * status = /api/surveillance/status → status. Der Kern schickt status.grund (WaechterGrund); ältere Kerne ohne
 * „grund“: dieselbe Reihenfolge hier. „Eingeschaltet“ heißt nie „passt auf“: bei Zündung an ist der Wächter
 * planmäßig nicht scharf, das sagt die Zeile. t(key, deutsch, vars) = Übersetzer der Seite. ES5 (WebView 74).
 */
(function () {
    'use strict';

    var GRUENDE = { soc: 'guard.reason_soc', v12: 'guard.reason_v12', park: 'guard.reason_park', window: 'guard.reason_window', quietzone: 'guard.reason_quiet' };
    var GRUENDE_DE = { soc: 'Fahrakku zu leer', v12: 'Starterbatterie zu schwach', park: 'lange geparkt', window: 'Ruhezeit', quietzone: 'Ruhezone' };

    function grund(s) {
        if (s.grund) return s.grund;
        if (s.safeZoneSuppressed || s.inSafeZone) return 'ruhezone';
        if (s.enabled && s.guardStopActive) return 'gestoppt';
        if (s.zustand === 'startet') return 'startet';
        if (s.zustand === 'unterdrueckt') return 'pausiert';
        if (s.enabled && s.active) return 'passt_auf';
        // 0.1.43 (S2): bei Zündung an ist enabled immer false; eingeschaltet heißt die Einstellung (gewollt)
        if ((s.enabled || s.gewollt) && s.zuendungAn === true) return 'zuendung_an';
        return (s.enabled || s.gewollt) ? 'bereit' : 'aus';
    }

    function zeile(s, t) {
        var g = grund(s), z = { grund: g, txt: '', dot: '', hinweis: '', kurz: '' };
        if (g === 'ruhezone') {
            z.txt = t('guard.st_quiet', 'Ruhezone – Wächter pausiert'); z.dot = 'aq';
            z.kurz = t('shell.pill_guard_safe', 'Wächter · Zuhause');
        } else if (g === 'gestoppt') {
            z.txt = t('guard.stopped_because', 'Gestoppt: {r}', { r: t(GRUENDE[s.guardStopReason] || 'x', GRUENDE_DE[s.guardStopReason] || s.guardStopReason || '') });
            z.dot = 'warn'; z.kurz = t('shell.pill_guard_paused', 'Wächter pausiert');
        } else if (g === 'startet') {
            z.txt = t('guard.st_starting', 'Wächter startet gerade …'); z.dot = 'warn';
            z.kurz = t('shell.pill_guard_starting', 'Wächter startet');
        } else if (g === 'pausiert') {
            z.txt = t('guard.st_paused', 'Wächter pausiert'); z.dot = 'aq';
            z.kurz = t('shell.pill_guard_paused', 'Wächter pausiert');
        } else if (g === 'passt_auf') {
            z.txt = t('guard.st_active', 'Wächter passt auf'); z.dot = 'ok';
            z.kurz = t('shell.pill_guard_on', 'Wächter aktiv');
        } else if (g === 'zuendung_an') {
            z.txt = t('guard.st_zuendung', 'Bereit – Zündung an. Passt auf, sobald du das Auto abstellst.'); z.dot = 'aq';
            z.kurz = t('shell.pill_guard_ready', 'Wächter bereit');
        } else if (g === 'bereit') {
            z.txt = t('guard.st_ready', 'Bereit – startet beim Abstellen'); z.dot = 'aq';
            z.kurz = t('shell.pill_guard_ready', 'Wächter bereit');
        } else {
            z.txt = t('guard.st_off', 'Wächter ist aus');
            z.kurz = t('shell.pill_guard_off', 'Wächter aus');
        }
        if ((g === 'passt_auf' || g === 'bereit') && s.verriegelt === false) z.hinweis = t('guard.st_offen', 'Das Auto ist nicht verriegelt.');
        return z;
    }

    /** Kein Status: der Wächter-Dienst fehlt oder antwortet nicht – das sagen, statt „…“ oder „an“ zu zeigen. */
    function unbekannt(t) {
        return { grund: 'unbekannt', dot: 'warn',
            txt: t('guard.st_unbekannt', 'Zustand gerade nicht lesbar'),
            hinweis: t('guard.st_unbekannt_hinweis', 'Der Wächter-Dienst antwortet nicht. Panoptes startet ihn neu; in einer Minute noch einmal nachsehen.'),
            kurz: t('guard.st_unbekannt_kurz', 'Wächter: unbekannt') };
    }

    /** Status holen; ruft fertig(z) immer auf (auch bei Fehler mit unbekannt). */
    function laden(t, fertig) {
        fetch('/api/surveillance/status', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (d) {
            var s = d && d.status;
            fertig(s ? zeile(s, t) : unbekannt(t), s || null);
        }).catch(function () { fertig(unbekannt(t), null); });
    }

    window.PanoptesWaechterZustand = { grund: grund, zeile: zeile, unbekannt: unbekannt, laden: laden };
})();
