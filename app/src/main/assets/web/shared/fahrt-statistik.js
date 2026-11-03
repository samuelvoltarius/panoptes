/*
 * 03.11.2026: Zwei Karten im Reiter „Statistik“ der Fahrten-Seite.
 *  1. Verbrauch gegen Außentemperatur (GET /api/trips/verbrauch-temperatur): ein Punkt je 5 °C, gewichtet nach Strecke.
 *     Fahrten ohne bekannte Außentemperatur zählen nicht mit und werden nur genannt.
 *  2. Verlust im Stand (GET /api/trips/standverlust): % und kWh je Tag, Wächter wach/aus getrennt, wenn belegt.
 * Nur Lesen, keine Orte. Lädt erst, wenn eine Karte sichtbar wird.
 */
(function () {
    'use strict';

    function t(k, d, v) {
        var s = d;
        try {
            var r = window.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t(k, v) : null;
            if (r && r !== k) return r;
        } catch (e) {}
        if (v) s = s.replace(/\{(\w+)\}/g, function (m, n) { return v[n] != null ? v[n] : m; });
        return s;
    }
    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }
    function zahl(v, stellen) {
        if (v == null || isNaN(v)) return '–';
        try { return Number(v).toLocaleString(undefined, { minimumFractionDigits: stellen, maximumFractionDigits: stellen }); }
        catch (e) { return Number(v).toFixed(stellen); }
    }
    function holen(url) {
        return fetch(url, { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    }

    var SVG = 'http://www.w3.org/2000/svg';
    function s(tag, attrs) {
        var e = document.createElementNS(SVG, tag);
        for (var k in attrs) if (Object.prototype.hasOwnProperty.call(attrs, k)) e.setAttribute(k, attrs[k]);
        return e;
    }

    function kurve(ziel, d) {
        ziel.innerHTML = '';
        var punkte = (d && d.punkte) || [];
        var hinweis = t('trip.statistik.temp_hinweis',
            '{mit} Fahrten mit Außentemperatur, {ohne} ohne (nicht mitgezählt). Ein Punkt je {grad} °C, gewichtet nach Strecke.',
            { mit: d ? d.fahrtenMitTemperatur || 0 : 0, ohne: d ? d.fahrtenOhneTemperatur || 0 : 0, grad: d ? d.topfGrad || 5 : 5 });
        if (punkte.length < 2) {
            ziel.appendChild(el('div', 'color:var(--text-muted);font-size:14px;',
                t('trip.statistik.temp_leer', 'Noch zu wenig Fahrten mit bekannter Außentemperatur.')));
            ziel.appendChild(el('div', 'color:var(--text-muted);font-size:12px;margin-top:6px;', hinweis));
            return;
        }
        var W = 600, H = 260, L = 48, R = 16, O = 16, U = 40;
        var xs = punkte.map(function (p) { return (p.von + p.bis) / 2; });
        var ys = punkte.map(function (p) { return p.kwhPro100km; });
        var x0 = Math.min.apply(null, xs) - 2.5, x1 = Math.max.apply(null, xs) + 2.5;
        var y0 = Math.max(0, Math.floor(Math.min.apply(null, ys) / 5) * 5 - 5), y1 = Math.ceil(Math.max.apply(null, ys) / 5) * 5 + 5;
        function px(x) { return L + (x - x0) / (x1 - x0) * (W - L - R); }
        function py(y) { return O + (1 - (y - y0) / (y1 - y0)) * (H - O - U); }
        var svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, width: '100%', role: 'img',
            'aria-label': t('trip.statistik.temp_titel', 'Verbrauch und Außentemperatur') });
        svg.style.maxWidth = '100%';
        var achse = 'var(--text-muted)';
        for (var y = y0; y <= y1; y += 5) {
            svg.appendChild(s('line', { x1: L, x2: W - R, y1: py(y), y2: py(y), stroke: 'var(--border-subtle)', 'stroke-width': 1 }));
            var ty = s('text', { x: L - 6, y: py(y) + 4, 'text-anchor': 'end', 'font-size': 12, fill: achse });
            ty.textContent = y;
            svg.appendChild(ty);
        }
        punkte.forEach(function (p) {
            var tx = s('text', { x: px((p.von + p.bis) / 2), y: H - U + 18, 'text-anchor': 'middle', 'font-size': 12, fill: achse });
            tx.textContent = p.von + '…' + p.bis;
            svg.appendChild(tx);
        });
        var xl = s('text', { x: (L + W - R) / 2, y: H - 4, 'text-anchor': 'middle', 'font-size': 12, fill: achse });
        xl.textContent = t('trip.statistik.temp_achse', 'Außentemperatur in °C');
        svg.appendChild(xl);
        var yl = s('text', { x: 12, y: O + 10, 'font-size': 12, fill: achse });
        yl.textContent = 'kWh/100 km';
        svg.appendChild(yl);
        var pfad = punkte.map(function (p, i) { return (i ? 'L' : 'M') + px((p.von + p.bis) / 2).toFixed(1) + ' ' + py(p.kwhPro100km).toFixed(1); }).join(' ');
        svg.appendChild(s('path', { d: pfad, fill: 'none', stroke: 'var(--brand-primary)', 'stroke-width': 2.5 }));
        punkte.forEach(function (p) {
            var c = s('circle', { cx: px((p.von + p.bis) / 2), cy: py(p.kwhPro100km), r: 5 + Math.min(5, p.fahrten / 4),
                fill: 'var(--brand-primary)' });
            var tip = s('title', {});
            tip.textContent = p.von + '…' + p.bis + ' °C: ' + zahl(p.kwhPro100km, 1) + ' kWh/100 km · '
                + p.fahrten + ' ' + t('trip.statistik.fahrten', 'Fahrten') + ' · ' + zahl(p.km, 0) + ' km';
            c.appendChild(tip);
            svg.appendChild(c);
        });
        ziel.appendChild(svg);
        ziel.appendChild(el('div', 'color:var(--text-muted);font-size:12px;margin-top:6px;', hinweis));
    }

    function stand(ziel, d) {
        ziel.innerHTML = '';
        var h = d && d.minStunden != null ? d.minStunden : 3;
        var g = d && d.gruppen;
        if (!g || !g.gesamt || !g.gesamt.parkzeiten) {
            ziel.appendChild(el('div', 'color:var(--text-muted);font-size:14px;',
                t('trip.statistik.stand_leer', 'Noch keine Parkzeit ab {h} Stunden ohne Laden.', { h: h })));
            return;
        }
        var tab = el('div', 'display:grid;grid-template-columns:1fr auto auto;gap:6px 14px;align-items:baseline;width:100%;');
        [['gesamt', 'stand_gesamt', 'Alle Parkzeiten'], ['an', 'stand_an', 'Wächter wach'],
         ['aus', 'stand_aus', 'Wächter aus'], ['unbekannt', 'stand_unbekannt', 'Wächter nicht belegt']].forEach(function (z) {
            var w = g[z[0]];
            if (!w || !w.parkzeiten) return;
            var name = el('div', 'font-size:14px;');
            name.appendChild(el('div', '', t('trip.statistik.' + z[1], z[2])));
            name.appendChild(el('div', 'color:var(--text-muted);font-size:12px;',
                t('trip.statistik.parkzeiten', '{n} Parkzeiten, {h} h', { n: w.parkzeiten, h: zahl(w.stunden, 0) })));
            tab.appendChild(name);
            tab.appendChild(el('div', 'font-family:\'JetBrains Mono\',monospace;font-size:18px;font-weight:700;text-align:right;',
                w.prozentProTag == null ? '–' : zahl(w.prozentProTag, 2) + ' %'));
            tab.appendChild(el('div', 'font-family:\'JetBrains Mono\',monospace;font-size:14px;color:var(--text-secondary);text-align:right;',
                w.kwhProTag == null ? '–' : zahl(w.kwhProTag, 2) + ' kWh'));
        });
        ziel.appendChild(el('div', 'align-self:flex-end;color:var(--text-muted);font-size:12px;margin-bottom:4px;',
            t('trip.statistik.pro_tag', 'pro Tag')));
        ziel.appendChild(tab);
        ziel.appendChild(el('div', 'color:var(--text-muted);font-size:12px;margin-top:8px;',
            t('trip.statistik.stand_hinweis', 'Aus „Zündung aus“ und „Zündung an“, ohne Ladezeiten, nur Parkzeiten ab {h} Stunden.', { h: h })));
    }

    var geladen = false;
    function laden() {
        if (geladen) return;
        geladen = true;
        var k = document.getElementById('tempKurveInhalt');
        var v = document.getElementById('standverlustInhalt');
        if (k) holen('/api/trips/verbrauch-temperatur?tage=365').then(function (d) { kurve(k, d); });
        if (v) holen('/api/trips/standverlust?tage=90').then(function (d) { stand(v, d); });
    }

    function start() {
        var karte = document.getElementById('tempKurveKarte');
        if (!karte) return;
        if ('IntersectionObserver' in window) {
            var io = new IntersectionObserver(function (es) {
                for (var i = 0; i < es.length; i++) if (es[i].isIntersecting) { io.disconnect(); laden(); return; }
            });
            io.observe(karte);
            var karte2 = document.getElementById('standverlustKarte');
            if (karte2) io.observe(karte2);
        } else {
            laden();
        }
    }

    window.FAHRT_STATISTIK = { kurve: kurve, stand: stand };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
