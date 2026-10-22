/**
 * Panoptes — „Wo ich lade“: Vorlieben fürs Laden, gespeichert im Auto (0.1.10, LADEN_VERTRAG Teil A, Kern 5).
 *
 * Das Auto ist die Quelle. Einfache Felder (Gruppe „laden“) zeichnet der Einstellungs-Store; die Listen
 * (Karten, bevorzugte Netze, Netze meiden, Stecker) sind im Schema verborgen und laufen hier über:
 *   GET/PUT /api/laden            {etag, laden:{nur geänderte Felder}} → 200 wie GET · 400 {fehler} · 409 veraltet
 *   GET /api/laden/tarife          Karten-Katalog des Planers (öffentlich)
 *   GET /api/laden/betreiber?q=    Betreiber-Suche (nur der Suchtext geht an den Planer)
 *   GET/POST /api/laden/aus-konto  einmalige Übernahme aus dem Planer-Konto (Vorschau, dann {bestaetigt:true})
 *   POST /api/laden/zuruecksetzen  zurück auf „Standard des Planers“
 * Keine Preise im Auto. Nach jedem Speichern wird zurückgelesen.
 *
 *   PanoptesLaden.init(karte, inhalt)   (ES5, Chromium 74)
 */
(function () {
    'use strict';

    var DE = {
        quelle_auto: 'Gespeichert in deinem Auto (Stand {stand}). Jede Planung nimmt diese Vorlieben.',
        quelle_standard: 'Noch nichts im Auto gespeichert. Bis dahin gelten dein Routenplaner-Konto bzw. dessen Standard.',
        karten: 'Meine Ladekarten',
        karten_hilfe: 'Der Planer bevorzugt Säulen, an denen eine deiner Karten einen bekannten Preis hat.',
        netze: 'Bevorzugte Netze',
        meiden: 'Netze meiden',
        stecker: 'Stecker',
        stecker_standard: 'Standard des Planers (CCS und Typ 2)',
        keine: 'Keine',
        hinzufuegen: 'Hinzufügen',
        suchen: 'Netz suchen …',
        karte_waehlen: 'Karte wählen …',
        entfernen: 'Entfernen',
        voll: 'Mehr geht nicht (höchstens {n}).',
        netzfehler: 'Der Routenplaner ist gerade nicht erreichbar. Deine gespeicherten Einträge bleiben.',
        gespeichert: 'Gespeichert',
        fehler: 'Speichern hat nicht geklappt. Nichts wurde geändert.',
        veraltet: 'Inzwischen woanders geändert. Bitte noch einmal.',
        konto_knopf: 'Aus dem Routenplaner-Konto übernehmen …',
        konto_titel: 'Das würde sich ändern:',
        konto_keine: 'Im Konto steht nichts anderes als hier.',
        konto_ok: 'Übernehmen',
        konto_leserecht: 'Der Routenplaner erlaubt das Lesen deines Kontos für dieses Auto noch nicht.',
        konto_kein_planer: 'Panoptes ist mit keinem Routenplaner verbunden.',
        zurueck_knopf: 'Auf Standard des Planers zurücksetzen',
        zurueck_frage: 'Alle Vorlieben im Auto löschen? Danach gilt wieder dein Routenplaner-Konto bzw. dessen Standard.',
        ja: 'Ja, zurücksetzen',
        abbrechen: 'Abbrechen'
    };
    var STECKER = [['ccs', 'CCS'], ['typ2', 'Typ 2'], ['chademo', 'CHAdeMO'], ['nacs', 'NACS']];
    var MAX = { karten: 30, netze: 20, meiden: 20 };

    function t(k, v) {
        var key = 'laden.ui.' + k, s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var x = BYD.i18n.t(key); if (x && x !== key) s = x; } } catch (e) {}
        if (s == null) s = DE[k] || k;
        if (v) Object.keys(v).forEach(function (n) { s = s.split('{' + n + '}').join(v[n]); });
        return s;
    }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function toast(m, ok) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, ok ? 'success' : 'error'); }
    function holen(url, opt) {
        return fetch(url, opt || { cache: 'no-store' }).then(function (r) {
            return r.text().then(function (x) { var d = {}; try { d = JSON.parse(x); } catch (e) {} d._status = r.status; return d; });
        });
    }
    function css() {
        if (document.getElementById('pn-laden-css')) return;
        var s = document.createElement('style');
        s.id = 'pn-laden-css';
        s.textContent = '.pn-ld-chips{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}'
            + '.pn-ld-chip{display:inline-flex;align-items:center;gap:6px;padding:6px 10px;border-radius:999px;border:1px solid var(--sm-line,#2b3b48);font-size:13px}'
            + '.pn-ld-chip button{background:none;border:0;color:inherit;font-size:16px;cursor:pointer;padding:0 2px}'
            + '.pn-ld-zeile{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:6px}'
            + '.pn-ld-zeile input,.pn-ld-zeile select{flex:1;min-width:160px;padding:8px 10px;background:var(--bg-elevated);border:1px solid var(--sm-line,#2b3b48);border-radius:8px;color:inherit}'
            + '.pn-ld-treffer{list-style:none;margin:6px 0 0;padding:0}.pn-ld-treffer li{padding:8px 10px;border-radius:8px;cursor:pointer}'
            + '.pn-ld-treffer li:hover{background:rgba(255,255,255,.06)}.pn-ld-abschnitt{margin-top:16px}'
            + '.pn-ld-titel{font-weight:600;margin-bottom:2px}.pn-ld-note{font-size:12.5px;opacity:.75}';
        document.head.appendChild(s);
    }

    function Laden(karte, inhalt) {
        this.karte = karte; this.inhalt = inhalt; this.d = null; this.tarife = null;
    }

    Laden.prototype.laden = function () {
        var self = this;
        return holen('/api/laden').then(function (d) {
            if (d._status === 404) return null;
            self.d = d;
            return d;
        });
    };

    Laden.prototype.speichern = function (feld, wert) {
        var self = this, body = { etag: this.d && this.d.etag, laden: {} };
        body.laden[feld] = wert;
        return holen('/api/laden', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
            .then(function (d) {
                if (d._status === 200 && d.ok !== false) { self.d = d; toast(t('gespeichert'), true); self.zeichne(); return true; }
                toast(d._status === 409 ? t('veraltet') : t('fehler'), false);
                return self.laden().then(function () { self.zeichne(); return false; });
            });
    };

    Laden.prototype.zeichne = function () {
        var self = this, d = this.d || {}, l = d.laden || {};
        var box = this.inhalt;
        box.innerHTML = '';
        box.appendChild(el('div', 'pn-ld-note', d.quelle === 'auto' ? t('quelle_auto', { stand: d.stand ? String(d.stand).slice(0, 16).replace('T', ' ') : '–' }) : t('quelle_standard')));

        // einfache Felder: generisch über den Einstellungs-Store (jedes Schreiben setzt laden.stand)
        var einfach = el('div', 'pn-ld-abschnitt');
        box.appendChild(einfach);
        if (window.PanoptesEinstellungen) PanoptesEinstellungen.laden().then(function (st) {
            if (st && st.felder('laden').length) st.zeichne(einfach, { gruppe: 'laden', nachSpeichern: function () { self.laden().then(function () { self.kopf(); }); } });
        });

        this.listeKarten(box, l.karten || []);
        this.listeNetze(box, 'netze', l.netze || []);
        this.listeNetze(box, 'meiden', l.meiden || []);
        this.stecker(box, l.stecker);

        var unten = el('div', 'pn-ld-zeile pn-ld-abschnitt');
        var kk = el('button', 'btn', t('konto_knopf')); kk.type = 'button';
        kk.onclick = function () { self.ausKonto(unten); };
        var zz = el('button', 'btn', t('zurueck_knopf')); zz.type = 'button';
        zz.onclick = function () { self.zuruecksetzen(unten); };
        unten.appendChild(kk); unten.appendChild(zz);
        box.appendChild(unten);
    };

    Laden.prototype.kopf = function () {
        var n = this.inhalt.querySelector('.pn-ld-note');
        if (n && this.d) n.textContent = this.d.quelle === 'auto' ? t('quelle_auto', { stand: this.d.stand ? String(this.d.stand).slice(0, 16).replace('T', ' ') : '–' }) : t('quelle_standard');
    };

    function chips(liste, text, entfernen) {
        var c = el('div', 'pn-ld-chips');
        if (!liste.length) c.appendChild(el('span', 'pn-ld-note', t('keine')));
        liste.forEach(function (x, i) {
            var ch = el('span', 'pn-ld-chip', text(x));
            var b = el('button', null, '×'); b.type = 'button'; b.title = t('entfernen'); b.setAttribute('aria-label', t('entfernen'));
            b.onclick = function () { entfernen(i); };
            ch.appendChild(b); c.appendChild(ch);
        });
        return c;
    }

    Laden.prototype.listeKarten = function (box, karten) {
        var self = this, ab = el('div', 'pn-ld-abschnitt');
        ab.appendChild(el('div', 'pn-ld-titel', t('karten')));
        ab.appendChild(el('div', 'pn-ld-note', t('karten_hilfe')));
        ab.appendChild(chips(karten, function (k) { return k.anzeige || ('#' + k.id); }, function (i) {
            var neu = karten.slice(); neu.splice(i, 1); self.speichern('karten', neu);
        }));
        var z = el('div', 'pn-ld-zeile');
        var sel = el('select'); sel.appendChild(el('option', null, t('karte_waehlen'))); sel.options[0].value = '';
        var add = el('button', 'btn', t('hinzufuegen')); add.type = 'button';
        z.appendChild(sel); z.appendChild(add); ab.appendChild(z);
        var msg = el('div', 'pn-ld-note'); ab.appendChild(msg);
        box.appendChild(ab);
        (this.tarife ? Promise.resolve(this.tarife) : holen('/api/laden/tarife').then(function (d) {
            if (d._status !== 200) { msg.textContent = t('netzfehler'); return []; }
            var arr = Array.isArray(d) ? d : (d.tarife || d.karten || []);
            self.tarife = arr; return arr;
        })).then(function (arr) {
            arr.forEach(function (x) {
                var id = x.tarif_id != null ? x.tarif_id : x.id;
                if (id == null) return;
                var name = [x.anbieter || x.name, x.tarif].filter(Boolean).join(' · ') + (x.land ? ' (' + x.land + ')' : '');
                var o = el('option', null, name); o.value = String(id); o.setAttribute('data-anzeige', name.slice(0, 80));
                sel.appendChild(o);
            });
        });
        add.onclick = function () {
            if (!sel.value) return;
            if (karten.length >= MAX.karten) { msg.textContent = t('voll', { n: MAX.karten }); return; }
            var id = +sel.value;
            if (karten.some(function (k) { return k.id === id; })) return;
            var o = sel.options[sel.selectedIndex];
            self.speichern('karten', karten.concat([{ id: id, anzeige: o.getAttribute('data-anzeige') || o.textContent }]));
        };
    };

    Laden.prototype.listeNetze = function (box, feld, liste) {
        var self = this, ab = el('div', 'pn-ld-abschnitt');
        ab.appendChild(el('div', 'pn-ld-titel', t(feld)));
        ab.appendChild(chips(liste, function (n) { return n.anzeige || n.cpo; }, function (i) {
            var neu = liste.slice(); neu.splice(i, 1); self.speichern(feld, neu);
        }));
        var z = el('div', 'pn-ld-zeile');
        var inp = el('input'); inp.type = 'search'; inp.placeholder = t('suchen'); inp.autocomplete = 'off';
        z.appendChild(inp); ab.appendChild(z);
        var ul = el('ul', 'pn-ld-treffer'); ab.appendChild(ul);
        var msg = el('div', 'pn-ld-note'); ab.appendChild(msg);
        var timer = null;
        inp.oninput = function () {
            clearTimeout(timer);
            var q = inp.value.trim();
            ul.innerHTML = ''; msg.textContent = '';
            if (q.length < 2) return;
            timer = setTimeout(function () {
                holen('/api/laden/betreiber?q=' + encodeURIComponent(q)).then(function (d) {
                    ul.innerHTML = '';
                    if (d._status !== 200) { msg.textContent = t('netzfehler'); return; }
                    (d.betreiber || []).slice(0, 12).forEach(function (b) {
                        var name = b.name || b.cpo;
                        var li = el('li', null, name + (b.laender ? ' · ' + [].concat(b.laender).join(', ') : ''));
                        li.onclick = function () {
                            if (liste.length >= MAX[feld]) { msg.textContent = t('voll', { n: MAX[feld] }); return; }
                            if (liste.some(function (n) { return n.cpo === b.cpo; })) return;
                            self.speichern(feld, liste.concat([{ cpo: String(b.cpo || name).slice(0, 60), anzeige: String(name).slice(0, 80) }]));
                        };
                        ul.appendChild(li);
                    });
                });
            }, 350);
        };
        box.appendChild(ab);
    };

    Laden.prototype.stecker = function (box, wert) {
        var self = this, ab = el('div', 'pn-ld-abschnitt');
        ab.appendChild(el('div', 'pn-ld-titel', t('stecker')));
        var std = el('label', 'pn-ld-zeile');
        var r = el('input'); r.type = 'checkbox'; r.checked = wert == null;
        std.appendChild(r); std.appendChild(el('span', null, t('stecker_standard')));
        ab.appendChild(std);
        var z = el('div', 'pn-ld-zeile');
        var boxes = STECKER.map(function (s) {
            var lab = el('label'); var c = el('input'); c.type = 'checkbox';
            c.checked = Array.isArray(wert) && wert.indexOf(s[0]) >= 0; c.disabled = wert == null;
            c.onchange = function () {
                var sel = STECKER.filter(function (x, i) { return boxes[i].checked; }).map(function (x) { return x[0]; });
                if (sel.length) self.speichern('stecker', sel);
            };
            lab.appendChild(c); lab.appendChild(document.createTextNode(' ' + s[1] + ' '));
            z.appendChild(lab); return c;
        });
        ab.appendChild(z);
        r.onchange = function () { self.speichern('stecker', r.checked ? null : ['ccs', 'typ2']); };
        box.appendChild(ab);
    };

    Laden.prototype.ausKonto = function (unten) {
        var self = this;
        var alt = unten.querySelector('.pn-ld-konto'); if (alt) alt.parentNode.removeChild(alt);
        var k = el('div', 'pn-ld-konto pn-ld-abschnitt'); k.style.flexBasis = '100%';
        unten.appendChild(k);
        holen('/api/laden/aus-konto').then(function (d) {
            if (d._status !== 200 || d.ok === false) {
                k.appendChild(el('div', 'pn-ld-note', d.grund === 'kein_leserecht' ? t('konto_leserecht') : (d.grund === 'kein_planer' || d.grund === 'nicht_verbunden') ? t('konto_kein_planer') : t('netzfehler')));
                return;
            }
            var ae = d.aenderungen || [];
            if (!ae.length) { k.appendChild(el('div', 'pn-ld-note', t('konto_keine'))); return; }
            k.appendChild(el('div', 'pn-ld-titel', t('konto_titel')));
            var ul = el('ul'); ul.style.margin = '6px 0 0 18px';
            function zeig(v) { return v == null ? '–' : (Array.isArray(v) ? v.map(function (x) { return x && (x.anzeige || x.cpo || x.id) || x; }).join(', ') || t('keine') : String(v)); }
            ae.forEach(function (a) { ul.appendChild(el('li', null, a.feld + ': ' + zeig(a.alt) + ' → ' + zeig(a.neu))); });
            k.appendChild(ul);
            var ok = el('button', 'btn btn-primary', t('konto_ok')); ok.type = 'button';
            ok.onclick = function () {
                ok.disabled = true;
                holen('/api/laden/aus-konto', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ bestaetigt: true }) })
                    .then(function (r) {
                        if (r._status === 200 && r.ok !== false) { self.d = r; toast(t('gespeichert'), true); self.zeichne(); }
                        else { toast(t('fehler'), false); ok.disabled = false; }
                    });
            };
            k.appendChild(ok);
        });
    };

    Laden.prototype.zuruecksetzen = function (unten) {
        var self = this;
        var alt = unten.querySelector('.pn-ld-konto'); if (alt) alt.parentNode.removeChild(alt);
        var k = el('div', 'pn-ld-konto pn-ld-abschnitt'); k.style.flexBasis = '100%';
        k.appendChild(el('div', null, t('zurueck_frage')));
        var ja = el('button', 'btn btn-primary', t('ja')); ja.type = 'button';
        var nein = el('button', 'btn', t('abbrechen')); nein.type = 'button'; nein.style.marginLeft = '8px';
        nein.onclick = function () { k.parentNode.removeChild(k); };
        ja.onclick = function () {
            ja.disabled = true;
            holen('/api/laden/zuruecksetzen', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }).then(function (r) {
                if (r._status === 200) { toast(t('gespeichert'), true); self.laden().then(function () { self.zeichne(); }); }
                else { toast(t('fehler'), false); ja.disabled = false; }
            });
        };
        k.appendChild(ja); k.appendChild(nein);
        unten.appendChild(k);
    };

    function init(karte, inhalt) {
        if (!karte || !inhalt) return;
        css();
        var l = new Laden(karte, inhalt);
        l.laden().then(function (d) {
            if (!d) return;                     // Kern ohne /api/laden: Karte bleibt unsichtbar
            karte.style.display = '';
            l.zeichne();
        });
        window.PanoptesLaden.aktuell = l;
    }

    window.PanoptesLaden = { init: init };
})();
