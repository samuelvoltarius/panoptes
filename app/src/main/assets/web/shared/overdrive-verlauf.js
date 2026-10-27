/**
 * Panoptes – Overdrive-Umzug Stufe 2: Verlauf übernehmen (0.1.10.1, OVERDRIVE_STUFE2_PLAN „GEBAUT“, Kern 5).
 *
 *   PanoptesODVerlauf.init(karteEl, inhaltEl)
 *
 * Vorschau (zählt nur) → Bestätigen (nur im Stand) → Fortschritt + Abbrechen → Ergebnis.
 * API nur mit Login (am Display bzw. angemeldet), nie über die Kopplung. Ohne Overdrive-Daten bleibt die Karte weg.
 * Die Übernahme liest nur eine Kopie; Panoptes-Einträge haben Vorrang (nur ältere Zeilen). ES5 (WebView 74).
 */
(function () {
    'use strict';

    var URL = '/api/uebernahme/overdrive/verlauf';
    var DE = {
        titel: 'Verlauf aus Overdrive übernehmen',
        text: 'Übernimmt ältere Fahrten, Ladungen und Akkuverlauf aus deiner früheren Overdrive-Installation. Overdrive selbst wird nur gelesen; bei Doppelten hat Panoptes Vorrang.',
        vorschau_laedt: 'Zähle, was übernommen würde …',
        zeitraum: 'Zeitraum: {von} – {bis}',
        zeile: '{name}: {neu} neu ({dubletten} schon da)',
        n_ladungen: 'Ladungen', n_fahrten: 'Fahrten', n_socVerlauf: 'Akku-Messpunkte', n_socTage: 'Akku-Tage', n_zuendung: 'Zündungen',
        ladekurven: 'Ladekurven-Punkte: {n}',
        nichts: 'Es gibt nichts Neues zu übernehmen.',
        speicher: 'Zu wenig freier Speicher: nötig {braucht}, frei {frei}.',
        nur_stand: 'Starten geht nur, wenn das Auto steht (Gang P).',
        starten: 'Übernehmen',
        frage: '{n} Einträge aus Overdrive übernehmen? Das dauert einige Minuten. Bitte nicht losfahren.',
        laeuft: 'Läuft: {phase} – {prozent} %',
        abbrechen: 'Abbrechen',
        fertig: 'Fertig. {ladungen} Ladungen und {fahrten} Fahrten übernommen.',
        abgebrochen: 'Abgebrochen. Bereits übernommene Einträge bleiben; ein neuer Start setzt fort.',
        fehler: 'Die Übernahme ist fehlgeschlagen. Panoptes-Daten sind unverändert geblieben, soweit nichts übernommen wurde.',
        ohne_preis: '{n} übernommene Ladungen ohne Preis.',
        nachbepreisen: 'Preise nachtragen',
        grund_nur_im_stand: 'Starten geht nur, wenn das Auto steht (Gang P).',
        grund_zu_wenig_speicher: 'Zu wenig freier Speicher.',
        grund_laeuft_schon: 'Die Übernahme läuft schon.',
        grund_keine_daten: 'Keine Overdrive-Daten gefunden.',
        phase_bereit: 'bereit', phase_kopieren: 'kopieren', phase_nacharbeit: 'Summen neu rechnen', phase_vorschau: 'zählen'
    };

    function T(k, vars) {
        var key = 'od_verlauf.' + k, s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key, vars); if (v && v !== key) s = v; } } catch (e) {}
        s = s || DE[k] || k;
        if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) s = s.split('{' + n + '}').join(vars[n]);
        return s;
    }
    function lang() { return (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de'; }
    function datum(ms) { try { return new Date(ms).toLocaleDateString(lang()); } catch (e) { return new Date(ms).toISOString().slice(0, 10); } }
    function gb(b) { return (b / 1073741824).toLocaleString(lang(), { maximumFractionDigits: 1 }) + ' GB'; }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function knopf(txt, cls) { var b = el('button', 'btn ' + (cls || 'btn-secondary'), txt); b.type = 'button'; b.style.margin = '8px 8px 0 0'; return b; }
    var TEILE = ['ladungen', 'fahrten', 'socVerlauf', 'socTage', 'zuendung'];

    function init(karte, inhalt) {
        if (!karte || !inhalt) return;
        var timer = null;

        function holen() {
            return fetch(URL, { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
        }
        // 0.1.20 Tempo (Messung am Auto: alle 2 s, auch unsichtbar): nur bei sichtbarer Seite, alle 5 s; ändert sich der
        // Stand 1 min lang nicht (z. B. hängende Phase), nur noch alle 30 s.
        var letzterStand = '', gleichSeit = 0;
        function planen() {
            var still = gleichSeit && (Date.now() - gleichSeit) > 60000;
            timer = setTimeout(function () {
                if (document.hidden) { planen(); return; }
                holen().then(function (d) {
                    timer = null;
                    if (d && d.stand && !laufend(d.stand)) { zeichne(d); return; }
                    var k = d ? JSON.stringify(d.stand || {}) : '';
                    if (k !== letzterStand) { letzterStand = k; gleichSeit = Date.now(); if (d) zeichneStand(d.stand); }
                    if (!timer) planen();
                });
            }, still ? 30000 : 5000);
        }
        function laufend(st) { return st && st.phase && ['bereit', 'fertig', 'abgebrochen', 'fehler'].indexOf(st.phase) < 0; }

        function zeichneStand(st) {
            inhalt.innerHTML = '';
            inhalt.appendChild(el('p', 'setting-desc', T('text')));
            if (laufend(st)) {
                inhalt.appendChild(el('div', 'setting-desc', T('laeuft', { phase: T('phase_' + st.phase), prozent: st.prozent || 0 })));
                var bar = el('div'); bar.style.cssText = 'height:8px;border-radius:4px;background:rgba(255,255,255,.08);margin-top:8px;overflow:hidden';
                var fill = el('div'); fill.style.cssText = 'height:100%;background:var(--sm-aqua);width:' + (st.prozent || 0) + '%';
                bar.appendChild(fill); inhalt.appendChild(bar);
                var ab = knopf(T('abbrechen'));
                ab.onclick = function () { ab.disabled = true; fetch(URL + '/abbrechen', { method: 'POST' }).then(neu, neu); };
                inhalt.appendChild(ab);
                if (!timer) planen();
                return true;
            }
            return false;
        }

        function ergebnis(st) {
            var e = st.ergebnis || {};
            var n = function (k) { return (e[k] && e[k].neu) || 0; };
            if (st.phase === 'fertig') inhalt.appendChild(el('div', 'setting-desc', T('fertig', { ladungen: n('ladungen'), fahrten: n('fahrten') })));
            else if (st.phase === 'abgebrochen') inhalt.appendChild(el('div', 'setting-desc', T('abgebrochen')));
            else if (st.phase === 'fehler') inhalt.appendChild(el('div', 'setting-desc', T('fehler')));
            var ohne = n('ladungen') - (e.bepreistNachOrtskreis || 0);
            if (st.phase !== 'fehler' && ohne > 0) {
                var z = el('div', 'setting-desc');
                z.style.color = 'var(--warning)';
                z.appendChild(document.createTextNode(T('ohne_preis', { n: ohne }) + ' '));
                var a = el('a', null, T('nachbepreisen')); a.href = 'charging.html#nachbepreisen';
                a.style.cssText = 'color:var(--sm-aqua);font-weight:600';
                z.appendChild(a);
                inhalt.appendChild(z);
            }
        }

        function zeichne(d) {
            if (!d || d.vorhanden === false || d.ok === false && d.grund === 'nur_mit_login') { karte.style.display = 'none'; return; }
            karte.style.display = '';
            if (zeichneStand(d.stand)) return;
            if (d.stand && ['fertig', 'abgebrochen', 'fehler'].indexOf(d.stand.phase) >= 0) ergebnis(d.stand);
            if (d.speicherOk === false) {
                inhalt.appendChild(el('div', 'setting-desc', T('speicher', { braucht: gb(d.brauchtBytes || 0), frei: gb(d.freiBytes || 0) })));
                return;
            }
            var e = d.ergebnis || {}, summe = 0, von = null, bis = null;
            TEILE.forEach(function (k) {
                var x = e[k]; if (!x || x.uebersprungen) return;
                summe += x.neu || 0;
                if (x.von && (von === null || x.von < von)) von = x.von;
                if (x.bis && (bis === null || x.bis > bis)) bis = x.bis;
                inhalt.appendChild(el('div', 'setting-desc', T('zeile', { name: T('n_' + k), neu: x.neu || 0, dubletten: x.dubletten || 0 })));
            });
            if (e.ladekurven) inhalt.appendChild(el('div', 'setting-desc', T('ladekurven', { n: e.ladekurven })));
            if (von && bis) inhalt.appendChild(el('div', 'setting-desc', T('zeitraum', { von: datum(von), bis: datum(bis) })));
            if (!summe) { inhalt.appendChild(el('div', 'setting-desc', T('nichts'))); return; }
            var meld = el('div', 'setting-desc');
            if (!d.steht) meld.textContent = T('nur_stand');
            var start = knopf(T('starten'), 'btn-primary');
            start.disabled = !d.steht;
            start.onclick = function () {
                if (!window.confirm(T('frage', { n: summe }))) return;
                start.disabled = true;
                fetch(URL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"start":true}' })
                    .then(function (r) { return r.json(); })
                    .then(function (j) {
                        if (j && j.ok === false) { meld.textContent = T('grund_' + j.grund); start.disabled = false; return; }
                        neu();
                    })
                    .catch(function () { meld.textContent = T('fehler'); start.disabled = false; });
            };
            inhalt.appendChild(start);
            inhalt.appendChild(meld);
        }

        function neu() {
            inhalt.innerHTML = '';
            inhalt.appendChild(el('div', 'setting-desc', T('vorschau_laedt')));
            holen().then(function (d) { inhalt.innerHTML = ''; if (!(d && d.stand && laufend(d.stand))) inhalt.appendChild(el('p', 'setting-desc', T('text'))); zeichne(d); });
        }
        neu();
    }

    window.PanoptesODVerlauf = { init: init, DE: DE };
})();
