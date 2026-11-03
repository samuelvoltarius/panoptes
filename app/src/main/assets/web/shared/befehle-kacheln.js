/**
 * Panoptes — Befehle als große Kategorie-Kacheln mit Favoriten (0.1.37 UI-Aufbau).
 *
 * Legt sich als zweite Ansicht über das 3D-Auto der Steuerungsseite. Die Kacheln sind keine Kopie: sie lesen
 * Symbol, Namen und den Punkt „gerade aktiv“ aus den vorhandenen Reitern (.vc-tab) und öffnen beim Tippen genau
 * deren Bedienfeld unten (VC.togglePanel über den Reiter). Befehle, Rechte und Rückfragen bleiben also dieselben.
 * Favoriten (Stern) und die gewählte Ansicht merkt sich nur dieser Browser.
 *
 * ES5 für alte BYD-WebViews.
 */
(function (global) {
    'use strict';

    var FAV_KEY = 'pn.befehle.favoriten';
    var ANSICHT_KEY = 'pn.befehle.ansicht';
    var STERN = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.2l2.6 5.5 6 .8-4.4 4.1 1.1 5.9L12 16.6l-5.3 2.9 1.1-5.9L3.4 9.5l6-.8z" stroke-linejoin="round"/></svg>';

    function t(key, fallback) {
        try {
            var v = global.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : '';
            return v && v !== key ? v : fallback;
        } catch (e) { return fallback; }
    }

    function lies(key) { try { return global.localStorage.getItem(key); } catch (e) { return null; } }
    function schreib(key, wert) { try { global.localStorage.setItem(key, wert); } catch (e) {} }

    function favoriten() {
        try { var f = JSON.parse(lies(FAV_KEY) || '[]'); return f && f.length !== undefined ? f : []; } catch (e) { return []; }
    }

    function reiter() {
        var out = [], tabs = document.querySelectorAll('.vc-tabs .vc-tab');
        for (var i = 0; i < tabs.length; i++) {
            if (tabs[i].getAttribute('data-panel')) out.push(tabs[i]);
        }
        return out;
    }

    function kachel(tab, favs) {
        var id = tab.getAttribute('data-panel');
        var name = tab.querySelector('span[data-i18n]');
        var svg = tab.querySelector('svg');
        var fav = favs.indexOf(id) >= 0;
        var el = document.createElement('div');
        el.className = 'pn-bk-kachel' + (tab.classList.contains('has-active') ? ' aktiv' : '');
        el.setAttribute('data-panel', id);
        var knopf = document.createElement('button');
        knopf.type = 'button';
        knopf.className = 'pn-bk-oeffnen';
        knopf.innerHTML = '<span class="pn-bk-symbol">' + (svg ? svg.outerHTML : '') + '</span>'
            + '<span class="pn-bk-name"></span><i class="pn-bk-punkt" aria-hidden="true"></i>';
        knopf.querySelector('.pn-bk-name').textContent = name ? name.textContent : id;
        knopf.onclick = function () { oeffnen(id); };
        var stern = document.createElement('button');
        stern.type = 'button';
        stern.className = 'pn-bk-stern' + (fav ? ' an' : '');
        stern.setAttribute('aria-pressed', fav ? 'true' : 'false');
        stern.setAttribute('aria-label', fav ? t('vehicle_control.kacheln_stern_aus', 'Aus Favoriten entfernen')
            : t('vehicle_control.kacheln_stern_an', 'Zu Favoriten'));
        stern.innerHTML = STERN;
        stern.onclick = function (e) { e.stopPropagation(); umschalten(id); };
        el.appendChild(knopf);
        el.appendChild(stern);
        return el;
    }

    function oeffnen(id) {
        var tab = document.querySelector('.vc-tabs .vc-tab[data-panel="' + id + '"]');
        if (!tab) return;
        // offenes Feld nicht durch einen zweiten Tipp wieder zuklappen
        if (!(global.VC && VC._activePanel === id)) tab.click();
        // nur scrollen, wenn das Bedienfeld unten nicht zu sehen ist
        var dock = document.getElementById('vcDock') || tab;
        var r = dock.getBoundingClientRect ? dock.getBoundingClientRect() : null;
        if (r && r.bottom > (global.innerHeight || 0) && dock.scrollIntoView) dock.scrollIntoView(false);
    }

    function umschalten(id) {
        var f = favoriten(), i = f.indexOf(id);
        if (i >= 0) f.splice(i, 1); else f.push(id);
        schreib(FAV_KEY, JSON.stringify(f));
        zeichnen();
    }

    function zeichnen() {
        var box = document.getElementById('pnBefehleKacheln');
        if (!box) return;
        var favs = favoriten(), tabs = reiter(), i;
        var favListe = box.querySelector('.pn-bk-fav');
        var alle = box.querySelector('.pn-bk-alle');
        favListe.innerHTML = '';
        alle.innerHTML = '';
        for (i = 0; i < favs.length; i++) {
            for (var j = 0; j < tabs.length; j++) {
                if (tabs[j].getAttribute('data-panel') === favs[i]) favListe.appendChild(kachel(tabs[j], favs));
            }
        }
        if (!favListe.children.length) {
            var leer = document.createElement('p');
            leer.className = 'pn-bk-leer';
            leer.textContent = t('vehicle_control.kacheln_fav_leer', 'Tippe auf den Stern einer Kachel, dann steht sie hier oben.');
            favListe.appendChild(leer);
        }
        for (i = 0; i < tabs.length; i++) alle.appendChild(kachel(tabs[i], favs));
        box.querySelector('.pn-bk-titel-fav').textContent = t('vehicle_control.kacheln_favoriten', 'Favoriten');
        box.querySelector('.pn-bk-titel-alle').textContent = t('vehicle_control.kacheln_kategorien', 'Kategorien');
        umschalterText();
    }

    function ansicht() { return document.documentElement.getAttribute('data-befehle') || 'auto'; }

    function setzeAnsicht(a, merken) {
        document.documentElement.setAttribute('data-befehle', a);
        if (merken) schreib(ANSICHT_KEY, a);
        umschalterText();
    }

    function umschalterText() {
        var u = document.getElementById('pnBefehleUmschalter');
        if (!u) return;
        var kacheln = ansicht() === 'kacheln';
        u.setAttribute('aria-pressed', kacheln ? 'true' : 'false');
        u.querySelector('span').textContent = kacheln ? t('vehicle_control.ansicht_auto', 'Auto zeigen')
            : t('vehicle_control.ansicht_kacheln', 'Kacheln');
    }

    function einbauen() {
        var viewport = document.querySelector('.vc-viewport');
        var status = document.querySelector('.vc-status-bar');
        if (!viewport || !status || document.getElementById('pnBefehleKacheln') || !reiter().length) return;
        var box = document.createElement('section');
        box.id = 'pnBefehleKacheln';
        box.className = 'pn-bk';
        box.innerHTML = '<h2 class="pn-bk-titel pn-bk-titel-fav"></h2><div class="pn-bk-raster pn-bk-fav"></div>'
            + '<h2 class="pn-bk-titel pn-bk-titel-alle"></h2><div class="pn-bk-raster pn-bk-alle"></div>';
        viewport.appendChild(box);

        var u = document.createElement('button');
        u.type = 'button';
        u.id = 'pnBefehleUmschalter';
        u.className = 'pn-bk-umschalter';
        u.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">'
            + '<rect x="3.5" y="3.5" width="7" height="7" rx="1.6"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.6"/>'
            + '<rect x="3.5" y="13.5" width="7" height="7" rx="1.6"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.6"/></svg><span></span>';
        u.onclick = function () { setzeAnsicht(ansicht() === 'kacheln' ? 'auto' : 'kacheln', true); };
        viewport.appendChild(u);

        // Ab Werk: am schmalen Handy die Kacheln (dort füllt das Auto sonst den ganzen Bildschirm), sonst das Auto
        var gemerkt = lies(ANSICHT_KEY);
        var schmal = global.matchMedia && global.matchMedia('(max-width: 720px)').matches;
        setzeAnsicht(gemerkt === 'kacheln' || gemerkt === 'auto' ? gemerkt : (schmal ? 'kacheln' : 'auto'), false);
        zeichnen();

        // Punkt „aktiv“ (z. B. verriegelt, Klima an) folgt den Reitern
        if (global.MutationObserver) {
            var mo = new MutationObserver(function () {
                var tabs = reiter();
                for (var i = 0; i < tabs.length; i++) {
                    var id = tabs[i].getAttribute('data-panel'), an = tabs[i].classList.contains('has-active');
                    var ks = box.querySelectorAll('.pn-bk-kachel[data-panel="' + id + '"]');
                    for (var k = 0; k < ks.length; k++) ks[k].classList.toggle('aktiv', an);
                }
            });
            var tabs = reiter();
            for (var i = 0; i < tabs.length; i++) mo.observe(tabs[i], { attributes: true, attributeFilter: ['class'] });
        }
        if (global.BYD && BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(zeichnen);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', einbauen);
    else einbauen();

    global.PanoptesBefehleKacheln = { zeichnen: zeichnen, ansicht: setzeAnsicht };
})(window);
