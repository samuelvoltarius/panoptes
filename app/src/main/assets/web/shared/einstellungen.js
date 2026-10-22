/**
 * Panoptes — Einstellungen aus dem Schema zeichnen (Vertrag EINSTELLUNGEN_SCHEMA.md v1.1).
 *
 * Eine Quelle für alle Einstellungen: Titel, Hilfen, Grenzen, Sichtbarkeit und Reihenfolge kommen
 * vom Daemon (GET /api/einstellungen/schema). Diese Datei kennt keine einzelnen Felder.
 *
 *   PanoptesEinstellungen.laden().then(function (store) {
 *       if (!store) …                       // Daemon ohne Schema-API (älteres Update)
 *       store.zeichne(container, { gruppe: 'waechter', abschnitt: 'stopp', ohne: ['…'],
 *                                  nur: ['…'],              // nur diese Schlüssel zeichnen
 *                                  einschalten: {key: 72},  // nullIstAus: Wert beim Einschalten (sonst empfohlen/Standard)
 *                                  immer: ['…'],            // trotz sichtbar:false zeigen (Seite entscheidet)
 *                                  standort: function () { return Promise<{lat,lng}> } });
 *       store.wert('surveillance.triggerMode')    // { wert, gesetzt, wirksam }
 *       store.feld('surveillance.triggerMode')    // Schema-Feld
 *       store.speichern({ key: wert }, ['key'])   // PUT mit etag, danach zurücklesen
 *       store.beiAenderung(function () { … })
 *   });
 *
 * Regeln: Über diese API gehen keine Befehle ans Auto, nur gespeicherte Einstellungen.
 * Nichts wird beim Öffnen geschrieben. Felder mit „gefahr“ fragen vor dem Einschalten nach.
 * Zahlen mit „nullIstAus“ bekommen einen Schalter (aus = 0).
 * Felder mit „bestaetigung“ (Pflichttext) zeichnet diese Datei nicht – dafür gibt es eigene Bausteine
 * (z. B. guard-trigger.js).
 *
 * ES5, Texte der Bedienelemente über BYD.i18n (settings.ui.*), deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    var DE = {
        more: 'Mehr Einstellungen',
        less: 'Weniger Einstellungen',
        save: 'Speichern',
        saved: 'Gespeichert',
        save_fail: 'Konnte nicht speichern',
        stale: 'Inzwischen woanders geändert. Die Seite zeigt jetzt den neuen Stand, bitte noch einmal.',
        restart: 'Greift nach einem Neustart von Panoptes.',
        danger: 'Das wirkt sichtbar oder hörbar am Auto, wenn der Wächter anschlägt. Wirklich einschalten?',
        readonly: 'Nur anzeigen',
        set: 'Gesetzt',
        not_set: 'Nicht gesetzt',
        secret_new: 'Neu eingeben',
        secret_clear: 'Löschen',
        add_window: '+ Zeit hinzufügen',
        add_place: '+ Ort hinzufügen',
        remove: 'Entfernen',
        days: 'Mo,Di,Mi,Do,Fr,Sa,So',
        place_name: 'Name, z. B. Garage',
        place_here: 'Standort des Autos übernehmen',
        place_none: 'Noch kein Ort angelegt.',
        place_nopos: 'Der Standort des Autos ist gerade nicht bekannt.',
        place_radius: 'Umkreis',
        max_entries: 'Höchstens {n} Einträge.',
        'err.min': 'Mindestens {n}',
        'err.max': 'Höchstens {n}',
        'err.typ': 'Ungültiger Wert',
        'err.option': 'Ungültige Auswahl',
        'err.unbekannt': 'Diese Einstellung kennt Panoptes nicht',
        'err.nurLesendExtern': 'Nur am Auto änderbar',
        'err.bestaetigungFehlt': 'Bitte zuerst den Hinweis bestätigen',
        'err.konflikt': 'Licht und Hupe gehen nicht gleichzeitig',
        'err.veraltet': 'Inzwischen woanders geändert. Bitte noch einmal.',
        'err.gesperrt': 'Hier nicht möglich',
        'auswahl_auto': 'Automatisch (Standard)',
        'auswahl_auto_jetzt': 'Automatisch (Standard) – jetzt {v}',
        'err.radius': 'Umkreis zwischen {a} und {b} m'
    };

    function t(k, vars) {
        var key = 'settings.ui.' + k;
        var s = null;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key);
                if (v && v !== key) s = v;
            }
        } catch (e) {}
        if (s == null) s = DE[k] || '';
        if (vars) Object.keys(vars).forEach(function (n) { s = s.split('{' + n + '}').join(vars[n]); });
        return s;
    }

    function fehlerText(g) {
        g = String(g || '');
        var m = /^(min|max) (.+)$/.exec(g);
        if (m) return t('err.' + m[1], { n: zahlText(m[2]) });
        m = /^radiusM ([\d.]+)-([\d.]+)$/.exec(g);
        if (m) return t('err.radius', { a: zahlText(m[1]), b: zahlText(m[2]) });
        return DE['err.' + g] ? t('err.' + g) : (g || t('save_fail'));
    }

    function zahlText(v) {
        var n = Number(v);
        if (!isFinite(n)) return String(v);
        var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de';
        try { return n.toLocaleString(lang, { maximumFractionDigits: 2 }); } catch (e) { return String(n); }
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    function json(r) {
        return r.json().then(function (d) { d = d || {}; d._status = r.status; return d; });
    }

    function sprache() {
        try { return (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || ''; } catch (e) { return ''; }
    }

    function injectStyle() {
        if (document.getElementById('pn-einst-style')) return;
        var s = document.createElement('style');
        s.id = 'pn-einst-style';
        s.textContent = [
            '.pn-es .r{display:flex;align-items:center;padding:12px 0;border-top:1px solid var(--sm-line)}',
            '.pn-es .r:first-child{border-top:0}',
            '.pn-es .r.blk{display:block}',
            '.pn-es .tx{flex:1;min-width:0;padding-right:12px}',
            '.pn-es .tx b{display:block;font-weight:600;color:var(--on-surface);font-size:14.5px}',
            '.pn-es .tx small{display:block;color:var(--outline);font-size:12.5px;margin-top:2px}',
            '.pn-es .tx .er{color:var(--status-error,#ff8a80);font-size:12.5px;margin-top:4px}',
            '.pn-es .tx .ok{color:var(--sm-lime);font-size:12.5px;margin-top:4px}',
            '.pn-es .vl{display:flex;align-items:center;flex:none}',
            '.pn-es input[type=number],.pn-es input[type=text],.pn-es input[type=password],.pn-es select,.pn-es input[type=time]{background:rgba(255,255,255,.07);border:1px solid var(--sm-line);border-radius:10px;color:var(--on-surface);font:600 14px var(--family-body,sans-serif);padding:7px 8px}',
            '.pn-es input[type=number]{width:78px;text-align:right}',
            '.pn-es select option{color:#000}',
            '.pn-es .un{margin-left:6px;color:var(--on-surface-variant);font-size:13px}',
            '.pn-es .ro{color:var(--on-surface-variant);font:600 13.5px var(--family-body,sans-serif)}',
            '.pn-es .sw{position:relative;width:46px;height:28px;flex:none;display:inline-block}',
            '.pn-es .sw input{opacity:0;width:0;height:0;position:absolute}',
            '.pn-es .sw i{position:absolute;top:0;left:0;right:0;bottom:0;border-radius:999px;background:rgba(255,255,255,.12);transition:background .2s;cursor:pointer}',
            '.pn-es .sw i::after{content:"";position:absolute;top:3px;left:3px;width:22px;height:22px;border-radius:50%;background:#EAF6F8;transition:transform .2s}',
            '.pn-es .sw input:checked+i{background:var(--sm-lime)}',
            '.pn-es .sw input:checked+i::after{transform:translateX(18px);background:#1d2a00}',
            '.pn-es .sw input:disabled+i{opacity:.45;cursor:default}',
            '.pn-es .sw input:focus-visible+i{outline:2px solid var(--sm-aqua);outline-offset:2px}',
            '.pn-es .ln{display:flex;flex-wrap:wrap;align-items:center;padding:6px 0}',
            '.pn-es .ln>*{margin:4px 8px 4px 0}',
            '.pn-es .day{border:1px solid var(--sm-line);background:transparent;color:var(--on-surface-variant);border-radius:9px;padding:6px 8px;font:600 12.5px var(--family-body,sans-serif);cursor:pointer}',
            '.pn-es .day.on{background:rgba(95,214,230,.16);color:var(--on-surface);border-color:rgba(95,214,230,.4)}',
            '.pn-es .bt{border:1px solid var(--sm-line);background:rgba(255,255,255,.075);color:var(--on-surface);border-radius:12px;padding:8px 12px;font:600 13px var(--family-body,sans-serif);cursor:pointer}',
            '.pn-es .bt.lime{background:var(--sm-lime);color:#1d2a00;border-color:transparent}',
            '.pn-es .bt:disabled{opacity:.5;cursor:default}',
            '.pn-es .more{margin-top:10px}',
            '.pn-es h3{font-family:var(--family-display,sans-serif);font-size:12.5px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:var(--outline);margin:16px 0 4px}'
        ].join('\n');
        document.head.appendChild(s);
    }

    // ------------------------------------------------------------------ Store

    function Store() {
        this.schema = null;
        this.werte = {};
        this.etag = '';
        this._hoerer = [];
        this._ansichten = [];
    }

    Store.prototype.neuLaden = function () {
        var self = this;
        var q = sprache() ? '?sprache=' + encodeURIComponent(sprache()) : '';
        return Promise.all([
            fetch('/api/einstellungen/schema' + q, { cache: 'no-store' }).then(json),
            fetch('/api/einstellungen', { cache: 'no-store' }).then(json)
        ]).then(function (r) {
            if (r[0]._status !== 200 || !r[0].gruppen || r[1]._status !== 200 || !r[1].werte) throw new Error('schema');
            self.schema = r[0];
            self.werte = r[1].werte;
            self.etag = r[1].etag || '';
            self._ansichten.forEach(function (a) { a(); });
            self._hoerer.forEach(function (h) { try { h(self); } catch (e) {} });
            return self;
        });
    };

    Store.prototype.beiAenderung = function (fn) { this._hoerer.push(fn); };

    Store.prototype.feld = function (key) {
        var gs = (this.schema && this.schema.gruppen) || [];
        for (var i = 0; i < gs.length; i++) {
            var fs = gs[i].felder || [];
            for (var j = 0; j < fs.length; j++) if (fs[j].key === key) return fs[j];
        }
        return null;
    };

    Store.prototype.wert = function (key) { return this.werte[key] || {}; };

    /** Sichtbare Felder einer Gruppe/eines Abschnitts, sortiert. */
    Store.prototype.felder = function (gruppe, abschnitt, immer) {
        var gs = (this.schema && this.schema.gruppen) || [], out = [];
        gs.forEach(function (g) {
            if (gruppe && g.id !== gruppe) return;
            (g.felder || []).forEach(function (f) {
                if (f.sichtbar === false && !(immer && immer.indexOf(f.key) >= 0)) return;
                if (abschnitt && !(f.abschnitt && f.abschnitt.id === abschnitt)) return;
                out.push(f);
            });
        });
        out.sort(function (a, b) {
            var sa = a.abschnitt ? a.abschnitt.reihenfolge : 0, sb = b.abschnitt ? b.abschnitt.reihenfolge : 0;
            return sa !== sb ? sa - sb : (a.reihenfolge || 0) - (b.reihenfolge || 0);
        });
        return out;
    };

    /**
     * Speichert nur die gesendeten Schlüssel. Ergebnis: { ok, fehler: {key: grund}, veraltet }.
     * Danach wird immer zurückgelesen (Regel: echten Zustand zeigen, nicht den gewünschten).
     */
    Store.prototype.speichern = function (werte, bestaetigt) {
        var self = this;
        var body = { etag: this.etag, werte: werte };
        if (bestaetigt && bestaetigt.length) body.bestaetigt = bestaetigt;
        return fetch('/api/einstellungen', {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        }).then(json).then(function (d) {
            var res = { ok: d.ok === true, fehler: d.fehler || {}, veraltet: d._status === 409 };
            if (d._status !== 200 && !res.veraltet) res.fehler._ = 'http ' + d._status;
            return self.neuLaden().then(function () { return res; }, function () { return res; });
        }, function () {
            return { ok: false, fehler: { _: 'netz' } };
        });
    };

    // ------------------------------------------------------------------ Zeichnen

    Store.prototype.zeichne = function (container, opts) {
        var self = this;
        opts = opts || {};
        injectStyle();
        var offen = false;
        var meldung = {};   // key → {ok|er: text}, überlebt das Neuzeichnen nach dem Zurücklesen

        function zeichnen() {
            var ohne = opts.ohne || [];
            var felder = self.felder(opts.gruppe, opts.abschnitt, opts.immer).filter(function (f) {
                return ohne.indexOf(f.key) < 0 && !f.bestaetigung && (!opts.nur || opts.nur.indexOf(f.key) >= 0);
            });
            container.innerHTML = '';
            var box = el('div', 'pn-es');
            var normal = el('div'), mehr = el('div');
            var letzterAbschnitt = null, hatMehr = false;
            felder.forEach(function (f) {
                var ziel = f.erweitert ? mehr : normal;
                if (f.erweitert) hatMehr = true;
                if (!opts.abschnitt && f.abschnitt && f.abschnitt.id !== letzterAbschnitt && !f.erweitert) {
                    letzterAbschnitt = f.abschnitt.id;
                    ziel.appendChild(el('h3', null, f.abschnitt.titel || f.abschnitt.id));
                }
                ziel.appendChild(zeile(f));
            });
            box.appendChild(normal);
            if (hatMehr) {
                mehr.hidden = !offen;
                var b = el('button', 'bt more', offen ? t('less') : t('more'));
                b.type = 'button';
                b.onclick = function () { offen = !offen; mehr.hidden = !offen; b.textContent = offen ? t('less') : t('more'); };
                box.appendChild(b);
                box.appendChild(mehr);
            }
            container.appendChild(box);
        }

        function speichere(f, wert, knopf) {
            if (knopf) knopf.disabled = true;
            return self.speichern(kv(f.key, wert)).then(function (r) {
                if (r.veraltet) meldung[f.key] = { er: t('stale') };
                else if (r.fehler[f.key] || r.fehler._) meldung[f.key] = { er: fehlerText(r.fehler[f.key] || '') };
                else meldung[f.key] = { ok: t('saved') + (f.neustartNoetig ? ' · ' + t('restart') : '') };
                zeichnen();
                if (typeof opts.nachSpeichern === 'function') opts.nachSpeichern(f, r);
            });
        }

        function zeile(f) {
            var w = self.wert(f.key);
            var nurLesen = !!f.nurLesend;
            var blk = f.typ === 'zeitfenster' || f.typ === 'geozone' || f.typ === 'liste';
            var r = el('div', 'r' + (blk ? ' blk' : ''));
            r.setAttribute('data-key', f.key);
            var tx = el('div', 'tx');
            tx.appendChild(el('b', null, f.titel || f.key));
            if (f.hilfe) tx.appendChild(el('small', null, f.hilfe));
            var m = meldung[f.key];
            if (m) tx.appendChild(el('div', m.er ? 'er' : 'ok', m.er || m.ok));
            r.appendChild(tx);
            var vl = el('div', 'vl');
            r.appendChild(vl);

            if (nurLesen) {
                vl.appendChild(el('span', 'ro', f.typ === 'geheim' ? (w.gesetzt ? t('set') : t('not_set')) : anzeige(w.wert)));
                return r;
            }
            switch (f.typ) {
                case 'bool': vl.appendChild(schalter(f, w)); break;
                case 'int': case 'zahl': zahlFeld(f, w, vl); break;
                case 'auswahl': vl.appendChild(auswahl(f, w)); break;
                case 'text': case 'zeit': case 'farbe': textFeld(f, w, vl); break;
                case 'geheim': geheimFeld(f, w, vl); break;
                case 'zeitfenster': zeitfenster(f, w, r); break;
                case 'geozone': geozonen(f, w, r); break;
                default: vl.appendChild(el('span', 'ro', anzeige(w.wert)));
            }
            return r;
        }

        function anzeige(v) {
            if (v == null || v === '') return '–';
            if (typeof v === 'number') return zahlText(v);
            return String(v);
        }

        function schalter(f, w) {
            var lab = el('label', 'sw');
            var inp = document.createElement('input');
            inp.type = 'checkbox';
            inp.checked = w.wert === true;
            inp.setAttribute('aria-label', f.titel || f.key);
            inp.onchange = function () {
                if (inp.checked && f.gefahr && !window.confirm((f.titel ? f.titel + '\n\n' : '') + t('danger'))) {
                    inp.checked = false;
                    return;
                }
                inp.disabled = true;
                speichere(f, inp.checked);
            };
            lab.appendChild(inp);
            lab.appendChild(el('i'));
            return lab;
        }

        function zahlFeld(f, w, vl) {
            var inp = document.createElement('input');
            inp.type = 'number';
            if (f.min != null) inp.min = f.min;
            if (f.max != null) inp.max = f.max;
            inp.step = f.schritt != null ? f.schritt : (f.typ === 'int' ? 1 : 'any');
            inp.value = w.wert != null ? w.wert : '';
            inp.setAttribute('aria-label', f.titel || f.key);
            inp.onchange = function () {
                var v = Number(String(inp.value).replace(',', '.'));
                if (inp.value === '' || !isFinite(v)) { meldung[f.key] = { er: t('err.typ') }; zeichnen(); return; }
                if (f.typ === 'int') v = Math.round(v);
                if (f.min != null && v < f.min) { meldung[f.key] = { er: fehlerText('min ' + f.min) }; zeichnen(); return; }
                if (f.max != null && v > f.max) { meldung[f.key] = { er: fehlerText('max ' + f.max) }; zeichnen(); return; }
                inp.disabled = true;
                speichere(f, v);
            };
            vl.appendChild(inp);
            if (f.einheit) vl.appendChild(el('span', 'un', f.einheit));
            if (!f.nullIstAus) return;
            // nullIstAus: 0 schaltet die Bedingung aus → Schalter + Zahl statt „0 = aus“.
            var an = Number(w.wert) !== 0;
            var ew = opts.einschalten && Number(opts.einschalten[f.key]);
            var ein = ew > 0 ? ew : Number(f.empfohlen) > 0 ? Number(f.empfohlen)
                : Number(f.standard) > 0 ? Number(f.standard) : (Number(f.min) > 0 ? Number(f.min) : (Number(f.schritt) || 1));
            if (!an) inp.value = ein;
            inp.disabled = !an;
            var lab = el('label', 'sw');
            lab.style.marginLeft = '12px';
            var cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.checked = an;
            cb.setAttribute('aria-label', f.titel || f.key);
            cb.onchange = function () {
                cb.disabled = true;
                var v = cb.checked ? Number(String(inp.value).replace(',', '.')) : 0;
                if (cb.checked && !(v > 0)) v = ein;
                speichere(f, f.typ === 'int' ? Math.round(v) : v);
            };
            lab.appendChild(cb);
            lab.appendChild(el('i'));
            vl.appendChild(lab);
        }

        function auswahl(f, w) {
            var sel = document.createElement('select');
            sel.setAttribute('aria-label', f.titel || f.key);
            // Nicht gesetzt (Standard null, z. B. kamera.fps*): sonst zeigte der Browser still die erste Option.
            var treffer = (f.optionen || []).some(function (o) { return w.wert === o.wert; });
            if (!treffer) {
                var leer = document.createElement('option');
                leer.value = '';
                var jetzt = null;
                (f.optionen || []).forEach(function (o) { if (w.wirksam != null && String(w.wirksam) === String(o.wert)) jetzt = o.titel || o.wert; });
                leer.textContent = jetzt ? t('auswahl_auto_jetzt', { v: jetzt }) : t('auswahl_auto');
                leer.disabled = true;
                leer.selected = true;
                sel.appendChild(leer);
            }
            (f.optionen || []).forEach(function (o) {
                var op = document.createElement('option');
                op.value = o.wert;
                op.textContent = (o.titel || o.wert) + (o.gesperrt && o.gesperrtGrund ? ' – ' + o.gesperrtGrund : '');
                op.disabled = !!o.gesperrt;
                if (w.wert === o.wert) op.selected = true;
                sel.appendChild(op);
            });
            sel.onchange = function () { sel.disabled = true; speichere(f, sel.value); };
            return sel;
        }

        function textFeld(f, w, vl) {
            var inp = document.createElement('input');
            inp.type = f.typ === 'zeit' ? 'time' : 'text';
            inp.value = w.wert != null ? w.wert : '';
            inp.setAttribute('aria-label', f.titel || f.key);
            inp.onchange = function () { inp.disabled = true; speichere(f, inp.value); };
            vl.appendChild(inp);
        }

        function geheimFeld(f, w, vl) {
            vl.appendChild(el('span', 'ro', w.gesetzt ? t('set') : t('not_set')));
            var inp = document.createElement('input');
            inp.type = 'password';
            inp.autocomplete = 'off';
            inp.placeholder = t('secret_new');
            inp.style.marginLeft = '8px';
            inp.style.width = '140px';
            inp.onchange = function () { if (inp.value) { inp.disabled = true; speichere(f, inp.value); } };
            vl.appendChild(inp);
            if (w.gesetzt) {
                var del = el('button', 'bt', t('secret_clear'));
                del.type = 'button';
                del.style.marginLeft = '8px';
                del.onclick = function () { speichere(f, null, del); };
                vl.appendChild(del);
            }
        }

        function zeitfenster(f, w, r) {
            var liste = el('div');
            var max = f.maxEintraege || 7;
            var names = t('days').split(',');
            function zeileZf(z) {
                var ln = el('div', 'ln');
                for (var d = 1; d <= 7; d++) {
                    var b = el('button', 'day' + ((z.days || []).indexOf(d) >= 0 ? ' on' : ''), names[d - 1] || String(d));
                    b.type = 'button';
                    b.setAttribute('data-day', d);
                    b.onclick = function () { this.classList.toggle('on'); };
                    ln.appendChild(b);
                }
                var von = document.createElement('input'); von.type = 'time'; von.value = z.from || ''; von.className = 'zf-von';
                var bis = document.createElement('input'); bis.type = 'time'; bis.value = z.to || ''; bis.className = 'zf-bis';
                var weg = el('button', 'bt', t('remove')); weg.type = 'button';
                weg.onclick = function () { liste.removeChild(ln); dazu.disabled = liste.children.length >= max; };
                ln.appendChild(von); ln.appendChild(el('span', 'un', '–')); ln.appendChild(bis); ln.appendChild(weg);
                liste.appendChild(ln);
            }
            (Array.isArray(w.wert) ? w.wert : []).forEach(zeileZf);
            r.appendChild(liste);
            var ln = el('div', 'ln');
            var dazu = el('button', 'bt', t('add_window')); dazu.type = 'button';
            dazu.disabled = liste.children.length >= max;
            dazu.title = t('max_entries', { n: max });
            dazu.onclick = function () {
                if (liste.children.length >= max) return;
                zeileZf({ days: [1, 2, 3, 4, 5, 6, 7], from: '22:00', to: '06:00' });
                dazu.disabled = liste.children.length >= max;
            };
            var sp = el('button', 'bt lime', t('save')); sp.type = 'button';
            sp.onclick = function () {
                var out = [];
                Array.prototype.forEach.call(liste.children, function (row) {
                    var days = Array.prototype.map.call(row.querySelectorAll('.day.on'), function (b) { return +b.getAttribute('data-day'); });
                    var von = row.querySelector('.zf-von').value, bis = row.querySelector('.zf-bis').value;
                    if (days.length && von && bis) out.push({ days: days, from: von, to: bis });
                });
                speichere(f, out, sp);
            };
            ln.appendChild(dazu); ln.appendChild(sp);
            r.appendChild(ln);
        }

        function geozonen(f, w, r) {
            var liste = el('div');
            var zonen = (Array.isArray(w.wert) ? w.wert : []).slice();
            var max = f.maxEintraege || 20;
            function zeichneListe() {
                liste.innerHTML = '';
                if (!zonen.length) liste.appendChild(el('div', 'un', t('place_none')));
                zonen.forEach(function (z, i) {
                    var ln = el('div', 'ln');
                    var name = document.createElement('input'); name.type = 'text'; name.value = z.name || ''; name.placeholder = t('place_name');
                    name.oninput = function () { z.name = name.value; };
                    var rad = document.createElement('input'); rad.type = 'number';
                    if (f.min != null) rad.min = f.min;
                    if (f.max != null) rad.max = f.max;
                    rad.step = f.schritt || 5; rad.value = z.radiusM || 50;
                    rad.setAttribute('aria-label', t('place_radius'));
                    rad.onchange = function () { z.radiusM = Math.round(Number(rad.value)); };
                    var weg = el('button', 'bt', t('remove')); weg.type = 'button';
                    weg.onclick = function () { zonen.splice(i, 1); zeichneListe(); };
                    ln.appendChild(name); ln.appendChild(el('span', 'un', t('place_radius'))); ln.appendChild(rad);
                    ln.appendChild(el('span', 'un', f.einheit || 'm')); ln.appendChild(weg);
                    liste.appendChild(ln);
                });
                dazu.disabled = zonen.length >= max || typeof opts.standort !== 'function';
            }
            var ln = el('div', 'ln');
            var dazu = el('button', 'bt', t('place_here')); dazu.type = 'button';
            var hinweis = el('span', 'un');
            dazu.onclick = function () {
                hinweis.textContent = '';
                Promise.resolve(opts.standort()).then(function (p) {
                    if (!p || !isFinite(p.lat) || !isFinite(p.lng)) { hinweis.textContent = t('place_nopos'); return; }
                    zonen.push({ name: '', lat: p.lat, lon: p.lng, radiusM: 50 });
                    zeichneListe();
                }, function () { hinweis.textContent = t('place_nopos'); });
            };
            var sp = el('button', 'bt lime', t('save')); sp.type = 'button';
            sp.onclick = function () {
                speichere(f, zonen.map(function (z) {
                    var o = { name: z.name || '', lat: z.lat, lon: z.lon, radiusM: z.radiusM || 50 };
                    if (z.id) o.id = z.id;
                    return o;
                }), sp);
            };
            r.appendChild(liste);
            ln.appendChild(dazu); ln.appendChild(sp); ln.appendChild(hinweis);
            r.appendChild(ln);
            zeichneListe();
        }

        self._ansichten.push(zeichnen);
        zeichnen();
    };

    function kv(k, v) { var o = {}; o[k] = v; return o; }

    /** Lädt Schema und Werte. Liefert null, wenn der Daemon die Schema-API noch nicht hat. */
    function laden() {
        var s = new Store();
        return s.neuLaden().then(function () { return s; }, function () { return null; });
    }

    window.PanoptesEinstellungen = { laden: laden, fehlerText: fehlerText };
})();
