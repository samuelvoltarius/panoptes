/**
 * Panoptes — „Was kann mein Tacho?“ (0.1.38.1) als kleiner Knopf auf der Display-Seite.
 * Derselbe lesende Fähigkeitstest wie in den Testfunktionen (GET /api/tacho/faehigkeit): es geht nichts an den
 * Tacho, nur im Stand. Das Ergebnis geht nur auf ausdrücklichen Wunsch hinaus: erst „Ergebnis senden“ zeigt genau
 * den Text, der gesendet würde, und erst „Jetzt senden“ schickt ihn (POST /api/tacho/rueckmeldung mit
 * einwilligung: true und genau diesem Text). Kein Senden ohne diese zwei Schritte.
 *
 *   <div id="tachoFaehigkeit"></div>  → wird beim Laden befüllt
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
    var KNOPF = 'min-height:48px;padding:10px 16px;font-size:16px;border-radius:12px;cursor:pointer;'
        + 'border:1px solid var(--border-default, var(--outline-variant));background:var(--bg-elevated, var(--surface-container));'
        + 'color:var(--text-primary, var(--on-surface));margin:6px 8px 0 0;';

    function grundText(g) {
        if (g === 'nur_im_stand' || g === 'nur_in_p') return t('testf.tacho_stand', 'Geht nur im Stand (Gang P).');
        return t('testf.fail', 'Das hat nicht geklappt.');
    }

    function mount(box) {
        var text = '';
        var start = el('button', KNOPF, t('testf.tacho_title', 'Was kann mein Tacho?'));
        start.type = 'button';
        start.id = 'tfStart';
        var info = el('p', 'font-size:15px;opacity:.8;margin:6px 0 0;line-height:1.5;',
            t('testf.tacho_lead', 'Prüft nur, was dein Tacho kann. Es wird nichts an den Tacho geschickt. Geht nur im Stand.'));
        var erg = el('div', 'margin-top:8px;font-size:16px;line-height:1.5;');
        var senden = el('button', KNOPF, t('testf.tacho_send', 'Ergebnis senden'));
        senden.type = 'button';
        senden.hidden = true;
        var hinweis = el('p', 'font-size:15px;opacity:.85;line-height:1.5;',
            t('testf.tacho_share', 'Willst du uns helfen? Schick uns das Ergebnis. Es enthält nur Modell, Software-Stand und die drei Antworten – keine Kennung, keinen Ort.'));
        hinweis.hidden = true;
        var vorschau = el('div', 'margin-top:8px;');
        vorschau.hidden = true;
        vorschau.appendChild(el('p', 'margin:0 0 4px;font-weight:700;', t('testf.tacho_preview', 'Genau das geht an uns:')));
        var pre = el('pre', 'max-height:220px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;'
            + 'padding:8px;border-radius:8px;background:var(--bg-elevated, var(--surface-container));');
        vorschau.appendChild(pre);
        var ja = el('button', KNOPF, t('testf.tacho_send_ja', 'Jetzt senden'));
        ja.type = 'button';
        var nein = el('button', KNOPF, t('testf.tacho_send_nein', 'Abbrechen'));
        nein.type = 'button';
        vorschau.appendChild(ja);
        vorschau.appendChild(nein);
        var gesendet = el('p', 'font-size:16px;');
        gesendet.hidden = true;
        [start, info, erg, hinweis, senden, vorschau, gesendet].forEach(function (x) { box.appendChild(x); });

        function zeige(d) {
            erg.textContent = '';
            [['totwinkel', t('testf.tacho_bs', 'Totwinkel am Tacho')], ['karte', t('testf.tacho_map', 'Karte am Tacho')],
             ['andereApps', t('testf.tacho_apps', 'Andere Apps am Tacho')]].forEach(function (z) {
                var u = d[z[0]] || {};
                var s = u.status === 'ja' ? t('testf.ja', 'ja') : u.status === 'nein' ? t('testf.nein', 'nein') : t('testf.unbekannt', 'noch offen');
                var p = el('p', 'margin:4px 0;');
                p.appendChild(el('b', '', z[1] + ': ' + s));
                if (u.grund) { p.appendChild(document.createElement('br')); p.appendChild(document.createTextNode(u.grund)); }
                erg.appendChild(p);
            });
            text = d.rueckmeldung || '';
            senden.hidden = !text;
            hinweis.hidden = !text;
            vorschau.hidden = true;
            gesendet.hidden = true;
        }

        start.onclick = function () {
            start.disabled = true;
            fetch('/api/tacho/faehigkeit', { cache: 'no-store' })
                .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
                .then(function (x) {
                    if (!x.ok) { erg.textContent = grundText(x.d && x.d.grund); senden.hidden = true; hinweis.hidden = true; return; }
                    zeige(x.d);
                }, function () { erg.textContent = grundText(''); })
                .then(function () { start.disabled = false; });
        };
        // Schritt 1: zeigen, was ginge. Nichts wird gesendet.
        senden.onclick = function () {
            pre.textContent = text;
            vorschau.hidden = false;
            senden.hidden = true;
            gesendet.hidden = true;
        };
        nein.onclick = function () { vorschau.hidden = true; senden.hidden = !text; };
        // Schritt 2: nur jetzt, nur mit Einwilligung, nur genau der gezeigte Text.
        ja.onclick = function () {
            ja.disabled = true;
            fetch('/api/tacho/rueckmeldung', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ einwilligung: true, text: pre.textContent }) })
                .then(function (r) { return r.json().then(function (j) { return { status: r.status, j: j }; }); })
                .then(function (a) {
                    gesendet.textContent = a.j.stand === 'gemerkt'
                        ? t('rueckmeldung.gemerkt', 'Gerade kein Internet. Deine Meldung ist gemerkt und geht raus, sobald wieder Netz da ist.')
                        : a.j.ok ? t('rueckmeldung.danke', 'Danke! Dein Meldungs-Code:') + ' ' + (a.j.code || '')
                        : a.status === 429 ? t('rueckmeldung.zuviel', 'Für heute sind 5 Meldungen erreicht. Morgen geht es wieder.')
                        : t('testf.fail', 'Das hat nicht geklappt.');
                    gesendet.hidden = false;
                    vorschau.hidden = true;
                    if (!a.j.ok) senden.hidden = false;
                }, function () { gesendet.textContent = t('testf.fail', 'Das hat nicht geklappt.'); gesendet.hidden = false; })
                .then(function () { ja.disabled = false; });
        };
    }

    function boot() {
        var box = document.getElementById('tachoFaehigkeit');
        if (box && !box.childNodes.length) mount(box);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
