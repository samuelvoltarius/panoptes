/**
 * Panoptes — „Tacho gestalten“ (0.1.41). Unter Display → Tacho → „Erweitert“. Eigenes Tacho-Design aus Farben,
 * Schrift, Zeigerform und fester Anordnung (Raster, kein freies Verschieben), mit Live-Vorschau.
 * Die fertigen Designs sind der Startpunkt („Kopie bearbeiten“). Große Knöpfe (REGEL 50+).
 *
 *   GET  /api/tacho/eigen                 → { eigen, standard, gespeichert, imStand }
 *   PUT  /api/tacho/eigen {eigen, waehlen} → { ok, eigen, warnungen }   (nur im Stand, Kern prüft)
 *   POST /api/tacho/eigen/pruefen {text}  → { ok, eigen, warnungen }   (Datei-Import, speichert nichts)
 *
 * Regeln (Erlaubnislisten, Tempo Pflicht, Mindestkontrast) kommen aus PanoptesTacho (tacho-designs.js) und
 * gelten im Kern noch einmal (TachoEigen.java). Die Datei enthält nur Farben und Anordnung.
 * ES5 (WebView 74 am Auto).
 *
 *   <div id="tachoGestalten"></div>  → Knopf „Tacho gestalten“, der Editor klappt darunter auf
 *   0.1.42 (T2): window.PanoptesTachoGestalten.vorlage(design) öffnet den Editor mit einem Galerie-Design als
 *   Ausgangspunkt (Farben, Schrift, Zeiger, Plätze des Designs; gespeichert wird erst mit „Speichern“).
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
    /** Feste, gut unterscheidbare Farben zur schnellen Wahl. Freie Wahl darunter über den Farbwähler. */
    var PALETTE = ['#000000', '#0E1620', '#1B2A3A', '#2B2B2B', '#FFFFFF', '#EEF4F8', '#FFD400', '#B5F23D',
        '#7FE3C4', '#4FD1FF', '#FF3B1F', '#FFB020', '#C4581F', '#A57CFF'];

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
            kontrast: t('tacho_gestalten.warn_kontrast', 'Tempo und Gang wären schlecht lesbar. Es gelten wieder die Standardfarben.'),
            tempo_pflicht: t('tacho_gestalten.warn_tempo', 'Die Geschwindigkeit muss immer sichtbar sein. Sie steht wieder rechts.'),
            farbe_ungueltig: t('tacho_gestalten.warn_farbe', 'Eine Farbe war ungültig und wurde ersetzt.')
        };
        return m[w] || '';
    }

    function anzeigeName(a) {
        var f = { tempo: 'Geschwindigkeit', akku: 'Akku %', reichweite: 'Reichweite', leistung: 'Leistung (kW)',
            gang: 'Gang', uhr: 'Uhr', aussen: 'Außentemperatur', navi: 'Navi-Hinweis', kmstand: 'km-Stand', leer: '– leer –' };
        return t('tacho_gestalten.a_' + a, f[a] || a);
    }

    function mount(box) {
        var T = window.PanoptesTacho;
        if (!T || !T.pruefeEigen) return;
        var R = T.REGELN;
        var stand = { imStand: false, geladen: false };
        var e = T.standardEigen();
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
        editor.appendChild(el('p', NOTIZ, t('tacho_gestalten.lead',
            'Wähle Farben, Schrift, Zeiger und was wo steht. Die Vorschau zeigt es sofort. Erst „Speichern“ übernimmt es.')));

        // ---- Live-Vorschau
        var bild = el('div', 'position:relative;padding-top:42%;border-radius:12px;overflow:hidden;background:#000;');
        var cv = el('canvas', 'position:absolute;left:0;top:0;width:100%;height:100%;');
        bild.appendChild(cv);
        editor.appendChild(bild);
        var kontrastZeile = el('p', 'font-size:16px;margin:8px 0 0;line-height:1.5;');
        kontrastZeile.setAttribute('aria-live', 'polite');
        editor.appendChild(kontrastZeile);

        var steuer = [];   // alles, was die Fahrsperre abschaltet
        function knopf(text, beiTipp, extra) {
            var b = el('button', GROSS_KNOPF + (extra || ''), text);
            b.type = 'button';
            b.onclick = beiTipp;
            steuer.push(b);
            return b;
        }

        // ---- Startpunkt: Kopie eines fertigen Designs
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.kopie_titel', 'Kopie bearbeiten von')));
        var kopieReihe = el('div', '');
        T.DESIGNS.forEach(function (d) {
            kopieReihe.appendChild(knopf(t('vehicle_control.cluster_design_' + d, d.charAt(0).toUpperCase() + d.slice(1)),
                function () { e = T.vorlage(d); allesZeigen(); meldung(''); }));
        });
        editor.appendChild(kopieReihe);

        // ---- Farben
        var farbTitel = {
            hintergrund: t('tacho_gestalten.f_hintergrund', 'Hintergrund'),
            ziffern: t('tacho_gestalten.f_ziffern', 'Ziffern'),
            zeiger: t('tacho_gestalten.f_zeiger', 'Zeiger und Balken'),
            akzent: t('tacho_gestalten.f_akzent', 'Akzent (Rekuperation)')
        };
        var farbFelder = {};
        R.FARBEN.forEach(function (k) {
            editor.appendChild(el('div', TITEL, farbTitel[k]));
            var reihe = el('div', 'display:flex;flex-wrap:wrap;align-items:center;');
            PALETTE.forEach(function (hex) {
                var b = knopf('', function () { e.farben[k] = hex; allesZeigen(); },
                    'width:52px;min-height:52px;padding:0;background:' + hex + ';');
                b.setAttribute('aria-label', farbTitel[k] + ' ' + hex);
                b.setAttribute('data-farbe', hex);
                reihe.appendChild(b);
            });
            var frei = el('input', 'width:64px;height:52px;margin:6px 8px 0 0;border-radius:12px;cursor:pointer;');
            frei.type = 'color';
            frei.setAttribute('aria-label', farbTitel[k] + ' – ' + t('tacho_gestalten.frei', 'eigene Farbe'));
            frei.oninput = frei.onchange = function () { e.farben[k] = String(frei.value).toUpperCase(); allesZeigen(); };
            steuer.push(frei);
            reihe.appendChild(frei);
            farbFelder[k] = { reihe: reihe, frei: frei };
            editor.appendChild(reihe);
        });

        // ---- Schrift und Zeigerform
        function wahlReihe(titel, liste, feld, namen) {
            editor.appendChild(el('div', TITEL, titel));
            var reihe = el('div', '');
            var kn = {};
            liste.forEach(function (w) {
                kn[w] = knopf(namen[w], function () { e[feld] = w; allesZeigen(); });
                reihe.appendChild(kn[w]);
            });
            editor.appendChild(reihe);
            return kn;
        }
        var schriftKn = wahlReihe(t('tacho_gestalten.schrift_titel', 'Schrift'), R.SCHRIFTEN, 'schrift', {
            schmal: t('tacho_gestalten.s_schmal', 'Schmal'), normal: t('tacho_gestalten.s_normal', 'Normal'),
            breit: t('tacho_gestalten.s_breit', 'Breit')
        });
        var zeigerKn = wahlReihe(t('tacho_gestalten.zeiger_titel', 'Zeigerform'), R.ZEIGER, 'zeiger', {
            nadel: t('tacho_gestalten.z_nadel', 'Nadel'), balken: t('tacho_gestalten.z_balken', 'Balken'),
            zahl: t('tacho_gestalten.z_zahl', 'Nur Zahl')
        });

        // ---- Anordnung: feste Plätze (oben 3 klein, Mitte 3 groß, unten 3 klein)
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.anordnung_titel', 'Was steht wo?')));
        editor.appendChild(el('p', NOTIZ, t('tacho_gestalten.anordnung_hilfe',
            'Oben und unten sind kleine Plätze, in der Mitte drei große. Die Geschwindigkeit bleibt immer auf einem großen Platz.')));
        var raster = el('div', 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-gap:8px;');
        var platzNamen = {
            oben_links: t('tacho_gestalten.p_oben_links', 'Oben links'), oben_mitte: t('tacho_gestalten.p_oben_mitte', 'Oben Mitte'),
            oben_rechts: t('tacho_gestalten.p_oben_rechts', 'Oben rechts'), links: t('tacho_gestalten.p_links', 'Groß links'),
            mitte: t('tacho_gestalten.p_mitte', 'Groß Mitte'), rechts: t('tacho_gestalten.p_rechts', 'Groß rechts'),
            unten_links: t('tacho_gestalten.p_unten_links', 'Unten links'), unten_mitte: t('tacho_gestalten.p_unten_mitte', 'Unten Mitte'),
            unten_rechts: t('tacho_gestalten.p_unten_rechts', 'Unten rechts')
        };
        var reihenfolge = R.KLEIN.slice(0, 3).concat(R.GROSS, R.KLEIN.slice(3));
        var auswahlen = {};
        reihenfolge.forEach(function (p) {
            var zelle = el('label', 'display:block;font-size:15px;font-weight:700;');
            zelle.appendChild(el('span', 'display:block;margin-bottom:4px;', platzNamen[p]));
            var s = el('select', 'width:100%;min-height:52px;font-size:17px;padding:8px;border-radius:10px;'
                + 'background:var(--bg-elevated, var(--surface-container));color:var(--text-primary, var(--on-surface));'
                + 'border:' + (R.GROSS.indexOf(p) >= 0 ? '2px' : '1px') + ' solid var(--border-default, var(--outline-variant));');
            R.ANZEIGEN.forEach(function (a) {
                if (R.KLEIN.indexOf(p) >= 0 && R.NUR_GROSS.indexOf(a) >= 0) return;
                var o = el('option', '', anzeigeName(a));
                o.value = a;
                s.appendChild(o);
            });
            s.onchange = function () { setzePlatz(p, s.value); };
            steuer.push(s);
            zelle.appendChild(s);
            raster.appendChild(zelle);
            auswahlen[p] = s;
        });
        editor.appendChild(raster);

        /** Tauscht mit dem Platz, der die Anzeige schon hat. Tempo darf nie verschwinden. */
        function setzePlatz(p, a) {
            var alt = e.plaetze[p];
            if (alt === a) return;
            var anderer = null;
            if (a !== 'leer') {
                for (var q in e.plaetze) if (e.plaetze.hasOwnProperty(q) && q !== p && e.plaetze[q] === a) anderer = q;
            }
            if (anderer) {
                var darf = !(R.KLEIN.indexOf(anderer) >= 0 && R.NUR_GROSS.indexOf(alt) >= 0);
                if (alt === 'tempo' && !darf) {
                    meldung(t('tacho_gestalten.tempo_bleibt', 'Die Geschwindigkeit muss sichtbar bleiben. Setze sie zuerst auf einen anderen großen Platz.'), true);
                    allesZeigen();
                    return;
                }
                e.plaetze[anderer] = darf ? alt : 'leer';
            } else if (alt === 'tempo') {
                meldung(t('tacho_gestalten.tempo_bleibt', 'Die Geschwindigkeit muss sichtbar bleiben. Setze sie zuerst auf einen anderen großen Platz.'), true);
                allesZeigen();
                return;
            }
            e.plaetze[p] = a;
            meldung('');
            allesZeigen();
        }

        // ---- Speichern, Datei, Standard
        editor.appendChild(el('div', TITEL, t('tacho_gestalten.sichern_titel', 'Übernehmen')));
        var reiheS = el('div', '');
        reiheS.appendChild(knopf(t('tacho_gestalten.speichern_zeigen', 'Speichern und am Tacho zeigen'), function () { speichern(true); },
            'border-color:#B5F23D;'));
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
            e = T.standardEigen(); allesZeigen(); meldung(t('tacho_gestalten.standard_info', 'Standard geladen. Erst „Speichern“ übernimmt es.'));
        }));
        editor.appendChild(reiheD);
        editor.appendChild(el('p', NOTIZ, t('tacho_gestalten.datei_info',
            'Die Datei enthält nur Farben und Anordnung, keine Orte, Pfade oder Zugangsdaten.')));
        var meld = el('div', 'font-size:16px;line-height:1.5;margin-top:8px;min-height:24px;');
        meld.id = 'tgMeldung';
        meld.setAttribute('aria-live', 'polite');
        editor.appendChild(meld);

        function meldung(text, schlecht) {
            meld.textContent = text || '';
            meld.style.color = schlecht ? 'var(--status-danger, var(--status-error))' : '';
        }

        function warnungen(liste) {
            var s = (liste || []).map(warnText).filter(function (x) { return x; });
            return s.join(' ');
        }

        function allesZeigen() {
            // Für Vorschau und Knöpfe gilt dieselbe Prüfung wie beim Speichern.
            var k = T.kontrast(e.farben.ziffern, e.farben.hintergrund);
            var gut = k >= R.MIN_KONTRAST;
            kontrastZeile.textContent = t('tacho_gestalten.kontrast', 'Kontrast Ziffern') + ': '
                + k.toFixed(1).replace('.', ',') + ' : 1  ' + (gut ? '✓' : '– ' + t('tacho_gestalten.kontrast_zu_wenig',
                    'zu wenig. Wähle eine hellere oder dunklere Farbe, sonst gelten beim Speichern die Standardfarben.'));
            kontrastZeile.style.color = gut ? '' : 'var(--status-danger, var(--status-error))';
            kontrastZeile.style.fontWeight = gut ? '' : '700';
            R.FARBEN.forEach(function (f) {
                var r = farbFelder[f];
                Array.prototype.forEach.call(r.reihe.querySelectorAll('[data-farbe]'), function (b) {
                    var an = b.getAttribute('data-farbe') === e.farben[f];
                    b.style.outline = an ? '4px solid #B5F23D' : 'none';
                    b.setAttribute('aria-pressed', an ? 'true' : 'false');
                });
                r.frei.value = e.farben[f].toLowerCase();
            });
            [[schriftKn, e.schrift], [zeigerKn, e.zeiger]].forEach(function (x) {
                for (var w in x[0]) if (x[0].hasOwnProperty(w)) {
                    x[0][w].style.borderColor = w === x[1] ? '#B5F23D' : '';
                    x[0][w].setAttribute('aria-pressed', w === x[1] ? 'true' : 'false');
                }
            });
            for (var p in auswahlen) if (auswahlen.hasOwnProperty(p)) auswahlen[p].value = e.plaetze[p] || 'leer';
            if (vorschau) vorschau.eigen(e);
        }

        function sperren() {
            var zu = !stand.imStand;
            sperre.hidden = !zu;
            steuer.forEach(function (x) { x.disabled = zu; x.style.opacity = zu ? '.5' : ''; });
        }

        function speichern(waehlen) {
            if (!stand.imStand) { sperren(); return; }
            var g = T.pruefeEigen(e);
            if (!g) return;
            // Der Kern prüft noch einmal; Warnungen beider Prüfungen (z. B. Kontrast) werden gezeigt.
            api('PUT', '/api/tacho/eigen', { eigen: g.eigen, waehlen: waehlen }).then(function (x) {
                if (x.status === 409) { stand.imStand = false; sperren(); return; }
                if (!x.ok) { meldung(t('tacho_gestalten.fehler', 'Speichern hat nicht geklappt. Der Tacho ist unverändert.'), true); return; }
                e = x.j.eigen || g.eigen;
                allesZeigen();
                var w = warnungen(g.warnungen.concat(x.j.warnungen || []).filter(function (v, i, a) { return a.indexOf(v) === i; }));
                meldung((waehlen ? t('tacho_gestalten.ok_zeigen', 'Gespeichert. Ist der Panoptes-Tacho an, siehst du es sofort.')
                    : t('tacho_gestalten.ok', 'Gespeichert. Unter „Was zeigt der Tacho?“ heißt es „Eigenes“.')) + (w ? ' ' + w : ''), !!w);
            }, function () { meldung(t('tacho_gestalten.fehler', 'Speichern hat nicht geklappt. Der Tacho ist unverändert.'), true); });
        }

        function exportieren() {
            var g = T.pruefeEigen(e);
            if (!g) return;
            var text = JSON.stringify(g.eigen, null, 2);
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
                    if (!g) { meldung(t('tacho_gestalten.datei_ungueltig', 'Diese Datei ist kein Panoptes-Tacho-Design.'), true); return; }
                    e = kopie(g.eigen);
                    allesZeigen();
                    var w = warnungen(g.warnungen);
                    meldung(t('tacho_gestalten.import_ok', 'Datei geladen. Erst „Speichern“ übernimmt es.') + (w ? ' ' + w : ''), !!w);
                });
            };
            r.readAsText(f);
        }

        // 0.1.42 (E2E G3): nur echte Tacho-Design-Dateien (Kennung und Version), wie der Kern (TachoEigen.pruefenText).
        function lokal(text) {
            try {
                if (text.length > R.MAX_ZEICHEN) return null;
                var o = JSON.parse(text);
                if (!o || typeof o !== 'object' || o.format !== R.FORMAT || o.version !== R.VERSION) return null;
                return T.pruefeEigen(o);
            } catch (x) { return null; }
        }

        function laden() {
            return api('GET', '/api/tacho/eigen').then(function (x) {
                stand.imStand = !!(x.ok && x.j.imStand);
                if (x.ok && !stand.geladen && x.j.eigen) {
                    var g = T.pruefeEigen(x.j.eigen);
                    if (g) e = g.eigen;
                    stand.geladen = true;
                    allesZeigen();
                }
                sperren();
            }, function () { stand.imStand = false; sperren(); });
        }

        // 0.1.42 (T2): „Als Vorlage nehmen“ aus der Galerie
        window.PanoptesTachoGestalten = {
            vorlage: function (d) {
                var erw = document.getElementById('tachoErweitert');
                if (erw && !erw.open) erw.open = true;
                e = T.vorlage(d);
                stand.geladen = true;   // das gespeicherte Design überschreibt die Vorlage nicht
                if (editor.hidden) oeffnen.onclick(); else { if (vorschau) vorschau.eigen(e); allesZeigen(); }
                meldung(t('tacho_galerie.vorlage_ok', 'Vorlage geladen. Ändern und dann „Speichern“.'));
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
