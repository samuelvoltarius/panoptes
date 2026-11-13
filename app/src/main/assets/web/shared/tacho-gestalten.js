/**
 * Panoptes — „Tacho gestalten“, 0.1.44 (E1) freier Editor. Unter Display → Tacho → „Erweitert“.
 * Statt fester Plätze liegen Elemente frei auf einer Fläche im Format des Fahrerdisplays (1000 × 450 Einheiten):
 * mit dem Finger verschieben und an der großen Ecke ziehen, Raster zum Einrasten (abschaltbar), Hilfslinien,
 * Rückgängig/Wiederholen. Start leer oder von einem der 20 Designs (in Elemente zerlegt, soweit möglich).
 *
 *   GET  /api/tacho/eigen                 → { eigen, standard, gespeichert, imStand }
 *   PUT  /api/tacho/eigen {eigen, waehlen} → { ok, eigen, warnungen }   (nur im Stand, Kern prüft)
 *   POST /api/tacho/eigen/pruefen {text}  → { ok, eigen, warnungen }   (Datei-Import, speichert nichts)
 *
 * Regeln (Erlaubnislisten, Tempo genau einmal und groß genug, Kontrast je Element, nichts außerhalb, kein Blinken)
 * kommen aus PanoptesTacho.pruefeFrei (tacho-designs.js) und gelten im Kern noch einmal (TachoFrei.java).
 * Datei: format panoptes-tacho-design, version 2; Version 1 (feste Plätze) bleibt ladbar.
 * ES5 (WebView 74 am Auto). Große Griffe und Knöpfe (REGEL 50+).
 *
 *   <div id="tachoGestalten"></div>  → Knopf „Tacho gestalten“, der Editor klappt darunter auf
 *   window.PanoptesTachoGestalten.vorlage(design) öffnet den Editor mit einem Galerie-Design als Ausgangspunkt.
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
    var GROSS_KNOPF = 'min-height:56px;padding:10px 16px;font-size:17px;font-weight:700;border-radius:12px;cursor:pointer;'
        + 'border:2px solid var(--border-default, var(--outline-variant));background:var(--bg-elevated, var(--surface-container));'
        + 'color:var(--text-primary, var(--on-surface));margin:6px 8px 0 0;';
    var TITEL = 'font-size:17px;font-weight:700;margin:18px 0 6px;';
    var NOTIZ = 'font-size:15px;line-height:1.5;margin:0 0 8px;opacity:.85;';
    var GRUEN = '#B5F23D';
    /** Feste, gut unterscheidbare Farben zur schnellen Wahl. Freie Wahl daneben über den Farbwähler. */
    var PALETTE = ['#000000', '#0E1620', '#1B2A3A', '#2B2B2B', '#FFFFFF', '#EEF4F8', '#FFD400', '#B5F23D',
        '#7FE3C4', '#4FD1FF', '#FF3B1F', '#FFB020', '#C4581F', '#A57CFF'];
    var RASTER = 25, FANG = 10, MAX_SCHRITTE = 60;

    function api(methode, pfad, body) {
        var o = { method: methode, cache: 'no-store', headers: { 'Content-Type': 'application/json' } };
        if (body) o.body = JSON.stringify(body);
        return fetch(pfad, o).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok && j && j.ok !== false, status: r.status, j: j || {} }; },
                function () { return { ok: false, status: r.status, j: {} }; });
        });
    }

    function kopie(o) { return JSON.parse(JSON.stringify(o)); }

    function warnText(w) {
        var m = {
            kontrast: t('tacho_gestalten.warn_kontrast_frei', 'Ein Element war schlecht lesbar. Es wird weiß oder schwarz.'),
            tempo_pflicht: t('tacho_gestalten.warn_tempo_frei', 'Die Geschwindigkeit muss immer sichtbar sein. Sie wurde wieder eingefügt.'),
            tempo_einmal: t('tacho_gestalten.warn_tempo_einmal', 'Die Geschwindigkeit darf nur einmal vorkommen.'),
            tempo_groesse: t('tacho_gestalten.warn_tempo_groesse', 'Die Geschwindigkeit war zu klein und wurde vergrößert.'),
            farbe_ungueltig: t('tacho_gestalten.warn_farbe', 'Eine Farbe war ungültig und wurde ersetzt.'),
            bild_ungueltig: t('tacho_gestalten.warn_bild_ungueltig', 'Das Bild war zu groß oder kein Bild und wurde weggelassen.'),
            bild_kontrast: t('tacho_gestalten.warn_bild_kontrast', 'Auf diesem Hintergrund wäre mit Bild nicht alles lesbar. Das Bild wurde weggelassen.'),
            zu_viele: t('tacho_gestalten.warn_zu_viele', 'Höchstens 40 Elemente. Der Rest wurde weggelassen.')
        };
        return m[w] || '';
    }

    function wertName(a) {
        var f = { tempo: 'Geschwindigkeit', akku: 'Akku %', reichweite: 'Reichweite', leistung: 'Leistung (kW)',
            aussen: 'Außentemperatur', kmstand: 'km-Stand' };
        return t('tacho_gestalten.a_' + a, f[a] || a);
    }

    /** Neue Elemente: Name, Vorlage (Größe in Einheiten der Fläche). */
    var NEU = [
        ['n_rund', 'Rundinstrument', { typ: 'rund', wert: 'leistung', form: 'ring', b: 260, h: 260 }],
        ['n_zahl', 'Zahl groß', { typ: 'zahl', wert: 'akku', b: 260, h: 120 }],
        ['n_balken_w', 'Balken waagrecht', { typ: 'balken', wert: 'akku', richtung: 'waagrecht', b: 320, h: 30 }],
        ['n_balken_s', 'Balken senkrecht', { typ: 'balken', wert: 'leistung', richtung: 'senkrecht', b: 30, h: 260 }],
        ['n_gang', 'Gang', { typ: 'gang', b: 120, h: 90 }],
        ['n_navi', 'Navi-Pfeil', { typ: 'navi', b: 260, h: 220 }],
        ['n_uhr', 'Uhr', { typ: 'uhr', b: 200, h: 60 }],
        ['n_akku', 'Akku', { typ: 'zahl', wert: 'akku', b: 200, h: 60 }],
        ['n_reichweite', 'Reichweite', { typ: 'zahl', wert: 'reichweite', b: 220, h: 60 }],
        ['n_kw', 'kW', { typ: 'zahl', wert: 'leistung', b: 200, h: 60 }],
        ['n_temperatur', 'Temperatur', { typ: 'zahl', wert: 'aussen', b: 160, h: 60 }],
        ['n_kmstand', 'km-Stand', { typ: 'zahl', wert: 'kmstand', b: 240, h: 50 }],
        ['n_ring', 'Ring', { typ: 'ring', b: 200, h: 200, dicke: 6 }],
        ['n_linie', 'Linie', { typ: 'linie', b: 400, h: 10, dicke: 4 }],
        ['n_flaeche', 'Fläche', { typ: 'flaeche', b: 300, h: 200, ecken: 20, farbe: '#1B2A3A' }],
        ['n_verlauf', 'Verlauf', { typ: 'verlauf', b: 400, h: 200, farbe: '#0E1620', farbe2: '#1B2A3A', richtung: 'waagrecht' }]
    ];
    function typName(x) {
        if (x.typ === 'zahl' || x.typ === 'rund' || x.typ === 'balken') return wertName(x.wert);
        for (var i = 0; i < NEU.length; i++) if (NEU[i][2].typ === x.typ) return t('tacho_gestalten.' + NEU[i][0], NEU[i][1]);
        return x.typ;
    }

    function mount(box) {
        var T = window.PanoptesTacho;
        if (!T || !T.pruefeFrei) return;
        var R = T.REGELN;
        var stand = { imStand: false, geladen: false };
        var e = T.leerFrei();
        var wahl = -1, rueckListe = [], vorListe = [], rasterAn = true;
        var vorschau = null, takt = null, start = Date.now();

        var oeffnen = el('button', GROSS_KNOPF + 'width:100%;', t('tacho_gestalten.knopf', 'Tacho gestalten'));
        oeffnen.type = 'button';
        oeffnen.id = 'tgOeffnen';
        var editor = el('div', 'margin-top:10px;');
        editor.hidden = true;
        box.appendChild(oeffnen);
        box.appendChild(editor);

        var sperre = el('div', 'padding:12px;border-radius:12px;font-size:16px;font-weight:700;line-height:1.5;'
            + 'border:2px solid var(--status-warning, #FFB547);margin-bottom:10px;',
            t('tacho_gestalten.fahrsperre', 'Während der Fahrt nicht bearbeitbar. Halte an und lege Gang P ein.'));
        sperre.id = 'tgSperre';
        sperre.hidden = true;
        editor.appendChild(sperre);
        editor.appendChild(el('p', NOTIZ, t('tacho_gestalten.lead_frei',
            'Baue deinen Tacho frei zusammen: Elemente hinzufügen, mit dem Finger verschieben, an der Ecke größer ziehen. Erst „Speichern“ übernimmt es.')));
        var ehrlich = el('p', NOTIZ + 'font-weight:700;', t('tacho_gestalten.nur_projektion',
            'Im BYD-Tacho erscheint dein Design nur bei Autos mit Tacho-Projektion. Beim Dolphin Surf (Single-OS) siehst du es nur hier als Vorschau.'));
        ehrlich.id = 'tgNurProjektion';
        editor.appendChild(ehrlich);

        var steuer = [];   // alles, was die Fahrsperre abschaltet
        var panelSteuer = [];
        function knopf(text, beiTipp, extra, liste) {
            var b = el('button', GROSS_KNOPF + (extra || ''), text);
            b.type = 'button';
            b.onclick = beiTipp;
            (liste || steuer).push(b);
            return b;
        }

        // ---- Startpunkt
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.start_titel', 'Beginnen mit')));
        var startReihe = el('div', '');
        startReihe.appendChild(knopf(t('tacho_gestalten.leer', 'Leer'), function () {
            merken(); e = T.leerFrei(); wahl = -1; allesZeigen(); meldung('');
        }, 'border-color:' + GRUEN + ';'));
        T.DESIGNS.forEach(function (d) {
            startReihe.appendChild(knopf(t('vehicle_control.cluster_design_' + d, d.charAt(0).toUpperCase() + d.slice(1)),
                function () { vonDesign(d); }));
        });
        editor.appendChild(startReihe);

        function vonDesign(d) {
            merken();
            var z = T.zuElementen(T.vorlage(d), d);
            e = z.eigen;
            wahl = -1;
            allesZeigen();
            meldung(z.zerlegt ? t('tacho_gestalten.zerlegt_ok', 'Design in Elemente zerlegt. Jetzt frei ändern.')
                : t('tacho_gestalten.zerlegt_teilweise', 'Dieses Design hat eine eigene Zeichnung, die sich nicht zerlegen lässt. Übernommen sind Farben und Anordnung als Elemente.'), !z.zerlegt);
        }

        // ---- Fläche mit Griffen
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.flaeche_titel', 'Dein Tacho')));
        editor.appendChild(el('p', NOTIZ, t('tacho_gestalten.flaeche_hilfe',
            'Tippe ein Element an, um es zu wählen. Ziehen verschiebt es, die grüne Ecke ändert die Größe.')));
        var bild = el('div', 'position:relative;padding-top:45%;border-radius:12px;overflow:hidden;background:#000;');
        var cv = el('canvas', 'position:absolute;left:0;top:0;width:100%;height:100%;');
        var deck = el('div', 'position:absolute;left:0;top:0;width:100%;height:100%;touch-action:none;cursor:pointer;');
        deck.id = 'tgFlaeche';
        var rahmen = el('div', 'position:absolute;border:3px dashed ' + GRUEN + ';box-sizing:border-box;pointer-events:none;display:none;');
        var griff = el('div', 'position:absolute;width:56px;height:56px;margin:-56px 0 0 -56px;border-radius:28px;background:' + GRUEN
            + ';border:3px solid #000;box-sizing:border-box;display:none;');
        griff.id = 'tgGriff';
        griff.setAttribute('aria-label', t('tacho_gestalten.groesse_griff', 'Größe ändern'));
        var linieX = el('div', 'position:absolute;top:0;bottom:0;width:2px;background:#FF3BD4;display:none;pointer-events:none;');
        var linieY = el('div', 'position:absolute;left:0;right:0;height:2px;background:#FF3BD4;display:none;pointer-events:none;');
        bild.appendChild(cv);
        bild.appendChild(deck);
        deck.appendChild(rahmen);
        deck.appendChild(linieX);
        deck.appendChild(linieY);
        deck.appendChild(griff);
        editor.appendChild(bild);

        var werkzeug = el('div', 'display:flex;flex-wrap:wrap;align-items:center;');
        var rueckKn = knopf('↶ ' + t('tacho_gestalten.rueck', 'Rückgängig'), function () { schritt(rueckListe, vorListe); });
        var vorKn = knopf('↷ ' + t('tacho_gestalten.vor', 'Wiederholen'), function () { schritt(vorListe, rueckListe); });
        werkzeug.appendChild(rueckKn);
        werkzeug.appendChild(vorKn);
        var rasterLab = el('label', 'display:inline-flex;align-items:center;min-height:56px;font-size:17px;font-weight:700;margin:6px 8px 0 0;');
        var rasterCb = el('input', 'width:32px;height:32px;margin-right:8px;');
        rasterCb.type = 'checkbox';
        rasterCb.checked = true;
        rasterCb.id = 'tgRaster';
        rasterCb.onchange = function () { rasterAn = rasterCb.checked; };
        steuer.push(rasterCb);
        rasterLab.appendChild(rasterCb);
        rasterLab.appendChild(document.createTextNode(t('tacho_gestalten.raster', 'Am Raster einrasten')));
        werkzeug.appendChild(rasterLab);
        editor.appendChild(werkzeug);
        var warnZeile = el('p', 'font-size:16px;margin:8px 0 0;line-height:1.5;');
        warnZeile.setAttribute('aria-live', 'polite');
        editor.appendChild(warnZeile);

        // ---- Hinzufügen
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.hinzufuegen_titel', 'Hinzufügen')));
        var neuReihe = el('div', '');
        NEU.forEach(function (n) {
            neuReihe.appendChild(knopf('+ ' + t('tacho_gestalten.' + n[0], n[1]), function () { hinzufuegen(n[2]); }));
        });
        editor.appendChild(neuReihe);

        // ---- Gewähltes Element
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.auswahl_titel', 'Gewähltes Element')));
        var panel = el('div', '');
        panel.id = 'tgPanel';
        editor.appendChild(panel);

        // ---- Hintergrund
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.hg_titel', 'Hintergrund')));
        var hgPanel = el('div', '');
        editor.appendChild(hgPanel);
        var bildDatei = el('input', 'display:none;');
        bildDatei.type = 'file';
        bildDatei.accept = 'image/png,image/jpeg';
        bildDatei.onchange = function () { bildLaden(bildDatei.files && bildDatei.files[0]); bildDatei.value = ''; };
        editor.appendChild(bildDatei);

        // ---- Speichern, Datei, Standard
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.sichern_titel', 'Übernehmen')));
        var reiheS = el('div', '');
        reiheS.appendChild(knopf(t('tacho_gestalten.speichern_zeigen', 'Speichern und am Tacho zeigen'), function () { speichern(true); },
            'border-color:' + GRUEN + ';'));
        reiheS.appendChild(knopf(t('tacho_gestalten.speichern', 'Nur speichern'), function () { speichern(false); }));
        editor.appendChild(reiheS);
        var reiheD = el('div', '');
        reiheD.appendChild(knopf(t('tacho_gestalten.export', 'Als Datei sichern'), exportieren));
        var datei = el('input', 'display:none;');
        datei.type = 'file';
        datei.accept = '.json,application/json';
        datei.onchange = function () { importieren(datei.files && datei.files[0]); datei.value = ''; };
        reiheD.appendChild(datei);
        reiheD.appendChild(knopf(t('tacho_gestalten.import', 'Datei laden'), function () { datei.click(); }));
        reiheD.appendChild(knopf(t('tacho_gestalten.standard', 'Zurück zum Standard'), function () {
            merken(); e = T.zuElementen(T.standardEigen(), 'klar').eigen; wahl = -1; allesZeigen();
            meldung(t('tacho_gestalten.standard_info', 'Standard geladen. Erst „Speichern“ übernimmt es.'));
        }));
        editor.appendChild(reiheD);
        editor.appendChild(el('p', NOTIZ, t('tacho_gestalten.datei_info_frei',
            'Die Datei enthält nur Elemente, Farben und höchstens ein kleines Hintergrundbild, keine Orte, Pfade oder Zugangsdaten.')));
        var meld = el('div', 'font-size:16px;line-height:1.5;margin-top:8px;min-height:24px;');
        meld.id = 'tgMeldung';
        meld.setAttribute('aria-live', 'polite');
        editor.appendChild(meld);

        function meldung(text, schlecht) {
            meld.textContent = text || '';
            meld.style.color = schlecht ? 'var(--status-danger, var(--status-error))' : '';
        }
        function warnungen(liste) {
            return (liste || []).map(warnText).filter(function (x) { return x; }).join(' ');
        }

        // ---- Rückgängig / Wiederholen
        function merken() {
            rueckListe.push(JSON.stringify(e));
            if (rueckListe.length > MAX_SCHRITTE) rueckListe.shift();
            vorListe = [];
        }
        function schritt(von, nach) {
            if (!von.length || !stand.imStand) return;
            nach.push(JSON.stringify(e));
            e = JSON.parse(von.pop());
            if (wahl >= e.elemente.length) wahl = -1;
            allesZeigen();
        }

        // ---- Elemente
        function lesbar(hg) { return T.kontrast('#FFFFFF', hg) >= T.kontrast('#000000', hg) ? '#FFFFFF' : '#000000'; }
        function istTempo(x) { return x && x.wert === 'tempo'; }

        function hinzufuegen(v) {
            if (e.elemente.length >= R.MAX_ELEMENTE) { meldung(warnText('zu_viele'), true); return; }
            merken();
            var x = kopie(v);
            x.x = Math.round((R.BREITE - x.b) / 2);
            x.y = Math.round((R.HOEHE - x.h) / 2);
            if (!x.farbe) x.farbe = lesbar(e.hintergrund.farbe);
            if (x.typ === 'rund' || x.typ === 'balken') x.akzent = GRUEN;
            e.elemente.push(T.elementPruefen(x, []));
            wahl = e.elemente.length - 1;
            allesZeigen();
            meldung('');
        }

        function loeschen() {
            if (wahl < 0) return;
            if (istTempo(e.elemente[wahl])) {
                meldung(t('tacho_gestalten.tempo_bleibt_frei', 'Die Geschwindigkeit muss sichtbar bleiben und lässt sich nicht löschen.'), true);
                return;
            }
            merken();
            e.elemente.splice(wahl, 1);
            wahl = -1;
            allesZeigen();
        }

        function ebene(richtung) {
            var n = wahl + richtung;
            if (wahl < 0 || n < 0 || n >= e.elemente.length) return;
            merken();
            var x = e.elemente[wahl];
            e.elemente[wahl] = e.elemente[n];
            e.elemente[n] = x;
            wahl = n;
            allesZeigen();
        }

        function mindest(x) {
            return istTempo(x) ? [R.TEMPO_MIN_BREITE, R.TEMPO_MIN_HOEHE] : [R.MIN_GROESSE, R.MIN_GROESSE];
        }
        function einpassen(x) {
            var m = mindest(x);
            x.b = Math.max(m[0], Math.min(R.BREITE, Math.round(x.b)));
            x.h = Math.max(m[1], Math.min(R.HOEHE, Math.round(x.h)));
            x.x = Math.max(0, Math.min(R.BREITE - x.b, Math.round(x.x)));
            x.y = Math.max(0, Math.min(R.HOEHE - x.h, Math.round(x.y)));
        }

        function groesse(faktor) {
            if (wahl < 0) return;
            merken();
            var x = e.elemente[wahl], cx = x.x + x.b / 2, cy = x.y + x.h / 2;
            x.b *= faktor; x.h *= faktor;
            x.x = cx - x.b / 2; x.y = cy - x.h / 2;
            einpassen(x);
            allesZeigen();
        }

        function aendern(feld, wert) {
            if (wahl < 0) return;
            var x = e.elemente[wahl];
            if (feld === 'wert' && wert === 'tempo' && !istTempo(x)) {
                meldung(t('tacho_gestalten.tempo_da', 'Die Geschwindigkeit gibt es schon. Sie darf nur einmal vorkommen.'), true);
                allesZeigen();
                return;
            }
            if (feld === 'wert' && istTempo(x) && wert !== 'tempo') {
                meldung(t('tacho_gestalten.tempo_bleibt_frei', 'Die Geschwindigkeit muss sichtbar bleiben und lässt sich nicht löschen.'), true);
                allesZeigen();
                return;
            }
            merken();
            x[feld] = wert;
            if (feld === 'wert' && (x.typ === 'rund' || x.typ === 'balken')) { delete x.min; delete x.max; }
            // Nur in Form bringen (Erlaubnislisten, Skala); die Farbe bleibt, der Kontrast steht darunter.
            e.elemente[wahl] = T.elementPruefen(x, []);
            einpassen(e.elemente[wahl]);
            meldung('');
            allesZeigen();
        }

        // ---- Ziehen mit dem Finger (Pointer Events, Chromium 74)
        var zug = null;
        function punkt(ev) {
            var r = deck.getBoundingClientRect();
            return { x: (ev.clientX - r.left) / r.width * R.BREITE, y: (ev.clientY - r.top) / r.height * R.HOEHE };
        }
        function treffer(p) {
            for (var i = e.elemente.length - 1; i >= 0; i--) {
                var x = e.elemente[i], rand = 12;
                if (p.x >= x.x - rand && p.x <= x.x + x.b + rand && p.y >= x.y - rand && p.y <= x.y + x.h + rand) return i;
            }
            return -1;
        }
        /** Einrasten: zuerst an Hilfslinien (Mitte, Ränder, andere Elemente), sonst am Raster. */
        function fang(wert, groesse, linien, achse) {
            var beste = null;
            [0, 0.5, 1].forEach(function (k) {
                linien.forEach(function (l) {
                    var d = l - (wert + groesse * k);
                    if (Math.abs(d) <= FANG && (beste === null || Math.abs(d) < Math.abs(beste.d))) beste = { d: d, l: l };
                });
            });
            var lin = achse === 'x' ? linieX : linieY;
            if (beste) {
                lin.style.display = 'block';
                if (achse === 'x') lin.style.left = (beste.l / R.BREITE * 100) + '%'; else lin.style.top = (beste.l / R.HOEHE * 100) + '%';
                return wert + beste.d;
            }
            lin.style.display = 'none';
            return rasterAn ? Math.round(wert / RASTER) * RASTER : wert;
        }
        function linien(achse) {
            var l = achse === 'x' ? [0, R.BREITE / 2, R.BREITE] : [0, R.HOEHE / 2, R.HOEHE];
            e.elemente.forEach(function (x, i) {
                if (i === wahl) return;
                if (achse === 'x') l.push(x.x, x.x + x.b / 2, x.x + x.b); else l.push(x.y, x.y + x.h / 2, x.y + x.h);
            });
            return l;
        }
        deck.addEventListener('pointerdown', function (ev) {
            if (!stand.imStand) { sperren(); return; }
            var p = punkt(ev), art = ev.target === griff ? 'groesse' : null;
            if (!art) {
                var i = treffer(p);
                if (i !== wahl) { wahl = i; allesZeigen(); }
                if (i < 0) return;
                art = 'ziehen';
            }
            var x = e.elemente[wahl];
            zug = { art: art, vorher: JSON.stringify(e), dx: p.x - x.x, dy: p.y - x.y, rx: x.x + x.b - p.x, ry: x.y + x.h - p.y, geaendert: false };
            try { deck.setPointerCapture(ev.pointerId); } catch (f) { }
            ev.preventDefault();
        });
        deck.addEventListener('pointermove', function (ev) {
            if (!zug || wahl < 0) return;
            var p = punkt(ev), x = e.elemente[wahl], m = mindest(x);
            if (zug.art === 'ziehen') {
                x.x = fang(p.x - zug.dx, x.b, linien('x'), 'x');
                x.y = fang(p.y - zug.dy, x.h, linien('y'), 'y');
            } else {
                var rx = fang(p.x + zug.rx, 0, linien('x'), 'x'), ry = fang(p.y + zug.ry, 0, linien('y'), 'y');
                x.b = Math.max(m[0], rx - x.x);
                x.h = Math.max(m[1], ry - x.y);
            }
            einpassen(x);
            zug.geaendert = true;
            allesZeigen(true);
            ev.preventDefault();
        });
        function zugEnde() {
            if (zug && zug.geaendert) {
                rueckListe.push(zug.vorher);
                if (rueckListe.length > MAX_SCHRITTE) rueckListe.shift();
                vorListe = [];
            }
            zug = null;
            linieX.style.display = 'none';
            linieY.style.display = 'none';
            allesZeigen();
        }
        deck.addEventListener('pointerup', zugEnde);
        deck.addEventListener('pointercancel', zugEnde);

        // ---- Bedienfelder
        function titel(ziel, text) { ziel.appendChild(el('div', 'font-size:15px;font-weight:700;margin:12px 0 2px;', text)); }
        function wahlKnoepfe(ziel, text, liste, aktuell, namen, beiWahl) {
            titel(ziel, text);
            var r = el('div', '');
            liste.forEach(function (w) {
                var b = knopf(namen[w] || w, function () { beiWahl(w); }, w === aktuell ? 'border-color:' + GRUEN + ';' : '', panelSteuer);
                b.setAttribute('aria-pressed', w === aktuell ? 'true' : 'false');
                r.appendChild(b);
            });
            ziel.appendChild(r);
        }
        function farbWahl(ziel, text, aktuell, beiWahl) {
            titel(ziel, text);
            var r = el('div', 'display:flex;flex-wrap:wrap;align-items:center;');
            PALETTE.forEach(function (hex) {
                var b = knopf('', function () { beiWahl(hex); }, 'width:52px;min-height:52px;padding:0;background:' + hex + ';'
                    + (hex === aktuell ? 'outline:4px solid ' + GRUEN + ';' : ''), panelSteuer);
                b.setAttribute('aria-label', text + ' ' + hex);
                b.setAttribute('data-farbe', hex);
                r.appendChild(b);
            });
            var frei = el('input', 'width:64px;height:52px;margin:6px 8px 0 0;border-radius:12px;cursor:pointer;');
            frei.type = 'color';
            frei.value = (aktuell || '#000000').toLowerCase();
            frei.setAttribute('aria-label', text + ' – ' + t('tacho_gestalten.frei', 'eigene Farbe'));
            frei.onchange = function () { beiWahl(String(frei.value).toUpperCase()); };
            panelSteuer.push(frei);
            r.appendChild(frei);
            ziel.appendChild(r);
        }
        function schieber(ziel, text, min, max, schrittW, aktuell, beiWahl) {
            titel(ziel, text + ': ' + aktuell);
            var s = el('input', 'width:100%;height:48px;');
            s.type = 'range'; s.min = min; s.max = max; s.step = schrittW; s.value = aktuell;
            s.setAttribute('aria-label', text);
            s.onchange = function () { beiWahl(Number(s.value)); };
            panelSteuer.push(s);
            ziel.appendChild(s);
        }
        function zahlFeld(ziel, text, aktuell, beiWahl) {
            var lab = el('label', 'display:inline-block;font-size:15px;font-weight:700;margin:8px 12px 0 0;', text + ' ');
            var f = el('input', 'width:110px;min-height:52px;font-size:18px;padding:6px;border-radius:10px;');
            f.type = 'number'; f.value = aktuell; f.min = -1000; f.max = 1000;
            f.onchange = function () { var v = Number(f.value); if (isFinite(v)) beiWahl(Math.round(v)); };
            panelSteuer.push(f);
            lab.appendChild(f);
            ziel.appendChild(lab);
        }

        var SCHRIFT_N = { schmal: t('tacho_gestalten.s_schmal', 'Schmal'), normal: t('tacho_gestalten.s_normal', 'Normal'), breit: t('tacho_gestalten.s_breit', 'Breit') };
        var FORM_N = { nadel: t('tacho_gestalten.z_nadel', 'Nadel'), balken: t('tacho_gestalten.z_balken', 'Balken'), ring: t('tacho_gestalten.z_ring', 'Ring') };
        var RICHT_N = { waagrecht: t('tacho_gestalten.r_waagrecht', 'Waagrecht'), senkrecht: t('tacho_gestalten.r_senkrecht', 'Senkrecht') };
        var AUS_N = { links: t('tacho_gestalten.au_links', 'Links'), mitte: t('tacho_gestalten.au_mitte', 'Mitte'), rechts: t('tacho_gestalten.au_rechts', 'Rechts') };

        function panelBauen() {
            panel.textContent = '';
            panelSteuer = [];
            if (wahl < 0 || !e.elemente[wahl]) {
                panel.appendChild(el('p', NOTIZ, t('tacho_gestalten.auswahl_keins', 'Kein Element gewählt. Tippe im Bild auf ein Element.')));
                return;
            }
            var x = e.elemente[wahl];
            panel.appendChild(el('div', 'font-size:17px;font-weight:700;', typName(x)));
            var k = T.kontrastElement(e, wahl);
            if (k !== null) {
                var gut = k >= R.MIN_KONTRAST, kz = el('p', 'font-size:16px;margin:6px 0 0;' + (gut ? '' : 'font-weight:700;color:var(--status-danger, var(--status-error));'),
                    t('tacho_gestalten.kontrast_element', 'Kontrast zum Hintergrund') + ': ' + k.toFixed(1).replace('.', ',') + ' : 1  '
                    + (gut ? '✓' : '– ' + t('tacho_gestalten.kontrast_zu_wenig_frei', 'zu wenig. Beim Speichern wird es weiß oder schwarz.')));
                kz.id = 'tgKontrast';
                panel.appendChild(kz);
            }
            if (x.typ === 'rund' || x.typ === 'zahl' || x.typ === 'balken') {
                var werte = x.typ === 'rund' ? R.RUND_WERTE : x.typ === 'zahl' ? R.ZAHL_WERTE : R.BALKEN_WERTE, wn = {};
                werte.forEach(function (w) { wn[w] = wertName(w); });
                wahlKnoepfe(panel, t('tacho_gestalten.wert', 'Zeigt'), werte, x.wert, wn, function (w) { aendern('wert', w); });
            }
            if (x.typ === 'rund') wahlKnoepfe(panel, t('tacho_gestalten.form', 'Form'), R.ZEIGERFORMEN, x.form, FORM_N, function (w) { aendern('form', w); });
            if (x.typ === 'balken' || x.typ === 'verlauf') wahlKnoepfe(panel, t('tacho_gestalten.richtung', 'Richtung'), R.RICHTUNGEN, x.richtung, RICHT_N, function (w) { aendern('richtung', w); });
            if (x.typ === 'rund' || x.typ === 'balken') {
                titel(panel, t('tacho_gestalten.skala', 'Skala'));
                zahlFeld(panel, t('tacho_gestalten.min', 'von'), x.min, function (v) { aendern('min', v); });
                zahlFeld(panel, t('tacho_gestalten.max', 'bis'), x.max, function (v) { aendern('max', v); });
            }
            if (x.typ === 'rund') schieber(panel, t('tacho_gestalten.drehung', 'Drehung (Grad)'), -180, 180, 15, x.drehung, function (v) { aendern('drehung', v); });
            farbWahl(panel, x.typ === 'balken' ? t('tacho_gestalten.f_spur', 'Spur') : t('tacho_gestalten.farbe', 'Farbe'), x.farbe, function (h) { aendern('farbe', h); });
            if (x.typ === 'rund' || x.typ === 'balken') farbWahl(panel, t('tacho_gestalten.f_zeiger', 'Zeiger und Balken'), x.akzent, function (h) { aendern('akzent', h); });
            if (x.typ === 'verlauf') farbWahl(panel, t('tacho_gestalten.farbe2', 'Zweite Farbe'), x.farbe2, function (h) { aendern('farbe2', h); });
            if (x.schrift) wahlKnoepfe(panel, t('tacho_gestalten.schrift_titel', 'Schrift'), R.SCHRIFTEN, x.schrift, SCHRIFT_N, function (w) { aendern('schrift', w); });
            if (x.ausrichtung) wahlKnoepfe(panel, t('tacho_gestalten.ausrichtung', 'Ausrichtung'), R.AUSRICHTUNGEN, x.ausrichtung, AUS_N, function (w) { aendern('ausrichtung', w); });
            if (x.typ === 'ring' || x.typ === 'linie') schieber(panel, t('tacho_gestalten.dicke', 'Dicke'), 1, 60, 1, x.dicke, function (v) { aendern('dicke', v); });
            if (x.typ === 'flaeche') schieber(panel, t('tacho_gestalten.ecken', 'Runde Ecken (%)'), 0, 100, 5, x.ecken, function (v) { aendern('ecken', v); });
            titel(panel, t('tacho_gestalten.anordnen', 'Größe und Ebene'));
            var r = el('div', '');
            r.appendChild(knopf('− ' + t('tacho_gestalten.kleiner', 'Kleiner'), function () { groesse(0.9); }, '', panelSteuer));
            r.appendChild(knopf('+ ' + t('tacho_gestalten.groesser', 'Größer'), function () { groesse(1.1); }, '', panelSteuer));
            r.appendChild(knopf(t('tacho_gestalten.vorne', 'Nach vorne'), function () { ebene(1); }, '', panelSteuer));
            r.appendChild(knopf(t('tacho_gestalten.hinten', 'Nach hinten'), function () { ebene(-1); }, '', panelSteuer));
            if (!istTempo(x)) r.appendChild(knopf(t('tacho_gestalten.loeschen', 'Löschen'), loeschen, 'border-color:var(--status-danger, #FF5A5A);', panelSteuer));
            panel.appendChild(r);
        }

        var hgSteuer = [];
        function hgBauen() {
            hgPanel.textContent = '';
            hgSteuer = [];
            var save = panelSteuer;
            panelSteuer = hgSteuer;
            var hg = e.hintergrund;
            wahlKnoepfe(hgPanel, t('tacho_gestalten.hg_art', 'Art'), R.HG_ARTEN, hg.art,
                { farbe: t('tacho_gestalten.hg_farbe', 'Eine Farbe'), verlauf: t('tacho_gestalten.hg_verlauf', 'Verlauf') },
                function (w) { hgAendern('art', w); });
            farbWahl(hgPanel, t('tacho_gestalten.f_hintergrund', 'Hintergrund'), hg.farbe, function (h) { hgAendern('farbe', h); });
            if (hg.art === 'verlauf') {
                farbWahl(hgPanel, t('tacho_gestalten.farbe2', 'Zweite Farbe'), hg.farbe2, function (h) { hgAendern('farbe2', h); });
                wahlKnoepfe(hgPanel, t('tacho_gestalten.richtung', 'Richtung'), R.RICHTUNGEN, hg.richtung, RICHT_N, function (w) { hgAendern('richtung', w); });
            }
            titel(hgPanel, t('tacho_gestalten.bild_titel', 'Eigenes Bild'));
            hgPanel.appendChild(el('p', NOTIZ, t('tacho_gestalten.bild_hilfe',
                'Wird verkleinert und liegt immer abgedunkelt unter dem Tacho. Gespeichert wird nur das kleine Bild, kein Pfad.')));
            var r = el('div', '');
            r.appendChild(knopf(t('tacho_gestalten.bild_waehlen', 'Bild wählen'), function () { bildDatei.click(); }, '', hgSteuer));
            if (e.bild) r.appendChild(knopf(t('tacho_gestalten.bild_weg', 'Bild entfernen'), function () { merken(); delete e.bild; allesZeigen(); }, '', hgSteuer));
            hgPanel.appendChild(r);
            panelSteuer = save;
        }
        function hgAendern(feld, wert) {
            merken();
            e.hintergrund[feld] = wert;
            allesZeigen();
        }

        /** Bild verkleinern (höchstens 480 × 216 Bildpunkte, JPEG), bis es unter die Grenze passt. Nur Bilddaten, kein Pfad. */
        function bildLaden(f) {
            if (!f || !stand.imStand) return;
            if (!/^image\/(png|jpeg)$/.test(f.type || '')) { meldung(t('tacho_gestalten.bild_falsch', 'Nur PNG- oder JPEG-Bilder.'), true); return; }
            var r = new FileReader();
            r.onload = function () {
                var im = new Image();
                im.onload = function () {
                    var bw = 480, bh = 216, daten = null;
                    for (var versuch = 0; versuch < 4 && !daten; versuch++) {
                        var c = document.createElement('canvas');
                        c.width = bw; c.height = bh;
                        var k = Math.max(bw / im.naturalWidth, bh / im.naturalHeight), iw = im.naturalWidth * k, ih = im.naturalHeight * k;
                        c.getContext('2d').drawImage(im, (bw - iw) / 2, (bh - ih) / 2, iw, ih);
                        for (var q = 0.7; q >= 0.3 && !daten; q -= 0.1) {
                            var s = c.toDataURL('image/jpeg', q);
                            if (s.length <= R.MAX_BILD) daten = s;
                        }
                        bw = Math.round(bw * 0.75); bh = Math.round(bh * 0.75);
                    }
                    if (!daten) { meldung(t('tacho_gestalten.bild_zu_gross', 'Das Bild lässt sich nicht klein genug machen.'), true); return; }
                    merken();
                    e.bild = daten;
                    var g = T.pruefeFrei(kopie(e));
                    allesZeigen();
                    var w = warnungen(g.warnungen);
                    meldung(t('tacho_gestalten.bild_ok', 'Bild gesetzt. Erst „Speichern“ übernimmt es.') + (w ? ' ' + w : ''), !!w);
                };
                im.onerror = function () { meldung(t('tacho_gestalten.bild_falsch', 'Nur PNG- oder JPEG-Bilder.'), true); };
                im.src = String(r.result || '');
            };
            r.readAsDataURL(f);
        }

        function allesZeigen(nurBild) {
            if (vorschau) vorschau.eigen(e);
            var x = wahl >= 0 ? e.elemente[wahl] : null;
            rahmen.style.display = griff.style.display = x ? 'block' : 'none';
            if (x) {
                rahmen.style.left = (x.x / R.BREITE * 100) + '%';
                rahmen.style.top = (x.y / R.HOEHE * 100) + '%';
                rahmen.style.width = (x.b / R.BREITE * 100) + '%';
                rahmen.style.height = (x.h / R.HOEHE * 100) + '%';
                griff.style.left = ((x.x + x.b) / R.BREITE * 100) + '%';
                griff.style.top = ((x.y + x.h) / R.HOEHE * 100) + '%';
            }
            if (nurBild) return;
            rueckKn.disabled = !rueckListe.length || !stand.imStand;
            vorKn.disabled = !vorListe.length || !stand.imStand;
            var g = T.pruefeFrei(kopie(e));
            warnZeile.textContent = warnungen(g.warnungen);
            warnZeile.style.color = g.warnungen.length ? 'var(--status-danger, var(--status-error))' : '';
            panelBauen();
            hgBauen();
            sperren();
        }

        function sperren() {
            var zu = !stand.imStand;
            sperre.hidden = !zu;
            deck.style.pointerEvents = zu ? 'none' : '';
            steuer.concat(panelSteuer, hgSteuer).forEach(function (x) { x.disabled = zu; x.style.opacity = zu ? '.5' : ''; });
            if (!zu) { rueckKn.disabled = !rueckListe.length; vorKn.disabled = !vorListe.length; }
        }

        function speichern(waehlen) {
            if (!stand.imStand) { sperren(); return; }
            var g = T.pruefeEigen(kopie(e));
            if (!g) return;
            // Der Kern prüft noch einmal; Warnungen beider Prüfungen (z. B. Kontrast) werden gezeigt.
            api('PUT', '/api/tacho/eigen', { eigen: g.eigen, waehlen: waehlen }).then(function (x) {
                if (x.status === 409) { stand.imStand = false; sperren(); return; }
                if (!x.ok) { meldung(t('tacho_gestalten.fehler', 'Speichern hat nicht geklappt. Der Tacho ist unverändert.'), true); return; }
                e = x.j.eigen && x.j.eigen.version === R.VERSION_FREI ? x.j.eigen : g.eigen;
                if (wahl >= e.elemente.length) wahl = -1;
                allesZeigen();
                var w = warnungen(g.warnungen.concat(x.j.warnungen || []).filter(function (v, i, a) { return a.indexOf(v) === i; }));
                meldung((waehlen ? t('tacho_gestalten.ok_zeigen', 'Gespeichert. Ist der Panoptes-Tacho an, siehst du es sofort.')
                    : t('tacho_gestalten.ok', 'Gespeichert. Unter „Was zeigt der Tacho?“ heißt es „Eigenes“.')) + (w ? ' ' + w : ''), !!w);
            }, function () { meldung(t('tacho_gestalten.fehler', 'Speichern hat nicht geklappt. Der Tacho ist unverändert.'), true); });
        }

        function exportieren() {
            var g = T.pruefeEigen(kopie(e));
            if (!g) return;
            var text = JSON.stringify(g.eigen, null, 1);
            try {
                var a = document.createElement('a');
                a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
                a.download = 'panoptes-tacho-design.json';
                document.body.appendChild(a);
                a.click();
                setTimeout(function () { URL.revokeObjectURL(a.href); a.parentNode.removeChild(a); }, 1000);
                meldung(t('tacho_gestalten.export_ok', 'Datei gesichert: panoptes-tacho-design.json'));
            } catch (x) {
                meldung(t('tacho_gestalten.export_fehler', 'Sichern als Datei geht hier nicht.'), true);
            }
        }

        /** Version 1 (feste Plätze) wird beim Laden in Elemente zerlegt; Version 2 bleibt, wie sie ist. */
        function alsFrei(g) {
            if (!g || !g.eigen) return null;
            if (g.eigen.version === R.VERSION_FREI) return { eigen: g.eigen, warnungen: g.warnungen || [] };
            var z = T.zuElementen(g.eigen, 'eigen');
            return { eigen: z.eigen, warnungen: (g.warnungen || []).concat(z.warnungen) };
        }

        function importieren(f) {
            if (!f) return;
            if (f.size > R.MAX_ZEICHEN) { meldung(t('tacho_gestalten.datei_ungueltig', 'Diese Datei ist kein Panoptes-Tacho-Design.'), true); return; }
            var r = new FileReader();
            r.onload = function () {
                var text = String(r.result || '');
                // Erst der Kern prüft (Erlaubnislisten). Ohne Kern gilt dieselbe Prüfung hier.
                api('POST', '/api/tacho/eigen/pruefen', { text: text }).then(function (x) {
                    if (x.ok && x.j.eigen) return { eigen: x.j.eigen, warnungen: x.j.warnungen };
                    if (x.status === 400) return null;
                    return lokal(text);
                }, function () { return lokal(text); }).then(function (g) {
                    var fr = alsFrei(g);
                    if (!fr) { meldung(t('tacho_gestalten.datei_ungueltig', 'Diese Datei ist kein Panoptes-Tacho-Design.'), true); return; }
                    merken();
                    e = kopie(fr.eigen);
                    wahl = -1;
                    allesZeigen();
                    var w = warnungen(fr.warnungen);
                    meldung(t('tacho_gestalten.import_ok', 'Datei geladen. Erst „Speichern“ übernimmt es.') + (w ? ' ' + w : ''), !!w);
                });
            };
            r.readAsText(f);
        }

        // Nur echte Tacho-Design-Dateien (Kennung und Version 1 oder 2), wie der Kern (TachoEigen.pruefenText).
        function lokal(text) {
            try {
                if (text.length > R.MAX_ZEICHEN) return null;
                var o = JSON.parse(text);
                if (!o || typeof o !== 'object' || o.format !== R.FORMAT || (o.version !== R.VERSION && o.version !== R.VERSION_FREI)) return null;
                return T.pruefeEigen(o);
            } catch (x) { return null; }
        }

        function laden() {
            return api('GET', '/api/tacho/eigen').then(function (x) {
                var vorher = stand.imStand;
                stand.imStand = !!(x.ok && x.j.imStand);
                if (x.ok && !stand.geladen && x.j.eigen) {
                    var fr = alsFrei(T.pruefeEigen(x.j.eigen));
                    if (fr) e = fr.eigen;
                    stand.geladen = true;
                    allesZeigen();
                } else if (vorher !== stand.imStand) allesZeigen();
                sperren();
            }, function () { stand.imStand = false; sperren(); });
        }

        // „Als Vorlage nehmen“ aus der Galerie: das Galerie-Design zerlegt in Elemente
        window.PanoptesTachoGestalten = {
            vorlage: function (d) {
                var erw = document.getElementById('tachoErweitert');
                if (erw && !erw.open) erw.open = true;
                stand.geladen = true;   // das gespeicherte Design überschreibt die Vorlage nicht
                if (editor.hidden) oeffnen.onclick();
                vonDesign(d);
                if (editor.scrollIntoView) editor.scrollIntoView();
            }
        };

        oeffnen.onclick = function () {
            editor.hidden = !editor.hidden;
            if (editor.hidden) {
                if (takt) clearInterval(takt);
                takt = null;
                if (vorschau) vorschau.stop();
                vorschau = null;
                return;
            }
            vorschau = T.create(cv, { design: T.EIGEN, eigen: e });
            allesZeigen();
            var tick = function () {
                var d = T.demo((Date.now() - start) / 1000);
                d.design = T.EIGEN;
                vorschau.setze(d);
            };
            tick();
            // Vorschau ruhig mitlaufen lassen, Fahrsperre alle 5 s nachziehen.
            var n = 0;
            takt = setInterval(function () {
                if (document.hidden) return;
                tick();
                if (++n % 10 === 0) laden();
            }, 500);
            laden();
        };
        sperren();
    }

    function boot() {
        var box = document.getElementById('tachoGestalten');
        if (box && !box.childNodes.length) mount(box);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
