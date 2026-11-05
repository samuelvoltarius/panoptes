/*
 * Panoptes – „Größe im Fahrerdisplay“ auf der Tacho-Seite (0.1.38.11, C1).
 *
 * Eine Quelle: GET/PUT /api/tacho/groesse (blindspot.clusterSizeProfile). 0 = Automatisch (vom Auto, Panoptes sendet
 * keine Größe), 29/31/30 = klein 8,8" / mittel 10,25" / groß 12,3". Ein Tipp speichert und zeigt die Größe sofort am
 * Fahrerdisplay; danach „Passt so“ oder „Zurück zum vorherigen“. Nur im Stand (der Kern sagt sonst nur_im_stand).
 * ES5 (WebView 74): kein ?., ??, Flex-gap, inset, keine Pfeilfunktionen.
 */
(function () {
    'use strict';
    function $(id) { return document.getElementById(id); }
    function t(key, fallback) {
        return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(key, fallback) : fallback;
    }
    var WAHL = [
        { p: 0, k: 'vehicle_control.tacho_groesse_auto', f: 'Automatisch (vom Auto)' },
        { p: 29, k: 'vehicle_control.tacho_groesse_klein', f: 'Klein · 8,8"' },
        { p: 31, k: 'vehicle_control.tacho_groesse_mittel', f: 'Mittel · 10,25"' },
        { p: 30, k: 'vehicle_control.tacho_groesse_gross', f: 'Groß · 12,3"' }
    ];
    var jetzt = null, vorher = null, vorschlag = 0, laeuft = false;

    function name(p) {
        for (var i = 0; i < WAHL.length; i++) if (WAHL[i].p === p) return t(WAHL[i].k, WAHL[i].f);
        return String(p);
    }
    function meldung(text, schlecht) {
        var el = $('tachoGroesseMeldung'); if (!el) return;
        el.textContent = text || '';
        el.style.color = schlecht ? 'var(--status-danger)' : '';
    }
    function zeichne() {
        var box = $('tachoGroesseKnoepfe'); if (!box) return;
        while (box.firstChild) box.removeChild(box.firstChild);
        WAHL.forEach(function (w) {
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'btn' + (w.p === jetzt ? ' btn-primary' : '');
            b.setAttribute('aria-pressed', w.p === jetzt ? 'true' : 'false');
            b.setAttribute('data-profil', String(w.p));
            b.style.minHeight = '56px';
            b.style.fontSize = '16px';
            b.textContent = t(w.k, w.f);
            b.onclick = function () { tippe(w.p); };
            box.appendChild(b);
        });
        var v = $('tachoGroesseVorschlag');
        if (v) {
            v.textContent = jetzt === 0 && vorschlag
                ? t('vehicle_control.tacho_groesse_vorschlag', 'Vorschlag für dein Auto:') + ' ' + name(vorschlag) : '';
        }
    }
    function frageZeigen(an) {
        var f = $('tachoGroesseFrage'); if (f) f.style.display = an ? '' : 'none';
    }
    function setze(p, danach) {
        if (laeuft) return;
        laeuft = true;
        meldung('');
        fetch('/api/tacho/groesse', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ profil: p }) })
            .then(function (r) { return r.json().then(function (j) { return { ok: r.ok && j && j.ok === true, j: j || {} }; }); })
            .then(function (x) {
                laeuft = false;
                if (!x.ok) {
                    meldung(x.j.grund === 'nur_im_stand'
                        ? t('vehicle_control.tacho_groesse_nur_stand', 'Die Größe lässt sich nur im Stand ändern.')
                        : t('vehicle_control.tacho_groesse_fehler', 'Das hat nicht geklappt.'), true);
                    return;
                }
                jetzt = p;
                zeichne();
                if (danach) danach(x.j);
            }, function () {
                laeuft = false;
                meldung(t('vehicle_control.tacho_groesse_fehler', 'Das hat nicht geklappt.'), true);
            });
    }
    function tippe(p) {
        if (p === jetzt || jetzt === null) return;
        var alt = jetzt;
        setze(p, function () {
            vorher = alt;
            meldung(p === 0
                ? t('vehicle_control.tacho_groesse_auto_text', 'Automatisch: Panoptes sendet keine Größe mehr. Ist der Tacho noch falsch, wähl die passende Größe.')
                : t('vehicle_control.tacho_groesse_frage', 'Schau aufs Fahrerdisplay: Passt die Größe jetzt?'));
            frageZeigen(true);
        });
    }
    function boot() {
        if (!$('tachoGroesse')) return;
        $('tachoGroesseOk').onclick = function () { vorher = null; frageZeigen(false); meldung(''); };
        $('tachoGroesseZurueck').onclick = function () {
            if (vorher === null) { frageZeigen(false); return; }
            var p = vorher;
            setze(p, function () {
                vorher = null;
                frageZeigen(false);
                meldung(t('vehicle_control.tacho_groesse_zurueck_ok', 'Die vorherige Größe ist wieder eingestellt.') + ' ' + name(p));
            });
        };
        fetch('/api/tacho/groesse', { cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                jetzt = d && typeof d.profil === 'number' ? d.profil : 0;
                vorschlag = d && d.vorschlag ? d.vorschlag : 0;
                zeichne();
            }, function () { jetzt = 0; zeichne(); });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
