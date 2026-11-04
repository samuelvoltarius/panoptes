/**
 * Panoptes Startansicht: fünf Darstellungen, ein bestehender Widget-Baum.
 *
 * Am Autobildschirm ist anzeige.startAnsicht die dauerhafte Quelle im Kern.
 * Handy und PC speichern nur in diesem Browser. ES5 fuer die alte Auto-WebView.
 */
(function () {
    'use strict';

    var KEY = 'pn.startAnsicht';
    var FELD = 'anzeige.startAnsicht';
    var PROFILE = ['panoptes', 'fahren', 'fahrzeug', 'kompakt', 'cockpit'];
    var kern = null;
    var abgleich = null;

    function istProfil(v) { return PROFILE.indexOf(v) >= 0; }
    function istAuto() {
        return document.documentElement.getAttribute('data-anzeige') === 'auto'
            || /PanoptesDisplay/.test(navigator.userAgent || '')
            || document.documentElement.hasAttribute('data-android-app');
    }
    function lokal() {
        var v = null;
        try { v = localStorage.getItem(KEY); } catch (e) { v = null; }
        return istProfil(v) ? v : null;
    }
    function wert() { return lokal() || 'panoptes'; }
    function spiegel(v) { try { localStorage.setItem(KEY, v); } catch (e) {} }
    function text(key, fallback) {
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key);
                if (v && v !== key) return v;
            }
        } catch (e) {}
        return fallback;
    }
    function ereignis(v) {
        try {
            var ev = document.createEvent('CustomEvent');
            ev.initCustomEvent('pn-home-layout', false, false, { wert: v });
            window.dispatchEvent(ev);
        } catch (e) {}
    }
    function anwenden() {
        var v = wert();
        document.documentElement.setAttribute('data-home-layout', v);
        var grid = document.querySelector('.home-grid');
        if (grid) grid.setAttribute('data-home-layout', v);
        ereignis(v);
        window.setTimeout(function () {
            if (window.Home && Home.map && typeof Home.map.resize === 'function') Home.map.resize();
        }, 0);
        return v;
    }
    // allen = auch am Handy/PC lesen („Für das Auto-Display“ aus der Ferne, 0.1.38.7)
    function kernLesen(allen) {
        if (!window.fetch || (!allen && !istAuto())) return Promise.resolve(null);
        return fetch('/api/einstellungen', { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (!d || !d.werte) return null;
                var f = d.werte[FELD];
                if (!f) { kern = false; return { etag: d.etag, wert: null }; }
                kern = true;
                return { etag: d.etag, wert: istProfil(f.wert) ? f.wert : 'panoptes' };
            }).catch(function () { return null; });
    }
    function abgleichen() {
        if (!istAuto()) return Promise.resolve(anwenden());
        if (abgleich) return abgleich;
        abgleich = kernLesen().then(function (r) {
            if (r && r.wert) spiegel(r.wert);
            return anwenden();
        });
        return abgleich;
    }
    /** Vom Kern-Takt am Auto-Display: neuen Wert übernehmen (Spiegel + anwenden), ohne zu schreiben. */
    function uebernehmen(v) {
        if (!istProfil(v) || !istAuto()) return;
        if (lokal() !== v) spiegel(v);
        anwenden();
    }
    // ziel „auto“ = im Kern (gilt für das Auto-Display, auch vom PC/Handy aus); „geraet“ = nur dieser Browser.
    // Ohne Angabe: am Display Kern, sonst lokal (wie bisher).
    function setzen(v, ziel) {
        if (!istProfil(v)) return Promise.resolve(false);
        ziel = ziel || (istAuto() ? 'auto' : 'geraet');
        if (ziel === 'geraet') {
            spiegel(v);
            anwenden();
            return Promise.resolve(wert() === v);
        }
        return kernLesen(true).then(function (r) {
            if (!r || r.wert === null) return false;
            var werte = {};
            werte[FELD] = v;
            return fetch('/api/einstellungen', {
                method: 'PUT', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ etag: r.etag, werte: werte })
            }).then(function () { return kernLesen(true); }).then(function (neu) {
                if (!neu || neu.wert !== v) return false;
                if (istAuto()) { spiegel(v); anwenden(); }
                return true;
            });
        }).catch(function () { return false; });
    }

    function icon(name) {
        var paths = {
            panoptes: '<rect x="3" y="3" width="8" height="8" rx="2"/><rect x="13" y="3" width="8" height="5" rx="2"/><rect x="13" y="10" width="8" height="11" rx="2"/><rect x="3" y="13" width="8" height="8" rx="2"/>',
            fahren: '<circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="6" r="2.5"/><path d="M8.5 18H15a3.5 3.5 0 0 0 0-7H9a3.5 3.5 0 0 1 0-7h6.5"/>',
            fahrzeug: '<path d="M4 16v-3l2-5a2 2 0 0 1 1.9-1.4h8.2A2 2 0 0 1 18 8l2 5v3M3 16h18v3h-3v-1.5H6V19H3z"/><circle cx="7.5" cy="13.5" r="1"/><circle cx="16.5" cy="13.5" r="1"/>',
            kompakt: '<path d="M5 7h14M5 12h14M5 17h9"/>',
            cockpit: '<rect x="3" y="4" width="7" height="6" rx="1.5"/><rect x="12" y="4" width="9" height="6" rx="1.5"/><rect x="3" y="12" width="11" height="8" rx="1.5"/><rect x="16" y="12" width="5" height="8" rx="1.5"/>'
        };
        return '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths[name] + '</svg>';
    }
    function waehler(box, ziel) {
        if (!box) return Promise.resolve();
        ziel = ziel || (istAuto() ? 'auto' : 'geraet');
        if (ziel === 'auto' && !istAuto()) {
            return kernLesen(true).then(function (r) { zeichne(box, ziel, r && r.wert ? r.wert : 'panoptes'); });
        }
        zeichne(box, ziel, wert());
        return Promise.resolve();
    }
    function zeichne(box, ziel, aktuell) {
        var namen = {
            panoptes: ['Panoptes', 'Die vertraute Startseite mit allen Bereichen.'],
            fahren: ['Fahren', 'Navigation, Reichweite, Fahrten und Laden zuerst.'],
            fahrzeug: ['Fahrzeug', 'Zustand und Schnellzugriffe stehen im Mittelpunkt.'],
            kompakt: ['Kompakt', 'Nur die wichtigsten Werte und Zugriffe auf einen Blick.'],
            cockpit: ['Cockpit', 'Dichtes Kachel-Cockpit mit Fahrzeug, Reichweite und Schnellzugriffen.']
        };
        box.innerHTML = '';
        var gruppe = document.createElement('div');
        gruppe.className = 'mode-selector home-layout-selector';
        gruppe.setAttribute('role', 'radiogroup');
        gruppe.setAttribute('aria-label', text('settings.anzeige.startAnsicht.title', 'Startansicht'));
        for (var i = 0; i < PROFILE.length; i++) {
            (function (p) {
                var option = document.createElement('label');
                option.className = 'mode-option';
                var input = document.createElement('input');
                input.type = 'radio'; input.name = 'home-layout'; input.value = p;
                input.checked = aktuell === p;
                var karte = document.createElement('span');
                karte.className = 'mode-card' + (p === 'panoptes' ? ' mode-card-default' : '');
                karte.innerHTML = '<span class="mode-icon">' + icon(p) + '</span>'
                    + '<span class="mode-body"><span class="mode-name"></span><span class="mode-desc"></span></span>'
                    + '<span class="mode-radio" aria-hidden="true"></span>';
                karte.querySelector('.mode-name').textContent = text('settings.anzeige.startAnsicht.' + p, namen[p][0]);
                karte.querySelector('.mode-desc').textContent = text('settings.anzeige.startAnsicht.' + p + '_help', namen[p][1]);
                input.onchange = function () {
                    if (!input.checked) return;
                    gruppe.setAttribute('aria-busy', 'true');
                    setzen(p, ziel).then(function (ok) {
                        gruppe.removeAttribute('aria-busy');
                        waehler(box, ziel);
                        if (!ok) {
                            var fehler = document.createElement('div');
                            fehler.className = 'sys-note';
                            fehler.textContent = text('startAnsicht.fehler', 'Speichern hat nicht geklappt. Die Startansicht ist unverändert.');
                            box.appendChild(fehler);
                        }
                    });
                };
                option.appendChild(input); option.appendChild(karte); gruppe.appendChild(option);
            })(PROFILE[i]);
        }
        box.appendChild(gruppe);
        var hinweis = document.createElement('div');
        hinweis.className = 'sys-note';
        hinweis.textContent = ziel === 'auto'
            ? text('startAnsicht.auto_wirkt', 'Gilt für den Bildschirm im Auto. Das Auto übernimmt es in wenigen Sekunden.')
            : text('startAnsicht.nur_hier', 'Gilt nur für dieses Gerät bzw. diesen Browser, nicht im Auto.');
        box.appendChild(hinweis);
    }

    anwenden();
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', anwenden);
    window.PanoptesHomeLayout = {
        profile: PROFILE.slice(0), wert: wert, setzen: setzen, anwenden: anwenden,
        abgleichen: abgleichen, waehler: waehler, display: istAuto, uebernehmen: uebernehmen
    };
    if (istAuto()) abgleichen();
}());
