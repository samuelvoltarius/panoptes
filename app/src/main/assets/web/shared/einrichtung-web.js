/**
 * Panoptes — Einrichtung im Web (0.1.10, WIZARD_AUFTRAG_NEUBAU Teil B, Web-Anteil).
 *
 * Nur Anzeige + ein Knopf, alles über den Vertrag §0 (WIZARD_AUFTRAG_KERN.md):
 *   GET  /api/einrichtung        → { abgeschlossen, schritte:[{id,pflicht,status,grund}], offen_spaeter:[id] }
 *   POST /api/einrichtung/neu    → setzt alle Merker zurück; der Assistent läuft am Bildschirm im Auto erneut
 *
 * Die Einrichtung selbst passiert am Bildschirm im Auto (Assistent), nicht hier.
 * Kennt der Kern /api/einrichtung nicht (404), bleibt die Karte unsichtbar.
 *
 *   PanoptesEinrichtung.init(karte, inhalt)
 *
 * ES5, Texte über BYD.i18n (Schlüssel einr.*), deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    var DE = {
        'lead': 'Der Einrichtungsassistent läuft am Bildschirm im Auto. Hier siehst du, was noch fehlt, und kannst ihn neu starten.',
        'offen_titel': 'Noch einrichten',
        'offen_keins': 'Nichts offen – die Einrichtung ist vollständig.',
        'offen_hinweis': 'Alles freiwillig. Tippe auf einen Punkt.',
        'neu_knopf': 'Einrichtung erneut starten',
        'neu_frage': 'Einrichtung wirklich neu starten? Der Assistent fragt dann am Bildschirm im Auto alles noch einmal ab. Deine Einstellungen bleiben, bis du sie dort änderst; Freigaben musst du neu bestätigen.',
        'neu_ok': 'Zurückgesetzt. Der Assistent erscheint beim nächsten Start der App am Bildschirm im Auto.',
        'neu_fehler': 'Das hat nicht geklappt. Bitte später nochmal versuchen.',
        'abbrechen': 'Abbrechen',
        'ja': 'Neu starten',
        'schritt.sprache': 'Sprache',
        'schritt.haftung': 'Hinweise zur Nutzung',
        'schritt.autostart': 'Autostart',
        'schritt.ueberlagerung': 'Anzeige über anderen Apps',
        'schritt.rechte': 'Berechtigungen',
        'schritt.usb_freigabe': 'USB-Freigabe',
        'schritt.auto': 'Dein Auto',
        'schritt.kameras': 'Kameras',
        'schritt.waechter': 'Wächter',
        'schritt.betrieb': 'Betriebsart',
        'schritt.karte': 'Kartenquelle',
        'schritt.daten': 'Daten teilen',
        'schritt.blitzer': 'Blitzer',
        'schritt.updates': 'Updates (auch über Mobilfunk festlegen)',
        'schritt.extra_telegram': 'Telegram',
        'schritt.extra_planer': 'Routenplaner',
        'schritt.extra_cockpit': 'EV-Cockpit',
        'schritt.extra_overdrive': 'Von Overdrive übernehmen',
        'schritt.extra_gewohnheiten': 'Gewohnheiten merken',
        'schritt.strompreis': 'Strompreis zu Hause',
        'schritt.extra_handy': 'Handy verbinden',
        'schritt.extra_fernzugang': 'Fernzugang',
        'schritt.extra_notzugang': 'Notzugang',
        'fortgeschritten': 'Für Fortgeschrittene',
        'schritt.fertig': 'Abschluss',
        'hinweis.carplay': 'Du nutzt CarPlay. Ob Panoptes-Hinweise darüber sichtbar sind, ist für dein Auto nicht bekannt. Bitte angeben oder Hinweiston einschalten.',
        'hinweis.androidauto': 'Du nutzt Android Auto. Ob Panoptes-Hinweise darüber sichtbar sind, ist für dein Auto nicht bekannt. Bitte angeben oder Hinweiston einschalten.',
        'link.faehigkeiten_ueberCarPlay': 'Sichtbar über CarPlay?',
        'link.faehigkeiten_ueberAndroidAuto': 'Sichtbar über Android Auto?',
        'link.speedcam_ton': 'Hinweiston einschalten',
        'hinweis.strompreis_pruefen': 'Dein Strompreis für Fahrten ist {wert}, stimmt das?',
        'hinweis.strompreis_null': 'Dein Strompreis für Fahrten ist 0 €/kWh. Lädst du zu Hause gratis?',
        'link.laden_strompreis': 'Preis prüfen',
        'hinweis.ladungen_ohne_preis': '{anzahl} Ladungen ohne Preis. Ohne Preis fehlen die Kosten in Fahrten und Monatsbericht.',
        'link.nachfrage': 'Preise nachtragen',
        'hinweis.bruecke_zustimmung': 'Dein Handy ist übers Internet verbunden. Bitte bestätige das noch einmal mit einem Häkchen.',
        'link.bruecke_zustimmung': 'Jetzt bestätigen',
        'hinweis.ordner_umbenennen.beide_da': 'Auf {volume} gibt es die Ordner „{alt}“ und „{neu}“. Panoptes nutzt weiter „{alt}“ und verschiebt nichts von selbst.',
        'hinweis.ordner_umbenennen.fehlgeschlagen': 'Auf {volume} ließ sich „{alt}“ nicht in „{neu}“ umbenennen. Panoptes nutzt weiter „{alt}“.',
        'hinweis.speicher_rueckfall': '{bereich}: Der gewählte Datenträger ({gewaehlt}) fehlt. Panoptes nimmt vorübergehend intern auf, mit begrenztem Platz.',
        'hinweis.rueckfall_aufnahmen': '{bereich}: {anzahl} Aufnahmen liegen noch intern, weil der Datenträger fehlte.',
        'verschieben_knopf': 'Auf {ziel} verschieben',
        'verschieben_frage': '{anzahl} Aufnahmen auf {ziel} verschieben? Jede Datei wird erst kopiert und geprüft, dann intern gelöscht.',
        'verschieben_laeuft': 'Verschieben läuft …',
        'verschieben_fertig': 'Verschoben: {verschoben}. Konflikte: {konflikte}. Fehler: {fehler}.',
        'verschieben_abbruch': 'Verschieben abgebrochen ({grund}). Verschoben: {verschoben}.',
        'verschieben_fehler.datentraeger_fehlt': 'Der Datenträger ist gerade nicht da.',
        'verschieben_fehler.intern_gewaehlt': 'Als Speicher ist „intern“ gewählt – da gibt es nichts zu verschieben.',
        'verschieben_fehler.laeuft_schon': 'Verschieben läuft schon.',
        'verschieben_fehler.bereich': 'Unbekannter Bereich.',
        'verschieben_fehler.x': 'Verschieben ließ sich nicht starten.',
        'bereich.recordings': 'Aufnahmen', 'bereich.surveillance': 'Wächter-Aufnahmen',
        'traeger.sd_card': 'SD-Karte', 'traeger.usb': 'USB-Stick', 'traeger.internal': 'intern',
        'abbruch.fehler': 'Fehler', 'abbruch.ziel_fehlt': 'Datenträger weg', 'abbruch.kein_platz': 'zu wenig Platz', 'abbruch.abgebrochen': 'abgebrochen',
        'hinweis.uebernommene_ladungen_ohne_preis': '{anzahl} aus Overdrive übernommene Ladungen haben keinen Preis.',
        'hinweis.akku_kapazitaet_pruefen': 'Eingetragene Akkugröße {wert} passt zu keiner Variante deines Modells. Bitte prüfen.',
        'akku_uebernehmen': '{name}: {wert} übernehmen',
        'akku_ok': 'Akkugröße übernommen.',
        'akku_fehler': 'Übernehmen hat nicht geklappt. Die Akkugröße ist unverändert.',
        'hinweis.sparsam_bis_betrieb': 'Bis du wählst, wann Panoptes laufen soll, läuft es sparsam.',
        'hinweis.keine_karte': 'Bis du eine Kartenquelle wählst, bleibt die Karte leer und es geht nichts raus.'
    };

    // 0.1.32 „Noch einrichten“: wo jeder Punkt im Web liegt. Ohne Eintrag (Auto, Kameras, Rechte) geht es nur im Assistenten am Display.
    var ZIEL = {
        betrieb: 'surveillance.html', karte: 'system.html#kartenquelle', daten: 'system.html#datenschutz',
        blitzer: 'system.html#blitzer', updates: 'system.html#updates', strompreis: 'charging.html#settings',
        extra_telegram: 'telegram.html', extra_handy: 'system.html#kopplung', extra_planer: 'system.html#planerKarte',
        extra_cockpit: 'system.html#kopplung', extra_overdrive: 'system.html#umzug',
        extra_fernzugang: 'system.html#tailscaleKarte', extra_notzugang: 'system.html#notzugang'
    };

    function t(k) {
        var key = 'einr.' + k;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key); if (v && v !== key) return v; }
        } catch (e) {}
        return DE[k] || k;
    }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
    function toast(m, ok) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, ok ? 'success' : 'error'); }

    function laden() {
        return fetch('/api/einrichtung', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    }

    // „Noch offen“: offen_spaeter plus Pflichtschritte, die noch offen sind (Bestand ohne Antwort, z. B. update.mobilfunk)
    function offeneSchritte(d) {
        var ids = [], status = {}, gruppe = {};
        (d.schritte || []).forEach(function (s) { status[s.id] = s.status; if (s.gruppe) gruppe[s.id] = s.gruppe; });
        (d.offen_spaeter || []).forEach(function (id) { if (ids.indexOf(id) < 0) ids.push(id); });
        (d.schritte || []).forEach(function (s) {
            if (s.id !== 'fertig' && (s.status === 'offen' || s.status === 'spaeter') && ids.indexOf(s.id) < 0) ids.push(s.id);
        });
        return ids.map(function (id) { return { id: id, status: status[id] || 'spaeter', gruppe: gruppe[id] }; });
    }

    function zeichne(inhalt, d) {
        inhalt.innerHTML = '';
        var lead = el('div', 'sys-note', t('lead'));
        lead.style.marginTop = '0';
        inhalt.appendChild(lead);
        var kopf = el('div', null, t('offen_titel'));
        kopf.style.cssText = 'font-weight:600;margin-top:12px';
        inhalt.appendChild(kopf);
        var offen = offeneSchritte(d);
        // Hinweise des Kerns (Teil C): solange Betriebsart/Karte offen sind – Textschlüssel einr.hinweis.*
        (d.hinweise || []).forEach(function (key) {
            var tx = null;
            try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key); if (v && v !== key) tx = v; } } catch (e) {}
            var n = el('div', 'sys-note', tx || DE[key.replace(/^einr\./, '')] || '');
            n.setAttribute('data-kern-hinweis', key);
            if (n.textContent) inhalt.appendChild(n);
        });
        var hinweise = d.noch_offen || [];
        hinweise.forEach(function (h) {
            var box = el('div', 'sys-note');
            box.setAttribute('data-hinweis', h.id || '');
            var key = h.textKey || ('einr.hinweis.' + h.id + (h.art ? '.' + h.art : ''));
            var tx = null;
            try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(key); if (v && v !== key) tx = v; } } catch (e) {}
            if (h.id === 'strompreis_pruefen') {
                var wert = (h.wert != null ? Number(h.wert).toLocaleString((BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de', { maximumFractionDigits: 3 }) : '?')
                    + ' ' + (h.waehrung || '€') + '/kWh';
                var k2 = h.grund === 'null_unbestaetigt' ? 'einr.hinweis.strompreis_null' : 'einr.hinweis.strompreis_pruefen';
                var tx2 = null;
                try { var v2 = BYD.i18n.t(k2, { wert: wert }); if (v2 && v2 !== k2) tx2 = v2; } catch (e) {}
                tx = (tx2 || DE[k2.replace(/^einr\./, '')] || '').split('{wert}').join(wert);
                h.einstellungen = ['laden.strompreis'];
            }
            // 0.1.24 Zustimmung zur Verbindung übers Internet nachholen (Brücke bleibt an)
            if (h.id === 'bruecke_zustimmung') {
                box.appendChild(el('div', null, tx || DE['hinweis.bruecke_zustimmung']));
                var lz = el('a', null, t('link.bruecke_zustimmung'));
                lz.href = 'system.html#kopplung';
                lz.style.cssText = 'color:var(--sm-aqua);font-weight:600;display:inline-block;margin-top:4px';
                box.appendChild(lz);
                inhalt.appendChild(box);
                return;
            }
            if (h.id === 'ladungen_ohne_preis' || h.id === 'uebernommene_ladungen_ohne_preis') {
                var uebern = h.id === 'uebernommene_ladungen_ohne_preis';
                tx = (tx || DE['hinweis.' + h.id]).split('{anzahl}').join(String(h.anzahl || 0));
                box.appendChild(el('div', null, tx));
                var ln = el('a', null, t('link.nachfrage'));
                ln.href = uebern ? 'charging.html#nachbepreisen' : 'charging.html#nachfrage';
                ln.style.cssText = 'color:var(--sm-aqua);font-weight:600;display:inline-block;margin-top:4px';
                box.appendChild(ln);
                inhalt.appendChild(box);
                return;
            }
            // 0.1.11 Speicher (Kern 4): still, nie als Popup. Rückfall-Aufnahmen nur auf Wunsch verschieben.
            if (h.id === 'ordner_umbenennen' || h.id === 'speicher_rueckfall' || h.id === 'rueckfall_aufnahmen') {
                var traeger = function (z) { return t('traeger.' + String(z || '').toLowerCase()) ; };
                var bereichTx = t('bereich.' + (h.bereich || ''));
                var ersetze = function (s) {
                    return String(s).split('{bereich}').join(bereichTx).split('{anzahl}').join(String(h.anzahl || 0))
                        .split('{ziel}').join(traeger(h.ziel || h.gewaehlt)).split('{gewaehlt}').join(traeger(h.gewaehlt))
                        .split('{volume}').join(h.volume || '').split('{alt}').join(h.alt || '').split('{neu}').join(h.neu || '');
                };
                var k3 = h.id === 'ordner_umbenennen' ? 'hinweis.ordner_umbenennen.' + (h.grund || 'fehlgeschlagen') : 'hinweis.' + h.id;
                var tx3 = null;
                try { var v3 = BYD.i18n.t('einr.' + k3); if (v3 && v3 !== 'einr.' + k3) tx3 = v3; } catch (e) {}
                box.appendChild(el('div', null, ersetze(tx3 || DE[k3] || k3)));
                if (h.id === 'rueckfall_aufnahmen') {
                    var vs = h.verschieben;
                    var zeileV = el('div');
                    if (vs && vs.laeuft) {
                        zeileV.textContent = t('verschieben_laeuft');
                        setTimeout(function () { laden().then(function (neu) { if (neu) zeichne(inhalt, neu); }); }, 3000);
                    } else {
                        if (vs && vs.ende) {
                            zeileV.textContent = ersetze(t(vs.abbruch ? 'verschieben_abbruch' : 'verschieben_fertig'))
                                .split('{verschoben}').join(String(vs.verschoben || 0)).split('{konflikte}').join(String(vs.konflikte || 0))
                                .split('{fehler}').join(String(vs.fehler || 0)).split('{grund}').join(t('abbruch.' + (vs.abbruch || '')));
                        }
                        if ((h.anzahl || 0) > 0) {
                            var vb = el('button', 'btn', ersetze(t('verschieben_knopf')));
                            vb.type = 'button';
                            vb.style.cssText = 'margin:6px 8px 0 0';
                            var vm = el('div');
                            vb.onclick = function () {
                                if (!window.confirm(ersetze(t('verschieben_frage')))) return;
                                vb.disabled = true;
                                fetch('/api/einrichtung/rueckfall_verschieben', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ bereich: h.bereich }) })
                                    .then(function (r) { return r.json().then(function (j) { j._s = r.status; return j; }); })
                                    .then(function (j) {
                                        if (j._s >= 400 || j.success === false) { vb.disabled = false; vm.textContent = t('verschieben_fehler.' + (j.error || 'x')); return; }
                                        return laden().then(function (neu) { if (neu) zeichne(inhalt, neu); });
                                    })
                                    .catch(function () { vb.disabled = false; vm.textContent = t('verschieben_fehler.x'); });
                            };
                            box.appendChild(zeileV);
                            box.appendChild(vb);
                            box.appendChild(vm);
                            inhalt.appendChild(box);
                            return;
                        }
                    }
                    box.appendChild(zeileV);
                }
                inhalt.appendChild(box);
                return;
            }
            if (h.id === 'akku_kapazitaet_pruefen') {
                // Kern 4 (KapazitaetPlausibel): Nutzerwert passt zu keiner Katalogvariante. Nichts still korrigieren –
                // ein Klick übernimmt den Katalogwert (POST /api/performance/soh/nominal), danach zurücklesen.
                var kwhTx = function (v) { return Number(v).toLocaleString((BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || 'de', { maximumFractionDigits: 1 }) + ' kWh'; };
                tx = (tx || DE['hinweis.akku_kapazitaet_pruefen']).split('{wert}').join(kwhTx(h.eingetragen));
                box.appendChild(el('div', null, tx));
                var meld = el('div');
                (h.katalog || []).forEach(function (v) {
                    if (!(v && v.nominalKwh > 0)) return;
                    var b = el('button', 'btn', t('akku_uebernehmen').split('{name}').join(v.label || v.id || '').split('{wert}').join(kwhTx(v.nominalKwh)));
                    b.type = 'button';
                    b.style.cssText = 'margin:6px 8px 0 0';
                    b.onclick = function () {
                        b.disabled = true;
                        fetch('/api/performance/soh/nominal', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ nominalKwh: v.nominalKwh }) })
                            .then(function (r) { return r.json(); })
                            .then(function (j) {
                                if (j && j.success === false) throw new Error('x');
                                return fetch('/api/performance/soh/nominal', { cache: 'no-store' }).then(function (r) { return r.json(); });
                            })
                            .then(function () { return laden(); })
                            .then(function (neu) { toast(t('akku_ok'), true); if (neu) zeichne(inhalt, neu); })
                            .catch(function () { b.disabled = false; meld.textContent = t('akku_fehler'); });
                    };
                    box.appendChild(b);
                });
                box.appendChild(meld);
                inhalt.appendChild(box);
                return;
            }
            box.appendChild(el('div', null, tx || DE['hinweis.' + (h.art || '')] || key));
            (h.einstellungen || []).forEach(function (k) {
                var a = el('a', null, t('link.' + k.replace(/\./g, '_')));
                a.href = /^speedcam\./.test(k) ? 'system.html#blitzer' : (/^laden\./.test(k) ? 'charging.html#settings' : 'system.html#anzeige');
                a.style.cssText = 'color:var(--sm-aqua);font-weight:600;margin-right:14px;display:inline-block;margin-top:4px';
                box.appendChild(a);
            });
            inhalt.appendChild(box);
        });
        if (!offen.length && !hinweise.length) {
            inhalt.appendChild(el('div', 'sys-note', t('offen_keins')));
        } else if (offen.length) {
            // 0.1.24: Tailscale-Schritte gesondert unter „Für Fortgeschrittene“ (Laien kommen ohne aus)
            [false, true].forEach(function (fort) {
                var teil = offen.filter(function (o) { return (o.gruppe === 'fortgeschritten') === fort; });
                if (!teil.length) return;
                if (fort) {
                    var kopf = el('div', 'sys-note', t('fortgeschritten'));
                    kopf.style.cssText = 'margin-top:10px;font-weight:600';
                    kopf.setAttribute('data-gruppe', 'fortgeschritten');
                    inhalt.appendChild(kopf);
                }
                var ul = el('ul');
                ul.style.cssText = 'margin:6px 0 0 18px;padding:0';
                teil.forEach(function (o) {
                    var li = el('li');
                    li.setAttribute('data-schritt', o.id);
                    if (ZIEL[o.id]) {
                        var a = el('a', null, t('schritt.' + o.id));
                        a.href = ZIEL[o.id];
                        a.style.cssText = 'color:var(--sm-aqua);font-weight:600';
                        li.appendChild(a);
                    } else {
                        li.textContent = t('schritt.' + o.id);
                    }
                    ul.appendChild(li);
                });
                inhalt.appendChild(ul);
            });
            inhalt.appendChild(el('div', 'sys-note', t('offen_hinweis')));
        }
        var knopf = el('button', 'btn', t('neu_knopf'));
        knopf.type = 'button';
        knopf.style.marginTop = '12px';
        knopf.setAttribute('data-einr', 'neu');
        inhalt.appendChild(knopf);
        var meldung = el('div', 'sys-note');
        inhalt.appendChild(meldung);
        knopf.onclick = function () {
            // eigene Rückfrage statt window.confirm (am Display und im Browser gleich lesbar)
            knopf.style.display = 'none';
            var frage = el('div', 'sys-note', t('neu_frage'));
            var ja = el('button', 'btn btn-primary', t('ja'));
            ja.type = 'button';
            ja.setAttribute('data-einr', 'ja');
            var nein = el('button', 'btn', t('abbrechen'));
            nein.type = 'button';
            nein.style.marginLeft = '8px';
            var box = el('div');
            box.style.marginTop = '12px';
            box.appendChild(frage); box.appendChild(ja); box.appendChild(nein);
            inhalt.insertBefore(box, meldung);
            nein.onclick = function () { box.parentNode.removeChild(box); knopf.style.display = ''; };
            ja.onclick = function () {
                ja.disabled = true; nein.disabled = true;
                fetch('/api/einrichtung/neu', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
                    .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return laden(); })
                    .then(function (neu) {
                        toast(t('neu_ok'), true);
                        if (neu) zeichne(inhalt, neu);
                        var m = inhalt.querySelector('.sys-note:last-child');
                        if (m) m.textContent = t('neu_ok');
                    })
                    .catch(function () {
                        toast(t('neu_fehler'), false);
                        ja.disabled = false; nein.disabled = false;
                    });
            };
        };
    }

    function init(karte, inhalt) {
        if (!karte || !inhalt) return;
        laden().then(function (d) {
            if (!d || !d.schritte) return;          // Kern ohne §0: Karte bleibt unsichtbar
            karte.style.display = '';
            zeichne(inhalt, d);
            if (window.BYD && BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(function () { zeichne(inhalt, d); });
        });
    }

    window.PanoptesEinrichtung = { init: init, offeneSchritte: offeneSchritte };
})();
