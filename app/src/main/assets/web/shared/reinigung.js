/*
 * Bildschirm-Reinigungsmodus (Konkurrenzvergleich Kinex, 03.11.2026): Vollbild, das 30 s lang jede Berührung
 * schluckt, mit Countdown. Nur in P. Lang drücken (1,5 s) beendet sofort, ebenso ein Gang außer P.
 * Reine Anzeige: liest nur /status, schickt nichts ans Auto und berührt keine Fahrzeugfunktion.
 * Grenze: Die Leisten des Autos selbst (außerhalb von Panoptes) kann eine Webseite nicht sperren.
 */
(function () {
    'use strict';
    var DAUER_S = 30, LANG_MS = 1500, PRUEF_MS = 3000;
    var EREIGNISSE = ['pointerdown', 'pointerup', 'pointermove', 'pointercancel', 'touchstart', 'touchmove', 'touchend',
        'mousedown', 'mouseup', 'click', 'dblclick', 'contextmenu', 'wheel', 'keydown', 'keyup'];
    var aktiv = null;

    function t(k, f, v) {
        if (window.PanoptesShell && PanoptesShell.tr) return PanoptesShell.tr(k, f, v);
        return String(f).replace(/\{(\w+)\}/g, function (m, n) { return v && v[n] != null ? v[n] : m; });
    }
    function hinweis(text) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(text, 'info'); else window.alert(text);
    }

    /** Nur in P: Gang P (oder Auto aus ohne Gang) und kein Tempo über Schritt. */
    function erlaubt(status) {
        if (!status) return false;
        var rec = status.recordingStatus || {};
        var gang = String(rec.gear || status.gear || '').toUpperCase();
        var gps = status.gps || {};
        var tempo = (gps.isStale || gps.isCached || gps.isFixStale) ? 0 : Number(gps.speed);
        if (isFinite(tempo) && tempo > 1.5) return false;
        if (gang) return gang === 'P';
        var acc = typeof status.acc === 'boolean' ? status.acc : (typeof rec.accOn === 'boolean' ? rec.accOn : null);
        return acc === false;
    }

    function holeStatus() {
        return fetch('/status', { credentials: 'same-origin', cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .catch(function () { return null; });
    }

    function schlucken(e) {
        if (!aktiv) return;
        if (e.type === 'pointerdown' || e.type === 'touchstart' || e.type === 'mousedown') {
            clearTimeout(aktiv.lang);
            aktiv.lang = setTimeout(ende, LANG_MS);
        } else if (e.type === 'pointerup' || e.type === 'pointercancel' || e.type === 'touchend' || e.type === 'mouseup') {
            clearTimeout(aktiv.lang);
        }
        if (e.cancelable) e.preventDefault();
        e.stopPropagation();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    }

    function zeige() {
        if (aktiv) return;
        var rest = DAUER_S;
        var hg = document.createElement('div');
        hg.id = 'pnReinigung';
        hg.setAttribute('role', 'dialog');
        hg.setAttribute('aria-modal', 'true');
        hg.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#000;color:#fff;display:flex;'
            + 'flex-direction:column;align-items:center;justify-content:center;gap:18px;text-align:center;'
            + 'touch-action:none;user-select:none;-webkit-user-select:none;padding:24px';
        var titel = document.createElement('div');
        titel.style.cssText = 'font-size:28px;font-weight:700';
        titel.textContent = t('reinigung.titel', 'Bildschirm reinigen');
        var zahl = document.createElement('div');
        zahl.style.cssText = 'font-size:96px;font-weight:700;font-variant-numeric:tabular-nums;line-height:1';
        zahl.textContent = String(rest);
        var text = document.createElement('div');
        text.style.cssText = 'font-size:18px;opacity:.85;max-width:34em';
        text.textContent = t('reinigung.text', 'Berührungen sind {n} Sekunden lang gesperrt. Jetzt in Ruhe wischen.', { n: DAUER_S });
        var ende1 = document.createElement('div');
        ende1.style.cssText = 'font-size:15px;opacity:.6';
        ende1.textContent = t('reinigung.beenden', 'Zum Beenden lange drücken.');
        hg.appendChild(titel); hg.appendChild(zahl); hg.appendChild(text); hg.appendChild(ende1);
        document.body.appendChild(hg);
        aktiv = { hg: hg, lang: null, takt: null, pruef: null };
        EREIGNISSE.forEach(function (n) { window.addEventListener(n, schlucken, { capture: true, passive: false }); });
        try {
            var de = document.documentElement;
            if (de.requestFullscreen) de.requestFullscreen().catch(function () {});
        } catch (e) { /* Vollbild ist nur ein Zusatz */ }
        aktiv.takt = setInterval(function () {
            rest--;
            zahl.textContent = String(Math.max(0, rest));
            if (rest <= 0) ende();
        }, 1000);
        // Verlässt das Auto P, sofort Schluss: der Bildschirm muss dann bedienbar sein.
        aktiv.pruef = setInterval(function () {
            holeStatus().then(function (s) { if (aktiv && s && !erlaubt(s)) ende(); });
        }, PRUEF_MS);
    }

    function ende() {
        if (!aktiv) return;
        var a = aktiv;
        aktiv = null;
        clearTimeout(a.lang); clearInterval(a.takt); clearInterval(a.pruef);
        EREIGNISSE.forEach(function (n) { window.removeEventListener(n, schlucken, { capture: true }); });
        if (a.hg.parentNode) a.hg.parentNode.removeChild(a.hg);
        try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {}); } catch (e) {}
    }

    function start() {
        if (aktiv) return Promise.resolve(false);
        return holeStatus().then(function (s) {
            if (!erlaubt(s)) { hinweis(t('reinigung.nur_p', 'Bildschirm reinigen geht nur, wenn das Auto in P steht.')); return false; }
            zeige();
            return true;
        });
    }

    document.addEventListener('click', function (e) {
        var b = e.target && e.target.closest ? e.target.closest('[data-reinigung]') : null;
        if (b && !aktiv) { e.preventDefault(); start(); }
    });

    window.PanoptesReinigung = { start: start, ende: ende, erlaubt: erlaubt, DAUER_S: DAUER_S, LANG_MS: LANG_MS };
}());
