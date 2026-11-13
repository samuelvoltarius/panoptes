/**
 * Panoptes System page navigation.
 *
 * Keeps every existing card and its behaviour, but shows one plain-language
 * group at a time. ES5 on purpose for older BYD WebViews.
 */
(function (global) {
    'use strict';

    var GROUPS = {
        connections: ['zugangscode', 'tailscaleKarte', 'wireguardSetupCard', 'kopplung', 'protokoll', 'homeassistant', 'planerKarte'],
        vehicle: ['manualVehicleCard', 'nativeVehicleTools', 'anzeige', 'displayLink', 'kartenquelle', 'blitzer', 'navigation'],
        privacy: ['datenschutz', 'energie', 'updates'],
        advanced: ['einrichtung', 'notzugang', 'eingriffe', 'umzug', 'tunnelKarte', 'coreServicesCard', 'weitereEinstellungen']
    };
    var STORAGE_KEY = 'pn.system.group';
    // 0.1.38.10: Navi-Schalter der Gruppe „system“ – EINE Liste für die Karte „Navigation“ (system.html) und die Suche
    var NAVI_FELDER = ['navi.sprachansagen', 'planner.ansage', 'planner.band'];
    // 0.1.38.10: weitere Felder der Gruppe „system“ – EINE Liste Karte → Felder für system.html (store.zeichne nur:[…])
    // und das Sprungziel der Suche. Eine Karte ohne passendes Thema: „Weitere Einstellungen“.
    var KARTEN_FELDER = {
        homeassistant: ['ha.positionTeilen', 'ha.mdns'],
        planerKarte: ['planner.autoId', 'fahrtenbuch.planer'],
        datenschutz: ['wetter.online', 'community.enabled', 'gewohnheiten.an', 'gewohnheiten.aufbewahrungTage',
            'gewohnheiten.vergessen', 'sprache.befehle', 'sprache.weckwort'],   // 0.1.44 (P2): Mikrofon = Datenschutz
        weitereEinstellungen: ['autostart.beiZuendung', 'telegram.zurueckAmAuto', 'logs.tage', 'logs.mb']
    };
    // Felder, die fachlich auf eine andere Seite gehören (dort mit nur:[…] gezeichnet)
    var SEITEN_FELDER = { 'display.html#launcher': ['anzeige.launcherVideo', 'launcher.tempoWarnung'] };

    /** Sprungziel eines Felds aus den Listen oben, sonst null. */
    function feldZiel(key) {
        if (NAVI_FELDER.indexOf(key) >= 0) return 'system.html#navigation';
        var k;
        for (k in KARTEN_FELDER) {
            if (Object.prototype.hasOwnProperty.call(KARTEN_FELDER, k) && KARTEN_FELDER[k].indexOf(key) >= 0) return 'system.html#' + k;
        }
        for (k in SEITEN_FELDER) {
            if (Object.prototype.hasOwnProperty.call(SEITEN_FELDER, k) && SEITEN_FELDER[k].indexOf(key) >= 0) return k;
        }
        return null;
    }

    function t(key, fallback) {
        try {
            var value = global.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : '';
            return value && value !== key ? value : fallback;
        } catch (e) { return fallback; }
    }

    function groupForHash() {
        var id = (global.location.hash || '').replace(/^#/, '').split('=')[0];
        var group;
        for (group in GROUPS) {
            if (Object.prototype.hasOwnProperty.call(GROUPS, group) && GROUPS[group].indexOf(id) >= 0) return group;
        }
        return '';
    }

    function storedGroup() {
        try {
            var value = global.sessionStorage.getItem(STORAGE_KEY);
            return GROUPS[value] ? value : '';
        } catch (e) { return ''; }
    }

    function remember(group) {
        try { global.sessionStorage.setItem(STORAGE_KEY, group); } catch (e) {}
    }

    function setGroup(group, moveFocus) {
        if (!GROUPS[group]) group = 'connections';
        var id, card, key;
        for (key in GROUPS) {
            if (!Object.prototype.hasOwnProperty.call(GROUPS, key)) continue;
            for (var i = 0; i < GROUPS[key].length; i++) {
                id = GROUPS[key][i];
                card = document.getElementById(id);
                if (card) card.hidden = key !== group;
            }
        }
        var buttons = document.querySelectorAll('[data-system-group]');
        for (var b = 0; b < buttons.length; b++) {
            var selected = buttons[b].getAttribute('data-system-group') === group;
            buttons[b].setAttribute('aria-selected', selected ? 'true' : 'false');
            buttons[b].setAttribute('tabindex', selected ? '0' : '-1');
        }
        var label = t('system.group_' + group, group);
        var status = document.getElementById('systemGroupStatus');
        if (status) status.textContent = label;
        var tablist = document.querySelector('.system-groups');
        if (tablist) tablist.setAttribute('aria-label', t('system.overview_aria', 'System areas'));
        remember(group);
        if (moveFocus) {
            var first = null;
            for (var f = 0; f < GROUPS[group].length && !first; f++) {
                var candidate = document.getElementById(GROUPS[group][f]);
                if (candidate && candidate.style.display !== 'none') first = candidate;
            }
            if (first) first.scrollIntoView();
        }
    }

    // ------------------------------------------------------------ Suche (0.1.37)
    // Durchsucht drei Quellen, alles nur im Browser: die Karten dieser Seite, die Seiten des Menüs
    // (PanoptesShell.sections, die eine Seitenliste) und die Schalter aus dem Einstellungs-Schema.
    // Schalter, die bei ihrer Funktion stehen (Wächter, Laden, Camping), führen zu ihrer Seite.
    var SCHEMA_SEITE = {
        waechter: 'waechter.html', auto: 'waechter.html', camping: 'camping.html', tiermodus: 'tiermodus.html',
        laden: 'charging.html', update: 'system.html#updates', energie: 'system.html#energie',
        datenschutz: 'system.html#datenschutz', system: 'system.html'
    };
    // Technikwörter, nach denen Leute trotzdem suchen, auf die einfachen Namen abbilden
    var SYNONYME = {
        tailscale: 'fernzugang', tailnet: 'fernzugang', vpn: 'fernzugang heimnetz', wireguard: 'heimnetz',
        mqtt: 'smart home', 'home assistant': 'smart home', abrp: 'routenplaner', wifi: 'wlan'
    };
    var schemaTreffer = null;

    // Fixrunde E2E 0.1.43.2 (G2): Einstellungen außerhalb dieser Seite und ohne Schema-Feld, die Leute mit eigenen
    // Wörtern suchen („Benachrichtigung“, „Waze“, „Pfeile“, „Overlay“). Suchwörter in allen 5 Sprachen (de, en, fr,
    // it, nl), weil die Suche sprachunabhängig über den Text geht; der Titel kommt aus der gewählten Sprache.
    var STICHWOERTER = [
        { ziel: 'road-sense.html', titel: 'road_sense.external_nav_guidance', fallback: 'Google-Maps-/Waze-Pfeile übernehmen',
          woerter: 'google maps waze pfeile pfeil abbiegepfeile benachrichtigung benachrichtigungen benachrichtigungszugriff '
            + 'mitteilungen navigation fremde navi notification notifications notification access arrows turn arrows '
            + 'fleches fleche notifications acces frecce freccia notifiche accesso notifiche pijlen pijl meldingen meldingtoegang' },
        { ziel: 'road-sense.html', titel: 'road_sense.oem_turn_guidance', fallback: 'Abbiegepfeile im Fahrerdisplay',
          woerter: 'pfeile pfeil abbiegepfeile fahrerdisplay kombi kombiinstrument tacho cluster arrows turn arrows '
            + 'driver display fleches combine frecce quadro strumenti pijlen instrumentenpaneel' },
        { ziel: 'meldungen.html', titel: 'shell.page_meldungen_start', fallback: 'Meldungen',
          woerter: 'benachrichtigung benachrichtigungen mitteilungen push meldungen notification notifications alerts '
            + 'notifications alertes notifiche avvisi meldingen' },
        { ziel: 'system.html#einrichtung', titel: 'einr.schritt.ueberlagerung', fallback: 'Anzeige über anderen Apps',
          woerter: 'overlay ueberlagerung uber anderen apps einblenden schwebend draw over other apps display over apps '
            + 'superposition au dessus des applis sovrapposizione sopra altre app weergave over andere apps' }
    ];

    function stichListe() {
        var out = [];
        for (var i = 0; i < STICHWOERTER.length; i++) {
            var e = STICHWOERTER[i];
            var titel = t(e.titel, e.fallback);
            out.push({ titel: titel, text: norm(titel + ' ' + e.woerter), ziel: e.ziel,
                ort: e.ziel.indexOf('system.html') === 0 ? '' : seitenOrt(e.ziel.split('#')[0]) });
        }
        return out;
    }

    function norm(s) {
        s = String(s || '').toLowerCase();
        try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
        return s.replace(/ß/g, 'ss').replace(/\s+/g, ' ');
    }

    function sprache() {
        try { return (global.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || ''; } catch (e) { return ''; }
    }

    function ladeSchema() {
        if (schemaTreffer) return;
        schemaTreffer = [];
        var q = sprache() ? '?sprache=' + encodeURIComponent(sprache()) : '';
        fetch('/api/einstellungen/schema' + q, { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; })
            .then(function (s) {
                var gs = (s && s.gruppen) || [];
                for (var i = 0; i < gs.length; i++) {
                    var fs = gs[i].felder || [];
                    for (var j = 0; j < fs.length; j++) {
                        var f = fs[j];
                        if (f.sichtbar === false || !f.titel) continue;
                        var ziel = feldZiel(f.key) || SCHEMA_SEITE[gs[i].id] || 'system.html';
                        schemaTreffer.push({ titel: f.titel, text: norm(f.titel + ' ' + (f.hilfe || '')), feld: f.key,
                            ziel: ziel, ort: ziel.indexOf('system.html') === 0 ? '' : seitenOrt(ziel.split('#')[0]) });
                    }
                }
                suchen();
            }).catch(function () {});
    }

    // „Bereich · Reiter“ einer Seite aus der Menüliste, damit man sieht, wo ein Schalter wohnt
    function seitenOrt(href) {
        var shell = global.PanoptesShell;
        if (!shell || !shell.sections) return '';
        for (var i = 0; i < shell.sections.length; i++) {
            var s = shell.sections[i];
            for (var j = 0; j < s.pages.length; j++) {
                var p = s.pages[j];
                if (p.href !== href) continue;
                var reiter = p.teil && shell.teile[p.teil] ? shell.tr(shell.teile[p.teil].i18n, shell.teile[p.teil].label)
                    : shell.tr(p.i18n, p.label);
                return shell.tr(s.i18n, s.label) + ' · ' + reiter;
            }
        }
        return '';
    }

    function seitenListe() {
        var shell = global.PanoptesShell, out = [];
        if (!shell || !shell.sections) return out;
        for (var i = 0; i < shell.sections.length; i++) {
            var s = shell.sections[i];
            var bereich = shell.tr(s.i18n, s.label);
            for (var j = 0; j < s.pages.length; j++) {
                var p = s.pages[j];
                if (shell.seiteAus ? shell.seiteAus(p) : p.hidden) continue;
                var name = shell.tr(p.i18n, p.label);
                var teil = p.teil && shell.teile[p.teil] ? shell.tr(shell.teile[p.teil].i18n, shell.teile[p.teil].label) : '';
                out.push({ titel: (teil && teil !== name ? teil + ' · ' : '') + name, ort: bereich,
                    text: norm(bereich + ' ' + teil + ' ' + name + ' ' + p.label + ' ' + p.href.replace('.html', '')), ziel: p.href });
            }
        }
        return out;
    }

    function kartenListe() {
        var out = [], group, ids, i, card, titel;
        for (group in GROUPS) {
            if (!Object.prototype.hasOwnProperty.call(GROUPS, group)) continue;
            ids = GROUPS[group];
            for (i = 0; i < ids.length; i++) {
                card = document.getElementById(ids[i]);
                // Karten, die das Auto nicht kann, bleiben auch in der Suche weg
                if (!card || card.style.display === 'none') continue;
                titel = card.querySelector('.card-title');
                out.push({ titel: titel ? titel.textContent.replace(/\s+/g, ' ').trim() : ids[i],
                    ort: t('system.group_' + group, group), text: norm(card.textContent), karte: ids[i], gruppe: group });
            }
        }
        return out;
    }

    function passt(eintrag, woerter) {
        for (var i = 0; i < woerter.length; i++) if (eintrag.text.indexOf(woerter[i]) < 0) return false;
        return true;
    }

    function suchen() {
        var feld = document.getElementById('pnEinstSuche');
        var liste = document.getElementById('pnEinstTreffer');
        if (!feld || !liste) return;
        var q = norm(feld.value).trim();
        liste.innerHTML = '';
        if (q.length < 2) { liste.hidden = true; return; }
        ladeSchema();
        var roh = q, k;
        for (k in SYNONYME) if (roh.indexOf(k) >= 0) q = q.split(k).join(SYNONYME[k].split(' ')[0]);
        var woerter = q.split(' ');
        var treffer = [], gesehen = {};
        function nimm(arr, art) {
            for (var i = 0; i < arr.length && treffer.length < 12; i++) {
                var e = arr[i], schluessel = art + (e.karte || e.ziel) + e.titel;
                if (gesehen[schluessel] || !(passt(e, woerter) || (q !== roh && passt(e, roh.split(' '))))) continue;
                gesehen[schluessel] = 1;
                e.art = art;
                treffer.push(e);
            }
        }
        nimm(seitenListe(), 'seite');
        nimm(kartenListe(), 'karte');
        nimm(stichListe(), 'stich');
        nimm(schemaTreffer || [], 'feld');
        liste.hidden = false;
        if (!treffer.length) {
            liste.innerHTML = '<p class="pn-suche-leer">' + esc(t('shell.suche_leer', 'Nichts gefunden. Probier ein anderes Wort.')) + '</p>';
            return;
        }
        for (var i = 0; i < treffer.length; i++) liste.appendChild(trefferKnopf(treffer[i]));
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return c === '&' ? '&amp;' : c === '<' ? '&lt;' : c === '>' ? '&gt;' : '&quot;';
        });
    }

    function trefferKnopf(e) {
        var a = document.createElement('a');
        a.className = 'pn-suche-treffer';
        var hier = e.art === 'karte' || (e.ziel && e.ziel.indexOf('system.html#') === 0);
        a.href = e.karte ? '#' + e.karte : e.ziel;
        a.innerHTML = '<b>' + esc(e.titel) + '</b><span>'
            + esc(e.ort || (hier ? t('shell.suche_hier', 'Auf dieser Seite') : t('shell.suche_seite', 'Eigene Seite'))) + '</span>';
        a.onclick = function (ev) {
            // E4 (0.1.44): ein Treffer zeigt sein Feld auch im Modus „Einfach“ (einstellungen.js liest den Sprung)
            try {
                if (e.feld) sessionStorage.setItem('pn.sprungFeld', e.feld);
                else sessionStorage.removeItem('pn.sprungFeld');
            } catch (x) {}
            var shell = global.PanoptesShell;
            if (e.feld && shell && shell.umfang && shell.umfang() === 'einfach') {
                // erweitertes Feld ist im Modus „Einfach“ nicht gezeichnet: Zielseite neu laden, dann zeigt es sich
                ev.preventDefault();
                var gleich = (global.location.pathname || '').split('/').pop() === e.ziel.split('#')[0];
                global.location.href = e.ziel;
                if (gleich) global.location.reload();
                return;
            }
            var id = e.karte || (e.ziel.indexOf('system.html#') === 0 ? e.ziel.split('#')[1] : '');
            if (!id) return;   // andere Seite: normaler Link
            ev.preventDefault();
            var gruppe = e.gruppe || groupForId(id);
            if (gruppe) setGroup(gruppe, false);
            var card = document.getElementById(id);
            if (card) {
                // 0.1.38.4: eingeklappten Abschnitt öffnen (app-collapse.js, nur einer offen)
                if (global.PanoptesEinklappen) PanoptesEinklappen.oeffne(card, { scrollen: false });
                card.scrollIntoView();
                card.classList.add('pn-suche-ziel');
                setTimeout(function () { card.classList.remove('pn-suche-ziel'); }, 2200);
            }
        };
        return a;
    }

    function groupForId(id) {
        for (var group in GROUPS) {
            if (Object.prototype.hasOwnProperty.call(GROUPS, group) && GROUPS[group].indexOf(id) >= 0) return group;
        }
        return '';
    }

    function sucheEinbauen() {
        var overview = document.querySelector('.system-overview');
        if (!overview || document.getElementById('pnEinstSuche')) return;
        var box = document.createElement('div');
        box.className = 'pn-suche';
        box.setAttribute('role', 'search');
        box.innerHTML = '<label class="pn-suche-label" for="pnEinstSuche">' + esc(t('shell.suche_label', 'Einstellungen durchsuchen')) + '</label>'
            + '<input type="search" id="pnEinstSuche" autocomplete="off" spellcheck="false" placeholder="'
            + esc(t('shell.suche_platzhalter', 'Suchen, z. B. Wallbox, Hotspot, Updates …')) + '">'
            + '<div class="pn-suche-liste" id="pnEinstTreffer" hidden aria-live="polite"></div>';
        var lead = overview.querySelector('.system-overview-lead');
        overview.insertBefore(box, lead ? lead.nextSibling : overview.firstChild);
        var feld = document.getElementById('pnEinstSuche');
        var timer = null;
        feld.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(suchen, 120); });
        feld.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter') {
                var erster = document.querySelector('#pnEinstTreffer .pn-suche-treffer');
                if (erster) erster.click();
            }
        });
    }

    function sucheTexte() {
        var l = document.querySelector('.pn-suche-label');
        if (l) l.textContent = t('shell.suche_label', 'Einstellungen durchsuchen');
        var f = document.getElementById('pnEinstSuche');
        if (f) f.setAttribute('placeholder', t('shell.suche_platzhalter', 'Suchen, z. B. Wallbox, Hotspot, Updates …'));
        schemaTreffer = null;
        suchen();
    }

    function init() {
        sucheEinbauen();
        var buttons = document.querySelectorAll('[data-system-group]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].onclick = function () { setGroup(this.getAttribute('data-system-group'), true); };
        }
        setGroup(groupForHash() || storedGroup() || 'connections', false);
        global.addEventListener('hashchange', function () {
            var group = groupForHash();
            if (group) setGroup(group, false);
        });
        if (global.BYD && BYD.i18n && BYD.i18n.onChange) {
            BYD.i18n.onChange(function () { setGroup(storedGroup() || 'connections', false); sucheTexte(); });
        }
    }

    global.PanoptesSystemOverview = { init: init, setGroup: setGroup, groups: GROUPS, suchen: suchen, naviFelder: NAVI_FELDER,
        kartenFelder: KARTEN_FELDER, seitenFelder: SEITEN_FELDER, feldZiel: feldZiel };
})(window);
