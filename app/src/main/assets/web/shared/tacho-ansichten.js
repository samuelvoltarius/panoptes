/**
 * Panoptes — „Kamera & Video im Tacho“ (0.1.42, T3). Unter Display → Tacho. Alles ab Werk aus, alles abschaltbar.
 *
 *   GET /api/tacho/kamera            → { wahl, imStand }
 *   PUT /api/tacho/kamera {schalter} → { ok, grund, wahl }   (einschalten nur im Stand, ausschalten immer)
 *
 * Die Regeln gelten im Kern (TachoKameraAnsicht.java), nicht hier:
 *  - Rückwärtsgang: Heckbild nur mit Bestätigung „mein Tacho zeigt kein eigenes Rückfahrbild“; im Zweifel nichts.
 *  - Blinker: Seitenblick links/rechts, nie im Rückwärtsgang, nicht bei Warnblinker, weg 0,8 s nach dem Blinken.
 *  - Rundumsicht nur in P; Video einer gewählten Aufnahme nur in P bei 0 km/h, ohne Ton, beim Gang-Wechsel sofort weg.
 * ES5 (WebView 74 am Auto).
 *
 *   <div id="tachoAnsichten"></div>
 */
(function () {
    'use strict';
    function t(k, f) { return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(k, f) : f; }
    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }
    var NOTIZ = 'font-size:15px;line-height:1.5;margin:0 0 8px;opacity:.85;';
    var ZEILE = 'display:flex;align-items:center;min-height:56px;margin:4px 0;font-size:17px;cursor:pointer;';
    var SCHALTER = [
        { k: 'kameraAn', t: 'tacho_ansichten.kamera', f: 'Kamera im Tacho' },
        { k: 'kameraSeite', t: 'tacho_ansichten.seite', f: 'Seitenblick beim Blinken', unter: 'kameraAn' },
        { k: 'kameraHeck', t: 'tacho_ansichten.heck', f: 'Heckbild im Rückwärtsgang', unter: 'kameraAn' },
        { k: 'kameraHeckOhneByd', t: 'tacho_ansichten.heck_ohne_byd', f: 'Mein Tacho zeigt beim Rückwärtsfahren kein eigenes BYD-Bild', unter: 'kameraHeck' },
        { k: 'kameraRundum', t: 'tacho_ansichten.rundum', f: 'Rundumsicht im Stand (nur in P)', unter: 'kameraAn' },
        { k: 'videoAn', t: 'tacho_ansichten.video', f: 'Video im Tacho (nur in P bei 0 km/h)' }
    ];
    var wahl = {}, box = null, meldung = null, laeuft = false;

    function api(methode, body) {
        var o = { method: methode, cache: 'no-store', headers: { 'Content-Type': 'application/json' } };
        if (body) o.body = JSON.stringify(body);
        return fetch('/api/tacho/kamera', o).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok && j && j.ok !== false, j: j || {} }; },
                function () { return { ok: false, j: {} }; });
        });
    }
    function sage(text, schlecht) {
        if (!meldung) return;
        meldung.textContent = text || '';
        meldung.style.color = schlecht ? 'var(--status-danger)' : '';
    }
    function grundText(g) {
        if (g === 'nur_im_stand') return t('tacho_ansichten.nur_im_stand', 'Einschalten geht nur im Stand (P).');
        return t('tacho_ansichten.fehler', 'Nicht gespeichert.');
    }
    function setze(teil) {
        if (laeuft) return;
        laeuft = true;
        sage('');
        api('PUT', teil).then(function (r) {
            laeuft = false;
            if (r.j && r.j.wahl) wahl = r.j.wahl;
            if (!r.ok) sage(grundText(r.j.grund), true);
            zeichne();
        }, function () { laeuft = false; sage(grundText(''), true); });
    }
    function sichtbar(s) {
        var u = s.unter;
        while (u) {
            if (!wahl[u]) return false;
            var weiter = null;
            for (var i = 0; i < SCHALTER.length; i++) if (SCHALTER[i].k === u) weiter = SCHALTER[i].unter;
            u = weiter;
        }
        return true;
    }
    function aufnahmen(sel) {
        fetch('/api/recordings', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (j) {
            var liste = (j && j.recordings) || [];
            for (var i = 0; i < liste.length && i < 30; i++) {
                var rec = liste[i];
                if (!rec || !rec.id || !/^[a-f0-9]{32}$/i.test(rec.id)) continue;
                var o = el('option', null, rec.filename || rec.id);
                o.value = String(rec.id).toLowerCase();
                if (o.value === wahl.videoId) o.selected = true;
                sel.appendChild(o);
            }
        }, function () { });
    }
    function zeichne() {
        if (!box) return;
        while (box.firstChild) box.removeChild(box.firstChild);
        box.appendChild(el('div', 'font-size:17px;font-weight:700;margin:18px 0 6px;', t('tacho_ansichten.titel', 'Kamera & Video im Tacho')));
        box.appendChild(el('p', NOTIZ, t('tacho_ansichten.hilfe',
            'Ab Werk aus. Die Kamera kommt über den Panoptes-Kamera-Weg. Läuft der Panoptes-Seitenblick, zeigt der Tacho keine Kamera.')));
        SCHALTER.forEach(function (s) {
            if (!sichtbar(s)) return;
            var z = el('label', ZEILE + (s.unter ? 'padding-left:24px;' : ''));
            var c = el('input', 'width:28px;height:28px;margin:0 12px 0 0;');
            c.type = 'checkbox';
            c.checked = !!wahl[s.k];
            c.setAttribute('data-schalter', s.k);
            c.onchange = function () { var teil = {}; teil[s.k] = c.checked; setze(teil); };
            z.appendChild(c);
            z.appendChild(el('span', null, t(s.t, s.f)));
            box.appendChild(z);
        });
        if (wahl.videoAn) {
            var sel = el('select', 'width:100%;margin:6px 0;padding:12px;font-size:16px;border-radius:10px;'
                + 'background:var(--bg-elevated);color:var(--text-primary);border:1px solid var(--border-default);');
            var leer = el('option', null, t('tacho_ansichten.video_waehlen', 'Aufnahme wählen …'));
            leer.value = '';
            sel.appendChild(leer);
            sel.onchange = function () { setze({ videoId: sel.value }); };
            box.appendChild(sel);
            aufnahmen(sel);
            box.appendChild(el('p', NOTIZ, t('tacho_ansichten.video_hilfe',
                'Ohne Ton. Sobald du aus P schaltest oder losfährst, ist das Video sofort weg.')));
        }
        meldung = el('p', NOTIZ);
        box.appendChild(meldung);
    }
    function start() {
        box = document.getElementById('tachoAnsichten');
        if (!box) return;
        api('GET').then(function (r) { wahl = (r.j && r.j.wahl) || {}; zeichne(); }, function () { zeichne(); });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
