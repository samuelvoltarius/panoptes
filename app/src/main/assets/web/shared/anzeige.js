/**
 * Panoptes — Anzeige-Modus für das Auto-Display (Koordination 21.10., ab 0.1.7.1/0.1.7.2).
 *
 * Aktiv, wenn app-shell.js den Modus erkannt hat (html[data-anzeige="auto"]): Vollflächen-Hülle (html[data-android-app]),
 * „PanoptesDisplay“ im User-Agent (ab 0.1.8) oder ?anzeige=auto zum Testen. AndroidBridge allein zählt nicht.
 *
 *  - Zurück-Knopf in der Kopfzeile (außer auf Start).
 *  - Ladebalken beim Seitenwechsel.
 *  - Offline-Hinweis, wenn /status zweimal hintereinander nicht antwortet.
 *  - Fahr-Modus (Zündung an und Gang D/R/M/S, oder GPS-Tempo > 1,5 m/s ≈ 5 km/h): keine Eingabefelder;
 *    Seiten außerhalb von Start, Navigation und Wächter-Übersicht werden durch einen Hinweis ersetzt.
 *  - „Erweitert (alt)“: öffnet die alten Bildschirme der App, falls die Hülle das anbietet
 *    (PanoptesHuelle.erweitert() oder AndroidBridge.openLegacy()); sonst unsichtbar.
 *
 * Nur lesen: /status wird abgefragt, es geht nie ein Befehl ans Auto. ES5 (Chrome-58-Untergrenze).
 */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-anzeige') !== 'auto') return;

    var FAHRT_SEITEN = { 'index.html': 1, 'navi.html': 1, 'waechter.html': 1, '': 1 };
    var DE = {
        'anzeige.zurueck': 'Zurück',
        'anzeige.offline': 'Panoptes ist gerade nicht erreichbar. Neuer Versuch läuft …',
        'anzeige.fahrt_band': 'Während der Fahrt zeigt Panoptes nur das Wichtigste.',
        'anzeige.fahrt_titel': 'Während der Fahrt nicht verfügbar',
        'anzeige.fahrt_text': 'Diese Seite geht nur im Stand. Unterwegs gibt es Start, Navigation und den Wächter.',
        'anzeige.zur_start': 'Start',
        'anzeige.zur_navi': 'Navigation',
        'anzeige.erweitert': 'Erweitert (alt)'
    };
    function t(k) {
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(k); if (v && v !== k) return v; } } catch (e) {}
        return DE[k] || k;
    }
    function seite() {
        var p = location.pathname.split('/').pop() || '';
        return p === '' ? 'index.html' : p;
    }
    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    // ---- Ladebalken
    var balken = el('div', 'pn-laden');
    function ladenAn() { balken.className = 'pn-laden an'; }
    function ladenAus() { balken.className = 'pn-laden fertig'; }

    // ---- Hinweisband
    var band = el('div', 'pn-band');
    band.setAttribute('role', 'status');
    var offline = false, fahrt = false, bandUhr = null;
    function bandZeigen() {
        if (offline) { band.textContent = t('anzeige.offline'); band.className = 'pn-band warn an'; }
        else if (fahrt) {
            // nur kurz einblenden, damit nichts dauerhaft die Karte verdeckt
            band.textContent = t('anzeige.fahrt_band'); band.className = 'pn-band an';
            clearTimeout(bandUhr);
            bandUhr = setTimeout(function () { if (!offline) band.className = 'pn-band'; }, 6000);
        }
        else band.className = 'pn-band';
    }

    // ---- Sperre für Seiten, die nur im Stand gehen
    var sperre = el('div', 'pn-fahrt-sperre');
    function sperreBauen() {
        sperre.innerHTML = '';
        var box = el('div', 'box');
        box.appendChild(el('h2', null, t('anzeige.fahrt_titel')));
        box.appendChild(el('p', null, t('anzeige.fahrt_text')));
        var a1 = el('a', null, t('anzeige.zur_start')); a1.href = 'index.html';
        var a2 = el('a', 'zwei', t('anzeige.zur_navi')); a2.href = 'navi.html';
        box.appendChild(a1); box.appendChild(a2);
        sperre.appendChild(box);
        sperre.className = 'pn-fahrt-sperre' + (FAHRT_SEITEN[seite()] ? '' : ' aktiv');
    }

    // ---- Zurück-Knopf
    function zurueckEinbauen() {
        if (seite() === 'index.html') return;
        var top = document.querySelector('.pn-top');
        if (!top || top.querySelector('.pn-zurueck')) return;
        var b = el('button', 'pn-zurueck');
        b.type = 'button';
        b.setAttribute('aria-label', t('anzeige.zurueck'));
        b.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>';
        b.appendChild(el('span', null, t('anzeige.zurueck')));
        b.onclick = function () {
            ladenAn();
            if (history.length > 1 && document.referrer && document.referrer.indexOf(location.host) >= 0) history.back();
            else location.href = 'index.html';
        };
        top.insertBefore(b, top.firstChild);
    }

    // ---- „Erweitert (alt)“: nur, wenn die Hülle die alten Bildschirme öffnen kann
    // Hülle ab kern 0.1.7.1: html[data-android-app] + Link panoptes-app://erweitert (Rückweg: Zurück-Taste).
    // Nur in der Vollflächen-Hülle (AndroidBridge allein gibt es auch in alten eingebetteten Seiten).
    function erweitertMoeglich() {
        return document.documentElement.hasAttribute('data-android-app') || /PanoptesDisplay/.test(navigator.userAgent || '');
    }
    function erweitertOeffnen() {
        var b = window.AndroidBridge;
        if (b && typeof b.openLegacy === 'function') b.openLegacy();             // ab 0.1.8
        else if (b && typeof b.navigate === 'function') b.navigate('erweitert'); // 0.1.7.2
        else location.href = 'panoptes-app://erweitert';
    }
    function erweitertEinbauen() {
        if (!erweitertMoeglich() || seite() !== 'system.html') return;   // Menüpunkt unter System
        var sub = document.querySelector('.pn-subnav');
        if (!sub || sub.querySelector('.pn-erweitert')) return;
        var a = el('a', 'pn-erweitert', t('anzeige.erweitert'));
        a.href = 'panoptes-app://erweitert';
        a.onclick = function (e) {
            var b = window.AndroidBridge;
            if (b && (typeof b.openLegacy === 'function' || typeof b.navigate === 'function')) { e.preventDefault(); erweitertOeffnen(); }
        };
        sub.appendChild(a);
    }

    // ---- Fahrt und Erreichbarkeit aus /status (nur lesen)
    var fehler = 0;
    function pruefen() {
        if (document.hidden) return;
        fetch('/status', { cache: 'no-store', credentials: 'same-origin' }).then(function (r) {
            if (!r.ok) throw new Error('http ' + r.status);
            return r.json();
        }).then(function (st) {
            fehler = 0;
            var rec = (st && st.recordingStatus) || {};
            var an = typeof st.acc === 'boolean' ? st.acc : rec.accOn === true;
            var gang = String(rec.gear || st.gear || '').toUpperCase();
            // gps.speed ist m/s (GpsMonitor); 1,5 m/s ≈ 5 km/h, nur bei frischer Position
            var g = st.gps || {};
            var tempo = (g.isStale || g.isCached || g.isFixStale) ? 0 : Number(g.speed);
            var f = !!(an && /^(D|R|M|S)$/.test(gang)) || (tempo > 1.5);
            setzen(false, f);
        }, function () {
            fehler += 1;
            if (fehler >= 2) setzen(true, fahrt);
        });
    }
    function setzen(off, f) {
        var aenderung = off !== offline || f !== fahrt;
        offline = off; fahrt = f;
        if (fahrt) document.documentElement.setAttribute('data-fahrt', '1');
        else document.documentElement.removeAttribute('data-fahrt');
        if (aenderung) bandZeigen();
    }

    function start() {
        document.body.appendChild(balken);
        document.body.appendChild(band);
        sperreBauen();
        document.body.appendChild(sperre);
        zurueckEinbauen();
        erweitertEinbauen();
        // Seitenwechsel über Links: Ladebalken zeigen
        document.addEventListener('click', function (e) {
            var a = e.target;
            while (a && a.nodeName !== 'A') a = a.parentNode;
            if (a && a.getAttribute('href') && a.getAttribute('href').charAt(0) !== '#' && a.target !== '_blank') ladenAn();
        }, true);
        window.addEventListener('pageshow', ladenAus);
        if (document.readyState === 'complete') ladenAus(); else window.addEventListener('load', ladenAus);
        pruefen();
        setInterval(pruefen, 5000);
        document.addEventListener('visibilitychange', function () { if (!document.hidden) pruefen(); });
        // Texte nachziehen, sobald die Übersetzung da ist
        if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            BYD.i18n.onChange(function () { sperreBauen(); bandZeigen(); });
        }
        setTimeout(function () { sperreBauen(); zurueckEinbauen(); erweitertEinbauen(); }, 1500);
    }

    window.PanoptesAnzeige = { pruefen: pruefen, fahrt: function () { return fahrt; } };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
