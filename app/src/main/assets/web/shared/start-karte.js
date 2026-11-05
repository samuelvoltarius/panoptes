/**
 * Panoptes – Karte „Start“ auf der Startseite (0.1.38.10, Anzeige je Dienst beim Start).
 *
 *   PanoptesStart.init(boxEl)
 *
 * Fragt GET /api/start/status (beantwortet die App am Display selbst aus StartStatus, sonst 404 → keine Karte).
 * Solange eine Stufe „startet …“ ist, steht die Karte oben mit Häkchen je Stufe und wird alle 2 s neu gelesen.
 * Ist alles bereit, verschwindet sie. Startet eine Stufe nicht, bleibt sie mit „Ausblenden“ stehen.
 * ES5 (WebView 74): kein ?., ??, Flex-gap, inset.
 */
(function () {
    'use strict';

    var DE = {
        titel: 'Panoptes startet …',
        titel_fertig: 'Nicht alles ist gestartet',
        zugang: 'Zugang (Tailscale)',
        oberflaeche: 'Oberfläche',
        waechter: 'Wächter',
        rest: 'Telegram',   // 0.1.38.11: nur Telegram wird geprüft; ohne Telegram zeigt der Start die Zeile nicht
        startet: 'startet …',
        bereit: 'bereit',
        startet_nicht: 'startet nicht',
        hinweis_nicht: 'Panoptes versucht es weiter. Hilft das nicht, Zündung einmal aus und wieder an.',
        ausblenden: 'Ausblenden'
    };
    var TAKT_MS = 2000;
    // „startet nicht“: weiter nachlesen, aber langsam – meldet der Dienst sich später (anderer Weg), wird daraus ✓
    var NACH_MS = 10000;
    var ZEICHEN = { startet: '…', bereit: '✓', startet_nicht: '✗' };
    var FARBE = { startet: '#c9d1d9', bereit: '#3fb950', startet_nicht: '#ffc857' };

    function T(k) {
        var key = 'home.start_' + k, s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key); if (v && v !== key) s = v; } } catch (e) {}
        return s || DE[k] || k;
    }
    function el(tag, txt, css) {
        var e = document.createElement(tag);
        if (txt != null) e.textContent = txt;
        if (css) e.style.cssText = css;
        return e;
    }

    var box = null, timer = null, weg = false, letzte = '';

    function verstecken() {
        if (box) box.style.display = 'none';
        if (timer) { clearTimeout(timer); timer = null; }
    }

    /** Reine Darstellung, auch für Tests: d = Antwort von /api/start/status. */
    function zeichne(d) {
        var stufen = (d && d.stufen) || [];
        var nicht = false, i;
        for (i = 0; i < stufen.length; i++) if (stufen[i].zustand === 'startet_nicht') nicht = true;
        var key = JSON.stringify(stufen) + (d && d.fertig ? '1' : '0');
        if (key === letzte) return;
        letzte = key;
        while (box.firstChild) box.removeChild(box.firstChild);
        box.appendChild(el('b', d.fertig && nicht ? T('titel_fertig') : T('titel'), 'display:block;font-size:1.5em;margin:0 0 10px'));
        var liste = el('ul', null, 'list-style:none;margin:0;padding:0');
        for (i = 0; i < stufen.length; i++) {
            var z = stufen[i].zustand;
            var li = el('li', null, 'display:flex;align-items:center;font-size:1.35em;min-height:48px;border-top:1px solid rgba(255,255,255,.08)');
            li.setAttribute('data-stufe', stufen[i].stufe);
            li.setAttribute('data-zustand', z);
            li.appendChild(el('span', ZEICHEN[z] || '…', 'display:inline-block;width:2em;text-align:center;font-weight:bold;color:' + (FARBE[z] || '#c9d1d9')));
            li.appendChild(el('span', T(stufen[i].stufe), 'flex:1 1 auto'));
            li.appendChild(el('span', T(z), 'color:' + (FARBE[z] || '#c9d1d9')));
            liste.appendChild(li);
        }
        box.appendChild(liste);
        if (d.fertig && nicht) {
            box.appendChild(el('p', T('hinweis_nicht'), 'margin:12px 0 10px;font-size:1.1em'));
            var b = el('button', T('ausblenden'), 'min-height:56px;font-size:1.15em;padding:0 22px;display:inline-flex;align-items:center;justify-content:center');
            b.type = 'button';
            b.className = 'act';
            b.onclick = function () { weg = true; verstecken(); };
            box.appendChild(b);
        }
        box.style.display = '';
    }

    function lesen() {
        timer = null;
        if (weg || !box) return;
        fetch('/api/start/status', { cache: 'no-store' }).then(function (r) {
            if (!r.ok) return null;
            return r.json();
        }).then(function (d) {
            if (!d || !d.aktiv) { verstecken(); return; }
            var nicht = false;
            for (var i = 0; i < d.stufen.length; i++) if (d.stufen[i].zustand === 'startet_nicht') nicht = true;
            if (d.fertig && !nicht) { verstecken(); return; }
            zeichne(d);
            timer = setTimeout(lesen, d.fertig ? NACH_MS : TAKT_MS);
        }, function () { verstecken(); });
    }

    window.PanoptesStart = {
        init: function (b) {
            box = b;
            if (!box) return;
            lesen();
        },
        zeichne: function (b, d) { box = b; letzte = ''; zeichne(d); }
    };
})();
