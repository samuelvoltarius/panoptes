/**
 * Panoptes — „Was zeigt der Tacho?“ (0.1.37). EINE Auswahl als große Kacheln (REGEL 50+):
 * „Normal“ (BYD-Tacho), die fünf Panoptes-Designs mit Vorschau, Navi-Apps und freigegebene Apps.
 * Ein Tipp wählt. Daten und Regeln kommen ausschließlich vom Kern:
 *   GET  /api/tacho/auswahl          → { jetzt, eintraege:[{id, art, name, waehlbar, gewaehlt, grund, …}] }
 *   POST /api/tacho/auswahl {id}     → { ok, grund }
 * Freigabe, „auch beim Fahren“ und der Hinweis bleiben in tacho-appwahl.js (darunter auf derselben Seite).
 * Vertrag für Handy-Apps und Cockpit: TACHO_AUSWAHL_API.md (Notizordner).
 */
(function () {
    'use strict';
    function $(id) { return document.getElementById(id); }
    function t(key, fallback) {
        return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(key, fallback) : fallback;
    }
    var stand = null, letzt = '', laeuft = false, vorschauen = [];

    function grundText(g) {
        var map = {
            nicht_freigegeben: t('vehicle_control.tacho_auswahl_erst_freigeben', 'Erst unten freigeben'),
            nur_im_stand: t('vehicle_control.cluster_app_park', 'Nur in P möglich.'),
            nicht_installiert: t('vehicle_control.cluster_app_uninstalled', 'Die App ist nicht mehr installiert oder nicht startbar.'),
            hinweis_noetig: t('vehicle_control.cluster_app_hinweis_noetig', 'Lies zuerst den Hinweis und tippe auf „Verstanden“.'),
            auf_diesem_auto_nicht_angeboten: t('vehicle_control.tacho_auswahl_nicht_moeglich', 'Auf diesem Auto nicht möglich'),
            ungueltige_auswahl: t('vehicle_control.cluster_app_invalid', 'Diese App kann nicht gewählt werden.')
        };
        if (g && g.indexOf('dilink5') === 0) return t('vehicle_control.tacho_auswahl_nicht_moeglich', 'Auf diesem Auto nicht möglich');
        return map[g] || t('vehicle_control.cluster_app_failed', 'Das hat nicht geklappt.');
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
        b.style.cssText = 'display:flex;flex-direction:column;align-items:stretch;gap:6px;width:100%;padding:8px;'
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
            z.style.cssText = 'position:absolute;top:0;right:0;bottom:0;left:0;inset:0;display:flex;align-items:center;justify-content:center;'
                + 'font:700 40px/1 sans-serif;color:' + (e.art === 'normal' ? '#EEF4F8' : '#B5F23D') + ';';
            z.textContent = e.art === 'normal' ? 'BYD' : (e.name || '?').trim().charAt(0).toUpperCase();
            bild.appendChild(z);
        }
        b.appendChild(bild);
        var name = document.createElement('span');
        name.style.cssText = 'font-size:18px;font-weight:700;line-height:1.2;';
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
        if (!e.waehlbar && e.grund) info.push(grundText(e.grund));
        var klein = document.createElement('span');
        klein.style.cssText = 'font-size:15px;opacity:.85;line-height:1.25;';
        klein.textContent = info.join(' · ');
        b.appendChild(klein);
        b.onclick = function () { waehle(e); };
        return b;
    }

    function zeichne() {
        var box = $('tachoAuswahlKacheln');
        if (!box || !stand) return;
        for (var i = 0; i < vorschauen.length; i++) if (vorschauen[i].tacho) vorschauen[i].tacho.stop();
        vorschauen = [];
        box.textContent = '';
        var jetzt = stand.jetzt || {};
        box.appendChild(kachel({ id: 'normal', art: 'normal', waehlbar: true, gewaehlt: jetzt.id === 'normal' }));
        var liste = stand.eintraege || [];
        for (var j = 0; j < liste.length; j++) box.appendChild(kachel(liste[j]));
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
        if (!e.waehlbar && e.grund) { meldung(grundText(e.grund), true); return; }
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
