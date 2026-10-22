/**
 * Panoptes — Pflichtwahl „Wann soll der Wächter aufnehmen?“ (Text C, PFLICHTTEXTE_WAECHTER.md).
 *
 * Alfreds Vorgabe (21.10.2026): Die Nutzer entscheiden selbst.
 *  - keine Vorauswahl; „Auswahl speichern“ erst nach einer Wahl (sonst Hinweis „Bitte wähle …“)
 *  - (a) nur Erschütterung · (b) KI-Erkennung überall · (c) KI nur an eigenen privaten Orten
 *  - in der Schweiz ist (b) gesperrt (ausgegraut, Hinweis)
 *  - (c) ohne angelegten Ort: Hinweis „nur bei Erschütterung, bis ein Ort festgelegt ist“
 *  - bei jeder Änderung erscheint derselbe Text erneut (der Baustein zeigt ihn immer ganz)
 *
 * Speichern ist eingesteckt (opts.onSave), damit Web-Seite, Einrichtung und Schema-Seiten
 * denselben Baustein nutzen. Der Baustein löst nichts am Auto aus.
 *
 *   PanoptesTrigger.render(container, {
 *       value: null | 'shock' | 'ai_everywhere' | 'ai_private' | 'aus',
 *       mitAus: true,                           // vierte Wahl „Wächter aus“ zeigen (Schema-Option „aus“)
 *       country: 'AT' | 'DE' | 'CH' | …,
 *       privatePlaces: 0,                       // Anzahl angelegter privater Orte
 *       blocked: { ai_everywhere: 'Grund' },    // vom Server gesperrte Optionen (Schema „gesperrt“)
 *       onPlaces: function () {},               // „Private Orte festlegen“
 *       onChange: function (value) {},          // Auswahl geändert (noch nicht gespeichert)
 *       onSave: function (value) { return Promise }
 *   })
 *
 * ES5, Texte über BYD.i18n (Schlüssel guardLegal.trigger.*), deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    var DE = {
        title: 'Wann soll der Wächter aufnehmen?',
        intro: 'Du entscheidest selbst. Die KI-Erkennung nimmt Video und Foto auf, sobald sich jemand dem Auto nähert. So siehst du auch, wer nur nachgeschaut hat. Dabei filmt sie aber auch Leute, die nur vorbeigehen.',
        'a.label': 'Nur bei Erschütterung',
        'a.desc': 'Aufnahme nur, wenn jemand das Auto anstößt oder beschädigt. Überall unbedenklich.',
        'b.label': 'KI-Erkennung überall',
        'b.desc': 'Aufnahme auch bei Annäherung, egal wo du parkst. Auf Straßen und öffentlichen Parkplätzen kann das ein Bußgeld kosten, weil fremde Menschen gefilmt werden. In der Schweiz nicht möglich.',
        'c.label': 'KI-Erkennung nur an meinen privaten Orten',
        'c.desc': 'Aufnahme bei Annäherung nur dort, wo du es festlegst, z. B. in deiner Garage oder Einfahrt. Sonst nur bei Erschütterung. Auf eigenem Grund ist das in der Regel erlaubt, solange die Kamera nicht die Straße oder das Nachbargrundstück mitfilmt.',
        'c.places': 'Private Orte festlegen',
        'c.noPlaces': 'Noch kein privater Ort festgelegt. Bis dahin nimmt der Wächter nur bei Erschütterung auf.',
        'b.note': 'Außerhalb deines Privatgrunds bist du selbst dafür verantwortlich, dass die Aufnahme erlaubt ist.',
        // ersetzt den früheren footer (DISCLAIMER.md, Abschnitt 3); Schlüssel disclaimer.guard.body
        guardBody: 'Der Wächter ist für deinen eigenen Privatgrund gedacht. Richte die Kameras so aus, dass Straße, Gehweg und Nachbargrund nicht im Bild sind. Für die Aufnahmen und ihre Weitergabe bist du allein verantwortlich; soweit gesetzlich zulässig, übernimmt der Entwickler keine Haftung.',
        confirm: 'Auswahl speichern',
        required: 'Bitte wähle eine Option.',
        blockedCH: 'In der Schweiz nicht möglich.',
        'aus.label': 'Wächter aus – nimmt nichts auf'
    };
    var OPTIONS = [
        { value: 'shock', key: 'a' },
        { value: 'ai_everywhere', key: 'b' },
        { value: 'ai_private', key: 'c' },
        { value: 'aus', key: 'aus', nurMitAus: true }   // 0.1.9.1: vierte Wahl, nur wenn der Kern sie anbietet
    ];

    function t(k) {
        var key = k === 'guardBody' ? 'disclaimer.guard.body' : 'guardLegal.trigger.' + k;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key);
                if (v && v !== key) return v;
            }
        } catch (e) {}
        return DE[k] || '';
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    function injectStyle() {
        if (document.getElementById('pn-trigger-style')) return;
        var s = document.createElement('style');
        s.id = 'pn-trigger-style';
        s.textContent = [
            '.pn-trig h3{font-family:var(--family-display,sans-serif);font-size:18px;margin:0 0 8px;color:var(--on-surface)}',
            '.pn-trig .intro,.pn-trig .foot{color:var(--on-surface-variant);font-size:13.5px;margin:0 0 12px}',
            '.pn-trig label.opt{display:flex;align-items:flex-start;padding:12px;border:1px solid var(--sm-line);border-radius:14px;margin:0 0 10px;cursor:pointer}',
            '.pn-trig label.opt.on{border-color:var(--sm-aqua);background:rgba(95,214,230,.08)}',
            '.pn-trig label.opt.off{opacity:.5;cursor:not-allowed}',
            '.pn-trig label.opt input{margin:4px 12px 0 0;flex:none}',
            '.pn-trig label.opt b{display:block;color:var(--on-surface);font-size:14.5px}',
            '.pn-trig label.opt small{display:block;color:var(--on-surface-variant);font-size:12.5px;margin-top:3px}',
            '.pn-trig .warn{color:var(--status-warning);font-size:12.5px;margin-top:6px}',
            '.pn-trig .row{display:flex;flex-wrap:wrap;align-items:center;margin-top:6px}',
            '.pn-trig .row>*{margin:0 10px 8px 0}',
            '.pn-trig button{border:1px solid var(--sm-line);background:rgba(255,255,255,.075);color:var(--on-surface);border-radius:12px;padding:10px 14px;font:600 13.5px var(--family-body,sans-serif);cursor:pointer}',
            '.pn-trig button.lime{background:var(--sm-lime);color:#1d2a00;border-color:transparent}',
            '.pn-trig button:disabled{opacity:.5;cursor:default}',
            '.pn-trig .msg{font-size:13px;color:var(--on-surface-variant)}'
        ].join('\n');
        document.head.appendChild(s);
    }

    function render(container, opts) {
        opts = opts || {};
        injectStyle();
        var country = String(opts.country || '').toUpperCase();
        var chosen = null;   // bewusst KEINE Vorauswahl – auch nicht der gespeicherte Wert
        var saved = opts.value || null;

        container.innerHTML = '';
        var box = el('div', 'pn-trig');
        box.appendChild(el('h3', null, t('title')));
        box.appendChild(el('p', 'intro', t('intro')));

        var labels = [];
        OPTIONS.forEach(function (o) {
            if (o.nurMitAus && !opts.mitAus) return;
            var serverGrund = opts.blocked && opts.blocked[o.value];
            var blocked = !!serverGrund || (o.value === 'ai_everywhere' && country === 'CH');
            var lab = el('label', 'opt' + (blocked ? ' off' : ''));
            var inp = document.createElement('input');
            inp.type = 'radio';
            inp.name = 'pn-trigger';
            inp.value = o.value;
            inp.disabled = blocked;
            var txt = el('span');
            txt.appendChild(el('b', null, t(o.key + '.label') + (saved === o.value ? ' ✓' : '')));
            if (o.key !== 'aus') txt.appendChild(el('small', null, t(o.key + '.desc')));
            if (o.value === 'ai_everywhere') txt.appendChild(el('small', null, t('b.note')));
            if (blocked) txt.appendChild(el('div', 'warn', typeof serverGrund === 'string' ? serverGrund : t('blockedCH')));
            if (o.value === 'ai_private') {
                var noPlaces = el('div', 'warn', t('c.noPlaces'));
                noPlaces.hidden = (opts.privatePlaces || 0) > 0;
                noPlaces.setAttribute('data-noplaces', '1');
                txt.appendChild(noPlaces);
                if (typeof opts.onPlaces === 'function') {
                    var pl = el('button', null, t('c.places'));
                    pl.type = 'button';
                    pl.style.marginTop = '8px';
                    pl.onclick = function (ev) { ev.preventDefault(); opts.onPlaces(); };
                    txt.appendChild(pl);
                }
            }
            lab.appendChild(inp);
            lab.appendChild(txt);
            inp.onchange = function () {
                chosen = inp.value;
                labels.forEach(function (l) { l.classList.toggle('on', l === lab); });
                msg.textContent = '';
                save.disabled = false;
                if (typeof opts.onChange === 'function') opts.onChange(chosen);
            };
            labels.push(lab);
            box.appendChild(lab);
        });

        box.appendChild(el('p', 'foot', t('guardBody')));
        var row = el('div', 'row');
        var save = el('button', 'lime', t('confirm'));
        save.type = 'button';
        save.disabled = true;
        var msg = el('span', 'msg');
        save.onclick = function () {
            if (!chosen) { msg.textContent = t('required'); return; }
            if (typeof opts.onSave !== 'function') return;
            save.disabled = true;
            Promise.resolve(opts.onSave(chosen)).then(function () {
                saved = chosen;
                msg.textContent = '✓';
            }, function () {
                msg.textContent = '✗';
                save.disabled = false;
            });
        };
        row.appendChild(save);
        row.appendChild(msg);
        box.appendChild(row);
        container.appendChild(box);

        return {
            chosen: function () { return chosen; },
            setPrivatePlaces: function (n) {
                var w = box.querySelector('[data-noplaces]');
                if (w) w.hidden = n > 0;
            }
        };
    }

    window.PanoptesTrigger = { render: render, OPTIONS: OPTIONS.map(function (o) { return o.value; }) };
})();
