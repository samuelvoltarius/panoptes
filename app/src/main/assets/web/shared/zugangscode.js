/**
 * Panoptes 0.1.38.14 – Karte „Dein Zugangscode“ (system.html → Verbindungen). Ersetzt 1:1 das alte native Dashboard.
 * Nur am Bildschirm im Auto: Der Kern gibt den Code nur dem Display-WebView der App (GET /api/zugang/code, App-UID).
 * Verdeckt; „Zeigen“ nur nach Rückfrage; nach 60 s, beim Verlassen oder Verbergen der Seite wieder verdeckt. ES5.
 */
(function () {
    'use strict';
    var VERDECKT = '••••••••';
    var ZEIT_MS = 60000;
    var uhr = null;
    var versuche = 0;

    function t(k, f) {
        return (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(k, f) : f;
    }

    function istDisplay() {
        try { return !!(window.PanoptesShell && PanoptesShell.istDisplay && PanoptesShell.istDisplay()); } catch (e) { return false; }
    }

    function init() {
        var karte = document.getElementById('zugangscode');
        if (!karte) return;
        if (karte.getAttribute('data-bereit')) return;
        // Karte bleibt verborgen (Handy, PC, Browser); das Display wird manchmal erst spät erkannt
        if (!istDisplay()) { if (versuche++ < 3) setTimeout(init, 2000); return; }
        karte.setAttribute('data-bereit', '1');
        karte.style.display = '';
        var wert = document.getElementById('zugangscodeWert');
        var knopf = document.getElementById('zugangscodeKnopf');
        var hinweis = document.getElementById('zugangscodeHinweis');
        var sichtbar = false;

        function verdecken() {
            sichtbar = false;
            if (uhr) { clearTimeout(uhr); uhr = null; }
            wert.textContent = VERDECKT;
            knopf.textContent = t('system.zugang_zeigen', 'Zeigen');
        }

        function zeigen() {
            hinweis.textContent = '';
            fetch('/api/zugang/code', { cache: 'no-store', credentials: 'same-origin' })
                .then(function (r) {
                    if (r.status === 403) throw new Error('nur_display');
                    if (!r.ok) throw new Error('fehler');
                    return r.json();
                })
                .then(function (j) {
                    if (!j || !j.code) throw new Error('fehler');
                    wert.textContent = j.code;
                    sichtbar = true;
                    knopf.textContent = t('system.zugang_verdecken', 'Verdecken');
                    uhr = setTimeout(verdecken, ZEIT_MS);
                })
                .catch(function (e) {
                    verdecken();
                    hinweis.textContent = e && e.message === 'nur_display'
                        ? t('system.zugang_nur_display', 'Den Zugangscode siehst du nur am Bildschirm im Auto.')
                        : t('system.zugang_fehler', 'Der Zugangscode ist gerade nicht lesbar.');
                });
        }

        knopf.addEventListener('click', function () {
            if (sichtbar) { verdecken(); return; }
            var frage = t('system.zugang_frage', 'Zugangscode jetzt zeigen? Achte darauf, dass niemand mitliest.');
            if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
                BYD.utils.confirmDialog({
                    title: t('system.zugang_titel', 'Dein Zugangscode'),
                    body: frage,
                    confirmLabel: t('system.zugang_zeigen', 'Zeigen'),
                    cancelLabel: t('common.cancel', 'Abbrechen')
                }).then(function (ok) { if (ok) zeigen(); });
            } else if (window.confirm(frage)) {
                zeigen();
            }
        });
        document.addEventListener('visibilitychange', function () { if (document.hidden) verdecken(); });
        window.addEventListener('pagehide', verdecken);
        verdecken();
    }

    window.PanoptesZugangscode = { init: init };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
