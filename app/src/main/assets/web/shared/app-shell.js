/**
 * Panoptes — Web-Shell (Leiste mit 5 Bereichen, Kopfzeile, Bereichs-Reiter).
 *
 * Each in-app web page used to copy-paste the same ~250-line <aside class="sidebar">
 * block. This script mounts it once at runtime so every page shares one source
 * of truth. The DOM produced here is identical (element IDs included) to what
 * the legacy markup produced — existing JS bindings (core.js, vehicle-control.js,
 * etc.) keep working without changes.
 *
 * Usage in HTML:
 *   <body>
 *     <div class="app-layout">
 *       <div id="app-shell-mount"></div>
 *       <main class="main-content">…page-specific content…</main>
 *     </div>
 *     <script src="../shared/app-shell.js"></script>
 *
 * If a page omits #app-shell-mount the shell appends itself to <body>.
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor). No const/let, no arrow
 * functions, no template strings, no async/await.
 *
 * Active link is detected from window.location.pathname's basename.
 */
(function () {
    'use strict';

    // The Android app supplies its own navigation rail and injects
    // AndroidBridge before the page starts loading. Its post-load CSS hides
    // this web sidebar, so rendering the sidebar's 3D vehicle there wastes a
    // full GLB parse and can race the visible aux canvas. Standalone/tunnel
    // pages have no bridge and keep the normal sidebar renderer.
    var embeddedInNativeApp =
        typeof window.AndroidBridge !== 'undefined';

    // Anzeige-Modus fürs Auto-Display (Koordination 21.10., 0.1.7.1): die native Hülle zeigt diese
    // Oberfläche vollflächig. Erkennung: html[data-android-app], „PanoptesDisplay“ im User-Agent, ?anzeige=auto (Test);
    // für die Sitzung gemerkt. Große Touch-Ziele, Fahr-Modus, Zurück, Ladebalken, Offline-Hinweis: anzeige.js/.css.
    // Alte WebViews (Chromium 74 am Surf): Flexbox-gap nachbilden; das Skript prüft selbst, ob es nötig ist.
    (function () {
        var k = document.createElement('script');
        k.src = '../shared/kompat.js?v=1';
        document.head.appendChild(k);
    })();

    var anzeigeAuto = false;
    try {
        if (/[?&]anzeige=auto(&|$)/.test(location.search)) sessionStorage.setItem('pn.anzeige', 'auto');
        if (/[?&]anzeige=aus(&|$)/.test(location.search)) sessionStorage.removeItem('pn.anzeige');
        anzeigeAuto = sessionStorage.getItem('pn.anzeige') === 'auto';
    } catch (e) {}
    // AndroidBridge allein reicht nicht: die gibt es auch in den alten eingebetteten Seiten der nativen Leiste.
    if (!anzeigeAuto && (/PanoptesDisplay/.test(navigator.userAgent || '')
            || document.documentElement.hasAttribute('data-android-app'))) anzeigeAuto = true;
    function anzeigeCss() {
        if (document.getElementById('pnAnzeigeCss')) return;
        var ac = document.createElement('link');
        ac.id = 'pnAnzeigeCss';
        ac.rel = 'stylesheet';
        ac.href = '../shared/anzeige.css?v=4';
        document.head.appendChild(ac);
    }
    var anzeigeJs = false;
    function anzeigeAktivieren() {
        if (anzeigeJs) return;
        anzeigeJs = true;
        anzeigeAuto = true;
        try { sessionStorage.setItem('pn.anzeige', 'auto'); } catch (e) {}
        document.documentElement.setAttribute('data-anzeige', 'auto');
        anzeigeCss();
        var sa = document.createElement('script');
        sa.src = '../shared/anzeige.js?v=4';
        document.head.appendChild(sa);
        // Display spät erkannt: Darstellung (Standard leicht) und Kern-Wert nachziehen
        dstAnwenden();
        dstAbgleichen();
    }
    // Die App-Hülle setzt html[data-android-app] je nach Zeitpunkt erst nach dem Laden: dann nachträglich einschalten.
    function anzeigeStarten() {
        if (anzeigeAuto || document.documentElement.hasAttribute('data-android-app')) { anzeigeAktivieren(); return; }
        if (!window.MutationObserver) return;
        var mo = new MutationObserver(function () {
            if (document.documentElement.hasAttribute('data-android-app')) { mo.disconnect(); anzeigeAktivieren(); }
        });
        mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-android-app'] });
    }
    if (anzeigeAuto) {
        try { sessionStorage.setItem('pn.anzeige', 'auto'); } catch (e) {}
        document.documentElement.setAttribute('data-anzeige', 'auto');
        // Tag/Nacht wie das Auto, solange niemand selbst ein Design gewählt hat
        try {
            if (!localStorage.getItem('surfmate_theme')) {
                var hell = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
                document.documentElement.setAttribute('data-theme', hell ? 'light' : 'dark');
            }
        } catch (e) {}
        anzeigeCss();
    }

    // ---------------------------------------------------------------- Darstellung im Auto: leicht | voll
    // Gemessen 21.10. (Chrome 74-Niveau, CPU 6× gedrosselt, 1280×720): die 3D-Karte auf Start hält den
    // Hauptthread über 100 % (6 Bilder/s); in 2D ohne Dauer-Animationen 59 Bilder/s bei 14 %.
    // „Leicht“: Karten nur 2D (ohne Gelände/3D-Gebäude), kein backdrop-filter, Dauer-Animationen laufen
    // nur einmal, große 3D-Ansichten des Autos erst auf Tipp (die kleine Draufsicht bleibt).
    // Standard am Display „leicht“, an Handy/PC „voll“.
    // Am Display gilt die Kern-Einstellung anzeige.darstellung (sofern vorhanden), localStorage ist Spiegel;
    // Handy/PC speichern nur lokal. Wirkt sofort (Attribut html[data-darstellung] + Ereignis pn-darstellung).
    var DST_KEY = 'pn.darstellung', DST_FELD = 'anzeige.darstellung';
    var dstKern = null;   // null = unbekannt, false = Kern ohne Feld, true = Kern führt
    function dstDisplay() {
        return anzeigeAuto || /PanoptesDisplay/.test(navigator.userAgent || '')
            || document.documentElement.hasAttribute('data-android-app');
    }
    function dstLokal() {
        var v = null;
        try { v = localStorage.getItem(DST_KEY); } catch (e) { v = null; }
        return v === 'leicht' || v === 'voll' ? v : null;
    }
    function dstWert() { return dstLokal() || (dstDisplay() ? 'leicht' : 'voll'); }
    function dstCss() {
        if (document.getElementById('pnDstCss')) return;
        var s = document.createElement('style');
        s.id = 'pnDstCss';
        var L = 'html[data-darstellung="leicht"] ';
        s.textContent =
            L + '*,' + L + '*::before,' + L + '*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important;' +
            'animation-iteration-count:1!important}' +
            'html[data-darstellung="leicht"][data-pn-shell] .sidebar.pn-rail,' + L + '.app-bar,' + L + '.bottom-tabs' +
            '{background:var(--bg-base,#0A2238)!important}' +
            '.pn-3d-tipp{position:absolute;top:0;right:0;bottom:0;left:0;display:flex;align-items:center;justify-content:center}' +
            '.pn-3d-tipp button{min-height:48px;padding:0 16px;border-radius:12px;border:1px solid var(--sm-line,rgba(255,255,255,.2));' +
            'background:rgba(7,26,43,.85);color:#e6f1f4;font-size:14px;font-weight:600;cursor:pointer}';
        (document.head || document.documentElement).appendChild(s);
    }
    function dstAnwenden() {
        var v = dstWert(), de = document.documentElement;
        if (de.getAttribute('data-darstellung') === v) return;
        de.setAttribute('data-darstellung', v);
        try {
            var ev = document.createEvent('CustomEvent');
            ev.initCustomEvent('pn-darstellung', false, false, { wert: v });
            window.dispatchEvent(ev);
        } catch (e) {}
    }
    function dstSpiegel(v) { try { localStorage.setItem(DST_KEY, v); } catch (e) {} }
    function dstKernLesen() {
        if (!window.fetch || !dstDisplay()) return Promise.resolve(null);
        return fetch('/api/einstellungen', { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (!d || !d.werte) return null;
                var f = d.werte[DST_FELD];
                if (!f) { dstKern = false; return { etag: d.etag, wert: null }; }
                dstKern = true;
                return { etag: d.etag, wert: f.wert === 'voll' ? 'voll' : 'leicht' };
            })
            .catch(function () { return null; });
    }
    var dstAbgleich = null;
    function dstAbgleichen() {
        if (dstAbgleich) return dstAbgleich;
        dstAbgleich = dstKernLesen().then(function (r) {
            if (r && r.wert) { dstSpiegel(r.wert); dstAnwenden(); }
            return dstWert();
        });
        return dstAbgleich;
    }
    // Speichern: am Display im Kern (PUT mit etag, danach zurücklesen), sonst nur lokal.
    function dstSetzen(v) {
        if (v !== 'leicht' && v !== 'voll') return Promise.resolve(false);
        return dstKernLesen().then(function (r) {
            if (!r || r.wert === null) { dstSpiegel(v); dstAnwenden(); return dstWert() === v; }
            var w = {}; w[DST_FELD] = v;
            return fetch('/api/einstellungen', {
                method: 'PUT', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ etag: r.etag, werte: w })
            }).then(function () { return dstKernLesen(); }).then(function (r2) {
                if (!r2 || r2.wert !== v) return false;
                dstSpiegel(v); dstAnwenden(); return true;
            });
        }).catch(function () { return false; });
    }
    function dstT(k, f) {
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) { var x = BYD.i18n.t(k); if (x && x !== k) return x; }
        } catch (e) {}
        return f;
    }
    // Schalter „Darstellung im Auto: Leicht / Voll“ (Seite System → Anzeige).
    function dstWaehler(box) {
        if (!box) return;
        box.innerHTML = '';
        var akt = dstWert();
        var zeile = document.createElement('div');
        zeile.className = 'pn-dst-zeile';
        zeile.style.cssText = 'display:flex;flex-wrap:wrap;margin-top:8px';
        [['leicht', 'Leicht'], ['voll', 'Voll']].forEach(function (o) {
            var b = document.createElement('button');
            b.type = 'button';
            b.setAttribute('data-dst', o[0]);
            b.setAttribute('aria-pressed', akt === o[0] ? 'true' : 'false');
            b.textContent = dstT('settings.anzeige.darstellung.' + o[0], o[1]);
            b.style.cssText = 'min-height:48px;min-width:120px;margin:0 8px 8px 0;padding:0 18px;border-radius:12px;font-size:15px;' +
                'font-weight:600;cursor:pointer;border:1px solid var(--outline,#2a6474);' +
                (akt === o[0] ? 'background:var(--primary,#5FD6E6);color:var(--on-primary,#06283a)' : 'background:transparent;color:inherit');
            b.onclick = function () {
                b.disabled = true;
                dstSetzen(o[0]).then(function (ok) {
                    dstWaehler(box);
                    if (!ok) {
                        var f = document.createElement('div');
                        f.className = 'sys-note';
                        f.textContent = dstT('darstellung.fehler', 'Speichern hat nicht geklappt. Die Darstellung ist unverändert.');
                        box.appendChild(f);
                    }
                });
            };
            zeile.appendChild(b);
        });
        box.appendChild(zeile);
        var h = document.createElement('div');
        h.className = 'sys-note';
        h.textContent = akt === 'voll'
            ? dstT('darstellung.voll_hinweis', 'Voll zeigt 3D-Karte, Effekte und Animationen. Am Bildschirm im Auto kostet das spürbar Leistung und kann ruckeln.')
            : dstT('darstellung.leicht_hinweis', 'Leicht: Karten in 2D, ohne Unschärfe-Effekte und Dauer-Animationen. So läuft die Oberfläche im Auto flüssig.');
        box.appendChild(h);
        var g = document.createElement('div');
        g.className = 'sys-note';
        g.textContent = dstDisplay() && dstKern === true
            ? dstT('darstellung.geraet_auto', 'Gilt für den Bildschirm im Auto.')
            : dstT('darstellung.geraet', 'Gilt für dieses Gerät bzw. diesen Browser.');
        box.appendChild(g);
    }
    dstCss();
    dstAnwenden();
    window.PanoptesDarstellung = {
        wert: dstWert, leicht: function () { return dstWert() === 'leicht'; },
        setzen: dstSetzen, abgleichen: dstAbgleichen, waehler: dstWaehler, display: dstDisplay
    };
    if (dstDisplay()) dstAbgleichen();

    // ---------------------------------------------------------------- Position in einer Karten-App öffnen (0.1.9)
    // Nur auf Klick. Android-Handy: geo:-Link (öffnet die Navi-App des Nutzers, kein Webdienst dazwischen).
    // Sonst (PC, iPhone, Display): Auswahl OpenStreetMap / Google Maps mit Hinweis, dass der Dienst die Position sieht.
    // art: 'ziel' (hinnavigieren) | 'zeigen' (Ort anzeigen). Links mit class="pn-navi-link" data-lat data-lng [data-art].
    function nlT(k, f, v) {
        var s = dstT(k, f);
        if (v) Object.keys(v).forEach(function (n) { s = s.split('{' + n + '}').join(String(v[n])); });
        return s;
    }
    function nlUrls(lat, lng, art) {
        var p = lat + ',' + lng;
        return {
            osm: art === 'ziel'
                ? 'https://www.openstreetmap.org/directions?route=%3B' + encodeURIComponent(p)
                : 'https://www.openstreetmap.org/?mlat=' + lat + '&mlon=' + lng + '#map=17/' + lat + '/' + lng,
            google: art === 'ziel'
                ? 'https://www.google.com/maps/dir/?api=1&destination=' + p + '&travelmode=driving'
                : 'https://www.google.com/maps/search/?api=1&query=' + p
        };
    }
    function nlOeffnen(lat, lng, art) {
        lat = Number(lat); lng = Number(lng);
        if (!isFinite(lat) || !isFinite(lng)) return;
        lat = Math.round(lat * 1e6) / 1e6; lng = Math.round(lng * 1e6) / 1e6;
        var ua = navigator.userAgent || '';
        if (/Android/i.test(ua) && !dstDisplay()) {
            window.location.href = 'geo:' + lat + ',' + lng + '?q=' + lat + ',' + lng;
            return;
        }
        var urls = nlUrls(lat, lng, art);
        var alt = document.getElementById('pnNaviWahl');
        if (alt) alt.parentNode.removeChild(alt);
        var hg = document.createElement('div');
        hg.id = 'pnNaviWahl';
        hg.setAttribute('role', 'dialog');
        hg.style.cssText = 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:3000;display:flex;background:rgba(0,0,0,.45)';
        var box = document.createElement('div');
        box.style.cssText = 'margin:auto;max-width:420px;width:calc(100% - 32px);padding:18px;border-radius:16px;' +
            'background:var(--bg-surface,#0e2a3c);color:var(--on-surface,#e6f1f4);box-shadow:0 12px 32px rgba(0,0,0,.4);line-height:1.45';
        var h = document.createElement('div');
        h.style.cssText = 'font-weight:700;font-size:16px;margin-bottom:10px';
        h.textContent = art === 'ziel' ? nlT('navilink.titel_ziel', 'Hinnavigieren mit …') : nlT('navilink.titel_zeigen', 'Ort öffnen mit …');
        box.appendChild(h);
        [['osm', 'OpenStreetMap'], ['google', 'Google Maps']].forEach(function (o) {
            var b = document.createElement('button');
            b.type = 'button';
            b.setAttribute('data-dienst', o[0]);
            b.style.cssText = 'display:block;width:100%;text-align:left;min-height:48px;margin:0 0 8px;padding:10px 14px;border-radius:12px;' +
                'border:1px solid var(--outline,#2a6474);background:transparent;color:inherit;cursor:pointer';
            var n = document.createElement('b');
            n.style.cssText = 'display:block;font-size:15px';
            n.textContent = o[1];
            var s = document.createElement('span');
            s.style.cssText = 'display:block;font-size:12px;opacity:.8';
            s.textContent = nlT('navilink.hinweis', 'Öffnet {dienst}. Der Dienst sieht die Position.', { dienst: o[1] });
            b.appendChild(n); b.appendChild(s);
            b.onclick = function () { zu(); window.open(urls[o[0]], '_blank', 'noopener'); };
            box.appendChild(b);
        });
        var ab = document.createElement('button');
        ab.type = 'button';
        ab.textContent = nlT('navilink.abbrechen', 'Abbrechen');
        ab.style.cssText = 'min-height:48px;padding:0 18px;border-radius:12px;border:0;background:transparent;color:inherit;cursor:pointer';
        ab.onclick = zu;
        box.appendChild(ab);
        hg.appendChild(box);
        hg.addEventListener('click', function (e) { if (e.target === hg) zu(); });
        function zu() { if (hg.parentNode) hg.parentNode.removeChild(hg); }
        document.body.appendChild(hg);
    }
    window.PanoptesNaviLink = { oeffnen: nlOeffnen };

    // Knöpfe mit data-nur-display (z. B. Aufsperren) wirken nur am Bildschirm im Auto; der Kern sperrt sie
    // außerhalb ohnehin. Klick abfangen (vor den Seiten-Handlern) und sichtbar kennzeichnen.
    function nurDisplayMarkieren() {
        if (dstDisplay()) return;
        Array.prototype.forEach.call(document.querySelectorAll('[data-nur-display]'), function (el) {
            if (el.getAttribute('data-nur-display-markiert')) return;
            el.setAttribute('data-nur-display-markiert', '1');
            el.style.opacity = '.45';
            el.setAttribute('aria-disabled', 'true');
            el.title = dstT('nurdisplay.titel', 'Geht nur am Bildschirm im Auto');
        });
    }
    document.addEventListener('click', function (e) {
        var el = e.target && e.target.closest ? e.target.closest('[data-nur-display]') : null;
        if (el && !dstDisplay()) {
            e.preventDefault(); e.stopPropagation();
            if (e.stopImmediatePropagation) e.stopImmediatePropagation();
            var m = dstT('nurdisplay.hinweis', 'Das geht nur am Bildschirm im Auto.');
            if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, 'info'); else window.alert(m);
            return;
        }
        var nl = e.target && e.target.closest ? e.target.closest('a.pn-navi-link') : null;
        if (nl) {
            e.preventDefault();
            nlOeffnen(nl.getAttribute('data-lat'), nl.getAttribute('data-lng'), nl.getAttribute('data-art') || 'zeigen');
        }
    }, true);
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', nurDisplayMarkieren);
    else nurDisplayMarkieren();
    if (window.MutationObserver && !dstDisplay()) {
        new MutationObserver(function () { nurDisplayMarkieren(); })
            .observe(document.documentElement, { childList: true, subtree: true });
    }

    /*
     * Panoptes-Shell (Entwurf design/mockup/start.html): eine schmale Leiste mit
     * fünf Bereichen statt der langen Seitenliste. Jeder Bereich bündelt seine
     * Seiten; die Seiten des aktiven Bereichs stehen als Reiter unter der
     * Kopfzeile. Am Handy wird die Leiste zur Leiste unten.
     */
    var SECTIONS = [
        { id: 'start', label: 'Start', i18n: 'shell.sec_start', icon: 'home', pages: [
            { href: 'index.html',           label: 'Übersicht',          i18n: 'shell.page_overview' },
            { href: 'assistant.html',       label: 'Assistent',          i18n: 'nav.assistant' }
        ] },
        { id: 'drive', label: 'Fahren', i18n: 'shell.sec_drive', icon: 'route', pages: [
            { href: 'navi.html',            label: 'Navigation',         i18n: 'shell.page_navi' },
            { href: 'trips.html',           label: 'Fahrten',            i18n: 'nav.trips' },
            { href: 'bericht.html',         label: 'Monatsbericht',      i18n: 'shell.page_report' },
            { href: 'charging.html',        label: 'Laden',              i18n: 'shell.page_charging' },
            { href: 'wallbox.html',         label: 'Wallbox',            i18n: 'shell.page_wallbox' },
            { href: 'road-sense.html',      label: 'Straßenzustand',     i18n: 'shell.page_road' }
        ] },
        { id: 'guard', label: 'Wächter', i18n: 'shell.sec_guard', icon: 'eye', pages: [
            { href: 'waechter.html',        label: 'Übersicht',          i18n: 'shell.page_guard_home' },
            { href: 'live-view.html',       label: 'Live',               i18n: 'shell.page_live' },
            { href: 'events.html',          label: 'Aufnahmen',          i18n: 'nav.recordings' },
            { href: 'parking.html',         label: 'Parken',             i18n: 'nav.parking' },
            { href: 'communicate.html',     label: 'Sprechen',           i18n: 'shell.page_talk' },
            { href: 'surveillance.html',    label: 'Wächter-Einstellungen', i18n: 'shell.page_guard_settings' },
            { href: 'recording.html',       label: 'Aufnahme',           i18n: 'shell.page_recording' },
            { href: 'notifications.html',   label: 'Meldungen',          i18n: 'shell.page_notifications' }
        ] },
        { id: 'car', label: 'Auto', i18n: 'shell.sec_car', icon: 'car', pages: [
            { href: 'vehicle-control.html', label: 'Steuerung',          i18n: 'shell.page_control' },
            { href: 'camping.html',         label: 'Camping',            i18n: 'shell.page_camping' },
            { href: 'seat-positions.html',  label: 'Sitze',              i18n: 'shell.page_seats' },
            { href: 'automations.html',     label: 'Regeln',             i18n: 'shell.page_rules' },
            { href: 'key-mapping.html',     label: 'Tasten',             i18n: 'shell.page_keys' },
            { href: 'byd-cloud.html',       label: 'BYD Cloud',          i18n: 'nav.byd_cloud' }
        ] },
        { id: 'system', label: 'System', i18n: 'shell.sec_system', icon: 'gear', pages: [
            { href: 'system.html',          label: 'Dienste',            i18n: 'shell.page_services' },
            { href: 'orte.html',            label: 'Orte',               i18n: 'shell.page_orte' },
            { href: 'network.html',         label: 'Netz',               i18n: 'shell.page_network' },
            { href: 'wireguard.html',       label: 'WireGuard',          i18n: 'shell.page_wireguard', hidden: true },
            { href: 'telegram.html',        label: 'Telegram',           i18n: 'nav.telegram' },
            { href: 'mqtt.html',            label: 'MQTT',               i18n: 'nav.mqtt' },
            // Fremde Anbindung (Koordination 0.1.8): nicht mehr unter Fahren, der eigene Routenplaner ersetzt sie.
            { href: 'abrp.html',            label: 'ABRP (fremder Dienst)', i18n: 'shell.page_abrp_ext' },
            { href: 'performance.html',     label: 'Leistung',           i18n: 'nav.performance' },
            { href: 'remote-dev-view.html', label: 'Fernansicht',        i18n: 'shell.page_devview' },
            { href: 'about.html',           label: 'Über',               i18n: 'shell.page_about' }
        ] }
    ];

    var ICONS = {
        home:  '<path d="M3 11.5 12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" stroke-linejoin="round"/>',
        route: '<circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="6" r="2.5"/><path d="M8.5 18H15a3.5 3.5 0 0 0 0-7H9a3.5 3.5 0 0 1 0-7h6.5" stroke-linecap="round"/>',
        eye:   '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
        car:   '<path d="M4 16v-3l2-5a2 2 0 0 1 1.9-1.4h8.2A2 2 0 0 1 18 8l2 5v3M3 16h18v3h-3v-1.5H6V19H3z" stroke-linejoin="round"/><circle cx="7.5" cy="13.5" r="1" fill="currentColor"/><circle cx="16.5" cy="13.5" r="1" fill="currentColor"/>',
        gear:  '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.4M12 18.8v2.4M21.2 12h-2.4M5.2 12H2.8M18.5 5.5l-1.7 1.7M7.2 16.8l-1.7 1.7M18.5 18.5l-1.7-1.7M7.2 7.2 5.5 5.5" stroke-linecap="round"/>'
    };

    // Seiten ohne Kopfzeile (Vollbild-Video bzw. Fernansicht brauchen die Höhe).
    var NO_TOPBAR = { 'live-view.html': 1, 'remote-dev-view.html': 1 };

    // Anzeigenamen je vehicle.modelId (nur für die Kopfzeile, wenn kein eigener Name gesetzt ist).
    var MODEL_NAMES = {
        seagull: 'BYD Dolphin Surf', dolphin: 'BYD Dolphin', atto2: 'BYD Atto 2', atto3: 'BYD Atto 3',
        seal: 'BYD Seal', sealu: 'BYD Seal U', sealion7: 'BYD Sealion 7', han: 'BYD Han', tang: 'BYD Tang'
    };

    function tr(key, fallback, vars) {
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key, vars);
                if (v && v !== key) return v;
            }
        } catch (e) {}
        if (vars) {
            for (var k in vars) fallback = fallback.split('{' + k + '}').join(vars[k]);
        }
        return fallback;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return c === '&' ? '&amp;' : c === '<' ? '&lt;' : c === '>' ? '&gt;' : '&quot;';
        });
    }

    function activeBasename() {
        var path = window.location.pathname || '';
        var idx = path.lastIndexOf('/');
        var base = idx >= 0 ? path.substring(idx + 1) : path;
        if (!base) base = 'index.html';
        return base;
    }

    function findSection(base) {
        for (var i = 0; i < SECTIONS.length; i++) {
            for (var j = 0; j < SECTIONS[i].pages.length; j++) {
                if (SECTIONS[i].pages[j].href === base) return { section: SECTIONS[i], page: SECTIONS[i].pages[j] };
            }
        }
        return { section: null, page: null };
    }

    function svgIcon(name) {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">'
            + (ICONS[name] || '') + '</svg>';
    }

    function buildRail(active) {
        var html = '<a href="index.html" class="pn-brand" aria-label="Start">'
            + '<img src="../shared/app-icon-glyph-dark.webp" alt=""></a>'
            + '<nav class="pn-rail-nav" aria-label="Hauptbereiche">';
        for (var i = 0; i < SECTIONS.length; i++) {
            var s = SECTIONS[i];
            if (s.id === 'system') html += '<div class="pn-rail-spacer"></div>';
            var on = active && active.id === s.id;
            html += '<a href="' + s.pages[0].href + '" class="pn-rail-link' + (on ? ' on' : '') + '"'
                + (on ? ' aria-current="page"' : '') + ' data-section="' + s.id + '">'
                + svgIcon(s.icon)
                + '<span data-i18n="' + s.i18n + '">' + esc(s.label) + '</span></a>';
        }
        html += '</nav>' + buildLegacyStatus();
        return html;
    }

    // Unsichtbare Status-Felder: core.js schreibt seit jeher in diese IDs. Die
    // Kopfzeilen-Pillen lesen sie mit, so braucht es keine zweite Abfrage.
    function buildLegacyStatus() {
        return '<div class="pn-legacy" hidden aria-hidden="true">'
            + '<span id="connDot"></span><span id="deviceId"></span><span id="appVersion"></span>'
            + '<span id="accValue"></span><span id="batteryValue"></span>'
            + '<span id="survStatus" class="status-value off"></span>'
            + '<span id="networkIcon"></span><span id="networkValue"></span>'
            + '<div id="evCard"><span id="evPercentValue"></span><span id="evRange"></span><span id="evPower"></span>'
            // evSohValue gehört nur der Kachel auf „Leistung“ (performance.html); doppelt traf getElementById das versteckte Feld
            + '<span id="evSohRow"></span><span id="evPersonalizedRow"></span>'
            + '<span id="evPersonalizedRange"></span><span id="evBatteryBar"></span><span id="evBatteryFill"></span></div>'
            + '<div id="fuelCard"><span id="fuelPercentValue"></span><span id="fuelBarFill"></span></div>'
            + '</div>';
    }

    function greeting() {
        var h = new Date().getHours();
        if (h < 5) return tr('shell.greet_night', 'Gute Nacht');
        if (h < 11) return tr('shell.greet_morning', 'Guten Morgen');
        if (h < 18) return tr('shell.greet_day', 'Guten Tag');
        return tr('shell.greet_evening', 'Guten Abend');
    }

    function buildTopbar(found, base) {
        var isHome = base === 'index.html';
        var title = isHome
            ? '<span id="pnGreet">' + esc(greeting()) + '</span><span id="pnName"></span>'
            : '<span' + (found.page ? ' data-i18n="' + found.page.i18n + '"' : '') + '>'
                + esc(found.page ? found.page.label : (document.title || '').split(' - ')[0]) + '</span>';
        return '<header class="pn-top">'
            + '<div class="pn-top-text"><h1>' + title + '</h1>'
            + '<div class="pn-top-sub"><span id="pnVehicle">&nbsp;</span><span id="pnState"></span></div></div>'
            + '<div class="pn-top-grow"></div>'
            + '<div class="pn-pills">'
            +   '<a class="pn-pill" id="pnPillGuard" href="surveillance.html"><i class="pn-dot"></i><span>' + esc(tr('shell.pill_guard', 'Wächter')) + '</span></a>'
            +   '<a class="pn-pill" id="pnPillTail" href="system.html"><i class="pn-dot"></i><span>Tailnet</span></a>'
            +   '<a class="pn-pill" id="pnPillNet" href="network.html"><span>' + esc(tr('shell.pill_net', 'Netz')) + '</span></a>'
            +   '<a class="pn-pill" id="pnPillReq" href="system.html#kopplung" hidden><i class="pn-dot warn"></i><span>' + esc(tr('system.req_pill', 'Gerät will sich verbinden')) + '</span></a>'
            + '</div></header>';
    }

    function buildSubnav(found, base) {
        if (!found.section) return '';
        var pages = found.section.pages, html = '', n = 0;
        for (var i = 0; i < pages.length; i++) {
            var p = pages[i];
            if (p.hidden && p.href !== base) continue;
            n++;
            var on = p.href === base;
            html += '<a href="' + p.href + '" class="pn-tab' + (on ? ' on' : '') + '"'
                + (on ? ' aria-current="page"' : '') + ' data-i18n="' + p.i18n + '">' + esc(p.label) + '</a>';
        }
        return n > 1 ? '<nav class="pn-subnav" aria-label="' + esc(found.section.label) + '">' + html + '</nav>' : '';
    }

    function findMount() {
        var explicit = document.getElementById('app-shell-mount');
        if (explicit) return explicit;
        var layout = document.querySelector('.app-layout');
        if (!layout) return null;
        var holder = document.createElement('div');
        holder.id = 'app-shell-mount';
        layout.insertBefore(holder, layout.firstChild);
        return holder;
    }

    // Seiten rufen noch toggleSidebar() aus ihren alten Kopfzeilen auf. Die neue
    // Leiste ist immer sichtbar, deshalb reicht ein harmloser Platzhalter.
    function ensureToggleStub() {
        if (typeof window.toggleSidebar !== 'function') window.toggleSidebar = function () {};
    }

    // ---------------------------------------------------------------- Kopfzeile

    var profileState = { displayName: '', vehicleName: '', modelId: '', color: '' };

    function readCachedProfile() {
        try {
            var raw = sessionStorage.getItem('pn-profile');
            if (raw) profileState = JSON.parse(raw) || profileState;
        } catch (e) {}
    }

    function renderProfile() {
        var nameEl = document.getElementById('pnName');
        if (nameEl) nameEl.textContent = profileState.displayName ? ', ' + profileState.displayName : '';
        var vehEl = document.getElementById('pnVehicle');
        if (vehEl) {
            var model = MODEL_NAMES[profileState.modelId] || '';
            var parts = [];
            if (profileState.vehicleName) parts.push(profileState.vehicleName);
            if (model && model !== profileState.vehicleName) parts.push(model);
            vehEl.textContent = parts.length ? parts.join(' · ') : tr('shell.my_car', 'Mein Auto');
        }
    }

    function loadProfile() {
        readCachedProfile();
        renderProfile();
        function store(p) {
            profileState = p;
            try { sessionStorage.setItem('pn-profile', JSON.stringify(p)); } catch (e) {}
            renderProfile();
            try {
                var ev = document.createEvent('Event');
                ev.initEvent('panoptes:profile', true, true);
                ev.profile = p;
                document.dispatchEvent(ev);
            } catch (e) {}
        }
        fetch('/api/system/profile', { cache: 'no-store' }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        }).then(function (p) {
            if (!p || p.success === false) throw new Error('profile');
            store({ displayName: p.displayName || '', vehicleName: p.vehicleName || '',
                    modelId: p.modelId || '', color: p.color || '', batteryKwh: p.batteryKwh || 0 });
        }).catch(function () {
            // Ältere App ohne Profil-Schnittstelle: wenigstens das Modell zeigen.
            fetch('/api/models/selected').then(function (r) { return r.json(); }).then(function (m) {
                store({ displayName: '', vehicleName: '', modelId: (m && m.modelId) || '', color: (m && m.color) || '' });
            }).catch(function () {});
        });
    }
    window.PanoptesShell = {
        profile: function () { return profileState; },
        sections: SECTIONS,
        modelName: function (id) { return MODEL_NAMES[id] || ''; },
        tr: tr
    };

    function setPill(id, text, dot) {
        var el = document.getElementById(id);
        if (!el) return;
        var span = el.querySelector('span');
        if (span && span.textContent !== text) span.textContent = text;
        var d = el.querySelector('.pn-dot');
        if (d) d.className = 'pn-dot' + (dot ? ' ' + dot : '');
    }

    // Der echte Wächter-Zustand kommt aus /api/surveillance/status. Das alte Feld
    // gpuSurveillance in /status meldet nach einem App-Neustart „aus“, obwohl der
    // Wächter läuft – es dient nur noch als Rückfall.
    var guardFromApi = false;
    function pollGuard() {
        function run() {
            if (document.hidden) return;
            fetch('/api/surveillance/status', { cache: 'no-store' }).then(function (r) { return r.json(); })
                .then(function (d) {
                    var st = d && d.success && d.status;
                    if (!st) throw new Error('no status');
                    guardFromApi = true;
                    // Seit kern 71c1ca7: zustand = scharf|startet|unterdrueckt|aus, active = wirklich scharf.
                    if (st.safeZoneSuppressed || st.inSafeZone) setPill('pnPillGuard', tr('shell.pill_guard_safe', 'Wächter · Zuhause'), 'aq');
                    else if (st.zustand === 'startet') setPill('pnPillGuard', tr('shell.pill_guard_starting', 'Wächter startet'), 'warn');
                    else if (st.zustand === 'unterdrueckt') setPill('pnPillGuard', tr('shell.pill_guard_paused', 'Wächter pausiert'), 'aq');
                    else if (st.enabled && st.active) setPill('pnPillGuard', tr('shell.pill_guard_on', 'Wächter aktiv'), 'ok');
                    else if (st.enabled) setPill('pnPillGuard', tr('shell.pill_guard_ready', 'Wächter bereit'), 'aq');
                    else setPill('pnPillGuard', tr('shell.pill_guard_off', 'Wächter aus'), '');
                }).catch(function () {
                    // Gleich nach dem Laden (Anmeldung/Übersetzung noch unterwegs) kurz nachfassen.
                    if (!guardFromApi && retries++ < 3) setTimeout(run, 3000);
                });
        }
        var retries = 0;
        setTimeout(run, 800);
        setInterval(run, 30000);
    }

    function syncGuardPill() {
        if (guardFromApi) return;
        var s = document.getElementById('survStatus');
        if (!s) return;
        var cls = s.className || '';
        if (!s.textContent) return;
        if (cls.indexOf('safe') >= 0) setPill('pnPillGuard', tr('shell.pill_guard_safe', 'Wächter · Zuhause'), 'aq');
        else if (cls.indexOf(' on') >= 0) setPill('pnPillGuard', tr('shell.pill_guard_on', 'Wächter aktiv'), 'ok');
        else setPill('pnPillGuard', tr('shell.pill_guard_off', 'Wächter aus'), '');
    }

    function syncNetPill() {
        var n = document.getElementById('networkValue');
        if (!n || !n.textContent || n.textContent === '--') return;
        // core.js schreibt „Name<span>IP</span>“ – in der Pille nur der Name.
        var ssid = n.querySelector('.net-ssid');
        setPill('pnPillNet', ssid ? ssid.textContent : n.textContent, '');
    }

    function syncConnPill() {
        var dot = document.getElementById('connDot');
        var top = document.querySelector('.pn-top');
        if (!dot || !top) return;
        var ok = (dot.className || '').indexOf('connected') >= 0;
        if (ok) top.setAttribute('data-conn', 'ok');
        else if (top.getAttribute('data-conn') === 'ok') top.setAttribute('data-conn', 'lost');
    }

    function watchLegacy() {
        if (typeof MutationObserver === 'undefined') return;
        var map = { survStatus: syncGuardPill, networkValue: syncNetPill, connDot: syncConnPill };
        Object.keys(map).forEach(function (id) {
            var el = document.getElementById(id);
            if (!el) return;
            new MutationObserver(map[id]).observe(el, { childList: true, characterData: true, subtree: true, attributes: true });
        });
    }

    // Seiten ohne core.js-Abfrage (oder bevor sie startet): /status selbst lesen,
    // damit die Pillen nie leer bleiben.
    function fallbackStatusPoll() {
        function apply(st) {
            if (!st) return;
            var s = document.getElementById('survStatus');
            if (s) {
                var safe = st.safeZoneSuppressed || st.inSafeZone;
                s.className = 'status-value ' + (safe ? 'safe' : st.gpuSurveillance ? 'on' : 'off');
                s.textContent = safe ? 'safe' : st.gpuSurveillance ? 'on' : 'off';
            }
            var n = document.getElementById('networkValue');
            var net = st.network || {};
            if (n && (!n.textContent || n.textContent === '--')) {
                n.textContent = net.type === 'wifi' ? (net.ssid || 'WLAN')
                    : net.type === 'cellular' ? tr('shell.net_mobile', 'Mobilfunk')
                    : net.type === 'ethernet' ? 'Ethernet' : tr('shell.net_none', 'Kein Netz');
            }
            var c = document.getElementById('connDot');
            if (c && c.className.indexOf('connected') < 0) c.className = 'connected';
        }
        setTimeout(function tick() {
            var core = window.BYD && BYD.core;
            var coreRunning = core && (core.deviceId || core.tickInFlight || core.pollInterval);
            if (coreRunning) return;
            fetch('/status', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
                .then(apply).catch(function () {});
            setTimeout(tick, 30000);
        }, 3500);
    }

    // Offene Kopplungs-Anfrage (KOPPLUNG_ANFRAGE.md): Pille zum Erlauben auf allen Seiten.
    // Nur mit Login; 403/404 (Kopplungs-Zugang, älterer Daemon) beendet das Abfragen.
    function pollKopplung(seite) {
        if (seite === 'system.html') return;   // dort steht die Liste selbst
        var timer = null;
        function run() {
            if (document.hidden) return;
            fetch('/api/kopplung/anfragen', { cache: 'no-store' }).then(function (r) {
                if (r.status === 403 || r.status === 404) { clearInterval(timer); return null; }
                return r.ok ? r.json() : null;
            }).then(function (d) {
                var el = document.getElementById('pnPillReq');
                if (el && d) el.hidden = !(d.anfragen && d.anfragen.length);
            }).catch(function () {});
        }
        run();
        timer = setInterval(run, 10000);
    }

    function pollTailnet() {
        function run() {
            if (document.hidden) return;
            fetch('/api/system/tailscale', { cache: 'no-store' }).then(function (r) { return r.json(); })
                .then(function (t) {
                    if (t && t.success && t.online) setPill('pnPillTail', tr('shell.pill_tail_on', 'Tailnet · verbunden'), 'aq');
                    else if (t && t.success) setPill('pnPillTail', tr('shell.pill_tail_wait', 'Tailnet · getrennt'), 'warn');
                    else setPill('pnPillTail', tr('shell.pill_tail_off', 'Tailnet aus'), '');
                }).catch(function () { setPill('pnPillTail', tr('shell.pill_tail_off', 'Tailnet aus'), ''); });
        }
        run();
        setInterval(run, 60000);
    }

    // Fähigkeiten des Autos (GET /api/system/capabilities). Elemente mit
    // data-needs="cluster" bzw. "hud" sind ausgeblendet, solange das Auto es nicht kann.
    // Wert aus sessionStorage sofort setzen (kein Aufblitzen), dann frisch holen.
    function applyCaps(c) {
        var root = document.documentElement;
        root.setAttribute('data-cap-cluster', c && c.cluster ? '1' : '0');
        root.setAttribute('data-cap-hud', c && c.hud ? '1' : '0');
        window.PanoptesCaps = c || {};
        try {
            var ev = document.createEvent('Event');
            ev.initEvent('panoptes:caps', true, true);
            document.dispatchEvent(ev);
        } catch (e) {}
    }
    (function () {
        try { var c = JSON.parse(sessionStorage.getItem('pn-caps') || 'null'); if (c) applyCaps(c); } catch (e) {}
    })();
    function loadCaps() {
        fetch('/api/system/capabilities', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
            .then(function (c) {
                if (!c || c.success === false) return;
                try { sessionStorage.setItem('pn-caps', JSON.stringify(c)); } catch (e) {}
                applyCaps(c);
            }).catch(function () {});
    }

    function mount() {
        var holder = findMount();
        if (!holder) return;
        var base = activeBasename();
        var found = findSection(base);

        var aside = document.createElement('aside');
        aside.className = 'sidebar pn-rail';
        aside.id = 'sidebar';
        aside.innerHTML = buildRail(found.section);
        if (holder.parentNode) holder.parentNode.replaceChild(aside, holder);

        document.documentElement.setAttribute('data-pn-shell', '1');
        if (document.body) document.body.setAttribute('data-app-shell', '1');
        ensureToggleStub();

        var main = document.querySelector('.main-content');
        if (main) {
            var wrap = document.createElement('div');
            wrap.className = 'pn-head';
            wrap.innerHTML = (NO_TOPBAR[base] ? '' : buildTopbar(found, base)) + buildSubnav(found, base);
            if (wrap.innerHTML) main.insertBefore(wrap, main.firstChild);
        }

        try { if (window.BYD && window.BYD.theme && window.BYD.theme.applyIcons) window.BYD.theme.applyIcons(); } catch (e) {}

        loadCaps();
        if (!NO_TOPBAR[base]) {
            loadProfile();
            watchLegacy();
            fallbackStatusPoll();
            pollTailnet();
            pollGuard();
            pollKopplung(base);
            // Haftungshinweise: einmal je Textversion bestätigen (DISCLAIMER.md). Nicht auf der Über-Seite,
            // dort steht der volle Text. Kurz warten, bis die Seite ihre Texte (BYD.i18n) geladen hat.
            if (base !== 'about.html') setTimeout(function () {
                var sc = document.createElement('script');
                sc.src = '../shared/hinweise.js?v=pn9';
                sc.onload = function () { if (window.PanoptesHinweise) window.PanoptesHinweise.pruefen(); };
                document.head.appendChild(sc);
            }, 1500);
            // Anzeige-Modus (Auto-Display): Bausteine nachladen, auch wenn die Hülle das Attribut erst später setzt.
            anzeigeStarten();
            // „Was ist neu“ nach einem Update, einmal (H10). Wartet selbst, bis die Haftungshinweise zu sind.
            if (base !== 'about.html') setTimeout(function () {
                var sn = document.createElement('script');
                sn.src = '../shared/neuigkeiten.js?v=2';
                sn.onload = function () { if (window.PanoptesNeu) window.PanoptesNeu.pruefen(); };
                document.head.appendChild(sn);
            }, 2500);
        }

        try {
            var ev = document.createEvent('Event');
            ev.initEvent('app-shell:ready', true, true);
            document.dispatchEvent(ev);
        } catch (e) { /* ignore — older browsers */ }

        if (window.BYD && window.BYD.i18n && typeof window.BYD.i18n.hydrate === 'function') {
            try { window.BYD.i18n.hydrate(aside.parentNode || document); } catch (e) {}
        }
        // Gruß + Pillen nach dem Laden der Übersetzung neu setzen.
        if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            BYD.i18n.onChange(function () {
                var g = document.getElementById('pnGreet');
                if (g) g.textContent = greeting();
                renderProfile();
                syncGuardPill();
            });
        }
    }

    // ============================================================
    // Vehicle selection → EV card visualization
    // ============================================================

    // Lazy-loaded EV-card 3D scene. Created on first setEvCardAppearance
    // call once the GLB renderer module has loaded. setModel/setColor on
    // the instance is safe even before vendor JS lands — pending values
    // are queued and applied when the renderer is ready.
    //
    // Sprite-cache fast path:
    //   On every page navigation the sidebar mounts the same #evCardCanvas.
    //   If a webp sprite for the user's (model, colour, view, dpr) is
    //   already in IndexedDB, paint it into the canvas immediately and
    //   skip the three.js + GLB load entirely. The 3D pipeline still
    //   runs once on first cold load (or after the user picks a colour
    //   we haven't seen before) — and at the end of that render we snap
    //   the canvas back into the cache for the next page visit.
    //
    //   Cache ownership: the cache key includes a SPRITE_VERSION constant
    //   in ev-card-sprite-cache.js, so a pipeline change (lighting rig,
    //   camera framing) invalidates everything in one go. Per-key
    //   invalidation also runs when the user changes their selection.
    var ev3dInstance = null;
    var ev3dLoadStarted = false;
    var spriteCacheLoadStarted = false;
    // True once the sidebar canvas has been painted from the sprite
    // cache. A canvas can hold a 2D context OR a WebGL context, never
    // both — so if a later setEvCardAppearance call has to fall back to
    // the live 3D pipeline (cache miss after a previous hit), we have
    // to swap in a fresh <canvas> element first.
    var sidebarPaintedFromSprite = false;
    // Per-canvas, per-key flag so we only snapshot the FIRST render
    // after a (model, colour) change — subsequent identical-key renders
    // (resize, visibility flip) shouldn't keep rewriting the same blob.
    var snapshotsTaken = {};

    // Replace #evCardCanvas with a fresh clone. Used when transitioning
    // from a 2D-painted sprite to the live WebGL pipeline within the
    // same page session — the existing 2D context permanently locks the
    // canvas out of WebGL, so we have to swap the element. References
    // held elsewhere (battery overlay, charge SVG) sit on adjacent DOM
    // and are unaffected.
    function swapSidebarCanvasForFresh() {
        var old = document.getElementById('evCardCanvas');
        if (!old || !old.parentNode) return null;
        var fresh = old.cloneNode(false);
        old.parentNode.replaceChild(fresh, old);
        sidebarPaintedFromSprite = false;
        return fresh;
    }

    function ensureSpriteCacheModule(cb) {
        if (typeof window.SurfMateEvSpriteCache === 'object') { cb(window.SurfMateEvSpriteCache); return; }
        if (spriteCacheLoadStarted) {
            // Poll briefly for the global to land.
            var tries = 0;
            var iv = setInterval(function () {
                tries++;
                if (typeof window.SurfMateEvSpriteCache === 'object') {
                    clearInterval(iv);
                    cb(window.SurfMateEvSpriteCache);
                } else if (tries > 60) {
                    clearInterval(iv);
                    cb(null);
                }
            }, 50);
            return;
        }
        spriteCacheLoadStarted = true;
        var s = document.createElement('script');
        s.src = '../shared/ev-card-sprite-cache.js';
        s.async = true;
        s.onload = function () { cb(window.SurfMateEvSpriteCache || null); };
        s.onerror = function () { cb(null); };
        document.head.appendChild(s);
    }

    // Try to paint a cached sprite into `canvasEl` for the given key
    // tuple. On success the 3D pipeline can be skipped for this canvas
    // until the user changes selection. Calls `cb(true|false)`.
    function tryPaintFromSpriteCache(canvasEl, modelId, color, view, cb) {
        if (!canvasEl || !modelId || !color) { cb(false); return; }
        ensureSpriteCacheModule(function (cache) {
            if (!cache) { cb(false); return; }
            cache.get(modelId, color, view, canvasEl).then(function (entry) {
                if (!entry || !entry.blob) { cb(false); return; }
                cache.paintInto(canvasEl, entry).then(cb);
            }, function () { cb(false); });
        });
    }

    // Schedule a one-shot post-render snapshot into the sprite cache.
    // The 3D instance fires `onceAfterRender` after the next paint that
    // includes both the GLB and the requested colour, so the captured
    // canvas contents reflect the correct (model, colour) tuple.
    function scheduleSpriteSnapshot(inst, canvasEl, modelId, color, view) {
        if (!inst || !canvasEl || !modelId || !color) return;
        ensureSpriteCacheModule(function (cache) {
            if (!cache) return;
            var key = cache.buildKey(modelId, color, view, canvasEl);
            if (snapshotsTaken[key]) return;
            snapshotsTaken[key] = true;
            inst.onceAfterRender(function () {
                cache.snapshot(canvasEl).then(function (blob) {
                    if (!blob) { delete snapshotsTaken[key]; return; }
                    cache.put(modelId, color, view, canvasEl, blob,
                              canvasEl.width, canvasEl.height);
                });
            });
        });
    }

    // Clear a previously-set snapshotsTaken flag so a later attempt
    // (different page in the same session, or a re-mount) can take a
    // fresh snapshot. Called when a pendingSidebarSnapshot is dropped
    // before its render callback ran.
    function clearSnapshotFlag(modelId, color, view, canvasEl) {
        if (!modelId || !color) return;
        ensureSpriteCacheModule(function (cache) {
            if (!cache) return;
            delete snapshotsTaken[cache.buildKey(modelId, color, view, canvasEl)];
        });
    }

    function ensureEv3d() {
        if (ev3dInstance) return ev3dInstance;
        var canvas = document.getElementById('evCardCanvas');
        if (!canvas) return null;
        if (ev3dLoadStarted) return null; // load is in flight
        ev3dLoadStarted = true;

        // Defer the script load until the page has had a chance to render
        // its primary content. requestIdleCallback is ideal; fall back to
        // a setTimeout for browsers without it (notably Safari ≤14).
        function instantiate() {
            if (ev3dInstance) return;
            if (typeof window.SurfMateEvCard3D !== 'function') return;
            // The native shell hides #sidebar after page load. The script may
            // still be needed by a visible aux canvas, but never bind WebGL
            // to the hidden sidebar canvas in that environment.
            if (embeddedInNativeApp) return;
            try {
                ev3dInstance = new window.SurfMateEvCard3D(canvas);
                // Replay the most recent (model, colour) pair. SOC +
                // charging state are painted on the DOM battery overlay
                // independently so they need no replay here.
                if (lastEv3dColor) ev3dInstance.setColor(lastEv3dColor);
                if (lastEv3dModel) ev3dInstance.setModel(lastEv3dModel);
                // If a setEvCardAppearance call hit the sprite-cache miss
                // path before the renderer module finished loading, we
                // owe a snapshot for that key. Replay it now.
                if (pendingSidebarSnapshot) {
                    var p = pendingSidebarSnapshot;
                    pendingSidebarSnapshot = null;
                    if (p.modelId && p.color) {
                        scheduleSpriteSnapshot(ev3dInstance, canvas,
                                               p.modelId, p.color, 'side');
                    }
                }
            } catch (e) {
                if (window.console) console.warn('[app-shell] ev-card-3d init failed:', e);
            }
        }

        function inject() {
            // Skip on pages that explicitly opt out (e.g. login.html could
            // set window.SurfMateDisableEvCard3D = true).
            if (window.SurfMateDisableEvCard3D) return;

            // If the global is already present (vehicle-control bundles
            // its own three.js + we got loaded second; or ev-map-sprite.js
            // got there first on a map page), instantiate immediately.
            if (typeof window.SurfMateEvCard3D === 'function') {
                instantiate();
                return;
            }

            // If a previous caller has already inserted the <script>,
            // don't insert a duplicate — just poll for the global to
            // appear once that load completes.
            if (document.querySelector('script[data-surfmate-ev-card-3d]')) {
                var pollTries = 0;
                var pollIv = setInterval(function () {
                    pollTries++;
                    if (typeof window.SurfMateEvCard3D === 'function') {
                        clearInterval(pollIv);
                        instantiate();
                    } else if (pollTries > 60) {
                        clearInterval(pollIv);
                    }
                }, 100);
                return;
            }

            var s = document.createElement('script');
            s.src = '../shared/ev-card-3d.js';
            s.async = true;
            s.setAttribute('data-surfmate-ev-card-3d', '1');
            s.onload = instantiate;
            s.onerror = function () {
                if (window.console) console.warn('[app-shell] ev-card-3d.js load failed');
            };
            document.head.appendChild(s);
        }
        if (typeof window.requestIdleCallback === 'function') {
            window.requestIdleCallback(inject, { timeout: 1500 });
        } else {
            setTimeout(inject, 200);
        }
        return null;
    }

    // Most-recent values applied to the 3D card. Used when the renderer
    // module finishes loading after one or more setEvCardAppearance calls
    // — replay these so the deferred mount is already correctly painted.
    var lastEv3dModel = null;
    var lastEv3dColor = null;
    // Holds the (modelId, colour) for which we owe the sidebar a
    // snapshot once the renderer module finishes loading. ensureEv3d()'s
    // instantiate() callback drains this. Hoisted up here (not down by
    // applyToAux) so the relationship to lastEv3dModel/lastEv3dColor is
    // visible at a glance.
    var pendingSidebarSnapshot = null;
    // Extra SurfMateEvCard3D instances mounted by other surfaces on the
    // same page (currently: Live View's top-down camera selector). These
    // share the same model/colour as the sidebar card and stay in sync
    // when the user changes their selection on vehicle-control.html.
    var auxEv3dInstances = [];
    // Aux canvases can mount as soon as app-shell:ready fires, while the
    // selected vehicle is still being fetched asynchronously. Treating
    // that short "unknown selection" window as a sprite-cache miss forces
    // every page visit through three.js + the full GLB, even when a cached
    // sprite exists. Hold those mounts until setEvCardAppearance resolves
    // either the saved selection or the normal fallback.
    var vehicleSelectionWaiters = [];
    var FALLBACK_MODEL_ID = 'seagull';
    var FALLBACK_MODEL_COLOR = '#E8E8EC';

    function setEvCardAppearance(modelId, hexColor) {
        // Forward the user's selection to the 3D card. ev-card-3d.js
        // queues setModel/setColor calls if the renderer hasn't loaded
        // yet, so the deferred mount lands on the right (model, colour).
        var prevModel = lastEv3dModel;
        var prevColor = lastEv3dColor;
        lastEv3dModel = modelId;
        lastEv3dColor = hexColor;

        // Sidebar canvas — try the sprite-cache fast path first. Only
        // injects the 3D pipeline on a miss, so subsequent page loads
        // paint the EV card from a ~30KB webp instead of a 600KB
        // three.js + 1.6MB GLB cold parse.
        var sidebarCanvas = embeddedInNativeApp
            ? null
            : document.getElementById('evCardCanvas');
        if (sidebarCanvas && modelId && hexColor) {
            tryPaintFromSpriteCache(sidebarCanvas, modelId, hexColor, 'side', function (hit) {
                if (hit) {
                    sidebarPaintedFromSprite = true;
                    // Cache hit — the canvas is already painted. Don't
                    // touch the 3D instance unless one already exists
                    // (e.g. the user is on vehicle-control.html where
                    // the live three.js scene is wanted anyway).
                    if (ev3dInstance) {
                        if (modelId)  ev3dInstance.setModel(modelId);
                        if (hexColor) ev3dInstance.setColor(hexColor);
                    }
                    return;
                }
                // Miss — fall through to the 3D pipeline AND register a
                // snapshot for the next page that lands on this key.
                // If we've already painted the canvas in 2D from a
                // prior cache hit, the canvas is locked out of WebGL;
                // swap it for a fresh clone first.
                var canvasForLive = sidebarCanvas;
                if (sidebarPaintedFromSprite && !ev3dInstance) {
                    canvasForLive = swapSidebarCanvasForFresh() || sidebarCanvas;
                }
                var inst = ensureEv3d();
                if (inst) {
                    if (modelId)  inst.setModel(modelId);
                    if (hexColor) inst.setColor(hexColor);
                    scheduleSpriteSnapshot(inst, canvasForLive, modelId, hexColor, 'side');
                } else {
                    // Renderer module is in flight. Schedule the
                    // snapshot once it lands — ensureEv3d's instantiate
                    // callback drains pendingSidebarSnapshot.
                    //
                    // Two rapid misses before the renderer instantiates
                    // would overwrite an earlier pending snapshot here.
                    // That's fine semantically (last-write-wins matches
                    // the user's most recent selection), but if the
                    // earlier (model,colour) had already set its
                    // snapshotsTaken flag we need to clear it so a
                    // future page navigation can still snapshot it.
                    if (pendingSidebarSnapshot
                            && (pendingSidebarSnapshot.modelId !== modelId
                                || pendingSidebarSnapshot.color !== hexColor)) {
                        clearSnapshotFlag(pendingSidebarSnapshot.modelId,
                                          pendingSidebarSnapshot.color, 'side',
                                          sidebarCanvas);
                    }
                    pendingSidebarSnapshot = { modelId: modelId, color: hexColor };
                }
            });
        } else if (!embeddedInNativeApp) {
            // Fallback for callers that don't have the canvas or only
            // have one of (model, colour) — preserve the original
            // behaviour so login.html and friends still work.
            var inst = ensureEv3d();
            if (inst) {
                if (modelId)  inst.setModel(modelId);
                if (hexColor) inst.setColor(hexColor);
            }
        }

        // Aux canvases (Live View top-down selector, map markers).
        // Iterate backwards so we can splice disposed instances out as
        // we go without confusing the index. Trip-map remounts churn
        // these — without the splice the array would grow each time
        // the user opens a different trip and stale entries would
        // throw on setColor (carModel/scene set to null in dispose()).
        for (var i = auxEv3dInstances.length - 1; i >= 0; i--) {
            var aux = auxEv3dInstances[i];
            if (aux._disposed) {
                auxEv3dInstances.splice(i, 1);
                continue;
            }
            applyToAux(aux, modelId, hexColor);
        }

        // Drain deferred aux mounts only after the existing aux iteration.
        // A callback can add a sprite-only/live entry to auxEv3dInstances;
        // deferring the drain avoids applying the same selection twice in
        // this pass and issuing duplicate cache lookups.
        if (modelId && hexColor && vehicleSelectionWaiters.length) {
            var waiters = vehicleSelectionWaiters.slice();
            vehicleSelectionWaiters = [];
            for (var w = 0; w < waiters.length; w++) {
                try { waiters[w](); } catch (e) {
                    if (window.console) console.warn('[app-shell] deferred vehicle canvas mount failed:', e);
                }
            }
        }

        // If the user actually changed their selection (vehicle-control
        // calls refreshVehicle() after a save), surface a
        // vehicle-changed event so other surfaces (map markers, custom
        // pages) can react without re-polling the daemon. Includes the
        // previous values so listeners that maintain their own caches
        // can invalidate the right entries.
        //
        // Suppress the synthetic "null → first value" diff that fires
        // on the initial applyVehicleSelection — listeners only care
        // about user-driven changes, not the first hydration.
        var hadPrior = (prevModel !== null) || (prevColor !== null);
        if (hadPrior && (prevModel !== modelId || prevColor !== hexColor)) {
            try {
                var ev;
                if (typeof CustomEvent === 'function') {
                    ev = new CustomEvent('surfmate:vehicle-changed', {
                        detail: {
                            modelId: modelId,  color: hexColor,
                            prevModelId: prevModel, prevColor: prevColor
                        }
                    });
                } else {
                    ev = document.createEvent('CustomEvent');
                    ev.initCustomEvent('surfmate:vehicle-changed', true, true, {
                        modelId: modelId,  color: hexColor,
                        prevModelId: prevModel, prevColor: prevColor
                    });
                }
                document.dispatchEvent(ev);
            } catch (e) {}
        }
    }

    // Apply (model, colour) to an aux instance. Sprite-only auxes
    // (created when mountVehicleCanvas hit the cache) re-attempt the
    // cache for the new key inside their own setModel/setColor; live
    // 3D auxes update their scene and queue a snapshot for the new key
    // so the next page that mounts the same aux gets a fast paint.
    //
    // CRITICAL: must NOT call tryPaintFromSpriteCache on a canvas that's
    // already bound to a WebGL context (live aux) — getContext('2d')
    // would fail and the call would be a no-op anyway. The sprite-only
    // path is opt-in via aux._spriteOnly; live auxes route through the
    // 3D state and snapshot AFTER their next render.
    function applyToAux(aux, modelId, hexColor) {
        if (!aux || !modelId || !hexColor) {
            if (aux) {
                if (modelId)  aux.setModel(modelId);
                if (hexColor) aux.setColor(hexColor);
            }
            return;
        }
        // Preserve the aux's actual view ('top' | 'three-quarter' | 'side')
        // so the sprite-cache key here matches the one mountVehicleCanvas
        // filed the snapshot under. Collapsing to a 'top'|'side' binary
        // mis-keyed the dashboard hero's 'three-quarter' canvas as 'side',
        // so every colour change missed the cache and re-rendered WebGL +
        // orphaned a sprite under the wrong key.
        var view = (aux.view === 'top' || aux.view === 'three-quarter') ? aux.view : 'side';
        if (aux._spriteOnly) {
            // Sprite-only aux: setModel/setColor on the pseudo-instance
            // re-try the cache. On miss they upgrade to a live mount,
            // which in turn schedules its own snapshot.
            aux.setModel(modelId);
            aux.setColor(hexColor);
            return;
        }
        // Live 3D aux. Update the scene; snapshot the next render into
        // the cache so a future page can take the fast path.
        aux.setModel(modelId);
        aux.setColor(hexColor);
        if (aux.canvas) {
            scheduleSpriteSnapshot(aux, aux.canvas, modelId, hexColor, view);
        }
    }


    function applyVehicleSelection() {
        // No-op if the EV card isn't mounted (login.html, dev-only pages).
        if (!document.getElementById('evCardCanvas')) return;
        function applyFallbackAppearance() {
            setEvCardAppearance(FALLBACK_MODEL_ID, FALLBACK_MODEL_COLOR);
        }
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/api/models/selected', true);
            xhr.timeout = 4000;
            xhr.onload = function () {
                if (xhr.status < 200 || xhr.status >= 300) {
                    applyFallbackAppearance();
                    return;
                }
                var data;
                try { data = JSON.parse(xhr.responseText) || {}; }
                catch (e) {
                    applyFallbackAppearance();
                    return;
                }
                setEvCardAppearance(data.modelId || FALLBACK_MODEL_ID,
                    data.color || FALLBACK_MODEL_COLOR);
            };
            xhr.onerror = function () { applyFallbackAppearance(); };
            xhr.ontimeout = function () { applyFallbackAppearance(); };
            xhr.send();
        } catch (e) { applyFallbackAppearance(); }
    }

    // ============================================================
    // Sidebar group collapse
    // ============================================================
    //
    // Each .nav-group-header (Integrations / Diagnostics / Settings) becomes
    // a tappable summary that toggles every .nav-link between it and the
    // next group-header / nav-divider.
    //
    // Implementation: walk the .sidebar-nav once, for each group-header
    //  - inject a chevron at the trailing edge,
    //  - record the .nav-link siblings up to the next break,
    //  - on click, toggle a `data-collapsed` attribute on the group-header
    //    and apply [hidden] to its children. Persist to localStorage.
    //
    // The persistence key uses the group's i18n slug (e.g. "integrations_group")
    // so it survives label translation.
    function wireGroupCollapse(aside) {
        var nav = aside.querySelector('.sidebar-nav');
        if (!nav) return;
        // Cache the nav so external callers (update-flow.js after it injects
        // its sidebar entry) can re-run wiring on the same DOM.
        wireGroupCollapse._lastAside = aside;
        var children = nav.children;
        // Collect group definitions: { headerEl, slug, members: [linkEl,…] }.
        var groups = [];
        var currentGroup = null;
        for (var i = 0; i < children.length; i++) {
            var el = children[i];
            if (el.classList.contains('nav-group-header')) {
                if (currentGroup) groups.push(currentGroup);
                var labelEl = el.querySelector('.nav-group-label');
                var slug = labelEl && labelEl.getAttribute('data-i18n')
                    ? labelEl.getAttribute('data-i18n')
                    : (labelEl ? labelEl.textContent.trim().toLowerCase() : 'group-' + i);
                currentGroup = { headerEl: el, slug: slug, members: [] };
            } else if (el.classList.contains('nav-divider')) {
                // Bare hairline closes the current group.
                if (currentGroup) { groups.push(currentGroup); currentGroup = null; }
            } else if (currentGroup) {
                // Anything else (.nav-link) between headers belongs to the group.
                currentGroup.members.push(el);
            }
        }
        if (currentGroup) groups.push(currentGroup);

        function readState(slug) {
            try { return localStorage.getItem('surfmate.navGroup.' + slug) === '1'; }
            catch (e) { return false; }
        }
        function writeState(slug, collapsed) {
            try { localStorage.setItem('surfmate.navGroup.' + slug, collapsed ? '1' : '0'); }
            catch (e) { /* ignore */ }
        }

        for (var g = 0; g < groups.length; g++) {
            (function (group) {
                var header = group.headerEl;

                // Determine whether this group is currently collapsed — we
                // need to apply that state to ALL current members (including
                // freshly-injected ones), even if the header itself is
                // already wired from a prior call.
                var alreadyWired = header.hasAttribute('data-group-wired');
                var collapsed;
                if (alreadyWired) {
                    collapsed = header.getAttribute('data-collapsed') === 'true';
                } else {
                    var hasActive = false;
                    for (var k = 0; k < group.members.length; k++) {
                        if (group.members[k].classList.contains('active') ||
                            group.members[k].getAttribute('aria-current') === 'page') {
                            hasActive = true; break;
                        }
                    }
                    collapsed = hasActive ? false : readState(group.slug);
                }

                // Apply state to every tracked member — re-runs reach newly
                // injected links so they pick up the current collapsed state.
                if (collapsed) header.setAttribute('data-collapsed', 'true');
                else header.removeAttribute('data-collapsed');
                header.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
                for (var m = 0; m < group.members.length; m++) {
                    if (collapsed) group.members[m].setAttribute('hidden', '');
                    else group.members[m].removeAttribute('hidden');
                }

                // Skip the click/keydown bind on re-runs to avoid duplicate
                // handlers firing the toggle twice per click.
                if (alreadyWired) return;
                header.setAttribute('data-group-wired', '1');

                // Add chevron + interactive affordance.
                header.classList.add('nav-group-header-collapsible');
                header.setAttribute('role', 'button');
                header.setAttribute('tabindex', '0');
                if (!header.querySelector('.nav-group-chevron')) {
                    var chev = document.createElement('span');
                    chev.className = 'nav-group-chevron';
                    chev.setAttribute('aria-hidden', 'true');
                    chev.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
                    header.appendChild(chev);
                }

                function toggle() {
                    var nowCollapsed = header.getAttribute('data-collapsed') !== 'true';
                    if (nowCollapsed) header.setAttribute('data-collapsed', 'true');
                    else header.removeAttribute('data-collapsed');
                    header.setAttribute('aria-expanded', nowCollapsed ? 'false' : 'true');
                    // Walk siblings live each click — handles members injected
                    // after the initial wire (e.g. update-flow.js's link).
                    for (var s2 = header.nextElementSibling; s2; s2 = s2.nextElementSibling) {
                        if (s2.classList.contains('nav-group-header') ||
                            s2.classList.contains('nav-divider')) break;
                        if (nowCollapsed) s2.setAttribute('hidden', '');
                        else s2.removeAttribute('hidden');
                    }
                    writeState(group.slug, nowCollapsed);
                }
                header.addEventListener('click', toggle);
                header.addEventListener('keydown', function (ev) {
                    if (ev.key === 'Enter' || ev.key === ' ' || ev.keyCode === 13 || ev.keyCode === 32) {
                        ev.preventDefault();
                        toggle();
                    }
                });
            }(groups[g]));
        }
    }

    // Re-apply on demand (for pages that change the selection live, like
    // vehicle-control.html itself). Pages can call:
    //     window.SurfMateAppShell.refreshVehicle()
    // after they save a new model/colour to /api/models/selected.
    window.SurfMateAppShell = window.SurfMateAppShell || {};
    window.SurfMateAppShell.refreshVehicle = applyVehicleSelection;

    // Mount an additional SurfMateEvCard3D on a custom canvas (e.g. the
    // Live View camera selector). Reuses the sidebar's vendor download —
    // ev-card-3d.js is idempotent and adopts already-loaded THREE — and
    // tracks whatever (model, colour) the user has selected, replaying
    // the latest values once the renderer module is in place.
    window.SurfMateAppShell.mountVehicleCanvas = function (canvasEl, opts) {
        if (!canvasEl) return null;
        var view = 'side';
        if (opts && opts.view === 'top') view = 'top';
        else if (opts && opts.view === 'three-quarter') view = 'three-quarter';
        var readyNotified = false;

        // Surfaces may keep an inexpensive placeholder visible until this
        // callback fires. A cache hit and the first complete WebGL render
        // both count as ready; notify at most once for the initial mount.
        function notifyReady(source) {
            if (readyNotified) return;
            readyNotified = true;
            if (opts && typeof opts.onReady === 'function') {
                try { opts.onReady({ source: source }); } catch (e) {
                    if (window.console) console.warn('[app-shell] vehicle canvas onReady failed:', e);
                }
            }
        }

        // Sprite-cache fast path. If we have a webp for this
        // (model, colour, view), paint the canvas in 2D and stop.
        // CRITICAL: a single HTMLCanvasElement can hold either a 2D
        // context OR a WebGL context, never both — once getContext('2d')
        // succeeds, a subsequent getContext('webgl') returns null. So
        // for aux canvases we have to commit to one path. Sprite path
        // is the right default for non-interactive surfaces (map
        // markers, top-down hotspot art); it gives the same perf win
        // the sidebar already enjoys.
        function tryCache(cb) {
            if (!lastEv3dModel || !lastEv3dColor) { cb(false); return; }
            tryPaintFromSpriteCache(canvasEl, lastEv3dModel, lastEv3dColor, view, cb);
        }

        var instance = null;
        function instantiate() {
            if (instance) return instance;
            if (typeof window.SurfMateEvCard3D !== 'function') return null;
            try {
                instance = new window.SurfMateEvCard3D(canvasEl, opts || {});
                auxEv3dInstances.push(instance);
                if (lastEv3dColor) instance.setColor(lastEv3dColor);
                if (lastEv3dModel) instance.setModel(lastEv3dModel);
                if (lastEv3dModel && lastEv3dColor) {
                    instance.onceAfterRender(function () { notifyReady('render'); });
                    scheduleSpriteSnapshot(instance, canvasEl,
                                           lastEv3dModel, lastEv3dColor, view);
                }
            } catch (e) {
                if (window.console) console.warn('[app-shell] aux ev-card-3d init failed:', e);
            }
            return instance;
        }

        // Track this canvas in auxEv3dInstances even when we satisfy it
        // from the sprite cache, so a later setEvCardAppearance can
        // re-paint it on user selection change. We use a lightweight
        // pseudo-instance — not a real SurfMateEvCard3D — exposing the
        // minimum the iteration loop needs (canvas, view, _disposed,
        // setModel/setColor that re-attempt the cache).
        function makeSpriteOnlyAux() {
            var aux = {
                canvas: canvasEl,
                view: view,
                _disposed: false,
                _spriteOnly: true,
                setModel: function (m) {
                    if (!m || !lastEv3dColor) return;
                    tryPaintFromSpriteCache(canvasEl, m, lastEv3dColor, view, function (hit) {
                        if (!hit) upgradeToLive(aux);
                    });
                },
                setColor: function (c) {
                    if (!c || !lastEv3dModel) return;
                    tryPaintFromSpriteCache(canvasEl, lastEv3dModel, c, view, function (hit) {
                        if (!hit) upgradeToLive(aux);
                    });
                }
            };
            auxEv3dInstances.push(aux);
            return aux;
        }

        // Sprite-only auxes upgrade to a real WebGL instance the first
        // time the user selects a (model, colour) we don't have a
        // sprite for — at that point we can't paint anything from the
        // cache, so we have to render live. Splice the sprite-only
        // entry out before mounting (it shares the canvas, and the
        // WebGL context will own it from now on).
        function upgradeToLive(spriteAux) {
            spriteAux._disposed = true;
            // Reset the canvas: dropping its parent and reinserting
            // gives us a fresh element. Cheap if simply replaceChildren
            // — but we can't replace the canvas itself without breaking
            // existing references in the page (Leaflet marker, hotspot
            // overlay). Instead clear the 2D context and let three.js
            // attach. WebGL will refuse if a 2D context exists, so we
            // null out the canvas's getContext result by reassigning
            // width — this DOES NOT release the 2D context. The only
            // safe way is to swap the <canvas> for a fresh one.
            var fresh = canvasEl.cloneNode(false);
            if (canvasEl.parentNode) canvasEl.parentNode.replaceChild(fresh, canvasEl);
            canvasEl = fresh;
            instantiate();
        }

        // Wait for vehicle selection, then try the cache before loading
        // the renderer. This makes warm navigations a sprite-only path.
        function mountResolvedSelection() {
            tryCache(function (hit) {
                if (hit) {
                    makeSpriteOnlyAux();
                    notifyReady('cache');
                    return;
                }
                // Darstellung „leicht“: großes WebGL-Auto erst auf Tipp. Die kleine Draufsicht (Kamera-Auswahl,
                // Kartenmarkierung) bleibt, sonst verdeckte der Knopf die Kamera-Knöpfe bzw. die Markierung fehlte.
                if (window.PanoptesDarstellung && PanoptesDarstellung.leicht() && view !== 'top' && !(opts && opts.sofort)) {
                    var huelle = canvasEl.parentNode;
                    if (huelle) {
                        try { if (window.getComputedStyle(huelle).position === 'static') huelle.style.position = 'relative'; } catch (e) {}
                        var tipp = document.createElement('div');
                        tipp.className = 'pn-3d-tipp';
                        var tb = document.createElement('button');
                        tb.type = 'button';
                        tb.textContent = dstT('darstellung.3d_laden', '3D-Ansicht laden');
                        tb.onclick = function (ev) {
                            if (ev && ev.stopPropagation) ev.stopPropagation();
                            if (tipp.parentNode) tipp.parentNode.removeChild(tipp);
                            liveStarten();
                        };
                        tipp.appendChild(tb);
                        huelle.appendChild(tipp);
                        notifyReady('tipp');
                        return;
                    }
                }
                liveStarten();
            });
        }

        function liveStarten() {
            (function () {
                ensureEv3d();
                if (typeof window.SurfMateEvCard3D === 'function') {
                    instantiate();
                    return;
                }
                // Vendor still in flight — poll for the global the same way
                // ensureEv3d() does for its own canvas.
                var tries = 0;
                var iv = setInterval(function () {
                    tries++;
                    if (typeof window.SurfMateEvCard3D === 'function') {
                        clearInterval(iv);
                        instantiate();
                    } else if (tries > 80) {
                        clearInterval(iv);
                    }
                }, 100);
            })();
        }

        if (lastEv3dModel && lastEv3dColor) {
            mountResolvedSelection();
        } else {
            vehicleSelectionWaiters.push(mountResolvedSelection);
        }

        return null;
    };

    // Battery strip driver. The bar lives at the base of the 3D body
    // (see CSS .ev-battery-body-overlay). Single solid colour by SOC
    // band — green when healthy, amber when low, red when critical, no
    // multi-colour ramp. data-soc-state on the wrapper picks the
    // gradient; SOC also sets the WIDTH of the visible portion.
    function socStateFor(pct) {
        if (pct <= 20) return 'critical';
        if (pct <= 50) return 'warning';
        return 'healthy';
    }
    window.SurfMateAppShell.setSoc = function (pct) {
        var fill = document.getElementById('evBatteryFill');
        var bar  = document.getElementById('evBatteryBar');
        var clamped = Math.max(0, Math.min(100, pct));
        if (fill) fill.style.width = clamped + '%';
        if (bar)  bar.setAttribute('data-soc-state', socStateFor(clamped));
    };
    // Map charging power (kW) to fill-up animation duration. Slow AC L1
    // crawls (5s/cycle) → fast DC charging visibly snaps (~0.8s/cycle).
    // The mapping is piecewise so the visual differentiation lines up
    // with the user's mental model: trickle, AC, DC, ultra-fast.
    function fillupDurationForKw(kw) {
        if (!kw || kw <= 0) return 2.4;          // unknown → default
        if (kw <= 3.6)   return 5.0;              // L1 trickle
        if (kw <= 11)    return 3.6;              // AC L2
        if (kw <= 22)    return 2.6;              // AC fast L2
        if (kw <= 50)    return 1.6;              // DC fast
        if (kw <= 150)   return 1.0;              // DC hyper
        return 0.8;                                // ultra-rapid (>150 kW)
    }
    window.SurfMateAppShell.setCharging = function (on, powerKw) {
        // The .charging class on the EV card root gates the CSS keyframes
        // (battery sweep + bolt icon scale-in). core.js already toggles
        // this class before calling us — forwarding here keeps the
        // contract intact for any direct callers. powerKw is optional;
        // when supplied, the fill-up cycle speed scales with charging
        // power (set as a CSS var on the card root so the keyframe
        // animation-duration follows live).
        var card = document.getElementById('evCard');
        if (!card) return;
        if (on) card.classList.add('charging');
        else    card.classList.remove('charging');
        var dur = fillupDurationForKw(powerKw);
        card.style.setProperty('--ev-charge-fillup-duration', dur + 's');
    };

    // Re-run group-collapse wiring after dynamic nav links land (e.g.
    // update-flow.js's "Check for Updates" link). The function is
    // idempotent — already-wired headers skip the click bind, and every
    // run re-applies the persisted collapsed state to current members.
    window.SurfMateAppShell.rewireNavGroups = function () {
        var aside = wireGroupCollapse._lastAside ||
                    document.getElementById('sidebar') ||
                    document.querySelector('.sidebar');
        if (aside) wireGroupCollapse(aside);
    };

    // Auto-load the collapsible-card script so every page that includes
    // app-shell.js gets the collapse behavior without needing a per-page
    // <script> tag. Idempotent: a second injection is a no-op via the
    // data-surfmate-collapse marker.
    function ensureCollapseScript() {
        if (document.querySelector('script[data-surfmate-collapse]')) return;
        var s = document.createElement('script');
        s.src = '../shared/app-collapse.js';
        s.setAttribute('data-surfmate-collapse', '1');
        s.async = false;
        // Place it next to app-shell.js so the relative path resolves the
        // same way as the page's other shared imports.
        var ref = document.querySelector('script[src*="app-shell.js"]');
        if (ref && ref.parentNode) ref.parentNode.insertBefore(s, ref.nextSibling);
        else document.head.appendChild(s);
    }
    ensureCollapseScript();

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', mount);
    } else {
        mount();
    }
}());
