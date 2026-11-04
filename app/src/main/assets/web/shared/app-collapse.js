/**
 * Panoptes — Einklappen (0.1.38.4, Alfred: „alle Menüpunkte sollten eingeklappt sein und nur beim Draufdrücken
 * aufgehen, systemweit“). Ein Baustein für alle Seiten; app-shell.js lädt ihn automatisch.
 *
 * Jede `.card` mit direktem `.card-header` + `.card-body` wird zum Abschnitt:
 *   - Standard EINGEKLAPPT: man sieht Symbol, Überschrift und (falls vorhanden) den kurzen Zustand im Kopf
 *     (`.card-badge`). Ein Tipp auf den Kopf klappt auf.
 *   - NUR EINER OFFEN je Seite (REGEL 50+): Wer einen Abschnitt öffnet, schließt den vorigen. So bleibt die Seite
 *     kurz, man verliert nie den Überblick und muss nichts suchen oder wieder zuklappen.
 *   - Steht auf einer Seite nur EIN sichtbarer Abschnitt, ist er offen (nichts zum Aufklappen suchen).
 *   - Sprungziel (`seite.html#id`, auch ein Element IN einem Abschnitt) und die Einstellungs-Suche öffnen den
 *     passenden Abschnitt von selbst und scrollen hin.
 *   - Kein Merken über Seitenwechsel: jede Seite startet aufgeräumt (eingeklappt).
 *
 * Ausnahmen je Karte: `data-collapse="off"` (nie einklappen), `data-collapse-default="open"` (startet offen).
 * Schnittstelle (auch für Handy-App/Cockpit-Weboberflächen, siehe EINKLAPPEN_MUSTER.md):
 *   PanoptesEinklappen.oeffne(idOderElement, {scrollen:true})  öffnet den Abschnitt, schließt die anderen
 *   PanoptesEinklappen.schliesseAlle()
 *   PanoptesEinklappen.neu()                                   neue Karten im DOM verdrahten
 * ES5 (Chromium 58/74 am Auto).
 */
(function () {
    'use strict';

    var karten = [];

    function kopf(card) { return card.querySelector(':scope > .card-header'); }
    function inhalt(card) { return card.querySelector(':scope > .card-body'); }
    function sichtbar(card) { return !card.hidden && card.style.display !== 'none' && card.offsetParent !== null; }

    function pfeil() {
        var span = document.createElement('span');
        span.className = 'card-collapse-chevron';
        span.setAttribute('aria-hidden', 'true');
        span.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
        return span;
    }

    function setze(card, zu, animiert) {
        var body = inhalt(card), header = kopf(card);
        if (!body) return;
        if (zu) {
            if (animiert && (body.style.maxHeight === '' || body.style.maxHeight === 'none')) {
                body.style.maxHeight = body.scrollHeight + 'px';
                /* eslint-disable-next-line no-unused-expressions */
                body.offsetHeight;
            }
            card.classList.add('is-collapsed');
            if (header) header.setAttribute('aria-expanded', 'false');
            body.style.maxHeight = '0px';
            body.style.opacity = '0';
        } else {
            card.classList.remove('is-collapsed');
            if (header) header.setAttribute('aria-expanded', 'true');
            if (animiert) {
                body.style.maxHeight = (body.scrollHeight + 64) + 'px';
                body.style.opacity = '1';
                setTimeout(function () { if (!card.classList.contains('is-collapsed')) body.style.maxHeight = 'none'; }, 320);
            } else {
                body.style.maxHeight = 'none';
                body.style.opacity = '1';
            }
        }
    }

    /** Öffnet einen Abschnitt und schließt alle anderen (nur einer offen). */
    function oeffne(ziel, opt) {
        var el = typeof ziel === 'string' ? document.getElementById(ziel) : ziel;
        if (!el) return false;
        var card = el.classList && el.classList.contains('card') && el.hasAttribute('data-collapse-wired')
            ? el : (el.closest ? el.closest('.card[data-collapse-wired]') : null);
        if (!card) return false;
        for (var i = 0; i < karten.length; i++) {
            if (karten[i] !== card && karten[i].getAttribute('data-collapse') !== 'off') setze(karten[i], true, true);
        }
        setze(card, false, !(opt && opt.ohneAnimation));
        if (!opt || opt.scrollen !== false) {
            setTimeout(function () { (el === card ? card : el).scrollIntoView(); }, 30);
        }
        return true;
    }

    function schliesseAlle() {
        for (var i = 0; i < karten.length; i++) if (karten[i].getAttribute('data-collapse') !== 'off') setze(karten[i], true, true);
    }

    function verdrahte(card) {
        if (card.getAttribute('data-collapse') === 'off') return;
        var header = kopf(card), body = inhalt(card);
        if (!header || !body || card.hasAttribute('data-collapse-wired')) return;
        card.setAttribute('data-collapse-wired', '1');
        karten.push(card);
        header.classList.add('card-header-collapsible');
        header.setAttribute('role', 'button');
        header.setAttribute('tabindex', '0');
        if (!header.querySelector(':scope > .card-collapse-chevron')) header.appendChild(pfeil());
        setze(card, card.getAttribute('data-collapse-default') !== 'open', false);

        function tipp(ev) {
            // Klicks auf eigene Bedienelemente im Kopf (Links, Knöpfe, Schalter) nicht abfangen
            var t = ev && ev.target;
            while (t && t !== header) {
                var tag = t.tagName ? t.tagName.toLowerCase() : '';
                if (tag === 'a' || tag === 'button' || tag === 'input' || tag === 'select' || tag === 'label') return;
                t = t.parentNode;
            }
            if (card.classList.contains('is-collapsed')) oeffne(card, { scrollen: false });
            else setze(card, true, true);
        }
        header.addEventListener('click', tipp);
        header.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter' || ev.key === ' ' || ev.keyCode === 13 || ev.keyCode === 32) { ev.preventDefault(); tipp(ev); }
        });
    }

    /** Ein einziger sichtbarer Abschnitt auf der Seite ist offen. */
    function einzigeOffen() {
        var sicht = [];
        for (var i = 0; i < karten.length; i++) if (sichtbar(karten[i])) sicht.push(karten[i]);
        if (sicht.length === 1 && sicht[0].classList.contains('is-collapsed')) setze(sicht[0], false, false);
    }

    function zielAusAdresse() {
        var id = (window.location.hash || '').replace(/^#/, '').split('=')[0];
        if (id) oeffne(id, { ohneAnimation: true });
    }

    function neu() {
        var cards = document.querySelectorAll('.card');
        for (var i = 0; i < cards.length; i++) verdrahte(cards[i]);
    }

    function start() {
        neu();
        zielAusAdresse();
        // Seiten blenden Karten oft erst nach dem Laden ein (Kern-Antwort): dann noch einmal prüfen
        setTimeout(function () { neu(); einzigeOffen(); }, 800);
        setTimeout(function () { neu(); einzigeOffen(); }, 2500);
        window.addEventListener('hashchange', zielAusAdresse);
        document.addEventListener('app-shell:ready', neu);
    }

    window.PanoptesEinklappen = { oeffne: oeffne, schliesseAlle: schliesseAlle, neu: neu };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
}());
