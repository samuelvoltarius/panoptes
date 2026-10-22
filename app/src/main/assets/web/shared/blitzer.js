/**
 * Panoptes — Blitzer-Anzeige mit Rechtshinweis (0.1.8, Standard aus).
 *
 * Kern-Feld `speedcam.enabled` (BOOL, Gruppe system) mit `bestaetigung: "speedcam.rechtshinweis"`.
 * Einschalten nur mit Quittung: PUT /api/einstellungen { werte, bestaetigt: ["speedcam.enabled"] }.
 * Ausschalten braucht keine Bestätigung. Danach wird immer zurückgelesen (Store.speichern).
 * Texte: Schema `bestaetigungText` (der Kern liest sie aus web/i18n unter speedcam.rechtshinweis.*),
 * sonst BYD.i18n, sonst deutscher Rückfall.
 *
 *   PanoptesBlitzer.zeichne(container, store)   store = PanoptesEinstellungen-Store
 *
 * ES5 (WebView 74).
 */
(function () {
    'use strict';

    var FELD = 'speedcam.enabled';
    var TON = 'speedcam.ton';
    var DE = {
        'rechtshinweis.title': 'Blitzer-Anzeige: bitte zuerst lesen',
        'rechtshinweis.text': 'Panoptes kann bekannte Blitzer auf der Karte zeigen (Daten aus OpenStreetMap). In Deutschland (§ 23 Abs. 1c StVO), in der Schweiz, in Frankreich und in weiteren Ländern ist es verboten, während der Fahrt ein Gerät zu benutzen, das Blitzer anzeigt oder vor ihnen warnt. Schalte die Anzeige nur ein, wo das erlaubt ist. Dafür bist du selbst verantwortlich. Das gilt genauso für Töne und Ansagen: Auch eine hörbare Warnung ist dort verboten. Eine Warnung während der Fahrt gibt Panoptes nur in Österreich. Keine Rechtsberatung, Stand 10/2026. Die Rechtslage kann sich ändern; prüfe die Regeln deines Landes.',
        lead: 'Zeigt bekannte Blitzer auf der Community-Karte. Standard ist aus.',
        an: 'Eingeschaltet',
        aus: 'Ausgeschaltet',
        einschalten: 'Einschalten …',
        ausschalten: 'Ausschalten',
        bestaetigen: 'Gelesen – einschalten',
        abbrechen: 'Abbrechen',
        fehler: 'Speichern hat nicht geklappt. Die Einstellung ist unverändert.'
    };

    function t(k) {
        var key = 'speedcam.' + k;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key);
                if (v && v !== key) return v;
            }
        } catch (e) {}
        return DE[k] || k;
    }

    function el(tag, cls, txt) {
        var n = document.createElement(tag);
        if (cls) n.className = cls;
        if (txt != null) n.textContent = txt;
        return n;
    }

    // bestaetigungText aus dem Schema: Objekt {title,text,…} oder Zeichenkette.
    function hinweisTexte(feld) {
        var b = feld && feld.bestaetigungText;
        var titel = t('rechtshinweis.title'), text = t('rechtshinweis.text');
        if (b && typeof b === 'object') {
            if (b.title) titel = b.title;
            if (b.text) text = b.text;
        } else if (typeof b === 'string' && b) {
            text = b;
        }
        return { titel: titel, text: text };
    }

    var cssDone = false;
    function css() {
        if (cssDone) return;
        cssDone = true;
        var s = document.createElement('style');
        s.textContent =
            '.pn-bz-zeile{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;margin-top:10px}' +
            '.pn-bz-zeile > *{margin:4px 0}' +
            '.pn-bz-status{font-weight:600}' +
            '.pn-bz-btn{min-height:48px;padding:0 18px;border-radius:12px;border:1px solid var(--outline,#2a6474);' +
            'background:transparent;color:inherit;font-size:15px;font-weight:600;cursor:pointer;margin-right:8px}' +
            '.pn-bz-btn.haupt{background:var(--primary,#5FD6E6);color:var(--on-primary,#06283a)}' +
            '.pn-bz-hinweis{margin-top:12px;padding:12px 14px;border-radius:12px;border:1px solid var(--warning,#e0a030);' +
            'background:rgba(224,160,48,.08);line-height:1.45}' +
            '.pn-bz-hinweis h4{margin:0 0 6px;font-size:15px}' +
            '.pn-bz-hinweis p{margin:0 0 10px}' +
            '.pn-bz-fehler{margin-top:8px;color:var(--danger,#e5484d)}';
        document.head.appendChild(s);
    }

    function zeichne(box, store, meldung) {
        css();
        box.innerHTML = '';
        var feld = store.feld(FELD);
        var an = store.wert(FELD).wert === true;
        box.appendChild(el('div', 'sys-note', t('lead')));
        var zeile = el('div', 'pn-bz-zeile');
        zeile.appendChild(el('span', 'pn-bz-status', an ? t('an') : t('aus')));
        var knopf = el('button', 'pn-bz-btn' + (an ? '' : ' haupt'), an ? t('ausschalten') : t('einschalten'));
        knopf.type = 'button';
        knopf.setAttribute('data-bz', an ? 'aus' : 'ein');
        zeile.appendChild(knopf);
        box.appendChild(zeile);
        if (meldung) box.appendChild(el('div', 'pn-bz-fehler', meldung));
        // 0.1.9.1: Ton (speedcam.ton aus|hinweiston|ansage) – nur wenn der Kern das Feld kennt und Blitzer an ist
        var tonFeld = store.feld(TON);
        if (an && tonFeld) {
            var tonBox = el('div', 'pn-bz-ton');
            box.appendChild(tonBox);
            store.zeichne(tonBox, { gruppe: tonFeld.gruppe, nur: [TON] });
        }

        function speichern(wert, bestaetigt) {
            knopf.disabled = true;
            var w = {}; w[FELD] = wert;
            store.speichern(w, bestaetigt).then(function (r) {
                var ok = r && r.ok && !(r.fehler && r.fehler[FELD]) && store.wert(FELD).wert === wert;
                zeichne(box, store, ok ? null : t('fehler'));
            });
        }

        knopf.onclick = function () {
            if (an) { speichern(false); return; }      // Ausschalten: ohne Bestätigung
            var tx = hinweisTexte(feld);
            var h = el('div', 'pn-bz-hinweis');
            h.setAttribute('role', 'dialog');
            h.appendChild(el('h4', null, tx.titel));
            h.appendChild(el('p', null, tx.text));
            var ja = el('button', 'pn-bz-btn haupt', t('bestaetigen'));
            ja.type = 'button';
            ja.setAttribute('data-bz', 'bestaetigen');
            ja.onclick = function () { ja.disabled = true; speichern(true, [FELD]); };
            var nein = el('button', 'pn-bz-btn', t('abbrechen'));
            nein.type = 'button';
            nein.onclick = function () { zeichne(box, store); };
            h.appendChild(ja);
            h.appendChild(nein);
            knopf.style.display = 'none';
            box.appendChild(h);
        };
    }

    // ---------------------------------------------------------------- allgemein (Regel 2)
    // Beliebiges BOOL-Feld mit Pflicht-Bestätigung (z. B. planner.zustandSenden, planner.positionSenden):
    // Titel/Hilfe aus dem Schema, vor dem Einschalten bestaetigungText {title,text}, PUT mit bestaetigt[key];
    // Ausschalten ohne Bestätigung. Danach zurücklesen.
    function ew(k, fb) {
        var key = 'einwilligung.' + k;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key); if (v && v !== key) return v; }
        } catch (e) {}
        return fb;
    }
    function zeichneFeld(box, store, key, meldung) {
        css();
        box.innerHTML = '';
        var feld = store.feld(key);
        if (!feld) return;
        var an = store.wert(key).wert === true;
        var kopf = el('div', null, feld.titel || key);
        kopf.style.cssText = 'font-weight:600;margin-top:12px';
        box.appendChild(kopf);
        if (feld.hilfe) box.appendChild(el('div', 'sys-note', feld.hilfe));
        var zeile = el('div', 'pn-bz-zeile');
        zeile.appendChild(el('span', 'pn-bz-status', an ? ew('an', 'Eingeschaltet') : ew('aus', 'Ausgeschaltet')));
        var knopf = el('button', 'pn-bz-btn' + (an ? '' : ' haupt'), an ? ew('ausschalten', 'Ausschalten') : ew('einschalten', 'Einschalten …'));
        knopf.type = 'button';
        knopf.setAttribute('data-ew', an ? 'aus' : 'ein');
        knopf.setAttribute('data-ew-key', key);
        if (feld.gesperrt) { knopf.disabled = true; }
        zeile.appendChild(knopf);
        box.appendChild(zeile);
        if (meldung) box.appendChild(el('div', 'pn-bz-fehler', meldung));

        function speichern(wert, bestaetigt) {
            knopf.disabled = true;
            var w = {}; w[key] = wert;
            store.speichern(w, bestaetigt).then(function (r) {
                var f = r && r.fehler && r.fehler[key];
                var ok = r && r.ok && !f && store.wert(key).wert === wert;
                var text = ok ? null : (f && window.PanoptesEinstellungen && PanoptesEinstellungen.fehlerText
                    ? PanoptesEinstellungen.fehlerText(f) : ew('fehler', 'Speichern hat nicht geklappt. Die Einstellung ist unverändert.'));
                zeichneFeld(box, store, key, text);
            });
        }
        knopf.onclick = function () {
            if (an) { speichern(false); return; }
            var b = feld.bestaetigungText || {};
            var h = el('div', 'pn-bz-hinweis');
            h.setAttribute('role', 'dialog');
            h.appendChild(el('h4', null, b.title || feld.titel || key));
            if (b.text) h.appendChild(el('p', null, b.text));
            var ja = el('button', 'pn-bz-btn haupt', ew('bestaetigen', 'Gelesen – einschalten'));
            ja.type = 'button';
            ja.setAttribute('data-ew', 'bestaetigen');
            ja.onclick = function () { ja.disabled = true; speichern(true, [key]); };
            var nein = el('button', 'pn-bz-btn', ew('abbrechen', 'Abbrechen'));
            nein.type = 'button';
            nein.onclick = function () { zeichneFeld(box, store, key); };
            h.appendChild(ja); h.appendChild(nein);
            knopf.style.display = 'none';
            box.appendChild(h);
        };
    }

    window.PanoptesBlitzer = { zeichne: zeichne, FELD: FELD };
    window.PanoptesEinwilligung = { zeichne: zeichneFeld };
})();
