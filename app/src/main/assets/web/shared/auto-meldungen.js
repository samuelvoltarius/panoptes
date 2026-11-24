/**
 * Panoptes – Meldungen des Autos auf der Startseite (0.1.43.20).
 *
 *   PanoptesAutoMeldungen.init(kachelEl, wertEl, unterEl)
 *
 * Liest GET /api/auto/meldungen (Kern: BYDAutoInstrumentDevice.getMalfunctionInfo, NUR lesen, kein UDS). Gibt das Auto
 * keine Meldungen heraus (verfuegbar=false) oder fehlt die API, bleibt die Kachel versteckt. Alle 5 min neu. ES5.
 */
(function () {
    'use strict';

    var DE = { keine: 'Keine', eine: '1 Meldung', anzahl: '{n} Meldungen', typ_unbekannt: 'Meldung Nr. {n}',
        hinweis: 'Was das Auto selbst meldet. Panoptes liest nur, löscht nichts.' };

    function T(k, fb, vars) {
        var v = null;
        try {
            var x = window.BYD && BYD.i18n && BYD.i18n.t('auto_meldungen.' + k, vars);
            if (x && x !== 'auto_meldungen.' + k) v = x;
        } catch (e) {}
        if (!v) {
            v = fb;
            if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) v = v.split('{' + n + '}').join(vars[n]);
        }
        return v;
    }

    function name(m) {
        return T('typ.' + m.schluessel, T('typ_unbekannt', DE.typ_unbekannt, { n: m.typ }));
    }

    function zeigen(kachel, wert, unter, d) {
        if (!d || !d.ok || !d.verfuegbar) { kachel.hidden = true; return; }
        kachel.hidden = false;
        var m = d.meldungen || [];
        kachel.title = T('hinweis', DE.hinweis);
        if (!m.length) {
            wert.textContent = T('keine', DE.keine);
            wert.className = 'v ok';
            unter.textContent = ' ';
            return;
        }
        wert.textContent = m.length === 1 ? T('eine', DE.eine) : T('anzahl', DE.anzahl, { n: m.length });
        wert.className = 'v warn';
        var namen = [];
        for (var i = 0; i < m.length; i++) namen.push(name(m[i]));
        unter.textContent = namen.slice(0, 2).join(', ') + (namen.length > 2 ? ' +' + (namen.length - 2) : '');
        kachel.title = namen.join('\n') + '\n\n' + T('hinweis', DE.hinweis);
    }

    function init(kachel, wert, unter) {
        if (!kachel || !wert || !unter) return;
        function laden() {
            fetch('/api/auto/meldungen', { cache: 'no-store', credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .catch(function () { return null; })
                .then(function (d) { zeigen(kachel, wert, unter, d); });
        }
        laden();
        setInterval(laden, 5 * 60 * 1000);
    }

    window.PanoptesAutoMeldungen = { init: init };
})();
