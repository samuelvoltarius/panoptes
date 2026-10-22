/**
 * Panoptes — Kartenquelle (Datenschutz, 0.1.8).
 *
 * Karten laden Kartenbilder aus dem Netz. Die Quelle sieht dabei IP-Adresse und
 * Kartenausschnitt, also ungefähr, wo das Auto steht. Deshalb wählt der Nutzer die
 * Quelle selbst; Standard ist „keine“ (leere Karte mit Hinweis). Kein stiller
 * Wechsel auf eine andere Quelle.
 *
 *   PanoptesKartenquelle.get()            → 'community' | 'osm' | 'keine'
 *   PanoptesKartenquelle.set(q)
 *   PanoptesKartenquelle.hinweis(el, art) art: 'wahl' | 'nicht_erreichbar'
 *   PanoptesKartenquelle.waehler(el, fn)  Auswahlliste (Seite System)
 *
 * Am Auto-Display (Hülle: html[data-android-app] bzw. User-Agent „PanoptesDisplay“) gilt die
 * Kern-Einstellung `karte.quelle` (Gruppe datenschutz) – derselbe Wert wie für die native Karte.
 * localStorage `pn.karte.quelle` ist dort nur Spiegel bzw. Rückfall für Kerne ohne das Feld.
 * Fremde Browser (Handy, PC) haben eine eigene IP und fragen deshalb getrennt: nur localStorage.
 * Fehlt alles, gilt „keine“.
 *
 * 0.1.10: Alle Karten (auch Live-Ansicht und sichere Orte) laufen über panoptes-map.js und folgen
 * dieser einen Quelle. Die frühere Zusatz-Einwilligung `pn.karte.osmLeaflet` entfällt und wird gelöscht.
 * ES5 (WebView 74).
 */
(function () {
    'use strict';

    var KEY = 'pn.karte.quelle';
    var QUELLEN = ['community', 'osm', 'keine'];

    var DE = {
        titel: 'Kartenquelle wählen',
        text: 'Für die Karte lädt Panoptes Kartenbilder aus dem Netz. Die gewählte Quelle sieht deine IP-Adresse und den Kartenausschnitt, also ungefähr, wo dein Auto steht. Solange du nichts wählst, bleibt die Karte leer und es geht nichts raus.',
        dauer: 'Wie lange die Quelle Zugriffe speichert, bestimmt ihr Betreiber. Du kannst die Wahl jederzeit unter System → Kartenquelle ändern.',
        waehlen: 'Kartenquelle wählen',
        nicht_erreichbar_titel: 'Karte nicht erreichbar',
        nicht_erreichbar_text: 'Die gewählte Kartenquelle antwortet gerade nicht. Panoptes wechselt nicht von selbst auf eine andere Quelle.',
        aktuell: 'Gewählt',
        q_community: 'Community-Server (byd.serveone.at)',
        q_community_sub: 'Betrieben vom Panoptes-Projekt. Mit Gelände und Ladesäulen-Belegung; dafür fragt die App dort auch mit dem Kartenausschnitt nach.',
        q_osm: 'OpenStreetMap Deutschland (tile.openstreetmap.de)',
        q_osm_sub: 'Freier Kartendienst. Nur das Kartenbild, ohne Gelände und Ladesäulen.',
        q_keine: 'Keine',
        q_keine_sub: 'Die Karten bleiben leer. Es geht nichts raus.',
        karte_titel: 'Kartenquelle',
        kurz: 'Keine Kartenquelle gewählt',
        geraet: 'Die Wahl gilt für dieses Gerät bzw. diesen Browser.',
        geraet_auto: 'Die Wahl gilt für das ganze Auto, auch für die Karte am Display.',
        fehler: 'Speichern hat nicht geklappt. Die Kartenquelle ist unverändert.'
    };

    function T(k) {
        var key = 'kartenquelle.' + k;
        if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
            var v = BYD.i18n.t(key);
            if (v && v !== key) return v;
        }
        return DE[k] || k;
    }

    function get() {
        var v = null;
        try { v = window.localStorage.getItem(KEY); } catch (e) { v = null; }
        return QUELLEN.indexOf(v) >= 0 ? v : 'keine';
    }

    function spiegel(q) {
        try { window.localStorage.setItem(KEY, q); } catch (e) { return false; }
        return true;
    }

    function istDisplay() {
        var de = document.documentElement;
        if (/PanoptesDisplay/.test(navigator.userAgent || '')) return true;
        if (de && de.hasAttribute('data-android-app')) return true;
        try { return sessionStorage.getItem('pn.anzeige') === 'auto'; } catch (e) { return false; }
    }

    // 0.1.10: alte Zusatz-Einwilligung der früheren OSM-Einzelkarten wird nicht mehr gebraucht – Rest löschen.
    try { window.localStorage.removeItem('pn.karte.osmLeaflet'); } catch (e) {}

    // ---------------------------------------------------------------- Kern-Einstellung
    var SCHLUESSEL = 'karte.quelle';
    var kern = null;          // null = unbekannt, false = Kern ohne Feld, true = Kern führt
    var kernPromise = null;

    function leseKern() {
        if (!window.fetch || !istDisplay()) return Promise.resolve(null);
        return fetch('/api/einstellungen', { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (!d || !d.werte) return null;
                if (!d.werte[SCHLUESSEL]) { kern = false; return { etag: d.etag, wert: null }; }
                kern = true;
                var w = d.werte[SCHLUESSEL].wert;
                return { etag: d.etag, wert: QUELLEN.indexOf(w) >= 0 ? w : 'keine' };
            })
            .catch(function () { return null; });
    }

    // Beim Laden einmal abgleichen; Karten warten darauf (bereit()). Kern-Wert gewinnt.
    // Antwortet der Kern nicht binnen 3 s, gilt der Spiegel (Standard „keine“).
    function abgleich() {
        if (kernPromise) return kernPromise;
        var vorher = get();
        kernPromise = new Promise(function (fertig) {
            var erledigt = false;
            setTimeout(function () { if (!erledigt) { erledigt = true; fertig(vorher); } }, 3000);
            leseKern().then(function (r) {
                var q = (!r || r.wert === null) ? vorher : r.wert;
                if (q !== vorher) spiegel(q);
                if (!erledigt) { erledigt = true; fertig(q); }
                // immer neu zeichnen: auch der Satz „gilt fürs ganze Auto“ hängt am Kern-Abgleich
                zeichneAlle();
            });
        });
        return kernPromise;
    }

    // Speichern: im Kern (PUT mit etag, danach zurücklesen), sonst nur im Spiegel.
    function set(q) {
        if (QUELLEN.indexOf(q) < 0) return Promise.resolve(false);
        return leseKern().then(function (r) {
            if (!r || r.wert === null) return spiegel(q) && get() === q;
            return fetch('/api/einstellungen', {
                method: 'PUT', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ etag: r.etag, werte: (function () { var w = {}; w[SCHLUESSEL] = q; return w; })() })
            }).then(function () { return leseKern(); }).then(function (r2) {
                if (!r2 || r2.wert !== q) return false;
                spiegel(q);
                return true;
            });
        }).catch(function () { return false; });
    }

    function el(tag, cls, key, txt) {
        var n = document.createElement(tag);
        if (cls) n.className = cls;
        if (key) n.setAttribute('data-i18n', 'kartenquelle.' + key);
        if (key || txt) n.textContent = txt || T(key);
        return n;
    }

    var cssDone = false;
    function css() {
        if (cssDone) return;
        cssDone = true;
        var s = document.createElement('style');
        s.textContent =
            '.pn-kq-box{position:absolute;top:0;right:0;bottom:0;left:0;z-index:1100;display:flex;' +
            'padding:12px;background:var(--surface,#0b2236);color:var(--on-surface,#e6f1f4);overflow:auto;-webkit-overflow-scrolling:touch}' +
            '.pn-kq-in{margin:auto;max-width:520px;width:100%;font-size:14px;line-height:1.45}' +
            '.pn-kq-box.klein .pn-kq-lang,.pn-kq-box.klein .pn-kq-neben,.pn-kq-box:not(.klein) .pn-kq-kurz{display:none}' +
            '.pn-kq-in h3{margin:0 0 8px;font-size:16px}' +
            '.pn-kq-in p{margin:0 0 8px}' +
            '.pn-kq-klein{opacity:.75;font-size:13px;margin-top:10px!important}' +
            '.pn-kq-btn{min-height:48px;padding:0 18px;border-radius:12px;border:1px solid var(--outline,#2a6474);' +
            'background:var(--primary,#5FD6E6);color:var(--on-primary,#06283a);font-weight:600;font-size:15px;cursor:pointer}' +
            '.pn-kq-liste{display:block}' +
            '.pn-kq-neben{display:block;margin-top:8px;background:transparent;color:inherit}' +
            '.pn-kq-opt{display:block;width:100%;text-align:left;min-height:48px;margin:0 0 8px;padding:10px 14px;border-radius:12px;' +
            'border:1px solid var(--outline,#2a6474);background:transparent;color:inherit;font-size:14px;cursor:pointer}' +
            '.pn-kq-opt b{display:block;font-size:15px;margin-bottom:2px}' +
            '.pn-kq-opt span{display:block;opacity:.8;font-size:13px}' +
            '.pn-kq-opt.on{border-color:var(--primary,#5FD6E6);box-shadow:inset 0 0 0 1px var(--primary,#5FD6E6)}' +
            '.pn-kq-opt .pn-kq-akt{display:inline;margin-left:6px;font-size:12px;color:var(--primary,#5FD6E6);opacity:1}';
        document.head.appendChild(s);
    }

    var waehlerListe = [];
    function zeichneAlle() {
        waehlerListe.forEach(function (w) { waehler(w.box, w.fn, true); });
    }

    // Auswahlliste. fn(q) nach erfolgreichem Speichern.
    function waehler(box, fn, neu) {
        css();
        if (!neu) waehlerListe.push({ box: box, fn: fn });
        box.innerHTML = '';
        var hinweisGeraet = document.querySelector('[data-kq-geraet]');
        if (hinweisGeraet) {
            var gk = kern === true ? 'geraet_auto' : 'geraet';
            hinweisGeraet.setAttribute('data-i18n', 'kartenquelle.' + gk);
            hinweisGeraet.textContent = T(gk);
        }
        var liste = el('div', 'pn-kq-liste');
        var akt = get();
        QUELLEN.forEach(function (q) {
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'pn-kq-opt' + (q === akt ? ' on' : '');
            b.setAttribute('data-quelle', q);
            b.setAttribute('aria-pressed', q === akt ? 'true' : 'false');
            var t = el('b', '', 'q_' + q);
            if (q === akt) t.appendChild(el('span', 'pn-kq-akt', 'aktuell'));
            b.appendChild(t);
            b.appendChild(el('span', '', 'q_' + q + '_sub'));
            b.onclick = function () {
                b.disabled = true;
                set(q).then(function (ok) {
                    waehler(box, fn, true);
                    if (!ok) { box.appendChild(el('p', 'pn-kq-klein', 'fehler')); return; }
                    if (typeof fn === 'function') fn(q);
                });
            };
            liste.appendChild(b);
        });
        box.appendChild(liste);
        return liste;
    }

    // Hinweis im Kartenfeld statt Kacheln. Nach einer Wahl lädt die Seite neu.
    function hinweis(mapEl, art) {
        if (!mapEl) return null;
        css();
        try {
            if (window.getComputedStyle(mapEl).position === 'static') mapEl.style.position = 'relative';
        } catch (e) {}
        var alt = mapEl.querySelector('.pn-kq-box');
        if (alt) alt.parentNode.removeChild(alt);
        var box = el('div', 'pn-kq-box');
        box.setAttribute('data-kq-art', art || 'wahl');
        var inner = el('div', 'pn-kq-in');
        if (art === 'nicht_erreichbar') {
            inner.appendChild(el('h3', '', 'nicht_erreichbar_titel'));
            inner.appendChild(el('p', '', 'nicht_erreichbar_text'));
        } else {
            inner.appendChild(el('h3', 'pn-kq-lang', 'titel'));
            inner.appendChild(el('h3', 'pn-kq-kurz', 'kurz'));
            inner.appendChild(el('p', 'pn-kq-lang', 'text'));
        }
        var btn = el('button', 'pn-kq-btn', 'waehlen');
        btn.type = 'button';
        btn.onclick = function (ev) {
            if (ev && ev.stopPropagation) ev.stopPropagation();
            btn.style.display = 'none';
            box.className = 'pn-kq-box';   // Langtext wieder zeigen
            var w = el('div', '');
            inner.appendChild(w);
            waehler(w, function () { setTimeout(function () { window.location.reload(); }, 150); });
        };
        inner.appendChild(btn);
        if (art !== 'nicht_erreichbar') inner.appendChild(el('p', 'pn-kq-klein pn-kq-lang', 'dauer'));
        box.appendChild(inner);
        // Karten-Gesten nicht auslösen
        ['mousedown', 'touchstart', 'wheel', 'click', 'dblclick'].forEach(function (t) {
            box.addEventListener(t, function (e) { e.stopPropagation(); }, false);
        });
        mapEl.appendChild(box);
        // Kleines Kartenfeld: nur Titel und Knopf; der Erklärtext kommt mit der Auswahl.
        if (box.clientHeight && box.clientHeight < 260) box.className = 'pn-kq-box klein';
        return box;
    }

    abgleich();

    window.PanoptesKartenquelle = { istDisplay: istDisplay,
        bereit: abgleich, abgleich: abgleich, kernFuehrt: function () { return kern; }, KEY: KEY, QUELLEN: QUELLEN, get: get, set: set, hinweis: hinweis, waehler: waehler, text: T };
})();
