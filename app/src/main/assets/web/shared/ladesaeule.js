/**
 * Panoptes – erkannte Ladesäule (03.11.2026, LadesaeuleZuordnung).
 *
 *   PanoptesLadesaeule.label(s)            → Text für die Ladeliste („Lidl Linz …“ bzw. „Lidl? · prüfen“) oder ''
 *   PanoptesLadesaeule.detail(box, s, cb)  → Zeile „Ladestation · ändern“, Preis-Vorschlag „~“, „Säule merken“
 *   PanoptesLadesaeule.nachtragen(btn, cb) → Knopf „Ladestationen nachtragen“ (alte Ladungen, nur auf Tipp)
 *
 * Daten nur vom Auto (/api/charging/{id}/station). Keine Koordinaten, nur Abstände in Metern. Der Preis-Vorschlag
 * wird nie automatisch übernommen; „Laut Säule“ bleibt die Wahrheit. ES5 (WebView 74).
 */
(function () {
    'use strict';

    var DE = {
        titel: 'Ladestation',
        aendern: 'ändern',
        unsicher: '{name}? · prüfen',
        keine_gefunden: 'Keine passende Säule gefunden',
        privat: 'Privater Ort – nicht nachgeschlagen',
        fehler: 'Routenplaner nicht erreichbar',
        offen: 'Noch nicht nachgeschlagen',
        hinweis_auto: 'erkannt',
        hinweis_unsicher: 'nicht sicher – bitte prüfen',
        hinweis_nutzer: 'von dir gewählt',
        hinweis_gemerkt: 'gemerkte Säule',
        waehlen: 'Welche Säule war es?',
        andere: 'Andere (Name eingeben)',
        keine: 'Keine davon',
        name_frage: 'Name der Ladestation',
        merken: 'Säule merken',
        merken_frage: 'Preis je kWh an dieser Säule. Panoptes erkennt die Säule danach sofort, ohne nachzufragen.',
        gemerkt: 'Säule gemerkt',
        vorschlag: 'Preis-Vorschlag',
        vorschlag_eigen: 'deine letzte Ladung bei {name} ({art})',
        vorschlag_karte: 'deine Karte {name}',
        vorschlag_planer: '{name} laut Routenplaner',
        stand: 'Stand {datum}',
        uebernehmen: 'übernehmen',
        uebernommen: 'Preis übernommen',
        gespeichert: 'Gespeichert',
        fehler_speichern: 'Konnte nicht speichern',
        preis_ungueltig: 'Bitte einen Preis eingeben (0 = gratis)',
        weiter: 'Weiter',
        nachtragen: 'Ladestationen nachtragen',
        nachtragen_ergebnis: '{n} erkannt, {u} zu prüfen, {k} ohne Treffer',
        nachtragen_mehr: 'Es sind noch ältere Ladungen offen – nochmal tippen.',
        nachtragen_aus: 'Nachschlagen ist in den Einstellungen aus'
    };

    function T(k, vars) {
        var key = 'laden.saeule.' + k, s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key, vars); if (v && v !== key) s = v; } } catch (e) {}
        s = s || DE[k] || k;
        if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) s = s.split('{' + n + '}').join(vars[n]);
        return s;
    }
    function lang() { return (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de'; }
    function zahl(v, d) {
        try { return Number(v).toLocaleString(lang(), { minimumFractionDigits: d, maximumFractionDigits: d }); }
        catch (e) { return Number(v).toFixed(d); }
    }
    function datum(x) {
        if (x == null || x === '') return '';
        try { return new Date(typeof x === 'number' ? x : String(x) + 'T12:00:00').toLocaleDateString(lang(), { day: '2-digit', month: '2-digit', year: 'numeric' }); }
        catch (e) { return String(x); }
    }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function knopf(txt, cls) { var b = el('button', 'btn ' + (cls || 'btn-secondary'), txt); b.type = 'button'; b.style.margin = '4px 8px 4px 0'; return b; }
    function toast(m, ok) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, ok ? 'success' : 'error'); }
    function post(url, body) {
        return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._status = r.status; return j; }); });
    }
    function frage(titel, text, wert, einheit) {
        return window.BYD && BYD.utils && typeof BYD.utils.promptDialog === 'function'
            ? BYD.utils.promptDialog({ title: titel, body: text, label: einheit || '', confirmLabel: T('weiter'),
                value: wert || '', inputType: 'text', inputMode: einheit ? 'decimal' : 'text' })
            : Promise.resolve(window.prompt(text, wert || ''));
    }
    function preisAus(txt) {
        var s = String(txt == null ? '' : txt).trim().replace(',', '.');
        if (!s) return null;
        var v = Number(s);
        return isFinite(v) && v >= 0 && v <= 10 ? v : NaN;
    }
    function art(s) { return s.isDc === true ? 'DC' : (s.isDc === false ? 'AC' : ''); }

    /** Text für die Ladeliste; '' = Ortsname wie bisher. */
    function label(s) {
        if (!s || !s.zuordnung) return '';
        var name = s.stationName || s.betreiber || '';
        if (!name) return '';
        if (s.zuordnung === 'unsicher') return T('unsicher', { name: s.betreiber || s.stationName });
        if (s.zuordnung === 'auto' || s.zuordnung === 'nutzer' || s.zuordnung === 'gemerkt') return name;
        return '';
    }

    function preisOffen(s) {
        // Vorschlag nur, wenn kein eigener Preis da ist („Laut Säule“ mit Betrag oder selbst gesetzt)
        if (s.saeuleBetrag != null) return false;
        if (s.preisQuelle === 'nutzer' || s.preisQuelle === 'tarif') return false;
        return true;
    }

    function detail(box, s, nachher) {
        if (!box) return;
        box.innerHTML = '';
        if (!s || s.inProgress || s.endTime == null || s.endTime <= 0) { box.style.display = 'none'; return; }
        box.style.display = '';
        var id = s.id;
        var kopf = el('div', 'ch-detail-kicker', T('titel'));
        box.appendChild(kopf);
        var zeile = el('div', 'ch-detail-location-line');
        var wert = el('div', 'ch-detail-value');
        var z = s.zuordnung || '';
        if (label(s)) wert.textContent = label(s);
        else wert.textContent = z === 'keine' ? T('keine_gefunden') : z === 'privat' ? T('privat') : z === 'fehler' ? T('fehler') : T('offen');
        zeile.appendChild(wert);
        var aendern = el('button', 'ch-map-link', T('aendern'));
        aendern.type = 'button';
        zeile.appendChild(aendern);
        box.appendChild(zeile);
        var hinweis = el('div', 'ch-detail-caption', '');
        if (z === 'auto' || z === 'unsicher' || z === 'nutzer' || z === 'gemerkt') {
            var t = T('hinweis_' + z);
            if (s.betreiber && s.stationName && s.betreiber !== s.stationName) t = s.betreiber + ' · ' + t;
            hinweis.textContent = t;
        }
        box.appendChild(hinweis);
        var liste = el('div');
        liste.style.display = 'none';
        box.appendChild(liste);
        var vorschlag = el('div');
        box.appendChild(vorschlag);
        var unten = el('div');
        box.appendChild(unten);

        var daten = null;
        function fertig(neu) { if (typeof nachher === 'function') nachher(neu || null); }
        function setze(body) {
            return post('/api/charging/' + id + '/station', body).then(function (r) {
                if (r._status >= 400 || r.success !== true) throw new Error('x');
                toast(T('gespeichert'), true);
                fertig(r.session);
                return r;
            }).catch(function () { toast(T('fehler_speichern'), false); });
        }

        aendern.onclick = function () {
            liste.innerHTML = '';
            liste.style.display = liste.style.display === 'none' ? '' : 'none';
            if (liste.style.display === 'none') return;
            liste.appendChild(el('div', 'ch-note', T('waehlen')));
            ((daten && daten.kandidaten) || []).forEach(function (k) {
                var txt = (k.name || k.betreiber) + (k.betreiber && k.name ? ' · ' + k.betreiber : '')
                    + ' · ' + k.art + (k.kw ? ' ' + zahl(k.kw, 0) + ' kW' : '') + ' · ' + k.abstandM + ' m';
                var b = knopf(txt, k.id === s.stationId ? 'btn-primary' : 'btn-secondary');
                b.onclick = function () { setze({ stationId: k.id }); };
                liste.appendChild(b);
            });
            var andere = knopf(T('andere'));
            andere.onclick = function () {
                frage(T('titel'), T('name_frage'), s.stationName || '', '').then(function (n) {
                    n = String(n == null ? '' : n).trim();
                    if (n) setze({ name: n });
                });
            };
            liste.appendChild(andere);
            var keine = knopf(T('keine'));
            keine.onclick = function () { setze({ keine: true }); };
            liste.appendChild(keine);
        };

        function uebernehmen(v) {
            var name = v.quelle === 'eigen' ? (s.betreiber || v.name) : ((s.betreiber ? s.betreiber + ' · ' : '') + v.name);
            post('/api/laden/nachfrage', { id: id, art: 'ortskreis', preisKwh: v.preisKwh, name: name }).then(function (r) {
                if (r._status >= 400 || r.ok === false) throw new Error('x');
                toast(T('uebernommen'), true);
                fertig(null);
            }).catch(function () { toast(T('fehler_speichern'), false); });
        }

        function merken() {
            var name = s.stationName || s.betreiber;
            if (!name) return;
            var start = s.electricityRate > 0 ? zahl(s.electricityRate, 2)
                : ((daten && daten.vorschlaege && daten.vorschlaege[0]) ? zahl(daten.vorschlaege[0].preisKwh, 2) : '');
            frage(T('merken'), T('merken_frage'), start, '€/kWh').then(function (txt) {
                if (txt === null) return;
                var p = preisAus(txt);
                if (p === null || isNaN(p)) { toast(T('preis_ungueltig'), false); return; }
                post('/api/laden/nachfrage', { id: id, art: 'ortskreis', preisKwh: p, name: name,
                    ortMerken: { name: name, radiusM: 60 } }).then(function (r) {
                    if (r._status >= 400 || r.ok === false || !r.ortMerken || r.ortMerken === 'fehlgeschlagen'
                            || r.ortMerken === 'keine_position') throw new Error('x');
                    var body = s.stationId ? { stationId: s.stationId } : { name: name };
                    body.gemerktTarif = r.ortMerken;
                    return post('/api/charging/' + id + '/station', body).then(function (q) {
                        if (q._status >= 400 || q.success !== true) throw new Error('x');
                        toast(T('gemerkt'), true);
                        fertig(q.session);
                    });
                }).catch(function () { toast(T('fehler_speichern'), false); });
            });
        }

        fetch('/api/charging/' + id + '/station', { cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .catch(function () { return null; })
            .then(function (d) {
                daten = d && d.success ? d : { kandidaten: [], vorschlaege: [] };
                var vs = daten.vorschlaege || [];
                if (vs.length && preisOffen(s)) {
                    vorschlag.appendChild(el('div', 'ch-detail-kicker', T('vorschlag')));
                    vs.forEach(function (v) {
                        var herkunft = v.quelle === 'eigen'
                            ? T('vorschlag_eigen', { name: v.name, art: art(s) })
                            : v.quelle === 'karte' ? T('vorschlag_karte', { name: v.name })
                            : T('vorschlag_planer', { name: v.name });
                        var stand = v.quelle === 'eigen' ? datum(v.datum) : datum(v.stand);
                        var r = el('div', 'ch-note', '~' + zahl(v.preisKwh, 2) + ' €/kWh · ' + herkunft
                            + (stand ? ' · ' + T('stand', { datum: stand }) : ''));
                        var b = knopf(T('uebernehmen'));
                        b.onclick = function () { uebernehmen(v); };
                        r.appendChild(document.createTextNode(' '));
                        r.appendChild(b);
                        vorschlag.appendChild(r);
                    });
                }
                if ((z === 'auto' || z === 'unsicher' || z === 'nutzer') && (s.stationName || s.betreiber)) {
                    var m = knopf(T('merken'));
                    m.onclick = merken;
                    unten.appendChild(m);
                }
            });
    }

    function nachtragen(btn, nachher) {
        if (!btn) return;
        btn.textContent = T('nachtragen');
        btn.onclick = function () {
            btn.disabled = true;
            post('/api/charging/stationen/nachtragen', {}).then(function (r) {
                btn.disabled = false;
                if (r.grund === 'aus') { toast(T('nachtragen_aus'), false); return; }
                if (r._status >= 400 || r.ok !== true) throw new Error('x');
                toast(T('nachtragen_ergebnis', { n: r.zugeordnet || 0, u: r.unsicher || 0, k: r.keine || 0 })
                    + (r.offen > 0 ? ' ' + T('nachtragen_mehr') : ''), true);
                if (typeof nachher === 'function') nachher();
            }).catch(function () { btn.disabled = false; toast(T('fehler_speichern'), false); });
        };
    }

    window.PanoptesLadesaeule = { label: label, detail: detail, nachtragen: nachtragen, DE: DE };
})();
