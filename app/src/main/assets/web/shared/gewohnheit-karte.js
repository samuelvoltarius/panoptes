/**
 * Panoptes – Gewohnheiten-Karte (0.1.23, GEWOHNHEITEN_KONZEPT §4/§5, REGEL DAU).
 *
 *   PanoptesGewohnheit.init()
 *
 * Eine große Karte über der Startseite: Symbole, EIN kurzer Satz, große Knöpfe. Nur im Stand (imStand aus
 * GET /api/gewohnheiten/karte), ohne Ton. Arten: vorschlag (Ja, automatisch / Nein / Später), probelauf
 * (Passt / Heute nicht), freigabe (Ja, ab jetzt / Weiter nur melden / Löschen), aufraeumen (Löschen / Behalten).
 * Den Satz baut die Karte aus Bausteinen (i18n gewohnheit.*), die API liefert nur Daten. ES5 (WebView 74).
 */
(function () {
    'use strict';

    var DE = {
        tag_werktag: 'Werktags', tag_wochenende: 'Am Wochenende',
        sym_werktag: 'Mo–Fr', sym_wochenende: 'Sa–So',
        klima_an: '{tag} bis {zeit} auf {temp} °C vorheizen?',
        klima_aus: '{tag} um {zeit} die Klima ausschalten?',
        klima_temp: '{tag} um {zeit} auf {temp} °C stellen?',
        sitzheizung: '{tag} um {zeit} Sitzheizung {sitz} auf Stufe {stufe}?',
        sitzlueftung: '{tag} um {zeit} Sitzlüftung {sitz} auf Stufe {stufe}?',
        lenkradheizung: '{tag} um {zeit} Lenkradheizung {an}?',
        wallbox_start: '{tag} um {zeit} zu Hause laden?',
        wallbox_stopp: '{tag} um {zeit} das Laden stoppen?',
        wallbox_strom: '{tag} um {zeit} mit {a} A laden?',
        waechter_an: '{tag} um {zeit} den Wächter einschalten?',
        camping_an: '{tag} um {zeit} Camping einschalten?',
        camping_aus: '{tag} um {zeit} Camping ausschalten?',
        sitz_fahrer: 'Fahrer', sitz_beifahrer: 'Beifahrer', an: 'an', aus: 'aus',
        unter: 'unter {x} °C',
        grund: '{n}-mal in 2 Wochen selbst gemacht.',
        meist_handy: 'Meist über das Handy.', meist_display: 'Meist hier am Bildschirm.', meist_auto: 'Meist an den Tasten im Auto.',
        meist_cockpit: 'Meist über das Cockpit.', meist_ha: 'Meist über Home Assistant.', meist_telegram: 'Meist über Telegram.',
        meist_byd_app: 'Meist über die BYD-App.',
        probe_marke: 'PROBELAUF', probe_satz: 'Gerade hätte ich das gemacht. Passt das?',
        freigabe_satz: '{n}-mal gepasst. Ab jetzt wirklich machen?',
        aufraeumen_satz: '{n}-mal nicht gepasst. Diese Regel löschen?',
        fuss_probe: 'Zuerst nur Probelauf: Panoptes meldet sich, schaltet aber noch nichts.',
        ja_auto: 'Ja, automatisch', nein: 'Nein', spaeter: 'Später',
        passt: 'Passt', heute_nicht: 'Heute nicht',
        ja_ab_jetzt: 'Ja, ab jetzt', weiter_melden: 'Weiter nur melden', loeschen: 'Löschen', behalten: 'Behalten',
        fehler: 'Das hat nicht geklappt.', fehler_ort_unbekannt: 'Zuerst unter Orte das Zuhause festlegen.'
    };
    var SYM = {
        klima_an: '🔥', klima_aus: '❄️', klima_temp: '🌡️', sitzheizung: '💺', sitzlueftung: '💨', lenkradheizung: '🎡',
        wallbox_start: '🔌', wallbox_stopp: '🔌', wallbox_strom: '🔌', waechter_an: '👁️', camping_an: '🏕️', camping_aus: '🏕️'
    };
    var QUELLE_SYM = { handy: '📱', display: '🖥️', auto: '🚗', cockpit: '⌚', ha: '🏠', telegram: '✈️', byd_app: '📲' };

    function T(k, vars) {
        var key = 'gewohnheit.' + k, s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key, vars); if (v && v !== key) s = v; } } catch (e) {}
        s = s || DE[k] || k;
        if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) s = s.split('{' + n + '}').join(vars[n]);
        return s;
    }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function uhr(min) { var h = Math.floor(min / 60), m = min % 60; return h + ':' + (m < 10 ? '0' : '') + m; }
    function post(body) {
        return fetch('/api/gewohnheiten/antwort', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
            .then(function (r) { return r.json().catch(function () { return {}; }); });
    }

    /** Satz und Symbole aus den Bausteinen eines Vorschlags bzw. der Gewohnheit einer Regel. */
    function satz(g) {
        var w = g.wert || {}, aktion = g.aktion, min = typeof g.minute === 'number' ? g.minute : 0;
        var vars = {
            tag: T('tag_' + (g.tag === 'wochenende' ? 'wochenende' : 'werktag')),
            zeit: uhr(min),
            temp: w.temp, stufe: w.stufe, a: w.a,
            sitz: T('sitz_' + (w.sitz === 'beifahrer' ? 'beifahrer' : 'fahrer')),
            an: T(w.an ? 'an' : 'aus')
        };
        var s = T(aktion, vars);
        if (g.bedingung && typeof g.bedingung.aussen_unter === 'number') s = s.replace(/\?$/, ' (' + T('unter', { x: g.bedingung.aussen_unter }) + ')?');
        return s;
    }
    function symbole(g) {
        var w = g.wert || {}, box = el('div', 'gw-sym');
        function sym(z, klein) { var d = el('div', 'gw-s'); d.appendChild(el('span', 'gw-z', z)); if (klein != null) d.appendChild(el('small', null, String(klein))); box.appendChild(d); }
        var wert = w.temp != null ? w.temp + ' °C' : w.a != null ? w.a + ' A' : w.stufe != null ? T('sitz_' + (w.sitz === 'beifahrer' ? 'beifahrer' : 'fahrer')) : null;
        sym(SYM[g.aktion] || '⚙️', wert);
        sym('📅', T('sym_' + (g.tag === 'wochenende' ? 'wochenende' : 'werktag')));
        sym('⏰', uhr(g.minute || 0));
        if (g.quelle_meist && QUELLE_SYM[g.quelle_meist]) sym(QUELLE_SYM[g.quelle_meist], null);
        return box;
    }

    function stil() {
        if (document.getElementById('gwStil')) return;
        var css = '.gw-schleier{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.45);z-index:9000;display:flex;align-items:center;justify-content:center}'
            + '.gw-karte{width:860px;max-width:92vw;box-sizing:border-box;background:var(--surface,#1e262e);color:var(--text,#eef3f7);border-radius:28px;padding:36px 44px;box-shadow:0 20px 60px rgba(0,0,0,.5)}'
            + '.gw-kopf{display:flex;align-items:center;gap:26px;flex-wrap:wrap}.gw-sym{display:flex;flex-wrap:wrap;gap:14px}'
            + '.gw-s{width:100px;height:100px;border-radius:24px;background:rgba(127,127,127,.18);display:flex;flex-direction:column;align-items:center;justify-content:center}'
            + '.gw-z{font-size:48px;line-height:1}.gw-s small{font-size:19px;font-weight:700;margin-top:6px}'
            + '.gw-satz{font-size:36px;font-weight:700;line-height:1.15;flex:1;min-width:280px}.gw-grund{opacity:.7;font-size:21px;margin-top:10px}'
            + '.gw-marke{display:inline-block;background:#5aa9e6;color:#04121e;font-weight:800;border-radius:10px;padding:4px 12px;font-size:16px;margin-bottom:10px}'
            + '.gw-knoepfe{display:grid;grid-template-columns:2fr 1fr 1fr;gap:16px;margin-top:30px}.gw-knoepfe.zwei{grid-template-columns:1fr 1fr}'
            + '.gw-knoepfe button{border:0;border-radius:20px;min-height:100px;font-size:28px;font-weight:700;color:inherit;background:rgba(127,127,127,.25)}'
            + '.gw-knoepfe .gw-ja{background:#3fbf7f;color:#06140c}.gw-fuss{margin-top:16px;font-size:18px;opacity:.7;text-align:center}'
            + '@media (max-width:640px){.gw-karte{padding:20px;border-radius:22px}.gw-sym{gap:10px}.gw-s{width:72px;height:72px;border-radius:18px}.gw-z{font-size:34px}.gw-s small{font-size:15px}.gw-grund{font-size:17px}.gw-satz{font-size:26px}.gw-knoepfe{grid-template-columns:1fr}.gw-knoepfe button{min-height:70px;font-size:22px}}';
        var s = el('style'); s.id = 'gwStil'; s.textContent = css; document.head.appendChild(s);
    }

    var schleier = null, gezeigt = '';
    function weg() { if (schleier && schleier.parentNode) schleier.parentNode.removeChild(schleier); schleier = null; gezeigt = ''; }

    function zeige(k) {
        var g = k.art === 'vorschlag' ? k.vorschlag : k.gewohnheit;
        if (!g) { weg(); return; }
        var schl = k.art + '|' + (k.automation || g.schluessel || '');
        if (schl === gezeigt) return;
        weg();
        stil();
        gezeigt = schl;
        schleier = el('div', 'gw-schleier');
        var karte = el('div', 'gw-karte');
        var kopf = el('div', 'gw-kopf');
        kopf.appendChild(symbole(g));
        var text = el('div', 'gw-satz');
        if (k.art === 'probelauf') text.appendChild(el('div', 'gw-marke', T('probe_marke') + ' ' + ((g.richtig || 0) + 1) + ' / 3'));
        text.appendChild(el('div', null, k.art === 'probelauf' ? T('probe_satz')
            : k.art === 'freigabe' ? T('freigabe_satz', { n: g.richtig || 3 })
            : k.art === 'aufraeumen' ? T('aufraeumen_satz', { n: g.nicht || 3 }) : satz(g)));
        if (k.art !== 'vorschlag') text.appendChild(el('div', 'gw-grund', satz(g)));
        else {
            var grund = T('grund', { n: g.anzahl || (g.treffer ? g.treffer.length : 3) });
            if (g.quelle_meist) grund += ' ' + T('meist_' + g.quelle_meist);
            text.appendChild(el('div', 'gw-grund', grund));
        }
        kopf.appendChild(text);
        karte.appendChild(kopf);

        var knoepfe = [];
        if (k.art === 'vorschlag') knoepfe = [['ja', T('ja_auto'), 1], ['nein', T('nein')], ['spaeter', T('spaeter')]];
        else if (k.art === 'probelauf') knoepfe = [['passt', T('passt'), 1], ['nicht', T('heute_nicht')]];
        else if (k.art === 'freigabe') knoepfe = [['ja', T('ja_ab_jetzt'), 1], ['weiter', T('weiter_melden')], ['loeschen', T('loeschen')]];
        else knoepfe = [['loeschen', T('loeschen'), 1], ['behalten', T('behalten')]];
        var reihe = el('div', 'gw-knoepfe' + (knoepfe.length === 2 ? ' zwei' : ''));
        knoepfe.forEach(function (kn) {
            var b = el('button', kn[2] ? 'gw-ja' : null, kn[1]);
            b.type = 'button';
            b.addEventListener('click', function () {
                var body = { art: k.art, antwort: kn[0] };
                if (k.art === 'vorschlag') body.schluessel = g.schluessel; else body.automation = k.automation;
                post(body).then(function (r) {
                    if (r && r.ok) { weg(); laden(); return; }
                    var m = r && r.grund === 'ort_unbekannt' ? T('fehler_ort_unbekannt') : T('fehler');
                    if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, 'error');
                });
            });
            reihe.appendChild(b);
        });
        karte.appendChild(reihe);
        if (k.art === 'vorschlag') karte.appendChild(el('div', 'gw-fuss', T('fuss_probe')));
        schleier.appendChild(karte);
        document.body.appendChild(schleier);
    }

    function laden() {
        fetch('/api/gewohnheiten/karte', { cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .catch(function () { return null; })
            .then(function (k) {
                if (!k || k.art === 'keine' || !k.imStand) { weg(); return; }
                zeige(k);
            });
    }

    var gestartet = false;
    function init() {
        if (gestartet) return;
        gestartet = true;
        laden();
        setInterval(function () { if (!document.hidden) laden(); }, 60000);
        document.addEventListener('visibilitychange', function () { if (!document.hidden) laden(); });
    }

    window.PanoptesGewohnheit = { init: init, satz: satz };
})();
