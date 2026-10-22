/**
 * Panoptes – Notzugang für die Fernwartung + Systemeingriffe (0.1.14 / 0.1.13, Kern 7).
 *
 *   PanoptesNotzugang.init(karteEl, inhaltEl)     GET /api/notzugang, POST /einrichten {authKey,name}, /an, /aus
 *   PanoptesEingriffe.init(karteEl, inhaltEl)     GET /api/system/eingriffe, POST /api/system/eingriffe/zuruecksetzen
 *
 * Beides nur im Auto bzw. mit Login, nie über die Kopplung (Kern antwortet 403 nur_im_auto → Karte zeigt das).
 * Der Auth-Key wird nur einmal gesendet und sofort aus dem Feld gelöscht; er wird nie angezeigt oder im Browser gespeichert.
 * Standard aus (Regel 1). ES5 (WebView 74).
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
    function knopf(txt, cls) { var b = el('button', 'btn ' + (cls || 'btn-secondary'), txt); b.type = 'button'; b.style.margin = '8px 8px 0 0'; return b; }
    function api(m, url, body) {
        return fetch(url, { method: m, cache: 'no-store', headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._s = r.status; return j; }); });
    }
    function toast(m, ok) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, ok ? 'success' : 'error'); }

    // ------------------------------------------------------------------ Notzugang
    function notzugang(karte, inhalt) {
        if (!karte || !inhalt) return;
        function zeichne(d) {
            inhalt.innerHTML = '';
            if (!d || d._s === 404) { karte.style.display = 'none'; return; }
            karte.style.display = '';
            inhalt.appendChild(el('p', 'setting-desc', T('notz.text', 'Der Notzugang ist ein zweiter, unabhängiger Weg ins Auto für die Fernwartung – falls der normale Fernzugang einmal nicht mehr geht. Er läuft über dein eigenes Tailscale-Netz (Tailnet): Nur Geräte in deinem Tailnet können ihn erreichen, und auch dann nur zur Wartung (adb), nicht die Panoptes-Oberfläche. Ausschalten kannst du ihn hier jederzeit; dann ist der Weg sofort zu.')));
            if (d._s === 403) { inhalt.appendChild(el('div', 'setting-desc', T('notz.nur_im_auto', 'Nur im Auto bzw. angemeldet einstellbar, nicht über ein gekoppeltes Gerät.'))); return; }
            var zeile = function (k, v) { var z = el('div', 'setting-desc'); var b = el('b', null, k + ': '); z.appendChild(b); z.appendChild(document.createTextNode(v)); inhalt.appendChild(z); };
            zeile(T('notz.status', 'Status'), !d.eingerichtet ? T('notz.nicht_eingerichtet', 'nicht eingerichtet')
                : (d.an ? (d.laeuft ? T('notz.an_laeuft', 'an, läuft') : T('notz.an_startet', 'an, startet gerade')) : T('notz.aus', 'aus')));
            if (d.eingerichtet && d.an && d.zustand) zeile(T('notz.tailnet', 'Tailnet'), d.zustand === 'Running' ? T('notz.verbunden', 'verbunden') : d.zustand);
            if (d.ip) zeile(T('notz.ip', 'Tailnet-IP'), d.ip);
            if (d.name) zeile(T('notz.name', 'Gerätename'), d.name);
            var meld = el('div', 'setting-desc');
            meld.style.color = 'var(--warning)';
            if (!d.eingerichtet) {
                inhalt.appendChild(el('div', 'setting-desc', T('notz.key_hint', 'Zum Einrichten brauchst du einen Auth-Key aus deiner Tailscale-Verwaltung (Settings → Keys, beginnt mit „tskey-“). Er wird nur einmal an den Notzugang übergeben und nie gespeichert oder angezeigt.')));
                var key = el('input', 'text-input'); key.type = 'password'; key.autocomplete = 'off'; key.setAttribute('autocomplete', 'off');
                key.placeholder = 'tskey-…'; key.style.cssText = 'width:100%;margin-top:8px';
                var name = el('input', 'text-input'); name.type = 'text'; name.value = 'panoptes-notzugang'; name.style.cssText = 'width:100%;margin-top:8px';
                name.setAttribute('aria-label', T('notz.name', 'Gerätename'));
                inhalt.appendChild(key); inhalt.appendChild(name);
                var ein = knopf(T('notz.einrichten', 'Notzugang einrichten'), 'btn-primary');
                ein.onclick = function () {
                    var k = key.value.trim();
                    key.value = '';   // sofort weg, egal wie es ausgeht
                    if (!k) { meld.textContent = T('notz.key_fehlt', 'Bitte den Auth-Key einfügen.'); return; }
                    ein.disabled = true;
                    api('POST', '/api/notzugang/einrichten', { authKey: k, name: name.value.trim() }).then(function (r) {
                        k = null;
                        ein.disabled = false;
                        if (!r.success) { meld.textContent = r.text || T('notz.fehler', 'Das hat nicht geklappt.'); return; }
                        toast(T('notz.eingerichtet', 'Notzugang eingerichtet.'), true);
                        zeichne((r.status || {}));
                        laden();
                    }).catch(function () { k = null; ein.disabled = false; meld.textContent = T('notz.fehler', 'Das hat nicht geklappt.'); });
                };
                inhalt.appendChild(ein);
            } else if (d.an) {
                var aus = knopf(T('notz.ausschalten', 'Notzugang ausschalten'));
                aus.onclick = function () {
                    aus.disabled = true;
                    api('POST', '/api/notzugang/aus').then(function (r) { if (!r.success) meld.textContent = r.text || T('notz.fehler', 'Das hat nicht geklappt.'); laden(); });
                };
                inhalt.appendChild(aus);
            } else {
                var an = knopf(T('notz.einschalten', 'Notzugang einschalten'), 'btn-primary');
                an.onclick = function () {
                    if (!window.confirm(T('notz.an_frage', 'Notzugang einschalten? Dann können Geräte in deinem Tailnet das Auto zur Wartung erreichen.'))) return;
                    an.disabled = true;
                    api('POST', '/api/notzugang/an').then(function (r) { if (!r.success) meld.textContent = r.text || T('notz.fehler', 'Das hat nicht geklappt.'); laden(); });
                };
                inhalt.appendChild(an);
            }
            inhalt.appendChild(meld);
        }
        function laden() {
            fetch('/api/notzugang', { cache: 'no-store' })
                .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._s = r.status; return j; }); })
                .catch(function () { return null; })
                .then(zeichne);
        }
        laden();
    }

    // ------------------------------------------------------------------ Systemeingriffe (0.1.13)
    function eingriffe(karte, inhalt) {
        if (!karte || !inhalt) return;
        function wert(v) { return v === null || v === undefined || v === '' ? T('eingr.unbekannt', 'unbekannt') : String(v); }
        // stay_on_while_plugged_in: 0 = aus, sonst Bitmaske der Ladearten (7 = alle) → für Laien nur an/aus
        function stayOn(v) { if (v === null || v === undefined || v === '') return T('eingr.unbekannt', 'unbekannt'); return String(v) === '0' ? T('eingr.aus', 'aus') : T('eingr.an', 'an'); }
        function zeichne(d) {
            inhalt.innerHTML = '';
            if (!d || d._s === 404) { karte.style.display = 'none'; return; }
            karte.style.display = '';
            inhalt.appendChild(el('p', 'setting-desc', T('eingr.text', 'Panoptes ändert einige Android-Einstellungen nur so lange wie nötig. Hier siehst du den Stand. Vor dem Deinstallieren kannst du alles auf den Ursprung zurücksetzen.')));
            var zeile = function (k, v) { var z = el('div', 'setting-desc'); z.appendChild(el('b', null, k + ': ')); z.appendChild(document.createTextNode(v)); inhalt.appendChild(z); };
            zeile(T('eingr.stay_on', 'Bildschirm bleibt beim Laden an'), stayOn(d.stayOnWhilePluggedIn) + (d.stayOnVonPanoptes ? ' (' + T('eingr.von_panoptes', 'von Panoptes') + ')' : '')
                + ' · ' + T('eingr.ursprung', 'ursprünglich {v}', { v: stayOn(d.stayOnUrsprung) }));
            zeile(T('eingr.doze', 'Android-Energiesparen (Doze)'), d.doze === 'true' ? T('eingr.an', 'an') : (d.doze === 'false' ? T('eingr.aus', 'aus') : wert(d.doze)));
            var meld = el('div', 'setting-desc');
            var b = knopf(T('eingr.zuruecksetzen', 'Systemeinstellungen zurücksetzen (vor Deinstallation)'));
            b.onclick = function () {
                if (!window.confirm(T('eingr.frage', 'Systemeinstellungen zurücksetzen? „Bildschirm bleibt beim Laden an“ geht auf den Ursprung, Energiesparen wieder an. Panoptes läuft weiter.'))) return;
                b.disabled = true;
                api('POST', '/api/system/eingriffe/zuruecksetzen').then(function (r) {
                    if (r._s === 403) { meld.textContent = T('notz.nur_im_auto', 'Nur im Auto bzw. angemeldet einstellbar, nicht über ein gekoppeltes Gerät.'); b.disabled = false; return; }
                    toast(r.success ? T('eingr.ok', 'Zurückgesetzt.') : T('eingr.fehler', 'Zurücksetzen hat nicht vollständig geklappt.'), !!r.success);
                    zeichne(r);   // Antwort = zurückgelesener Stand
                }).catch(function () { b.disabled = false; meld.textContent = T('eingr.fehler', 'Zurücksetzen hat nicht vollständig geklappt.'); });
            };
            inhalt.appendChild(b);
            inhalt.appendChild(meld);
        }
        fetch('/api/system/eingriffe', { cache: 'no-store' })
            .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._s = r.status; return j; }); })
            .catch(function () { return null; })
            .then(zeichne);
    }

    window.PanoptesNotzugang = { init: notzugang };
    window.PanoptesEingriffe = { init: eingriffe };
})();
