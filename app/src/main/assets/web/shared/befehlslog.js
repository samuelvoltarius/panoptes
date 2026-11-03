/**
 * Panoptes – Protokoll „Wer hat was gemacht?“ (0.1.34 Befehls-Log).
 *
 *   PanoptesBefehlslog.init(karteEl, inhaltEl)    GET /api/system/befehle?max=300
 *   (In der Web-Sitzung der Handy-App liefert der Kern nur die eigenen Einträge, mit Recht „protokoll“ alle.)
 *
 * Zeigt Zeit, Gerät, Aktion, Weg und Ergebnis, neueste zuerst, filterbar nach Gerät. Standard: nur wichtige Einträge
 * (Auf-/Zusperren, Klima, Kopplungen, Rechte, Einstellungen, Updates, Ablehnungen). Nur am Auto bzw. mit Login; ein
 * gekoppeltes Gerät bekommt 403, dann zeigt die Karte das. Keine Orte, keine Werte (der Kern liefert keine). ES5.
 */
(function () {
    'use strict';

    function T(k, fb, vars) {
        var s = null;
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(k, vars); if (v && v !== k) s = v; } } catch (e) {}
        s = s || fb;
        if (vars) for (var n in vars) if (vars.hasOwnProperty(n)) s = s.split('{' + n + '}').join(vars[n]);
        return s;
    }
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }

    var AKTIONEN = {
        aufsperren: ['protokoll.a_aufsperren', 'Aufsperren'],
        zusperren: ['protokoll.a_zusperren', 'Zusperren'],
        klima_an: ['protokoll.a_klima_an', 'Klima an'],
        klima_aus: ['protokoll.a_klima_aus', 'Klima aus'],
        sitzheizung: ['protokoll.a_sitzheizung', 'Sitzheizung'],
        akku_heizen: ['protokoll.a_akku_heizen', 'Akku vorheizen'],
        heizen_stoppen: ['protokoll.a_heizen_stoppen', 'Heizen stoppen'],
        wallbox: ['protokoll.a_wallbox', 'Wallbox'],
        route: ['protokoll.a_route', 'Ziel gesendet'],
        route_stand: ['protokoll.a_route', 'Ziel gesendet'],
        update_starten: ['protokoll.a_update', 'Update'],
        tiermodus: ['protokoll.a_tiermodus', 'Tiermodus'],
        tiermodus_stand: ['protokoll.a_tiermodus', 'Tiermodus']
    };

    /** Lesbare Aktion: erst die Aktion, dann das erste Ereignis, dann der Bereich des Pfads. */
    function aktion(e) {
        if (e.aktion && AKTIONEN[e.aktion]) return T(AKTIONEN[e.aktion][0], AKTIONEN[e.aktion][1]);
        var ev = (e.ereignisse && e.ereignisse[0]) || '';
        if (ev.indexOf('fahrzeug.unlock') === 0) return T('protokoll.a_aufsperren', 'Aufsperren');
        if (ev.indexOf('fahrzeug.lock') === 0) return T('protokoll.a_zusperren', 'Zusperren');
        if (ev.indexOf('fahrzeug.climate') === 0) return T('protokoll.a_klima', 'Klima');
        if (ev.indexOf('fahrzeug.') === 0) return T('protokoll.a_fahrzeug', 'Fahrzeugbefehl') + ' (' + ev.substring(9).split(':')[0] + ')';
        if (ev.indexOf('kopplung.angelegt') === 0) return T('protokoll.a_gekoppelt', 'Gerät gekoppelt');
        if (ev.indexOf('kopplung.widerrufen') === 0) return T('protokoll.a_entfernt', 'Gerät entfernt');
        if (ev.indexOf('kopplung.rechte') === 0) return T('protokoll.a_rechte', 'Rechte geändert');
        if (ev.indexOf('kopplung.anfrage') === 0) return T('protokoll.a_anfrage', 'Kopplungsanfrage entschieden');
        if (ev.indexOf('update.') === 0) return T('protokoll.a_update', 'Update');
        if (ev.indexOf('dateien.') === 0) return T('protokoll.a_dateien', 'Dateien');
        var p = e.pfad || '';
        if (p.indexOf('/api/kopplung/rechte') === 0) return T('protokoll.a_rechte', 'Rechte geändert');
        if (p.indexOf('/api/kopplung') === 0) return T('protokoll.a_kopplung', 'Kopplung');
        if (p.indexOf('/api/einstellungen') === 0) return T('protokoll.a_einstellungen', 'Einstellungen geändert');
        if (p.indexOf('/api/appupdate') === 0) return T('protokoll.a_update', 'Update');
        if (p.indexOf('/api/vehicle') === 0) return T('protokoll.a_fahrzeug', 'Fahrzeugbefehl');
        if (p.indexOf('/api/tiermodus') === 0) return T('protokoll.a_tiermodus', 'Tiermodus');
        if (p.indexOf('/api/dateien') === 0) return T('protokoll.a_dateien', 'Dateien');
        if (p.indexOf('/api/charging/') === 0 && /\/(station|stationen)(\/|$)/.test(p)) return T('protokoll.a_saeule', 'Ladesäule');
        return T('protokoll.a_sonstiges', 'Änderung') + (p ? ' (' + p.replace(/^\/api\//, '') + ')' : '');
    }

    function geraet(e) {
        if (e.art === 'display') return T('protokoll.w_display', 'Autobildschirm');
        if (e.art === 'login') return T('protokoll.w_login', 'Browser mit Anmeldung');
        if (e.eigen) return T('protokoll.w_eigen', 'Dieses Handy');
        if (e.art === 'intern') {
            var q = { telegram: ['protokoll.w_telegram', 'Telegram'], ha: ['protokoll.w_ha', 'Home Assistant'],
                automation: ['protokoll.w_automation', 'Automatik'] }[e.wer];
            return q ? T(q[0], q[1]) : T('protokoll.w_automation', 'Automatik');
        }
        var art = { handy: 'Handy', web: 'Handy-Web', dienst: 'Dienst', homeassistant: 'Home Assistant', cockpit: 'Cockpit' }[e.art] || '';
        return (e.name || e.wer || '?') + (art ? ' · ' + art : '');
    }

    function weg(e) {
        if (e.weg === 'bruecke') return T('protokoll.weg_bruecke', 'über die Brücke');
        if (e.weg === 'direkt') return T('protokoll.weg_direkt', 'direkt');
        return T('protokoll.weg_lokal', 'im Auto');
    }

    function ergebnis(e) {
        if (e.ergebnis === 'ok') return T('protokoll.e_ok', 'erledigt');
        var g = e.grund ? ' (' + String(e.grund).replace(/_/g, ' ') + ')' : '';
        if (e.ergebnis === 'abgelehnt') return T('protokoll.e_abgelehnt', 'abgelehnt') + g;
        return T('protokoll.e_fehler', 'Fehler') + g;
    }

    function zeit(t) {
        var d = new Date(t);
        function z(n) { return (n < 10 ? '0' : '') + n; }
        return z(d.getDate()) + '.' + z(d.getMonth() + 1) + '. ' + z(d.getHours()) + ':' + z(d.getMinutes()) + ':' + z(d.getSeconds());
    }

    function init(karte, inhalt, url) {
        if (!karte || !inhalt) return;
        var daten = [], filter = '', nurWichtig = true, gesperrt = false, nurEigene = false, kopf, wahl, kh, liste;

        /** Feste Teile; nach dem Laden der Texte neu, damit die Sprache stimmt. */
        function aufbauen() {
            kopf = el('div', 'sys-actions');
            wahl = el('select', 'text-input');
            wahl.setAttribute('aria-label', T('protokoll.filter', 'Gerät'));
            var wichtig = el('label', 'sys-note');
            kh = document.createElement('input'); kh.type = 'checkbox'; kh.checked = nurWichtig;
            wichtig.appendChild(kh); wichtig.appendChild(document.createTextNode(' ' + T('protokoll.nur_wichtig', 'Nur wichtige Einträge')));
            var neu = el('button', 'btn', T('system.refresh', 'Aktualisieren')); neu.type = 'button';
            kopf.appendChild(wahl); kopf.appendChild(wichtig); kopf.appendChild(neu);
            liste = el('div');
            inhalt.innerHTML = '';
            inhalt.appendChild(el('div', 'sys-note', T('protokoll.lead', 'Wer hat wann was am Auto gemacht – die letzten 30 Tage. Ohne Orte und ohne Inhalte.')));
            inhalt.appendChild(kopf);
            inhalt.appendChild(liste);
            wahl.onchange = function () { filter = wahl.value; zeichne(); };
            kh.onchange = function () { nurWichtig = kh.checked; zeichne(); };
            neu.onclick = lade;
        }

        function zeichne() {
            liste.innerHTML = '';
            kopf.style.display = gesperrt ? 'none' : '';
            if (gesperrt) {
                liste.appendChild(el('div', 'sys-note', T('protokoll.nur_im_auto', 'Das Protokoll gibt es nur am Auto oder mit Anmeldung.')));
                return;
            }
            var n = 0;
            if (nurEigene) liste.appendChild(el('div', 'sys-note', T('protokoll.nur_eigene', 'Du siehst nur, was dieses Handy gemacht hat.')));
            for (var i = 0; i < daten.length; i++) {
                var e = daten[i];
                if (nurWichtig && e.klasse !== 'pflicht') continue;
                if (filter && geraet(e) !== filter) continue;
                n++;
                var z = el('div', 'protokoll-zeile' + (e.ergebnis === 'ok' ? '' : ' protokoll-nicht-ok'));
                z.appendChild(el('div', 'protokoll-zeit', zeit(e.t)));
                var was = aktion(e);
                if (e.zielName || e.ziel) was += ' → ' + (e.zielName || e.ziel);
                if (e.rechte && e.rechte.length) was += ': ' + e.rechte.join(', ');
                z.appendChild(el('div', 'protokoll-was', was));
                z.appendChild(el('div', 'protokoll-wer', geraet(e) + ' · ' + weg(e)));
                z.appendChild(el('div', 'protokoll-ergebnis', ergebnis(e)));
                liste.appendChild(z);
            }
            if (!n) liste.appendChild(el('div', 'sys-note', T('protokoll.leer', 'Noch keine Einträge.')));
        }

        function fuelleWahl() {
            var namen = {}, l = [];
            for (var i = 0; i < daten.length; i++) { var g = geraet(daten[i]); if (!namen[g]) { namen[g] = 1; l.push(g); } }
            l.sort();
            wahl.innerHTML = '';
            var alle = el('option', null, T('protokoll.alle', 'Alle Geräte')); alle.value = ''; wahl.appendChild(alle);
            for (var j = 0; j < l.length; j++) { var o = el('option', null, l[j]); o.value = l[j]; wahl.appendChild(o); }
            if (filter && !namen[filter]) filter = '';
            wahl.value = filter;
        }

        function lade() {
            fetch(url || '/api/system/befehle?max=300', { cache: 'no-store' }).then(function (r) {
                return r.json().catch(function () { return {}; }).then(function (j) { j._s = r.status; return j; });
            }).then(function (d) {
                karte.style.display = '';
                gesperrt = d._s === 403;
                daten = gesperrt ? [] : (d.eintraege || []);
                nurEigene = !!d.nurEigene;
                fuelleWahl();
                zeichne();
            }).catch(function () {
                liste.innerHTML = '';
                liste.appendChild(el('div', 'sys-note', T('protokoll.fehler_laden', 'Protokoll konnte nicht geladen werden.')));
            });
        }

        aufbauen();
        if (window.BYD && BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(function () { aufbauen(); fuelleWahl(); zeichne(); });
        lade();
    }

    window.PanoptesBefehlslog = { init: init, _aktion: aktion, _geraet: geraet };
})();
