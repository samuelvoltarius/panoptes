/**
 * Panoptes – Ladenachfrage „Wo hast du geladen?“ (0.1.10, LADENACHFRAGE_VERTRAG „GEBAUT“, Kern 5).
 *
 *   PanoptesNachfrage.init(boxEl, { nurJetzt: true|false })
 *
 * Ruhige Karte, kein Ton, kein Popup: Am Start nur bei jetztZeigen (Auto steht in P), auf der Laden-Seite
 * immer, solange Ladungen ohne Preis offen sind. Fehlt die API (404) oder ist nichts offen, bleibt die Karte weg.
 * Antworten gehen an POST /api/laden/nachfrage (bzw. /spaeter, /kein-preis); danach wird neu gelesen.
 * Keine Koordinaten: die API liefert nur Ortsnamen. ES5 (WebView 74).
 */
(function () {
    'use strict';

    var DE = {
        titel: 'Wo hast du geladen?',
        titel_n: 'Wo hast du geladen? ({nr} von {n})',
        text: 'Für diese Ladung kennt Panoptes keinen Preis. Ohne Preis fehlen die Kosten in Fahrten und Monatsbericht.',
        ort: 'Ort: {name}',
        ort_unbekannt: 'Ort unbekannt',
        vorschlag: 'Vorschlag',
        art_karte: 'Ladekarte {name}',
        art_adhoc: 'Ad hoc (ohne Karte)',
        art_zuhause: 'Zu Hause',
        art_zuhause_gratis: 'Zu Hause, gratis',
        preis: 'Preis je kWh',
        speichern: 'Speichern',
        spaeter: 'Später',
        kein_preis: 'Preis unbekannt',
        gespeichert: 'Gespeichert.',
        fehler: 'Speichern hat nicht geklappt. Bitte nochmal versuchen.',
        preis_fehlt: 'Bitte einen Preis eingeben (0 = gratis).',
        hinweis_plausibel: 'Gespeichert. Der Preis wirkt ungewöhnlich – bitte prüfen.',
        gratis: 'gratis'
    };

    function T(k, vars) {
        var key = 'laden.nachfrage.' + k, s = null;
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
    function zeit(ms) {
        try { return new Date(ms).toLocaleString(lang(), { weekday: 'short', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }); }
        catch (e) { return new Date(ms).toISOString().slice(0, 16).replace('T', ' '); }
    }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function knopf(txt, cls) { var b = el('button', 'btn ' + (cls || 'btn-secondary'), txt); b.type = 'button'; b.style.margin = '4px 8px 4px 0'; return b; }
    function post(url, body) {
        return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._status = r.status; return j; }); });
    }
    function toast(m, ok) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, ok ? 'success' : 'error'); }
    // Eingabe „0,35“ oder „0.35“ → Zahl; leer → null
    function preisAus(txt) {
        var s = String(txt == null ? '' : txt).trim().replace(',', '.');
        if (!s) return null;
        var v = Number(s);
        return isFinite(v) && v >= 0 ? v : NaN;
    }

    function init(box, opts) {
        if (!box) return;
        opts = opts || {};
        function laden() {
            fetch('/api/laden/nachfrage', { cache: 'no-store' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .catch(function () { return null; })
                .then(function (d) { zeichne(d); });
        }
        function weg() { box.style.display = 'none'; box.innerHTML = ''; }

        function zeichne(d) {
            var offen = (d && d.ok && d.offen) || [];
            // „Später“ zurückgestellte Ladungen nur auf der Laden-Seite, nicht als Karte am Start
            if (opts.nurJetzt) offen = (d && d.jetztZeigen) ? offen.filter(function (o) { return !o.spaeter; }) : [];
            if (!offen.length) { weg(); return; }
            var z = offen[0];
            box.innerHTML = '';
            box.style.display = '';
            box.appendChild(el('h3', null, offen.length > 1 ? T('titel_n', { nr: 1, n: offen.length }) : T('titel')));
            var kopf = zeit(z.start) + ' · ' + zahl(z.kwh || 0, 1) + ' kWh' + (z.dc === true ? ' · DC' : (z.dc === false ? ' · AC' : ''));
            box.appendChild(el('div', 'ch-note', kopf));
            box.appendChild(el('div', 'ch-note', z.ort && z.ort.bekannt && z.ort.name ? T('ort', { name: z.ort.name }) : T('ort_unbekannt')));
            box.appendChild(el('p', 'ch-note', T('text')));
            var meldung = el('div', 'ch-note');

            function senden(url, body) {
                [].forEach.call(box.querySelectorAll('button'), function (b) { b.disabled = true; });
                post(url, body).then(function (r) {
                    if (r._status >= 400 || r.ok === false) throw new Error('x');
                    var plaus = r.ergebnisse && r.ergebnisse[0] && r.ergebnisse[0].hinweis;
                    toast(plaus ? T('hinweis_plausibel') : T('gespeichert'), true);
                    laden();   // zurücklesen: die beantwortete Ladung verschwindet aus der Liste
                }).catch(function () {
                    meldung.textContent = T('fehler');
                    [].forEach.call(box.querySelectorAll('button'), function (b) { b.disabled = false; });
                });
            }

            // Vorschläge (gemerkter Ort mit Preis) – ein Tipp
            (z.vorschlaege || []).forEach(function (v) {
                if (v.art !== 'ortskreis' || typeof v.preisKwh !== 'number') return;
                var b = knopf(T('vorschlag') + ': ' + v.name + ' · ' + (v.preisKwh === 0 ? T('gratis') : zahl(v.preisKwh, 2) + ' €/kWh'), 'btn-primary');
                b.onclick = function () { senden('/api/laden/nachfrage', { id: z.id, art: 'ortskreis', preisKwh: v.preisKwh, name: v.name }); };
                box.appendChild(b);
            });

            // Auswahl: Art wählen, dann (falls nötig) Preis eingeben
            var reihe = el('div');
            var preisZeile = el('div');
            preisZeile.style.cssText = 'display:none;margin:8px 0;align-items:center;gap:8px;flex-wrap:wrap';
            var eingabe = el('input', 'text-input');
            eingabe.type = 'text'; eingabe.setAttribute('inputmode', 'decimal');
            eingabe.style.cssText = 'width:120px';
            var speichern = knopf(T('speichern'), 'btn-primary');
            preisZeile.appendChild(el('span', null, T('preis') + ' (€)'));
            preisZeile.appendChild(eingabe);
            preisZeile.appendChild(speichern);
            var gewaehlt = null;
            (z.auswahl || []).forEach(function (a) {
                var name = a.art === 'karte' ? T('art_karte', { name: a.anzeige || '' }) : T('art_' + a.art);
                var b = knopf(name);
                b.onclick = function () {
                    if (a.art === 'zuhause_gratis') { senden('/api/laden/nachfrage', { id: z.id, art: 'zuhause_gratis' }); return; }
                    gewaehlt = a;
                    [].forEach.call(reihe.querySelectorAll('button'), function (x) { x.className = 'btn btn-secondary'; });
                    b.className = 'btn btn-primary';
                    eingabe.value = typeof a.preisKwh === 'number' ? zahl(a.preisKwh, 2) : '';
                    preisZeile.style.display = 'flex';
                    try { eingabe.focus(); } catch (e) {}
                };
                reihe.appendChild(b);
            });
            speichern.onclick = function () {
                if (!gewaehlt) return;
                var p = preisAus(eingabe.value);
                if (p === null || isNaN(p)) { meldung.textContent = T('preis_fehlt'); return; }
                var body = { id: z.id, art: gewaehlt.art, preisKwh: p };
                if (gewaehlt.art === 'karte') body.karteId = gewaehlt.id;
                senden('/api/laden/nachfrage', body);
            };
            box.appendChild(reihe);
            box.appendChild(preisZeile);

            var unten = el('div');
            var sp = knopf(T('spaeter'));
            sp.onclick = function () { senden('/api/laden/nachfrage/spaeter', { id: z.id }); };
            var kp = knopf(T('kein_preis'));
            kp.onclick = function () { senden('/api/laden/nachfrage/kein-preis', { id: z.id }); };
            unten.appendChild(sp); unten.appendChild(kp);
            box.appendChild(unten);
            box.appendChild(meldung);
        }

        laden();
        if (window.BYD && BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(laden);
        // Start-Seite: alle 60 s nachsehen (Auto könnte inzwischen in P stehen); keine Daten nach außen
        if (opts.nurJetzt) setInterval(function () { if (!document.hidden) laden(); }, 60000);
    }

    window.PanoptesNachfrage = { init: init, DE: DE };
})();
