/*
 * Display-Werkzeuge (0.1.37, DashArc-Abgleich): Hintergrundbild, Display-Sperre (system.html), Apps deaktivieren
 * (apps.html), SD-Karte und USB-Stick (dateien.html). Jede Karte erscheint nur, wenn ihr Platzhalter auf der Seite
 * steht. Alles läuft über /api/display, /api/apps/verwaltung und /api/dateien/wechsel; folgenreiche Schritte holen
 * vorher eine Rückfrage-Marke vom Auto, ein einzelner Aufruf kann also nichts deaktivieren oder löschen.
 */
(function () {
    'use strict';

    function t(k, f, v) {
        var s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { s = BYD.i18n.t(k); if (s === k) s = null; } } catch (e) { s = null; }
        if (!s && window.PanoptesShell && PanoptesShell.tr) { s = PanoptesShell.tr(k, f, v); return s; }
        s = s || f;
        return String(s).replace(/\{(\w+)\}/g, function (m, n) { return v && v[n] != null ? v[n] : m; });
    }
    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }
    function knopf(text, primaer) {
        var b = el('button', primaer ? 'btn btn-primary' : 'btn', text);
        b.type = 'button';
        b.style.minHeight = '48px';
        return b;
    }
    function holeJson(url, opt) {
        return fetch(url, Object.assign({ credentials: 'same-origin', cache: 'no-store' }, opt || {}))
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (d) { d.status = r.status; return d; }); })
            .catch(function () { return { ok: false, success: false, grund: 'netz' }; });
    }
    function post(url, body) {
        return holeJson(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    }
    function status(box, text, art) {
        box.textContent = text || '';
        box.style.color = art === 'error' ? 'var(--status-error)' : art === 'success' ? 'var(--status-success)' : '';
    }
    function grund(g) {
        var m = {
            nicht_in_p: t('display.grund_nicht_in_p', 'Geht nur, wenn das Auto in P steht.'),
            pin_ungueltig: t('display.grund_pin', 'Die PIN muss 4 bis 8 Ziffern haben.'),
            schon_an: t('display.grund_schon_an', 'Die Sperre ist schon an.'),
            falsch: t('display.valet_falsch', 'PIN falsch.'),
            warten: t('display.grund_warten', 'Zu viele Versuche. Bitte kurz warten.'),
            art: t('display.grund_art', 'Nur Bilder: JPG, PNG oder WEBP.'),
            zu_gross: t('display.grund_zu_gross', 'Das Bild ist zu groß (höchstens 20 MB).'),
            rueckfrage: t('display.grund_rueckfrage', 'Bitte nochmal bestätigen.'),
            gesperrt: t('display.grund_gesperrt', 'Diese App gehört zum System und bleibt an.'),
            startseite: t('display.grund_startseite', 'Das ist die Startseite des Displays und bleibt an.'),
            tastatur: t('display.grund_tastatur', 'Das ist die Tastatur und bleibt an.'),
            nicht_erlaubt: t('display.grund_nicht_erlaubt', 'Dieser Ort ist gesperrt.'),
            schon_da: t('display.grund_schon_da', 'Dort liegt schon eine Datei mit diesem Namen.'),
            app: t('display.grund_app', 'Panoptes am Display antwortet nicht. Bitte Panoptes einmal öffnen.'),
            recht: t('display.grund_recht', 'Android erlaubt das hier nicht.')
        };
        return m[g] || t('display.grund_fehler', 'Das hat nicht geklappt. Bitte nochmal versuchen.');
    }
    function mb(n) { return (n / 1048576).toFixed(n < 10485760 ? 1 : 0) + ' MB'; }
    function lesen(stueck) {
        return new Promise(function (ok, nein) {
            var r = new FileReader();
            r.onload = function () { var s = String(r.result); ok(s.substring(s.indexOf(',') + 1)); };
            r.onerror = function () { nein(new Error('lesen')); };
            r.readAsDataURL(stueck);
        });
    }

    // ------------------------------------------------------------------ Hintergrundbild + Sperre (system.html)

    function display(mount) {
        mount.innerHTML = '';
        mount.appendChild(el('h3', null, t('display.hg_titel', 'Hintergrundbild')));
        mount.appendChild(el('div', 'sys-note', t('display.hg_hilfe', 'Eigenes Bild für den Mittelschirm. JPG, PNG oder WEBP, höchstens 20 MB.')));
        var wahl = el('input'); wahl.type = 'file'; wahl.accept = 'image/jpeg,image/png,image/webp';
        var setzen = knopf(t('display.hg_setzen', 'Bild setzen'), true); setzen.disabled = true;
        var standard = knopf(t('display.hg_standard', 'Zurück auf Standard'));
        var hgStatus = el('div', 'sys-note'); hgStatus.setAttribute('role', 'status');
        var reihe = el('div'); reihe.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;margin-top:8px';
        reihe.appendChild(setzen); reihe.appendChild(standard);
        mount.appendChild(wahl); mount.appendChild(reihe); mount.appendChild(hgStatus);
        mount.appendChild(el('div', 'sys-note', t('display.hg_video', 'Bewegte Hintergründe (Video) kommen später.')));
        wahl.onchange = function () { setzen.disabled = !(wahl.files && wahl.files[0]); status(hgStatus, ''); };
        setzen.onclick = function () {
            var f = wahl.files && wahl.files[0];
            if (!f) return;
            if (f.size > 20 * 1048576) { status(hgStatus, grund('zu_gross'), 'error'); return; }
            if (!/\.(jpe?g|png|webp)$/i.test(f.name)) { status(hgStatus, grund('art'), 'error'); return; }
            setzen.disabled = true; status(hgStatus, t('display.hg_laeuft', 'Bild wird übertragen …'));
            var id = null;
            post('/api/display/hintergrund/start', { name: f.name, groesse: f.size }).then(function (d) {
                if (!d.ok) throw d;
                id = d.id;
                var teil = d.teilBytes || 2097152, off = 0, nr = 0;
                function weiter() {
                    if (off >= f.size) return post('/api/display/hintergrund/fertig', { id: id });
                    var st = f.slice(off, Math.min(f.size, off + teil));
                    return lesen(st).then(function (b) { return post('/api/display/hintergrund/teil', { id: id, nr: nr, daten: b }); })
                        .then(function (r) { if (!r.ok) throw r; off += st.size; nr++; status(hgStatus, Math.round(off * 100 / f.size) + ' %'); return weiter(); });
                }
                return weiter();
            }).then(function (d) {
                if (!d.ok) throw d;
                status(hgStatus, t('display.hg_ok', 'Fertig. Das Bild ist gesetzt.'), 'success');
                wahl.value = '';
            }).catch(function (d) {
                if (id) post('/api/display/hintergrund/abbrechen', { id: id });
                status(hgStatus, grund(d && d.grund), 'error');
            }).then(function () { setzen.disabled = !(wahl.files && wahl.files[0]); });
        };
        standard.onclick = function () {
            standard.disabled = true;
            post('/api/display/hintergrund/standard', {}).then(function (d) {
                if (!d.ok) { status(hgStatus, grund(d.grund), 'error'); return; }
                status(hgStatus, d.gleichWieVorher === false
                    ? t('display.hg_standard_byd', 'Zurückgesetzt. Falls das BYD-Bild fehlt: in den BYD-Einstellungen wieder wählen.')
                    : t('display.hg_standard_ok', 'Zurück auf Standard.'), 'success');
            }).then(function () { standard.disabled = false; });
        };

        // Display-Sperre
        mount.appendChild(el('h3', null, t('display.sp_titel', 'Display sperren')));
        mount.appendChild(el('div', 'sys-note', t('display.sp_hilfe', 'Für Werkstatt oder Parkservice: sperrt den Mittelschirm mit einer PIN. Nur in P. Rückfahrkamera, Klima-Leiste, Anrufe und Notruf bleiben frei. Beim Losfahren geht die Sperre von selbst weg.')));
        var pin = el('input'); pin.type = 'password'; pin.inputMode = 'numeric'; pin.autocomplete = 'new-password';
        pin.maxLength = 8; pin.placeholder = t('display.sp_pin', 'PIN (4 bis 8 Ziffern)');
        pin.style.cssText = 'min-height:48px;font-size:20px;max-width:260px';
        var pin2 = pin.cloneNode(); pin2.placeholder = t('display.sp_pin2', 'PIN nochmal');
        var an = knopf(t('display.sp_an', 'Display sperren'), true);
        var aus = knopf(t('display.sp_aus', 'Sperre aufheben'));
        var spStatus = el('div', 'sys-note'); spStatus.setAttribute('role', 'status');
        var r2 = el('div'); r2.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:8px';
        r2.appendChild(pin); r2.appendChild(pin2); r2.appendChild(an); r2.appendChild(aus);
        mount.appendChild(r2); mount.appendChild(spStatus);
        function stand() {
            holeJson('/api/display').then(function (d) {
                var s = d && d.sperre;
                if (!s) { status(spStatus, ''); return; }
                aus.hidden = !s.aktiv; an.hidden = !!s.aktiv; pin2.hidden = !!s.aktiv;
                status(spStatus, s.aktiv
                    ? (s.sichtbar ? t('display.sp_ist_an', 'Gesperrt.') : t('display.sp_zurueck', 'Gesperrt, gerade zurückgezogen (Kamera oder Anruf).'))
                      + (s.fehlversuche ? ' ' + t('display.sp_fehl', 'Falsche PIN: {n}×', { n: s.fehlversuche }) : '')
                    : (s.imStand ? '' : grund('nicht_in_p')), s.aktiv ? 'success' : '');
            });
        }
        an.onclick = function () {
            if (!/^\d{4,8}$/.test(pin.value)) { status(spStatus, grund('pin_ungueltig'), 'error'); return; }
            if (pin.value !== pin2.value) { status(spStatus, t('display.sp_ungleich', 'Die zwei PINs sind nicht gleich.'), 'error'); return; }
            an.disabled = true;
            post('/api/display/sperre/an', { pin: pin.value }).then(function (d) {
                pin.value = ''; pin2.value = '';
                if (!d.ok) status(spStatus, grund(d.grund), 'error'); else stand();
            }).then(function () { an.disabled = false; });
        };
        aus.onclick = function () {
            aus.disabled = true;
            post('/api/display/sperre/aus', { pin: pin.value }).then(function (d) {
                pin.value = '';
                if (!d.ok) status(spStatus, grund(d.grund), 'error'); else stand();
            }).then(function () { aus.disabled = false; });
        };
        stand();
    }

    // ------------------------------------------------------------------ Apps deaktivieren (apps.html)

    function apps(mount) {
        mount.innerHTML = '';
        var box = el('div', 'sys-note'); box.setAttribute('role', 'status');
        var liste = el('div');
        mount.appendChild(el('p', 'setting-desc', t('display.apps_hilfe', 'Nur Apps, die du selbst installiert hast. Apps von BYD, Android und Panoptes bleiben immer an. Deaktivierte Apps kannst du hier jederzeit wieder einschalten.')));
        mount.appendChild(liste); mount.appendChild(box);
        function laden() {
            holeJson('/api/apps/verwaltung').then(function (d) {
                liste.innerHTML = '';
                if (!d.success) { status(box, grund(d.grund), 'error'); return; }
                if (!(d.apps || []).length) { status(box, t('display.apps_leer', 'Keine eigenen Apps gefunden.')); return; }
                (d.apps || []).forEach(function (a) {
                    var z = el('div'); z.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 0;border-bottom:1px solid var(--border-default)';
                    var n = el('div', null, a.label); n.title = a.package;
                    if (a.deaktiviert) n.appendChild(el('span', 'sys-note', ' · ' + t('display.apps_ist_aus', 'aus')));
                    var b = knopf(a.deaktiviert ? t('display.apps_an', 'Wieder einschalten') : t('display.apps_aus', 'Deaktivieren'), a.deaktiviert);
                    b.onclick = function () { a.deaktiviert ? einschalten(a, b) : ausschalten(a, b); };
                    z.appendChild(n); z.appendChild(b); liste.appendChild(z);
                });
            });
        }
        function ausschalten(a, b) {
            b.disabled = true;
            post('/api/apps/verwaltung/frage', { package: a.package }).then(function (d) {
                if (!d.success) { status(box, grund(d.grund), 'error'); return; }
                if (!window.confirm(t('display.apps_frage', '„{name}“ deaktivieren? Die App verschwindet vom Display, bis du sie hier wieder einschaltest.', { name: a.label }))) return;
                return post('/api/apps/verwaltung/deaktivieren', { package: a.package, marke: d.marke }).then(function (r) {
                    status(box, r.success ? t('display.apps_ist_deaktiviert', 'Deaktiviert: {name}', { name: a.label }) : grund(r.grund), r.success ? 'success' : 'error');
                    laden();
                });
            }).then(function () { b.disabled = false; });
        }
        function einschalten(a, b) {
            b.disabled = true;
            post('/api/apps/verwaltung/aktivieren', { package: a.package }).then(function (r) {
                status(box, r.success ? t('display.apps_ist_aktiviert', 'Wieder an: {name}', { name: a.label }) : grund(r.grund), r.success ? 'success' : 'error');
                laden();
            }).then(function () { b.disabled = false; });
        }
        laden();
    }

    // ------------------------------------------------------------------ SD und USB (dateien.html)

    function wechsel(mount) {
        mount.innerHTML = '';
        var wahl = el('select'); wahl.style.cssText = 'min-height:48px;font-size:16px';
        var weg = el('div', 'sys-note'); var liste = el('div'); var box = el('div', 'sys-note');
        var kopierWahl = el('select'); kopierWahl.style.cssText = 'min-height:48px;font-size:16px;max-width:100%';
        var kopier = knopf(t('display.ws_kopieren', 'Aus „Im Auto“ hierher kopieren'));
        var zurueck = knopf('⬆ ' + t('display.ws_hoch', 'Eine Ebene höher'));
        var k = el('div'); k.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;margin-top:10px';
        k.appendChild(kopierWahl); k.appendChild(kopier);
        mount.appendChild(el('p', 'setting-desc', t('display.ws_hilfe', 'Dateien auf SD-Karte und USB-Stick ansehen und herunterladen. Dashcam-Aufnahmen stehen hier nicht, die findest du bei den Aufnahmen.')));
        mount.appendChild(wahl); mount.appendChild(weg); mount.appendChild(zurueck); mount.appendChild(liste); mount.appendChild(k); mount.appendChild(box);
        var pfad = '';
        function name(s) { return s === 'usb' ? t('display.ws_usb', 'USB-Stick') : t('display.ws_sd', 'SD-Karte'); }
        function speicher() {
            holeJson('/api/dateien/wechsel').then(function (d) {
                wahl.innerHTML = '';
                (d.speicher || []).forEach(function (s) { var o = el('option', null, name(s)); o.value = s; wahl.appendChild(o); });
                var da = (d.speicher || []).length > 0;
                wahl.hidden = !da; k.hidden = !da; zurueck.hidden = true;
                if (!da) { status(box, t('display.ws_keiner', 'Keine SD-Karte und kein USB-Stick erkannt.')); liste.innerHTML = ''; return; }
                pfad = ''; laden();
            });
            holeJson('/api/dateien').then(function (d) {
                kopierWahl.innerHTML = '';
                (d.dateien || []).forEach(function (f) { var o = el('option', null, f.name); o.value = f.name; kopierWahl.appendChild(o); });
                kopier.disabled = !(d.dateien || []).length;
            });
        }
        function laden() {
            weg.textContent = name(wahl.value) + (pfad ? ' / ' + pfad.split('/').join(' / ') : '');
            zurueck.hidden = !pfad;
            post('/api/dateien/wechsel/liste', { speicher: wahl.value, pfad: pfad }).then(function (d) {
                liste.innerHTML = '';
                if (!d.ok) { status(box, grund(d.grund), 'error'); return; }
                status(box, (d.eintraege || []).length ? '' : t('display.ws_leer', 'Dieser Ordner ist leer.'));
                (d.eintraege || []).forEach(function (e) {
                    var z = el('div'); z.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 0;border-bottom:1px solid var(--border-default)';
                    var n = el('div', null, (e.ordner ? '📁 ' : '') + e.name);
                    if (!e.ordner) n.appendChild(el('span', 'sys-note', ' · ' + mb(e.groesse)));
                    var r = el('div'); r.style.cssText = 'display:flex;gap:6px';
                    var ziel = (pfad ? pfad + '/' : '') + e.name;
                    if (e.ordner) {
                        var o = knopf(t('display.ws_oeffnen', 'Öffnen')); o.onclick = function () { pfad = ziel; laden(); }; r.appendChild(o);
                    } else {
                        var h = knopf(t('dateien.holen', 'Herunterladen')); h.onclick = function () { holen(ziel, e.name, h); };
                        var l = knopf(t('dateien.loeschen', 'Löschen')); l.onclick = function () { loeschen(ziel, e.name, l); };
                        r.appendChild(h); r.appendChild(l);
                    }
                    z.appendChild(n); z.appendChild(r); liste.appendChild(z);
                });
            });
        }
        function holen(ziel, n, b) {
            b.disabled = true;
            fetch('/api/dateien/wechsel/holen', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ speicher: wahl.value, pfad: ziel }) })
                .then(function (r) { if (!r.ok) throw { grund: 'nicht_erlaubt' }; return r.blob(); })
                .then(function (bl) {
                    var url = URL.createObjectURL(bl), a = document.createElement('a');
                    a.href = url; a.download = n; a.rel = 'noopener'; document.body.appendChild(a); a.click(); a.remove();
                    setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
                })
                .catch(function (d) { status(box, grund(d && d.grund), 'error'); })
                .then(function () { b.disabled = false; });
        }
        function loeschen(ziel, n, b) {
            b.disabled = true;
            post('/api/dateien/wechsel/frage', { aktion: 'loeschen', speicher: wahl.value, pfad: ziel }).then(function (d) {
                if (!d.ok) { status(box, grund(d.grund), 'error'); return; }
                if (!window.confirm(t('display.ws_loeschen_frage', '„{name}“ auf {wo} löschen? Das geht nicht rückgängig.', { name: n, wo: name(wahl.value) }))) return;
                return post('/api/dateien/wechsel/loeschen', { speicher: wahl.value, pfad: ziel, marke: d.marke }).then(function (r) {
                    status(box, r.ok ? t('dateien.geloescht', 'Gelöscht.') : grund(r.grund), r.ok ? 'success' : 'error');
                    laden();
                });
            }).then(function () { b.disabled = false; });
        }
        kopier.onclick = function () {
            var n = kopierWahl.value;
            if (!n) return;
            kopier.disabled = true;
            post('/api/dateien/wechsel/frage', { aktion: 'kopieren', speicher: wahl.value, pfad: pfad, name: n }).then(function (d) {
                if (!d.ok) { status(box, grund(d.grund), 'error'); return; }
                if (!window.confirm(t('display.ws_kopieren_frage', '„{name}“ auf {wo} kopieren?', { name: n, wo: name(wahl.value) }))) return;
                return post('/api/dateien/wechsel/kopieren', { speicher: wahl.value, pfad: pfad, name: n, marke: d.marke }).then(function (r) {
                    status(box, r.ok ? t('display.ws_kopiert', 'Kopiert.') : grund(r.grund), r.ok ? 'success' : 'error');
                    laden();
                });
            }).then(function () { kopier.disabled = false; });
        };
        zurueck.onclick = function () { pfad = pfad.indexOf('/') >= 0 ? pfad.substring(0, pfad.lastIndexOf('/')) : ''; laden(); };
        wahl.onchange = function () { pfad = ''; laden(); };
        speicher();
    }

    function start() {
        var a = document.getElementById('displayWerkzeuge'); if (a) display(a);
        var b = document.getElementById('appVerwaltung'); if (b) apps(b);
        var c = document.getElementById('wechselspeicher'); if (c) wechsel(c);
    }
    function bereit() {
        if (window.BYD && BYD.i18n && BYD.i18n.init) BYD.i18n.init().then(start, start); else start();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bereit); else bereit();
}());
