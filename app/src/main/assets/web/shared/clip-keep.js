/**
 * Panoptes — Aufnahme „Behalten“ (Pflichttext B, PFLICHTTEXTE_WAECHTER.md; Kern 4ac034e).
 *
 * Aufnahmen werden nach der eingestellten Frist (Standard 72 h) gelöscht. „Behalten“ hebt eine
 * einzelne Aufnahme bis zu keepDays Tage auf. Vor dem Behalten erscheint immer der Pflichttext B.
 * Nach Ablauf fragt Panoptes nach (rueckfrage): „Weiter behalten“ oder „Löschen“.
 *
 *   PanoptesKeep.init({ banner: el, knopf: el, box: el })   // Seite einmal
 *   PanoptesKeep.setClip('event_20261021_021120.mp4')       // beim Öffnen einer Aufnahme
 *
 * API: GET /api/aufnahmen/behalten · POST /api/aufnahmen/behalten {datei} ·
 *      POST /api/aufnahmen/freigeben {datei}. Behalten gilt je Clip-Stamm (event_ und dvr_ getrennt).
 * Fehlt die API (älterer Daemon), bleibt alles ausgeblendet. Nichts am Auto wird ausgelöst.
 * ES5, Texte über BYD.i18n (guardLegal.keep.*, events.keep_*), deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    var DE = {
        'guardLegal.keep.body': 'Du behältst diese Aufnahme bis zu {keepDays} Tage. Bitte nur, wenn du sie für einen Schaden oder Unfall brauchst.',
        'guardLegal.keep.confirm': 'Behalten',
        'guardLegal.keep.reminder': 'Diese Aufnahme ist {keepDays} Tage alt. Brauchst du sie noch?',
        'guardLegal.keep.reminder_yes': 'Weiter behalten',
        'guardLegal.keep.reminder_no': 'Löschen',
        'events.keep_btn': 'Behalten',
        'events.keep_kept': 'Behalten bis {datum}',
        'events.keep_release': 'Nicht mehr behalten',
        'events.keep_cancel': 'Abbrechen',
        'events.keep_saved': 'Wird behalten',
        'events.keep_released': 'Wird mit dem nächsten Aufräumen gelöscht, wenn sie älter als {stunden} Stunden ist.',
        'events.keep_released_size': 'Nicht mehr behalten. Sie wird nur gelöscht, wenn der Speicher knapp wird.',
        'events.keep_unsaved': 'Konnte nicht gespeichert werden. Bitte noch einmal versuchen.',
        'events.keep_fail': 'Das hat nicht geklappt.'
    };

    function t(k, vars) {
        var s = null;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(k);
                if (v && v !== k) s = v;
            }
        } catch (e) {}
        if (s == null) s = DE[k] || k;
        if (vars) Object.keys(vars).forEach(function (n) { s = s.split('{' + n + '}').join(vars[n]); });
        return s;
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    function stamm(datei) { return String(datei || '').replace(/^.*\//, '').replace(/\.[a-z0-9]+$/i, ''); }

    function datum(ms) {
        try {
            var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de';
            return new Date(ms).toLocaleDateString(lang, { day: '2-digit', month: '2-digit', year: 'numeric' });
        } catch (e) { return new Date(ms).toLocaleDateString(); }
    }

    function toast(m, art) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, art); }

    function post(url, datei) {
        return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ datei: datei }) })
            .then(function (r) { return r.json().then(function (d) { return d || {}; }, function () { return { ok: false }; }); });
    }

    function injectStyle() {
        if (document.getElementById('pn-keep-style')) return;
        var s = document.createElement('style');
        s.id = 'pn-keep-style';
        s.textContent = [
            '.pn-keep-btn{display:inline-flex;align-items:center;justify-content:center;padding:10px 18px;border-radius:12px;border:1px solid var(--sm-line,rgba(255,255,255,.15));background:rgba(255,255,255,.075);color:var(--on-surface,#fff);font:600 14px var(--family-body,sans-serif);cursor:pointer;margin-left:8px}',
            '.pn-keep-btn.on{border-color:var(--sm-lime,#c6f432);color:var(--sm-lime,#c6f432)}',
            '.pn-keep-btn:disabled{opacity:.5;cursor:default}',
            '.pn-keep-box{margin-top:12px;padding:14px;border-radius:14px;border:1px solid var(--sm-line,rgba(255,255,255,.15));background:rgba(4,16,27,.6);color:var(--on-surface,#fff);font-size:14px}',
            '.pn-keep-box p{margin:0 0 10px}',
            '.pn-keep-row{display:flex;flex-wrap:wrap;align-items:center}',
            '.pn-keep-row>*{margin:0 8px 8px 0}',
            '.pn-keep-row button{padding:9px 14px;border-radius:12px;border:1px solid var(--sm-line,rgba(255,255,255,.15));background:rgba(255,255,255,.075);color:var(--on-surface,#fff);font:600 13.5px var(--family-body,sans-serif);cursor:pointer}',
            '.pn-keep-row button.lime{background:var(--sm-lime,#c6f432);color:#1d2a00;border-color:transparent}',
            '.pn-keep-banner{margin:0 0 14px}',
            '#keepBox{flex:1 1 100%;width:100%}',
            '.pn-keep-banner .pn-keep-box{margin:0 0 8px;border-color:var(--status-warning,#f5b942)}',
            '.pn-keep-banner b{display:block;margin-bottom:4px;word-break:break-all}'
        ].join('\n');
        document.head.appendChild(s);
    }

    var K = {
        liste: {}, tage: 30, stunden: 72, da: false, clip: null, ui: null,

        laden: function () {
            var self = this;
            return fetch('/api/aufnahmen/behalten', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
                .then(function (d) {
                    if (!d || !Array.isArray(d.behalten)) { self.da = false; self.zeichne(); return; }
                    self.da = true;
                    self.tage = d.tage || self.tage;
                    self.stunden = Number(d.stunden) || 0;   // 0 = keine Löschung nach Alter
                    self.liste = {};
                    d.behalten.forEach(function (b) { self.liste[stamm(b.datei)] = b; });
                    self.zeichne();
                }, function () { self.da = false; self.zeichne(); });
        },

        setClip: function (datei) {
            this.clip = datei || null;
            if (this.ui && this.ui.box) { this.ui.box.hidden = true; this.ui.box.innerHTML = ''; }
            this.zeichneKnopf();
        },

        zeichne: function () { this.zeichneKnopf(); this.zeichneBanner(); },

        zeichneKnopf: function () {
            var self = this, b = this.ui && this.ui.knopf;
            if (!b) return;
            b.hidden = !this.da || !this.clip;
            if (b.hidden) return;
            var k = this.liste[stamm(this.clip)];
            b.className = 'pn-keep-btn' + (k ? ' on' : '');
            b.disabled = false;
            b.textContent = k ? t('events.keep_kept', { datum: datum(k.bis) }) : t('events.keep_btn');
            b.onclick = function () { k ? self.frage(true) : self.frage(false); };
        },

        /** Pflichttext B vor dem Behalten; bei schon behaltenen Clips die Wahl „Nicht mehr behalten“. */
        frage: function (behalten) {
            var self = this, box = this.ui.box, datei = this.clip;
            box.innerHTML = '';
            var p = el('div', 'pn-keep-box');
            var row = el('div', 'pn-keep-row');
            if (!behalten) {
                p.appendChild(el('p', null, t('guardLegal.keep.body', { keepDays: this.tage })));
                var ok = el('button', 'lime', t('guardLegal.keep.confirm'));
                ok.type = 'button';
                ok.onclick = function () { ok.disabled = true; self.behalte(datei); };
                row.appendChild(ok);
            } else {
                var weg = el('button', null, t('events.keep_release'));
                weg.type = 'button';
                weg.onclick = function () { weg.disabled = true; self.freigeben(datei); };
                row.appendChild(weg);
            }
            var ab = el('button', null, t('events.keep_cancel'));
            ab.type = 'button';
            ab.onclick = function () { box.hidden = true; box.innerHTML = ''; };
            row.appendChild(ab);
            p.appendChild(row);
            box.appendChild(p);
            box.hidden = false;
        },

        behalte: function (datei) {
            var self = this;
            return post('/api/aufnahmen/behalten', datei).then(function (d) {
                if (d.ok) toast(t('events.keep_saved'), 'success');
                else toast(d.grund === 'nicht_gespeichert' ? t('events.keep_unsaved') : t('events.keep_fail'), 'error');
            }, function () { toast(t('events.keep_fail'), 'error'); }).then(function () {
                if (self.ui.box) { self.ui.box.hidden = true; self.ui.box.innerHTML = ''; }
                return self.laden();
            });
        },

        freigeben: function (datei) {
            var self = this;
            return post('/api/aufnahmen/freigeben', datei).then(function (d) {
                if (d.ok) toast(self.stunden > 0 ? t('events.keep_released', { stunden: self.stunden }) : t('events.keep_released_size'), 'success');
                else toast(d.grund === 'nicht_gespeichert' ? t('events.keep_unsaved') : t('events.keep_fail'), 'error');
            }, function () { toast(t('events.keep_fail'), 'error'); }).then(function () {
                if (self.ui.box) { self.ui.box.hidden = true; self.ui.box.innerHTML = ''; }
                return self.laden();
            });
        },

        /** Rückfrage nach Ablauf: oben auf der Seite, je Aufnahme. */
        zeichneBanner: function () {
            var self = this, ban = this.ui && this.ui.banner;
            if (!ban) return;
            ban.innerHTML = '';
            var offen = Object.keys(this.liste).filter(function (s) { return self.liste[s].rueckfrage; });
            ban.hidden = !this.da || !offen.length;
            offen.forEach(function (s) {
                var b = self.liste[s];
                var box = el('div', 'pn-keep-box');
                box.appendChild(el('b', null, s));
                box.appendChild(el('p', null, t('guardLegal.keep.reminder', { keepDays: self.tage })));
                var row = el('div', 'pn-keep-row');
                var ja = el('button', 'lime', t('guardLegal.keep.reminder_yes'));
                ja.type = 'button';
                ja.onclick = function () { ja.disabled = true; self.behalte(b.datei); };
                var nein = el('button', null, t('guardLegal.keep.reminder_no'));
                nein.type = 'button';
                nein.onclick = function () { nein.disabled = true; self.freigeben(b.datei); };
                row.appendChild(ja);
                row.appendChild(nein);
                box.appendChild(row);
                ban.appendChild(box);
            });
        },

        init: function (ui) {
            injectStyle();
            this.ui = ui || {};
            if (this.ui.banner) this.ui.banner.className = 'pn-keep-banner';
            return this.laden();
        }
    };

    window.PanoptesKeep = K;
})();
