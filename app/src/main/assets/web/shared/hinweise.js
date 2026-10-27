/**
 * Panoptes — Haftungshinweise (DISCLAIMER.md, Koordination 21.10.2026).
 *
 *  - PanoptesHinweise.volltext(container): Grundtext (disclaimer.full.*) für die Über-Seite.
 *  - PanoptesHinweise.pruefen(): Pflicht-Bestätigung mit Häkchen, einmal je Textversion.
 *    Gespeichert wird im Daemon über das Einstellungs-Schema (Feld „hinweise.version“ mit
 *    bestaetigung, der Server setzt den Zeitpunkt). Fehlt das Feld (älterer Daemon), wird
 *    nicht gefragt. Bestehende Installationen bekommen die Abfrage beim nächsten Öffnen einmal.
 *
 * Die Textversion steht in disclaimer.version (i18n); ändert sich der Text, wird sie erhöht.
 * ES5, deutscher Rückfall eingebaut. Nichts am Auto wird ausgelöst.
 */
(function () {
    'use strict';

    var VERSION = '2026-10-21';
    var FELD = 'hinweise.version';
    var DE = {
        'disclaimer.full.title': 'Wichtige Hinweise',
        'disclaimer.setup.title': 'Bevor es losgeht',
        'disclaimer.setup.body': 'Panoptes ist ein privates Freizeitprojekt, kostenlos und ohne Gewähr. Du nutzt es auf eigenes Risiko, nur privat mit deinem eigenen Auto. Du bist selbst dafür verantwortlich, dass deine Nutzung erlaubt ist, vor allem bei Kamera-Aufnahmen, Datenschutz und Fernbefehlen. Panoptes ist nicht mit BYD verbunden.',
        'disclaimer.setup.link': 'Alle Hinweise lesen',
        'disclaimer.setup.checkbox': 'Gelesen und einverstanden',
        'disclaimer.setup.required': 'Bitte bestätige die Hinweise, um fortzufahren.',
        'disclaimer.setup.continue': 'Weiter'
    };

    function t(k) {
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(k);
                if (v && v !== k) return v;
            }
        } catch (e) {}
        return DE[k] || '';
    }

    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }

    function version() {
        if (aktuell) return aktuell;
        var v = t('disclaimer.version');
        return v && v !== 'disclaimer.version' ? v : VERSION;
    }

    /** Grundtext als nummerierte Liste; der erste Satz jedes Punkts fett. */
    function volltext(box) {
        box.innerHTML = '';
        var ol = el('ol', 'margin:0;padding-left:20px;');
        for (var i = 1; i <= 8; i++) {
            var s = t('disclaimer.full.p' + i);
            if (!s) continue;
            var li = el('li', 'margin:0 0 8px;');
            var dot = s.indexOf('. ');
            if (dot > 0 && dot < 60) {
                li.appendChild(el('strong', null, s.substring(0, dot + 1)));
                li.appendChild(document.createTextNode(' ' + s.substring(dot + 2)));
            } else li.textContent = s;
            ol.appendChild(li);
        }
        box.appendChild(ol);
    }

    function modal(etag) {
        if (document.getElementById('pnHinweise')) return;
        var bg = el('div', 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:10000;background:rgba(2,10,18,.78);display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box;');
        bg.id = 'pnHinweise';
        bg.setAttribute('role', 'dialog');
        bg.setAttribute('aria-modal', 'true');
        var box = el('div', 'max-width:520px;width:100%;background:var(--sm-card,#0d2233);border:1px solid var(--sm-line,rgba(255,255,255,.15));border-radius:20px;padding:22px;color:var(--on-surface,#fff);font:14.5px/1.5 var(--family-body,sans-serif);box-sizing:border-box;max-height:90vh;overflow:auto;');
        box.appendChild(el('h2', 'font:700 20px var(--family-display,sans-serif);margin:0 0 10px;', t('disclaimer.setup.title')));
        box.appendChild(el('p', 'margin:0 0 10px;color:var(--on-surface-variant,#cfe);', t('disclaimer.setup.body')));
        var a = el('a', 'color:var(--sm-aqua,#5fd6e6);font-weight:600;', t('disclaimer.setup.link'));
        a.href = 'about.html#hinweise';
        box.appendChild(el('p', 'margin:0 0 14px;')).appendChild(a);
        var lab = el('label', 'display:flex;align-items:center;cursor:pointer;font-weight:600;margin:0 0 12px;');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.style.cssText = 'width:22px;height:22px;margin:0 10px 0 0;';
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(t('disclaimer.setup.checkbox')));
        box.appendChild(lab);
        var msg = el('div', 'color:var(--status-warning,#f5b942);font-size:13px;min-height:18px;margin:0 0 8px;');
        box.appendChild(msg);
        var btn = el('button', 'border:0;border-radius:12px;padding:12px 22px;font:700 15px var(--family-body,sans-serif);background:var(--sm-lime,#c6f432);color:#1d2a00;cursor:pointer;', t('disclaimer.setup.continue'));
        btn.type = 'button';
        btn.onclick = function () {
            if (!cb.checked) { msg.textContent = t('disclaimer.setup.required'); return; }
            btn.disabled = true;
            var werte = {}; werte[FELD] = version();
            fetch('/api/einstellungen', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ etag: etag, werte: werte, bestaetigt: [FELD] }) })
                .then(function (r) { return r.json().then(function (d) { return { s: r.status, d: d || {} }; }); })
                .then(function (r) {
                    if (r.s === 409) return pruefen();           // anderswo geändert: neu lesen
                    if (r.d.gespeichert && Object.prototype.hasOwnProperty.call(r.d.gespeichert, FELD)) {
                        bg.parentNode.removeChild(bg);
                        sitzungOk();
                        return;
                    }
                    msg.textContent = (r.d.fehler && r.d.fehler[FELD]) || 'Fehler';
                    btn.disabled = false;
                }, function () { btn.disabled = false; });
        };
        box.appendChild(btn);
        bg.appendChild(box);
        document.body.appendChild(bg);
        cb.focus();
    }

    /** Fragt, wenn die bestätigte Version fehlt oder älter ist. Ohne Schema-Feld: nichts. */
    var aktuell = null;   // vom Daemon (Schema-Merkmal „aktuell“), sonst disclaimer.version

    function aktuelleVersion() {
        return fetch('/api/einstellungen/schema', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
            .then(function (s) {
                (s && s.gruppen || []).forEach(function (g) {
                    (g.felder || []).forEach(function (f) { if (f.key === FELD && f.aktuell) aktuell = String(f.aktuell); });
                });
            }, function () {});
    }

    // 0.1.20 Tempo: bestätigt → für diese Sitzung nicht mehr laden (app-shell liest den Merker)
    function sitzungOk() { try { window.sessionStorage.setItem('pn.hinweise.ok', '1'); } catch (e) {} }

    function pruefen() {
        return Promise.all([
            fetch('/api/einstellungen', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; }),
            aktuelleVersion()
        ]).then(function (res) {
                var d = res[0];
                var w = d && d.werte && d.werte[FELD];
                if (!w) return;
                if (w.gesetzt && w.wert === version()) { sitzungOk(); return; }
                var ex = document.getElementById('pnHinweise');
                if (ex) ex.parentNode.removeChild(ex);
                modal(d.etag || '');
            }, function () {});
    }

    window.PanoptesHinweise = { volltext: volltext, pruefen: pruefen, VERSION: VERSION };
})();
