/**
 * Panoptes — „Was zeigt der Tacho?“ (0.1.37). EINE Auswahl als große Kacheln (REGEL 50+):
 * „Normal“ (BYD-Tacho), die fünf Panoptes-Designs mit Vorschau, Navi-Apps und freigegebene Apps.
 * Ein Tipp wählt. Daten und Regeln kommen ausschließlich vom Kern:
 *   GET  /api/tacho/auswahl          → { jetzt, eintraege:[{id, art, name, waehlbar, gewaehlt, grund, …}] }
 *   POST /api/tacho/auswahl {id}     → { ok, grund }
 * Freigabe, „auch beim Fahren“ und der Hinweis bleiben in tacho-appwahl.js (darunter auf derselben Seite).
 * Vertrag für Handy-Apps und Cockpit: TACHO_AUSWAHL_API.md (Notizordner).
 * 0.1.42 (T2) Galerie: 20 Designs als große Bilder, Filter Analog/Digital/Navi/Ruhig (PanoptesTacho.arten),
 * Live-Vorschau des zuletzt getippten Designs mit Beispielfahrt und „Als Vorlage nehmen“ (öffnet „Tacho gestalten“).
 */
(function () {
    'use strict';
    function $(id) { return document.getElementById(id); }
    function t(key, fallback) {
        return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(key, fallback) : fallback;
    }
    var stand = null, letzt = '', laeuft = false, vorschauen = [];
    var filter = 'alle', live = { design: null, tacho: null, takt: null, start: Date.now() };

    // 0.1.42 (E2E G1): Gründe aus der Fähigkeitsprüfung (single_os, kein_projektionsdienst …) heißen auf den Karten
    // „Dieses Auto zeigt im Tacho keine Panoptes-Designs“; der Rückfall ist „Das hat nicht geklappt“, nie der App-Start-Text.
    var KEIN_TACHO = ['single_os', 'kein_projektionsdienst', 'keine_shell_rechte', 'warnleuchten_verdeckt', 'warnleuchten_ungeprueft'];

    function grundText(g, karte) {
        var keinTacho = t('vehicle_control.tacho_auswahl_kein_tacho', 'Dieses Auto zeigt im Tacho keine Panoptes-Designs.');
        if (KEIN_TACHO.indexOf(g) >= 0) return keinTacho;
        var map = {
            nicht_freigegeben: t('vehicle_control.tacho_auswahl_erst_freigeben', 'Erst unten freigeben'),
            nur_im_stand: t('vehicle_control.cluster_app_park', 'Nur in P möglich.'),
            nicht_installiert: t('vehicle_control.cluster_app_uninstalled', 'Die App ist nicht mehr installiert oder nicht startbar.'),
            hinweis_noetig: t('vehicle_control.cluster_app_hinweis_noetig', 'Lies zuerst den Hinweis und tippe auf „Verstanden“.'),
            auf_diesem_auto_nicht_angeboten: t('vehicle_control.tacho_auswahl_nicht_moeglich', 'Auf diesem Auto nicht möglich'),
            ungueltige_auswahl: t('vehicle_control.cluster_app_invalid', 'Diese App kann nicht gewählt werden.')
        };
        if (g && g.indexOf('dilink5') === 0) return t('vehicle_control.tacho_auswahl_nicht_moeglich', 'Auf diesem Auto nicht möglich');
        if (map[g]) return map[g];
        return karte ? keinTacho : t('vehicle_control.tacho_auswahl_fehlgeschlagen', 'Das hat nicht geklappt.');
    }

    function meldung(text, schlecht) {
        var el = $('tachoAuswahlMeldung');
        if (!el) return;
        el.textContent = text || '';
        el.style.color = schlecht ? 'var(--status-danger)' : '';
    }

    function kachel(e) {
        var b = document.createElement('button');
        b.type = 'button';
        b.setAttribute('data-tacho-id', e.id);
        b.setAttribute('aria-pressed', e.gewaehlt ? 'true' : 'false');
        b.style.cssText = 'display:flex;flex-direction:column;align-items:stretch;width:100%;padding:8px;'
            + 'border-radius:14px;border:3px solid ' + (e.gewaehlt ? '#B5F23D' : 'var(--outline-variant)') + ';'
            + 'background:var(--surface-container);color:var(--on-surface);cursor:pointer;text-align:center;'
            + (e.waehlbar ? '' : 'opacity:.55;');
        var bild = document.createElement('div');
        bild.style.cssText = 'position:relative;padding-top:45%;border-radius:10px;overflow:hidden;background:#000;';
        if (e.art === 'design' && window.PanoptesTacho) {
            var cv = document.createElement('canvas');
            cv.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;';
            bild.appendChild(cv);
            vorschauen.push({ cv: cv, design: e.design });
        } else {
            var z = document.createElement('div');
            z.style.cssText = 'position:absolute;top:0;right:0;bottom:0;left:0;display:flex;align-items:center;justify-content:center;'
                + 'font:700 40px/1 sans-serif;color:' + (e.art === 'normal' ? '#EEF4F8' : '#B5F23D') + ';';
            z.textContent = e.art === 'normal' ? 'BYD' : (e.name || '?').trim().charAt(0).toUpperCase();
            bild.appendChild(z);
        }
        b.appendChild(bild);
        var name = document.createElement('span');
        name.style.cssText = 'font-size:18px;font-weight:700;line-height:1.2;margin-top:6px;';
        name.textContent = e.art === 'design' ? t('vehicle_control.cluster_design_' + e.design, e.name)
            : e.art === 'normal' ? t('vehicle_control.tacho_auswahl_normal', 'Normal') : e.name;
        b.appendChild(name);
        var info = [];
        if (e.gewaehlt) info.push(t('vehicle_control.tacho_auswahl_laeuft', 'Läuft'));
        if (e.art === 'normal') info.push(t('vehicle_control.tacho_auswahl_normal_info', 'BYD-Tacho'));
        if (e.art === 'app') {
            if (e.navi) info.push(t('vehicle_control.tacho_auswahl_navi', 'Navi'));
            if (e.video) info.push(t('vehicle_control.tacho_auswahl_video', 'Video'));
            info.push(e.fahrt ? t('vehicle_control.tacho_auswahl_fahrt', 'Auch beim Fahren')
                : t('vehicle_control.tacho_auswahl_nur_p', 'Nur in P'));
        }
        if (!e.waehlbar && e.grund) info.push(grundText(e.grund, true));
        var klein = document.createElement('span');
        klein.style.cssText = 'font-size:15px;opacity:.85;line-height:1.25;margin-top:6px;';
        klein.textContent = info.join(' · ');
        b.appendChild(klein);
        b.onclick = function () { if (e.art === 'design') zeigeLive(e.design); waehle(e); };
        return b;
    }

    // ---------- 0.1.42 (T2) Galerie: Filter und Live-Vorschau

    function passt(e) {
        if (filter === 'alle') return true;
        if (e.art !== 'design' || !window.PanoptesTacho || !PanoptesTacho.arten) return false;
        return PanoptesTacho.arten(e.design).indexOf(filter) >= 0;
    }

    function filterLeiste() {
        var box = $('tachoGalerieFilter');
        if (!box || box.childNodes.length) return;
        var namen = { alle: t('tacho_galerie.alle', 'Alle'), analog: t('tacho_galerie.analog', 'Analog'),
            digital: t('tacho_galerie.digital', 'Digital'), navi: t('tacho_galerie.navi', 'Navi'), ruhig: t('tacho_galerie.ruhig', 'Ruhig') };
        ['alle', 'analog', 'digital', 'navi', 'ruhig'].forEach(function (f) {
            var k = document.createElement('button');
            k.type = 'button';
            k.className = 'btn';
            k.setAttribute('data-filter', f);
            k.style.cssText = 'min-height:56px;min-width:110px;font-size:17px;margin:0 8px 8px 0;';
            k.textContent = namen[f];
            k.onclick = function () { filter = f; filterZeigen(); zeichne(); };
            box.appendChild(k);
        });
        filterZeigen();
    }

    function filterZeigen() {
        var box = $('tachoGalerieFilter');
        if (!box) return;
        Array.prototype.forEach.call(box.querySelectorAll('[data-filter]'), function (k) {
            var an = k.getAttribute('data-filter') === filter;
            k.setAttribute('aria-pressed', an ? 'true' : 'false');
            k.style.border = '3px solid ' + (an ? '#B5F23D' : 'transparent');
        });
    }

    /** Große Vorschau mit Beispielfahrt; ruhig, zwei Bilder pro Sekunde, nur solange die Seite sichtbar ist. */
    function zeigeLive(dsg) {
        var box = $('tachoGalerieLive');
        if (!box || !window.PanoptesTacho || !dsg || dsg === live.design) return;
        live.design = dsg;
        if (live.tacho) live.tacho.stop();
        if (live.takt) clearInterval(live.takt);
        box.textContent = '';
        var titel = document.createElement('div');
        titel.className = 'dp-titel';
        titel.textContent = t('tacho_galerie.live', 'Vorschau') + ': ' + t('vehicle_control.cluster_design_' + dsg, dsg);
        box.appendChild(titel);
        var bild = document.createElement('div');
        bild.style.cssText = 'position:relative;padding-top:40%;border-radius:12px;overflow:hidden;background:#000;';
        var cv = document.createElement('canvas');
        cv.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;';
        bild.appendChild(cv);
        box.appendChild(bild);
        if (dsg !== 'eigen') {
            var v = document.createElement('button');
            v.type = 'button';
            v.className = 'btn';
            v.id = 'tachoGalerieVorlage';
            v.style.cssText = 'min-height:56px;font-size:17px;margin-top:8px;';
            v.textContent = t('tacho_galerie.vorlage', 'Als Vorlage nehmen');
            v.onclick = function () { if (window.PanoptesTachoGestalten) window.PanoptesTachoGestalten.vorlage(dsg); };
            box.appendChild(v);
        }
        live.tacho = PanoptesTacho.create(cv, { design: dsg });
        var tick = function () {
            if (document.hidden) return;
            var d = PanoptesTacho.demo((Date.now() - live.start) / 1000);
            d.design = dsg;
            live.tacho.setze(d);
        };
        tick();
        live.takt = setInterval(tick, 500);
        if (dsg === 'eigen') eigenVorschau(live.tacho);
    }

    function zeichne() {
        var box = $('tachoAuswahlKacheln');
        if (!box || !stand) return;
        for (var i = 0; i < vorschauen.length; i++) if (vorschauen[i].tacho) vorschauen[i].tacho.stop();
        vorschauen = [];
        box.textContent = '';
        var jetzt = stand.jetzt || {};
        filterLeiste();
        if (filter === 'alle') box.appendChild(kachel({ id: 'normal', art: 'normal', waehlbar: true, gewaehlt: jetzt.id === 'normal' }));
        var liste = stand.eintraege || [];
        for (var j = 0; j < liste.length; j++) if (passt(liste[j])) box.appendChild(kachel(liste[j]));
        if (!live.design) zeigeLive(jetzt.art === 'design' && jetzt.design ? jetzt.design : 'klar');
        for (var k = 0; k < vorschauen.length; k++) {
            var v = vorschauen[k];
            v.tacho = window.PanoptesTacho.create(v.cv, { design: v.design });
            var d = window.PanoptesTacho.demo(8);
            d.design = v.design;
            v.tacho.setze(d);
            if (v.design === 'eigen') eigenVorschau(v.tacho);
        }
    }

    /** 0.1.41: Kachel „Eigenes“ zeigt das gespeicherte eigene Design. */
    function eigenVorschau(tacho) {
        fetch('/api/tacho/eigen', { cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (j) { if (j && j.eigen) tacho.eigen(j.eigen); }, function () { });
    }

    function laden() {
        return fetch('/api/tacho/auswahl', { cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (j) {
                if (!j) return;
                var neu = JSON.stringify(j);
                if (neu === letzt) return;   // nur neu zeichnen, wenn sich etwas geändert hat
                letzt = neu;
                stand = j;
                zeichne();
            }, function () { });
    }

    function waehle(e) {
        if (laeuft) return;
        if (!e.waehlbar && e.grund) { meldung(grundText(e.grund, true), true); return; }
        laeuft = true;
        meldung('');
        fetch('/api/tacho/auswahl', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: e.id }) })
            .then(function (r) { return r.json().then(function (j) { return { ok: r.ok && j.ok !== false, j: j }; }); })
            .then(function (x) {
                if (x.ok) meldung(t('vehicle_control.tacho_auswahl_ok', 'Erledigt.'));
                else {
                    meldung(grundText(x.j && x.j.grund), true);
                    if (x.j && x.j.grund === 'hinweis_noetig' && $('clusterAppHinweis')) $('clusterAppHinweis').style.display = '';
                }
            }, function () { meldung(grundText(''), true); })
            .then(function () { laeuft = false; return laden(); });
    }

    function boot() {
        if (!$('tachoAuswahlKacheln')) return;
        laden();
        // Ruhig nachziehen, solange die Seite sichtbar ist (z. B. nach Freigabe weiter unten).
        setInterval(function () { if (!document.hidden && !laeuft) laden(); }, 8000);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
