/**
 * Panoptes – Preise nachtragen (0.1.10.1, LADENACHFRAGE_VERTRAG „GEBAUT“, Kern 5 POST /api/laden/preis).
 *
 *   PanoptesNachbepreisung.init(karteEl)
 *
 * Für Ladungen ohne Preis (z. B. aus dem Overdrive-Umzug): auswählen → Preis je kWh oder „gratis“ → Vorschau
 * (dryRun, ändert nichts) → Übernehmen → Rückgängig möglich (protokollId). Nur mit Login; ohne API bleibt die Karte weg.
 * Liste aus GET /api/laden/ladungen (ohne Koordinaten). ES5 (WebView 74).
 */
(function () {
    'use strict';

    var DE = {
        titel: 'Preise nachtragen',
        text: 'Diese Ladungen haben keinen Preis. Wähle aus, welche denselben Preis bekommen, z. B. alle AC-Ladungen zu Hause.',
        alle: 'Alle', nur_ac: 'Nur AC', nur_dc: 'Nur DC', keine: 'Keine',
        preis: 'Preis je kWh (€)', gratis: 'gratis',
        vorschau: 'Vorschau', uebernehmen: 'Übernehmen', rueckgaengig: 'Rückgängig',
        gewaehlt: '{n} ausgewählt',
        vorschau_text: '{n} Ladungen bekommen {preis}. Kosten zusammen: {summe}.',
        fertig: '{n} Ladungen bepreist.',
        zurueck: 'Zurückgesetzt.',
        fehler: 'Das hat nicht geklappt. Es wurde nichts geändert.',
        preis_fehlt: 'Bitte einen Preis eingeben oder „gratis“ wählen.',
        auffaellig: 'Hinweis: {n} Preise wirken ungewöhnlich – bitte prüfen.',
        mehr: 'Es gibt mehr als {n} Ladungen ohne Preis; nach dem Speichern erscheinen die nächsten.'
    };
    var MAX = 200;   // so viele IDs nimmt /api/laden/preis je Aufruf

    function T(k, vars) {
        var key = 'nachpreis.' + k, s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key, vars); if (v && v !== key) s = v; } } catch (e) {}
        s = s || DE[k] || k;
        if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) s = s.split('{' + n + '}').join(vars[n]);
        return s;
    }
    function lang() { return (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de'; }
    function zahl(v, d) { try { return Number(v).toLocaleString(lang(), { minimumFractionDigits: d, maximumFractionDigits: d }); } catch (e) { return Number(v).toFixed(d); } }
    function datum(ms) { try { return new Date(ms).toLocaleString(lang(), { day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit' }); } catch (e) { return String(ms); } }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function knopf(txt, cls) { var b = el('button', 'btn ' + (cls || 'btn-secondary'), txt); b.type = 'button'; b.style.margin = '6px 8px 0 0'; return b; }
    function post(url, body) {
        return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._status = r.status; return j; }); });
    }

    function init(karte) {
        if (!karte) return;
        var protokoll = null;

        function laden() {
            // neueste zuerst; bis zu 4 Seiten à 200 lesen und nur die ohne Preis behalten
            var ohne = [], mehr = false;
            function seite(n) {
                return fetch('/api/laden/ladungen?anzahl=200&seite=' + n, { cache: 'no-store' })
                    .then(function (r) { if (!r.ok) throw new Error('api'); return r.json(); })
                    .then(function (d) {
                        (d.ladungen || []).forEach(function (l) { if (l.rate === null || l.rate === undefined) ohne.push(l); });
                        if (d.mehr && n < 3) return seite(n + 1);
                        mehr = !!d.mehr;
                    });
            }
            seite(0).then(function () { zeichne(ohne, mehr); }, function () { karte.style.display = 'none'; });
        }

        function zeichne(liste, mehr) {
            karte.innerHTML = '';
            if (!liste.length && !protokoll) { karte.style.display = 'none'; return; }
            karte.style.display = '';
            karte.appendChild(el('h3', null, T('titel')));
            var meld = el('div', 'ch-note');
            if (protokoll) {
                var rb = knopf(T('rueckgaengig'));
                rb.onclick = function () {
                    rb.disabled = true;
                    post('/api/laden/preis/rueckgaengig', { protokollId: protokoll }).then(function (r) {
                        if (r._status >= 400 || r.ok === false) throw new Error('x');
                        protokoll = null; laden();
                        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(T('zurueck'), 'success');
                    }).catch(function () { rb.disabled = false; meld.textContent = T('fehler'); });
                };
                karte.appendChild(rb);
                karte.appendChild(meld);
                if (!liste.length) return;
            }
            karte.appendChild(el('p', 'ch-note', T('text')));
            var gewaehlt = {};
            var zaehler = el('div', 'ch-note');
            function zaehle() { var n = Object.keys(gewaehlt).length; zaehler.textContent = T('gewaehlt', { n: n }); return n; }
            var boxen = [];
            var wahl = el('div');
            [['alle', function () { return true; }], ['nur_ac', function (l) { return l.dc === false; }],
             ['nur_dc', function (l) { return l.dc === true; }], ['keine', function () { return false; }]].forEach(function (w) {
                var b = knopf(T(w[0]));
                b.onclick = function () {
                    boxen.forEach(function (x) { x.cb.checked = w[1](x.l); x.cb.onchange(); });
                    try { tab.dispatchEvent(new Event('change')); } catch (e) {}   // alte Vorschau verwerfen
                };
                wahl.appendChild(b);
            });
            karte.appendChild(wahl);
            var tab = el('div');
            tab.style.cssText = 'max-height:280px;overflow-y:auto;margin:8px 0;border:1px solid var(--border-subtle);border-radius:10px;padding:4px 8px';
            liste.slice(0, MAX).forEach(function (l) {
                var z = el('label');
                z.style.cssText = 'display:flex;gap:10px;align-items:center;padding:6px 0;font-size:13px;border-bottom:1px solid var(--border-subtle)';
                var cb = el('input'); cb.type = 'checkbox';
                cb.onchange = function () { if (cb.checked) gewaehlt[l.id] = true; else delete gewaehlt[l.id]; zaehle(); };
                z.appendChild(cb);
                z.appendChild(el('span', null, datum(l.start) + ' · ' + zahl(l.kwh || 0, 1) + ' kWh · ' + (l.dc === true ? 'DC' : (l.dc === false ? 'AC' : '?'))
                    + (l.ort ? ' · ' + l.ort : '')));
                tab.appendChild(z);
                boxen.push({ cb: cb, l: l });
            });
            karte.appendChild(tab);
            if (mehr || liste.length > MAX) karte.appendChild(el('div', 'ch-note', T('mehr', { n: MAX })));
            karte.appendChild(zaehler); zaehle();

            var zeile = el('div');
            zeile.style.cssText = 'display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:8px';
            zeile.appendChild(el('span', null, T('preis')));
            var inp = el('input', 'text-input'); inp.type = 'text'; inp.setAttribute('inputmode', 'decimal'); inp.style.width = '110px';
            zeile.appendChild(inp);
            var gl = el('label'); var gcb = el('input'); gcb.type = 'checkbox';
            gcb.onchange = function () { inp.disabled = gcb.checked; };
            gl.appendChild(gcb); gl.appendChild(document.createTextNode(' ' + T('gratis')));
            zeile.appendChild(gl);
            karte.appendChild(zeile);

            var vorschauBox = el('div', 'ch-note');
            var vb = knopf(T('vorschau'));
            var ub = knopf(T('uebernehmen'), 'btn-primary'); ub.disabled = true;
            var anfrage = null;
            function bauen() {
                var ids = Object.keys(gewaehlt).map(Number);
                if (!ids.length) return null;
                var a = { ids: ids };
                if (gcb.checked) a.gratis = true;
                else {
                    var p = Number(String(inp.value || '').trim().replace(',', '.'));
                    if (!(p > 0)) return 'preis';
                    a.rate = p;
                }
                return a;
            }
            vb.onclick = function () {
                var a = bauen();
                if (a === 'preis') { meld.textContent = T('preis_fehlt'); return; }
                if (!a) return;
                meld.textContent = '';
                a.dryRun = true;
                vb.disabled = true;
                post('/api/laden/preis', a).then(function (r) {
                    vb.disabled = false;
                    if (r._status >= 400 || r.ok === false) throw new Error('x');
                    var summe = 0, auff = 0, cur = '€';
                    (r.ergebnisse || []).forEach(function (e) { if (e.neu) { summe += e.neu.cost || 0; cur = e.neu.currency || cur; } if (e.hinweis) auff++; });
                    vorschauBox.textContent = T('vorschau_text', { n: (r.ergebnisse || []).length, preis: a.gratis ? T('gratis') : zahl(a.rate, 2) + ' ' + cur + '/kWh', summe: zahl(summe, 2) + ' ' + cur })
                        + (auff ? ' ' + T('auffaellig', { n: auff }) : '');
                    delete a.dryRun; anfrage = a; ub.disabled = false;
                }).catch(function () { vb.disabled = false; meld.textContent = T('fehler'); });
            };
            ub.onclick = function () {
                if (!anfrage) return;
                ub.disabled = true;
                post('/api/laden/preis', anfrage).then(function (r) {
                    if (r._status >= 400 || r.ok === false) throw new Error('x');
                    protokoll = r.protokollId || null;
                    if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(T('fertig', { n: (r.ergebnisse || []).length }), 'success');
                    laden();   // zurücklesen: bepreiste Ladungen verschwinden aus der Liste
                }).catch(function () { ub.disabled = false; meld.textContent = T('fehler'); });
            };
            // Auswahl oder Preis geändert → Vorschau gilt nicht mehr
            [inp, gcb].forEach(function (x) { x.addEventListener('input', function () { anfrage = null; ub.disabled = true; }); x.addEventListener('change', function () { anfrage = null; ub.disabled = true; }); });
            tab.addEventListener('change', function () { anfrage = null; ub.disabled = true; });
            karte.appendChild(vb); karte.appendChild(ub);
            karte.appendChild(vorschauBox);
            karte.appendChild(meld);
        }
        laden();
    }

    window.PanoptesNachbepreisung = { init: init, DE: DE };
})();
