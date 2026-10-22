/**
 * Panoptes — „Was ist neu“ (H10) nach jedem Update einmal anzeigen, dazu ein Hinweis bei geänderter BYD-Firmware.
 *
 * Texte: shared/neuigkeiten.json (eine Quelle, auch für die Release-Notes in version.json).
 * Stand „gesehen“:
 *   - mit Kern-API (NEUBAU2_API_VORSCHLAG.md §2): GET /api/neuigkeiten, POST /api/neuigkeiten/gesehen
 *     → einmal je Auto, auch am Mittelschirm.
 *   - ohne (älteres Panoptes): Version aus /status, Firmware aus /api/about/vehicle-info, gesehen im Browser.
 *
 *   PanoptesNeu.pruefen()          Dialog zeigen, falls etwas Neues da ist (app-shell ruft das auf)
 *   PanoptesNeu.liste(container)   Notizen der laufenden Version (Über-Seite)
 *
 * Wartet, bis der Haftungs-Dialog (hinweise.js) zu ist. Nichts am Auto wird ausgelöst. ES5.
 */
(function () {
    'use strict';

    var LS_VER = 'pn.neu.version', LS_FW = 'pn.neu.firmware';
    var DE = {
        'whatsnew.title': 'Neu in Panoptes {v}',
        'whatsnew.ok': 'Alles klar',
        'whatsnew.fw_title': 'Die Software deines Autos hat sich geändert',
        'whatsnew.fw_body': 'Neue Firmware {neu} (vorher {alt}). Panoptes prüft seine Funktionen neu. Falls etwas fehlt oder anders ist, melde es bitte.',
        'whatsnew.fw_body_short': 'Neue Firmware {neu}. Panoptes prüft seine Funktionen neu. Falls etwas fehlt oder anders ist, melde es bitte.',
        'whatsnew.about_none': 'Für diese Version gibt es keine Notizen.'
    };

    function t(k, v) {
        var s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { s = BYD.i18n.t(k); if (s === k) s = null; } } catch (e) {}
        s = s || DE[k] || '';
        if (v) s = s.replace(/\{(\w+)\}/g, function (m, n) { return v[n] != null ? v[n] : m; });
        return s;
    }
    function lang() { try { return BYD.i18n.getLang() || 'de'; } catch (e) { return 'de'; } }
    function ls(k, v) {
        try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) {}
        return null;
    }
    function json(url) {
        return fetch(url, { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; }, function () { return null; });
    }
    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }

    var texte = null;
    function ladeTexte() {
        if (texte) return Promise.resolve(texte);
        return json('../shared/neuigkeiten.json').then(function (d) { texte = (d && d.versionen) || []; return texte; });
    }
    function zeilen(e) {
        var l = lang();
        return (e && (e[l] || e.en || e.de)) || [];
    }
    function eintrag(versionCode, version) {
        for (var i = 0; i < texte.length; i++) {
            var e = texte[i];
            if ((versionCode && e.versionCode === versionCode) || (!versionCode && version && e.version === version)) return e;
        }
        return null;
    }

    // Was soll gezeigt werden? → {versionen:[eintrag], firmware:{neu,alt}|null, server:bool, versionCode, version, fw}
    function ermitteln() {
        return Promise.all([ladeTexte(), json('/api/neuigkeiten')]).then(function (r) {
            var api = r[1];
            if (api && api.versionCode) {
                var seit = Number(api.gesehenVersionCode) || 0;
                var neu = texte.filter(function (e) {
                    return seit ? e.versionCode > seit && e.versionCode <= api.versionCode : e.versionCode === api.versionCode;
                }).sort(function (a, b) { return b.versionCode - a.versionCode; }).slice(0, 3);
                var fw = api.firmware && api.firmware.vorher && !api.firmware.gesehen && api.firmware.jetzt !== api.firmware.vorher
                    ? { neu: api.firmware.jetzt, alt: api.firmware.vorher } : null;
                return { versionen: neu, firmware: fw, server: true, versionCode: api.versionCode, version: api.version };
            }
            // Rückfall ohne Kern-API: gesehen nur in diesem Browser.
            return Promise.all([json('/status'), json('/api/about/vehicle-info')]).then(function (x) {
                var ver = x[0] && x[0].appVersion, fwJetzt = x[1] && x[1].success && x[1].firmware;
                var e = ver && ls(LS_VER) !== ver ? eintrag(null, ver) : null;
                var fwAlt = ls(LS_FW);
                if (fwJetzt && !fwAlt) ls(LS_FW, fwJetzt);   // erster Besuch: nur merken
                if (ver && !e) ls(LS_VER, ver);               // keine Notizen für diese Version: als gesehen merken
                var fw = fwJetzt && fwAlt && fwAlt !== fwJetzt ? { neu: fwJetzt, alt: fwAlt } : null;
                return { versionen: e ? [e] : [], firmware: fw, server: false, version: ver, fw: fwJetzt };
            });
        });
    }

    function gesehen(s) {
        if (s.server) {
            var b = { versionCode: s.versionCode };
            if (s.firmware) b.firmware = true;
            return fetch('/api/neuigkeiten/gesehen', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) })
                .then(function () {}, function () {});
        }
        if (s.version) ls(LS_VER, s.version);
        if (s.fw) ls(LS_FW, s.fw);
        return Promise.resolve();
    }

    function dialog(s) {
        if (document.getElementById('pnNeu')) return;
        var bg = el('div', 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:9999;background:rgba(2,10,18,.72);display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box;');
        bg.id = 'pnNeu';
        bg.setAttribute('role', 'dialog');
        bg.setAttribute('aria-modal', 'true');
        var box = el('div', 'max-width:540px;width:100%;background:var(--surface-container,#0F2B40);border:1px solid var(--sm-line,rgba(255,255,255,.15));border-radius:20px;padding:22px;color:var(--on-surface,#fff);font:15px/1.5 var(--family-body,sans-serif);box-sizing:border-box;max-height:90vh;overflow:auto;');
        s.versionen.forEach(function (e, i) {
            box.appendChild(el('h2', 'font:700 20px var(--family-display,sans-serif);margin:' + (i ? '16px' : '0') + ' 0 8px;', t('whatsnew.title', { v: e.version })));
            var ul = el('ul', 'margin:0;padding-left:20px;');
            zeilen(e).forEach(function (z) { ul.appendChild(el('li', 'margin:0 0 6px;', z)); });
            box.appendChild(ul);
        });
        if (s.firmware) {
            var fwb = el('div', 'margin:' + (s.versionen.length ? '16px' : '0') + ' 0 0;padding:12px 14px;border:1px solid var(--status-warning,#f5b942);border-radius:12px;');
            fwb.appendChild(el('div', 'font-weight:700;margin:0 0 4px;', t('whatsnew.fw_title')));
            fwb.appendChild(el('div', 'font-size:14px;', s.firmware.alt ? t('whatsnew.fw_body', { neu: s.firmware.neu, alt: s.firmware.alt })
                : t('whatsnew.fw_body_short', { neu: s.firmware.neu })));
            box.appendChild(fwb);
        }
        var btn = el('button', 'margin-top:18px;border:0;border-radius:12px;padding:12px 26px;min-height:48px;font:700 16px var(--family-body,sans-serif);background:var(--sm-lime,#c6f432);color:#1d2a00;cursor:pointer;', t('whatsnew.ok'));
        btn.type = 'button';
        btn.onclick = function () {
            btn.disabled = true;
            gesehen(s).then(function () { if (bg.parentNode) bg.parentNode.removeChild(bg); });
        };
        box.appendChild(btn);
        bg.appendChild(box);
        document.body.appendChild(bg);
        btn.focus();
    }

    function pruefen() {
        return ermitteln().then(function (s) {
            if (!s.versionen.length && !s.firmware) return;
            var versuche = 0;
            (function warte() {   // erst nach dem Haftungs-Dialog
                if (document.getElementById('pnHinweise') && versuche++ < 120) { setTimeout(warte, 1000); return; }
                dialog(s);
            })();
        }, function () {});
    }

    /** Notizen der laufenden Version in einen Container schreiben (Über-Seite). */
    function liste(box) {
        return Promise.all([ladeTexte(), json('/api/neuigkeiten'), json('/status')]).then(function (r) {
            var api = r[1], st = r[2];
            var e = api && api.versionCode ? eintrag(api.versionCode) : eintrag(null, st && st.appVersion);
            box.innerHTML = '';
            var z = zeilen(e);
            if (!z.length) { box.appendChild(el('div', null, t('whatsnew.about_none'))); return; }
            var ul = el('ul', 'margin:0;padding-left:20px;');
            z.forEach(function (x) { ul.appendChild(el('li', 'margin:0 0 6px;', x)); });
            box.appendChild(ul);
        });
    }

    window.PanoptesNeu = { pruefen: pruefen, liste: liste };
})();
