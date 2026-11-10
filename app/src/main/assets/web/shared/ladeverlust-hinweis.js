/*
 * 03.11.2026 (Koordination): Einmal nach dem Update erklären, dass die Kosten jetzt den Ladeverlust enthalten.
 * „Verstanden“ schaltet den Hinweis ab (Einstellung laden.verlustHinweis = false, auch in den Einstellungen
 * wieder einschaltbar). „Später“ fragt erst in der nächsten Sitzung wieder. Nichts verlässt das Auto.
 */
(function () {
    'use strict';
    var FELD = 'laden.verlustHinweis';
    var MERKER = 'pn.verlustHinweis.ok';

    function t(k, d) {
        try {
            var v = window.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t(k) : null;
            return v && v !== k ? v : d;
        } catch (e) { return d; }
    }
    function merken() { try { window.sessionStorage.setItem(MERKER, '1'); } catch (e) {} }
    try { if (window.sessionStorage.getItem(MERKER)) return; } catch (e) {}

    function abschalten() {
        return fetch('/api/einstellungen', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (!d) return;
                var werte = {}; werte[FELD] = false;
                return fetch('/api/einstellungen', {
                    method: 'PUT', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ etag: d.etag, werte: werte })
                });
            }).catch(function () {});
    }

    function zeigen(versuch) {
        // Erst der Haftungshinweis und „Neu in“ (0.1.38.14), dann dieser – nie zwei Fenster übereinander
        if (document.getElementById('pnHinweise') || document.getElementById('pnNeu') || document.querySelector('.modal-backdrop')) {
            if (versuch < 20) setTimeout(function () { zeigen(versuch + 1); }, 3000);
            return;
        }
        if (!(window.BYD && BYD.utils && typeof BYD.utils.confirmDialog === 'function')) return;
        merken();
        BYD.utils.confirmDialog({
            title: t('ladeverlust.hinweis_titel', 'Ladekosten'),
            body: t('ladeverlust.hinweis_text',
                'Kosten enthalten jetzt den Ladeverlust (~8–12 %). Genauer wird es mit „Laut Säule“.'),
            confirmLabel: t('ladeverlust.hinweis_ok', 'Verstanden'),
            cancelLabel: t('ladeverlust.hinweis_spaeter', 'Später')
        }).then(function (ok) { if (ok) abschalten(); });
    }

    function pruefen() {
        fetch('/api/einstellungen', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                var w = d && d.werte && d.werte[FELD];
                if (!w || w.wert === false) { merken(); return; }
                setTimeout(function () { zeigen(0); }, 1500);
            }).catch(function () {});
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', pruefen);
    else pruefen();
})();
