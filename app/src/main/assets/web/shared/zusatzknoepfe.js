/**
 * Panoptes 0.1.38 — Knöpfe für Funktionen, die der Kern schon hatte, aber ohne Einstieg (UNGENUTZTE_FUNKTIONEN_0137 §1a).
 * Keine neue Logik: nur vorhandene Endpunkte. Jeder Baustein erscheint nur, wenn sein Platzhalter auf der Seite steht.
 *
 *   #sprechenNotaus  communicate.html  POST /api/communicate/emergency-stop   (Rückfrage; schaltet nur AB)
 *   #ladekurve       charging.html     GET  /api/laden/kurve                  (nur lesen)
 *   #zugriffeBox     system.html       GET  /api/system/zugriffe              (nur lesen, Kopplungen gesperrt)
 * ES5 für ältere WebViews im Auto.
 */
(function () {
    'use strict';

    function t(k, f, v) {
        var s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { s = BYD.i18n.t(k); if (s === k) s = null; } } catch (e) { s = null; }
        s = s || f;
        return String(s).replace(/\{(\w+)\}/g, function (m, n) { return v && v[n] != null ? v[n] : m; });
    }
    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }
    function knopf(text, primaer) {
        var b = el('button', primaer ? 'btn btn-primary' : 'btn', text);
        b.type = 'button';
        b.style.minHeight = '48px';
        b.style.fontSize = '16px';
        return b;
    }
    function json(url, opt) {
        return fetch(url, Object.assign({ credentials: 'same-origin', cache: 'no-store' }, opt || {}))
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (d) { d.status = r.status; return d; }); })
            .catch(function () { return { status: 0 }; });
    }
    function fragen(text, ja) {
        if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
            return BYD.utils.confirmDialog({ title: ja, body: text, confirmLabel: ja,
                cancelLabel: t('common.cancel', 'Abbrechen'), danger: true });
        }
        return Promise.resolve(window.confirm(text));
    }

    // ------------------------------------------------------------------ Sprechen: Not-Aus
    function notaus(box) {
        box.textContent = '';
        box.appendChild(el('p', 'zk-text', t('communicate.notaus_hilfe',
            'Schaltet Sprechen, Mithören und Texte am Auto sofort ab. Wieder einschalten geht nur am Bildschirm im Auto.')));
        var b = knopf(t('communicate.notaus_knopf', 'Alles sofort abschalten'));
        b.style.color = 'var(--status-error)';
        b.style.border = '2px solid var(--status-error)';
        b.style.width = '100%';
        var st = el('p', 'zk-text');
        st.setAttribute('role', 'status');
        b.onclick = function () {
            fragen(t('communicate.notaus_frage', 'Sprechen, Mithören und Texte am Auto jetzt abschalten?'),
                t('communicate.notaus_ja', 'Abschalten')).then(function (ok) {
                if (!ok) return;
                b.disabled = true;
                json('/api/communicate/emergency-stop', { method: 'POST' }).then(function (d) {
                    st.textContent = d.success ? t('communicate.notaus_ok', 'Abgeschaltet.')
                        : t('communicate.notaus_fehler', 'Das hat nicht geklappt. Bitte nochmal versuchen.');
                    b.disabled = false;
                });
            });
        };
        box.appendChild(b);
        box.appendChild(st);
    }

    // ------------------------------------------------------------------ Laden: eigene Ladekurve
    function ladekurve(box) {
        json('/api/laden/kurve').then(function (d) {
            var k = d && d.kurve;
            if (d.status !== 200 || !k) return;   // ältere Version oder kein Recht: Karte bleibt weg
            box.style.display = '';
            var inhalt = box.querySelector('[data-inhalt]') || box;
            inhalt.textContent = '';
            if (!k.length) {
                inhalt.appendChild(el('p', 'zk-text', t('charge.kurve_leer',
                    'Noch zu wenig Ladungen. Nach ein paar Schnellladungen steht hier, wie schnell dein Auto bei welchem Akkustand lädt.')));
                return;
            }
            var max = 0;
            for (var i = 0; i < k.length; i++) max = Math.max(max, k[i].kw || 0);
            var liste = el('div', 'zk-kurve');
            for (var j = 0; j < k.length; j++) {
                var z = el('div', 'zk-zeile');
                z.appendChild(el('span', 'zk-soc', k[j].soc_von + '–' + k[j].soc_bis + ' %'));
                var bar = el('span', 'zk-balken');
                var fill = el('i');
                fill.style.width = (max ? Math.round((k[j].kw || 0) * 100 / max) : 0) + '%';
                bar.appendChild(fill);
                z.appendChild(bar);
                z.appendChild(el('span', 'zk-kw', (k[j].kw || 0).toLocaleString() + ' kW'));
                liste.appendChild(z);
            }
            inhalt.appendChild(liste);
            inhalt.appendChild(el('p', 'zk-text', t('charge.kurve_anzahl', 'Aus {n} Ladungen.', { n: d.ladungen || 0 })));
        });
    }

    // ------------------------------------------------------------------ Gekoppelte Geräte: Zugriffe
    function zugriffe(box) {
        box.textContent = '';
        var b = knopf(t('system.zugriffe_knopf', 'Wer hat in der letzten Stunde zugegriffen?'));
        var out = el('div');
        b.onclick = function () {
            b.disabled = true;
            json('/api/system/zugriffe').then(function (d) {
                b.disabled = false;
                out.textContent = '';
                if (d.status !== 200) { out.appendChild(el('p', 'zk-text', t('system.zugriffe_fehler', 'Nur am Bildschirm im Auto oder mit Anmeldung.'))); return; }
                var summe = {};
                var m = d.minuten || [];
                for (var i = 0; i < m.length; i++) {
                    var g = m[i].geraete || {};
                    for (var wer in g) {
                        if (!Object.prototype.hasOwnProperty.call(g, wer)) continue;
                        var n = 0;
                        for (var p in g[wer]) if (Object.prototype.hasOwnProperty.call(g[wer], p)) n += g[wer][p] || 0;
                        summe[wer] = (summe[wer] || 0) + n;
                    }
                }
                var namen = Object.keys(summe);
                if (!namen.length) { out.appendChild(el('p', 'zk-text', t('system.zugriffe_leer', 'Keine Zugriffe in der letzten Stunde.'))); return; }
                namen.sort(function (a, c) { return summe[c] - summe[a]; });
                for (var j = 0; j < namen.length; j++) {
                    var w = namen[j];
                    var name = w === 'lokal' ? t('system.zugriffe_lokal', 'Dieser Bildschirm und angemeldete Browser')
                        : w.indexOf('web:') === 0 ? t('system.zugriffe_web', 'Handy-App im Browser ({id})', { id: w.substring(4) })
                        : t('system.zugriffe_geraet', 'Gekoppeltes Gerät ({id})', { id: w });
                    out.appendChild(el('p', 'zk-text', t('system.zugriffe_zeile', '{wer}: {n} Abrufe', { wer: name, n: summe[w] })));
                }
            });
        };
        box.appendChild(b);
        box.appendChild(out);
    }

    function start() {
        var a = document.getElementById('sprechenNotaus'); if (a) notaus(a);
        var b = document.getElementById('ladekurve'); if (b) ladekurve(b);
        var c = document.getElementById('zugriffeBox'); if (c) zugriffe(c);
    }
    function bereit() {
        if (window.BYD && BYD.i18n && BYD.i18n.init) BYD.i18n.init().then(start, start); else start();
    }
    var css = document.createElement('style');
    css.textContent = '.zk-text{font-size:15px;line-height:1.5;color:var(--text-muted);margin:8px 0}'
        + '.zk-kurve{display:flex;flex-direction:column;gap:6px;margin-top:8px}'
        + '.zk-zeile{display:grid;grid-template-columns:90px 1fr 80px;gap:8px;align-items:center;font-size:15px}'
        + '.zk-balken{height:14px;border-radius:7px;background:var(--bg-elevated);overflow:hidden}'
        + '.zk-balken i{display:block;height:100%;background:var(--status-success)}'
        + '.zk-kw{text-align:right;font-weight:600}';
    document.head.appendChild(css);
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bereit); else bereit();
})();
