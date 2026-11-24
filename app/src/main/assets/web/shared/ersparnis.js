/**
 * Panoptes – Ersparnis gegenüber Benzin bzw. Diesel (0.1.43.20).
 *
 *   PanoptesErsparnis.karte(el)          Karte mit Monat / Jahr / Seit Beginn und dem Vergleichsauto
 *   PanoptesErsparnis.fahrt(km, strom, el)  eine Zeile „Gespart gegenüber Benzin: …“ für eine Fahrt
 *   PanoptesErsparnis.lade_export(el, art)  Datumsbereich + Knöpfe für den CSV-Export (art: laden | fahrten)
 *
 * Gerechnet wird im Auto (GET /api/laden/ersparnis), hier wird nur angezeigt. Keine Live-Preise.
 * 0.1.43.21: Jeder Betrag ist als Schätzung gekennzeichnet (Karte unter dem Titel, Fahrt-Zeile, CSV-Kopfzeile).
 * Ohne API (älterer Kern) bleibt die Karte weg. ES5 (WebView 74).
 */
(function () {
    'use strict';

    var DE = {
        titel: 'Ersparnis gegenüber {kraftstoff}', benzin: 'Benzin', diesel: 'Diesel',
        monat: 'Dieser Monat', jahr: 'Dieses Jahr', gesamt: 'Seit Beginn',
        zeile: '{km} gefahren · Strom {strom} · mit {kraftstoff} {sprit}',
        zu_wenig: 'Noch zu wenig gefahren für einen Wert.',
        preis_fehlt: 'Spritpreis eintragen, dann rechnet Panoptes die Ersparnis.',
        strom_fehlt: 'Noch keine Ladung mit Preis.',
        unvollstaendig: 'Einige Ladungen haben keinen Preis. Die Ersparnis ist deshalb zu hoch.',
        durchschnitt: 'In diesem Zeitraum nicht geladen: gerechnet mit deinem Ø-Strompreis.',
        teurer: 'Strom war {betrag} teurer als {kraftstoff}.',
        vergleich: 'Vergleichsauto', kraftstoff: 'Kraftstoff', verbrauch: 'Verbrauch in Liter je 100 km',
        preis: 'Spritpreis je Liter', preis_stand: 'Eingetragen am {datum}',
        richtwert: 'Richtwert, Stand {datum}. Trag deinen Preis von der Tankstelle ein.',
        ohne_richtwert: 'Für deine Währung gibt es keinen Richtwert. Trag deinen Preis ein.',
        speichern: 'Speichern', gespeichert: 'Gespeichert', fehler: 'Nicht gespeichert. Bitte Werte prüfen.',
        fahrt: 'Gespart gegenüber {kraftstoff}: {betrag}',
        hinweis: 'Gerechnet im Auto aus deinen Ladekosten. Keine Live-Preise aus dem Netz.',
        schaetzung: 'Schätzung – hängt von deinen eingetragenen Preisen und dem Vergleichsverbrauch ab.',
        schaetzung_kurz: 'Schätzung'
    };
    var DE_EXPORT = {
        von: 'Von', bis: 'Bis', ladungen: 'Ladevorgänge speichern', kosten: 'Ladekosten je Monat speichern',
        fahrten: 'Fahrten im Zeitraum speichern'
    };

    function T(gruppe, k, fb, vars) {
        var v = null;
        try {
            var x = window.BYD && BYD.i18n && BYD.i18n.t(gruppe + '.' + k, vars);
            if (x && x !== gruppe + '.' + k) v = x;
        } catch (e) {}
        if (!v) {
            v = fb;
            if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) v = v.split('{' + n + '}').join(vars[n]);
        }
        return v;
    }
    function E(k, vars) { return T('ersparnis', k, DE[k], vars); }

    function sprache() {
        try { return (BYD.i18n.getLang && BYD.i18n.getLang()) || 'de'; } catch (e) { return 'de'; }
    }
    function zahl(v, d) {
        try { return Number(v).toLocaleString(sprache(), { minimumFractionDigits: d, maximumFractionDigits: d }); }
        catch (e) { return Number(v).toFixed(d); }
    }
    function geld(v, w) { return zahl(v, 2) + ' ' + (w || '€'); }
    function km(v) {
        try { if (window.BYD && BYD.units && BYD.units.dist) return BYD.units.dist(v); } catch (e) {}
        return zahl(v, 0) + ' km';
    }
    function datum(ms) {
        try { return new Date(ms).toLocaleDateString(sprache()); } catch (e) { return new Date(ms).toISOString().slice(0, 10); }
    }
    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }
    function holen(url) {
        return fetch(url, { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .catch(function () { return null; });
    }
    function kraftstoffName(v) { return E(v && v.kraftstoff === 'diesel' ? 'diesel' : 'benzin'); }

    var ZR = ['monat', 'jahr', 'gesamt'];

    function karte(box) {
        if (!box) return;
        var z = 'monat';
        try { z = localStorage.getItem('pn.ersparnis.zeitraum') || 'monat'; } catch (e) {}
        if (ZR.indexOf(z) < 0) z = 'monat';
        holen('/api/laden/ersparnis').then(function (d) {
            if (!d || !d.ok || !d.vergleich || box.getAttribute('data-aus')) { box.style.display = 'none'; return; }
            box.style.display = '';
            zeichnen(box, d, z);
        });
    }

    function zeichnen(box, d, z) {
        var v = d.vergleich, w = v.waehrung || '€', k = kraftstoffName(v);
        box.textContent = '';
        var kopf = el('div', 'display:flex;align-items:center;gap:8px;font-size:14px;font-weight:600;color:var(--text-secondary);margin-bottom:12px;width:100%;');
        kopf.appendChild(el('span', 'font-size:18px;', '🌱'));
        kopf.appendChild(el('span', 'flex:1;', E('titel', { kraftstoff: k })));
        var sel = el('select', 'width:auto;padding:4px 8px;font-size:13px;');
        sel.className = 'text-input';
        sel.id = 'ersparnisZeitraum';
        ZR.forEach(function (x) { var o = el('option', null, E(x)); o.value = x; sel.appendChild(o); });
        sel.value = z;
        sel.onchange = function () {
            try { localStorage.setItem('pn.ersparnis.zeitraum', sel.value); } catch (e) {}
            zeichnen(box, d, sel.value);
        };
        kopf.appendChild(sel);
        box.appendChild(kopf);
        // 0.1.43.21: gut sichtbar, nicht klein unten – die Beträge sind eine Schätzung
        var sch = el('div', 'font-size:14px;color:var(--text-secondary);margin:-6px 0 10px;', 'ⓘ ' + E('schaetzung'));
        sch.id = 'ersparnisSchaetzung';
        box.appendChild(sch);

        var r = d[z] || {};
        var wert = el('div', "font-family:'JetBrains Mono',monospace;font-size:34px;font-weight:700;line-height:1.1;");
        wert.id = 'ersparnisWert';
        var unter = el('div', 'font-size:15px;color:var(--text-secondary);margin-top:4px;');
        var notiz = el('div', 'font-size:13px;margin-top:8px;color:var(--warning);display:none;');
        if (r.ersparnis != null) {
            var e = r.ersparnis;
            wert.textContent = (e >= 0 ? '' : '−') + geld(Math.abs(e), w);
            wert.style.color = e >= 0 ? 'var(--success, #22C55E)' : 'var(--warning)';
            unter.textContent = E('zeile', { km: km(r.km), strom: geld(r.strom_kosten, w), kraftstoff: k, sprit: geld(r.sprit_kosten, w) });
            var n = [];
            if (e < 0) n.push(E('teurer', { betrag: geld(-e, w), kraftstoff: k }));
            if (r.grund === 'strom_unvollstaendig') n.push(E('unvollstaendig'));
            if (r.basis === 'durchschnitt') n.push(E('durchschnitt'));
            if (n.length) { notiz.textContent = n.join(' '); notiz.style.display = ''; }
        } else {
            wert.textContent = '--';
            wert.style.color = 'var(--text-primary)';
            unter.textContent = E(r.grund === 'preis_fehlt' ? 'preis_fehlt' : r.grund === 'strom_fehlt' ? 'strom_fehlt' : 'zu_wenig');
        }
        box.appendChild(wert);
        box.appendChild(unter);
        box.appendChild(notiz);
        box.appendChild(el('div', 'font-size:12px;color:var(--text-muted);margin-top:8px;', E('hinweis')));
        box.appendChild(einstellung(v, function () { karte(box); }));
    }

    /** Vergleichsauto: Kraftstoff, Verbrauch, Spritpreis mit Datum. Aufklappbar, große Felder. */
    function einstellung(v, fertig) {
        var det = el('details', 'margin-top:12px;width:100%;');
        var sum = el('summary', 'cursor:pointer;font-size:16px;min-height:44px;line-height:44px;color:var(--sm-aqua, var(--brand-primary));font-weight:600;', E('vergleich') + ': ' + kraftstoffName(v) + ', ' + zahl(v.verbrauch, 1) + ' l · ' + (v.preis != null ? geld(v.preis, v.waehrung || '€') : '–'));
        det.appendChild(sum);
        var f = el('div', 'display:flex;flex-direction:column;gap:10px;margin-top:8px;');
        function feld(text, input) {
            var l = el('label', 'display:flex;flex-direction:column;gap:4px;font-size:15px;');
            l.appendChild(el('span', null, text));
            l.appendChild(input);
            return l;
        }
        var ks = el('select', 'font-size:16px;padding:8px;min-height:44px;');
        ks.className = 'text-input';
        ['benzin', 'diesel'].forEach(function (x) { var o = el('option', null, E(x)); o.value = x; ks.appendChild(o); });
        ks.value = v.kraftstoff === 'diesel' ? 'diesel' : 'benzin';
        var vb = el('input', 'font-size:16px;padding:8px;min-height:44px;');
        vb.className = 'text-input';
        vb.type = 'number'; vb.step = '0.1'; vb.min = '2'; vb.max = '25'; vb.inputMode = 'decimal';
        vb.value = v.verbrauch != null ? v.verbrauch : '';
        var pr = el('input', 'font-size:16px;padding:8px;min-height:44px;');
        pr.className = 'text-input';
        pr.type = 'number'; pr.step = '0.001'; pr.min = '0.1'; pr.inputMode = 'decimal';
        pr.value = v.preis != null ? v.preis : '';
        f.appendChild(feld(E('kraftstoff'), ks));
        f.appendChild(feld(E('verbrauch'), vb));
        f.appendChild(feld(E('preis') + (v.waehrung ? ' (' + v.waehrung + ')' : ''), pr));
        var stand = el('div', 'font-size:13px;color:var(--text-muted);');
        if (v.preis_stand) stand.textContent = E('preis_stand', { datum: datum(v.preis_stand) });
        else if (v.preis_richtwert) {
            var rs = String(v.richtwert_stand || '');
            stand.textContent = E('richtwert', { datum: rs.length === 7 ? rs.slice(5) + '/' + rs.slice(0, 4) : rs });
        } else stand.textContent = E('ohne_richtwert');
        f.appendChild(stand);
        var knopf = el('button', 'min-height:48px;font-size:16px;align-self:flex-start;', E('speichern'));
        knopf.type = 'button';
        knopf.className = 'btn btn-primary';
        var meldung = el('span', 'font-size:14px;margin-left:8px;');
        knopf.onclick = function () {
            var body = { kraftstoff: ks.value };
            var a = parseFloat(String(vb.value).replace(',', '.'));
            if (isFinite(a)) body.verbrauch = a;
            var p = parseFloat(String(pr.value).replace(',', '.'));
            // Nur ein geänderter Preis bekommt ein neues Datum; der Richtwert wird nicht als eigener Preis gespeichert
            if (isFinite(p) && (v.preis_richtwert ? p !== v.preis : true)) body.preis = p;
            if (!isFinite(p) && !v.preis_richtwert && v.preis != null) body.preis = null;
            knopf.disabled = true;
            fetch('/api/laden/ersparnis', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
            }).then(function (r) { return r.json().catch(function () { return null; }); })
                .catch(function () { return null; })
                .then(function (d) {
                    knopf.disabled = false;
                    if (d && d.ok) { meldung.textContent = E('gespeichert'); setTimeout(fertig, 600); }
                    else meldung.textContent = E('fehler');
                });
        };
        var zeile = el('div', null);
        zeile.appendChild(knopf);
        zeile.appendChild(meldung);
        f.appendChild(zeile);
        det.appendChild(f);
        return det;
    }

    /** Eine Fahrt: km und Stromkosten der Fahrt (aus der Fahrtenliste), gerechnet im Auto. */
    function fahrt(kmWert, strom, ziel) {
        if (!ziel) return;
        ziel.style.display = 'none';
        ziel.textContent = '';
        if (!(kmWert >= 1) || !(strom >= 0)) return;
        holen('/api/laden/ersparnis?km=' + encodeURIComponent(kmWert) + '&strom=' + encodeURIComponent(strom)).then(function (d) {
            if (!d || !d.ok || !d.fahrt || d.fahrt.ersparnis == null || d.fahrt.ersparnis <= 0) return;
            var w = (d.vergleich && d.vergleich.waehrung) || '€';
            ziel.textContent = '🌱 ' + E('fahrt', { kraftstoff: kraftstoffName(d.vergleich), betrag: geld(d.fahrt.ersparnis, w) })
                + ' (' + E('schaetzung_kurz') + ')';
            ziel.title = E('schaetzung');
            ziel.style.display = '';
        });
    }

    /** CSV-Export mit Datumsbereich. art = 'laden' (Ladevorgänge + Ladekosten) oder 'fahrten'. */
    function ladeExport(box, art) {
        if (!box) return;
        function X(k) { return T('lade_export', k, DE_EXPORT[k]); }
        var heute = new Date();
        function iso(d) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
        var reihe = el('div', 'display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end;');
        function datumsfeld(text, wert) {
            var l = el('label', 'display:flex;flex-direction:column;gap:4px;font-size:15px;');
            l.appendChild(el('span', null, text));
            var i = el('input', 'font-size:16px;padding:8px;min-height:44px;');
            i.type = 'date';
            i.value = wert;
            l.appendChild(i);
            reihe.appendChild(l);
            return i;
        }
        var von = datumsfeld(X('von'), heute.getFullYear() + '-01-01');
        var bis = datumsfeld(X('bis'), iso(heute));
        box.appendChild(reihe);
        var knoepfe = el('div', 'display:flex;gap:10px;flex-wrap:wrap;margin-top:12px;');
        function knopf(text, url, primaer) {
            var b = el('button', 'min-height:48px;font-size:16px;', text);
            b.type = 'button';
            b.className = primaer ? 'btn btn-primary' : 'btn';
            b.onclick = function () {
                if (!von.value || !bis.value) return;
                window.location.href = url() + '&von=' + encodeURIComponent(von.value) + '&bis=' + encodeURIComponent(bis.value)
                    + '&sprache=' + encodeURIComponent(sprache().slice(0, 2));
            };
            knoepfe.appendChild(b);
        }
        if (art === 'fahrten') {
            knopf(X('fahrten'), function () { return '/api/trips/datei?format=csv'; }, true);
        } else {
            knopf(X('ladungen'), function () { return '/api/laden/export?liste=ladungen'; }, true);
            knopf(X('kosten'), function () { return '/api/laden/export?liste=kosten'; }, false);
        }
        box.appendChild(knoepfe);
    }

    window.PanoptesErsparnis = { karte: karte, fahrt: fahrt, lade_export: ladeExport };
})();
