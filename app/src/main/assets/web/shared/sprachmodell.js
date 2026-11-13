/**
 * Panoptes — Sprachmodell laden (0.1.44, P2b). In Einstellungen → Alle Einstellungen → Datenfreigaben, unter den Sprachbefehlen.
 * Laden NUR auf Tipp, nie automatisch: Beim Öffnen fragt die Seite nur den Stand ab (Kern, kein Internet).
 * Geladen wird in der App (0.1.43.2, Fix E2E M2: der Kern darf ab Android 11 den App-Ordner nicht anlegen) – von der festen
 * Vosk-Quelle oder aus dem Ordner Download (Weg von Hand), immer mit der fest eingetragenen SHA-256; Abweichung = verworfen.
 *
 *   GET  /api/sprache/v1/modell                           → { modelle:{de:{da,mb,noetigMb}}, laden:{sprache,phase,grund,bytes,gesamt} }
 *   POST /api/sprache/v1/modell {aktion:'laden', sprache} → startet (409 mit grund: schon_da, kein_platz, laeuft_schon)
 *   POST /api/sprache/v1/modell {aktion:'abbrechen'}
 *
 * Nur am Autobildschirm (der Kern lässt nur die Panoptes-App direkt am Auto zu). ES5 (WebView 74).
 */
(function () {
    'use strict';
    function t(k, f) { return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(k, f) : f; }
    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }
    var KNOPF = 'min-height:56px;padding:10px 16px;font-size:17px;font-weight:700;border-radius:12px;cursor:pointer;margin:6px 8px 0 0;'
        + 'border:2px solid var(--border-default, var(--outline-variant));background:var(--bg-elevated, var(--surface-container));'
        + 'color:var(--text-primary, var(--on-surface));';
    var PFAD = '/api/sprache/v1/modell';
    var NAMEN = { de: 'Deutsch', en: 'English' };

    function api(methode, body) {
        var o = { method: methode, cache: 'no-store', headers: { 'Content-Type': 'application/json' } };
        if (body) o.body = JSON.stringify(body);
        return fetch(PFAD, o).then(function (r) {
            return r.json().then(function (j) { return { status: r.status, j: j || {} }; },
                function () { return { status: r.status, j: {} }; });
        });
    }

    function grundText(g, m) {
        var x = {
            pruefsumme: t('sprachmodell.fehler_pruefsumme', 'Die Prüfsumme stimmt nicht. Die Datei wurde verworfen, nichts wurde installiert.'),
            groesse: t('sprachmodell.fehler_pruefsumme', 'Die Prüfsumme stimmt nicht. Die Datei wurde verworfen, nichts wurde installiert.'),
            kein_platz: t('sprachmodell.fehler_platz', 'Zu wenig Speicher. Nötig sind etwa {mb} MB frei.').replace('{mb}', m ? m.noetigMb : '?'),
            netz: t('sprachmodell.fehler_netz', 'Laden hat nicht geklappt (Internet?). Nichts wurde installiert.'),
            entpacken: t('sprachmodell.fehler_entpacken', 'Entpacken hat nicht geklappt. Nichts wurde installiert.'),
            schon_da: t('sprachmodell.da', 'vorhanden'),
            laeuft_schon: t('sprachmodell.laeuft_schon', 'Es lädt schon ein Modell.'),
            nicht_app: t('sprachmodell.nur_am_auto', 'Laden geht nur hier am Autobildschirm.'),
            nicht_lokal: t('sprachmodell.nur_am_auto', 'Laden geht nur hier am Autobildschirm.'),
            ordner: t('sprachmodell.fehler_ordner', 'Panoptes kann den Ordner für das Sprachmodell nicht anlegen. Es wurde nichts geladen.'),
            sprache: t('sprachmodell.fehler_sprache', 'Für diese Sprache gibt es kein Sprachmodell zum Laden.'),
            kein_dienst: t('sprachmodell.fehler_dienst', 'Panoptes konnte das Laden nicht starten. Bitte Panoptes einmal öffnen und erneut tippen.'),
            datei_fehlt: t('sprachmodell.fehler_datei', 'Die Datei liegt nicht im Ordner Download.'),
            datei: t('sprachmodell.fehler_datei', 'Die Datei liegt nicht im Ordner Download.')
        };
        return x[g] || t('sprachmodell.fehler_netz', 'Laden hat nicht geklappt (Internet?). Nichts wurde installiert.');
    }

    function mount(box) {
        var takt = null;
        box.appendChild(el('div', 'font-size:17px;font-weight:700;margin:6px 0;', t('sprachmodell.titel', 'Sprachmodell für Sprachbefehle')));
        box.appendChild(el('div', 'font-size:15px;line-height:1.5;opacity:.85;', t('sprachmodell.lead',
            'Lädt nur, wenn du tippst – von der offiziellen Vosk-Quelle, mit fester Prüfsumme. Danach erkennt Panoptes die Befehle ohne Internet.')));
        var liste = el('div', 'margin-top:6px;');
        box.appendChild(liste);
        var balken = el('div', 'height:14px;border-radius:7px;background:var(--surface-container-high, #333);margin-top:10px;overflow:hidden;display:none;');
        var fuell = el('div', 'height:100%;width:0;background:#B5F23D;');
        balken.appendChild(fuell);
        box.appendChild(balken);
        var meld = el('div', 'font-size:16px;line-height:1.5;margin-top:8px;min-height:24px;');
        meld.id = 'sprachModellMeldung';
        meld.setAttribute('aria-live', 'polite');
        box.appendChild(meld);
        var hand = el('div', 'font-size:14px;line-height:1.5;opacity:.75;margin-top:6px;');
        box.appendChild(hand);

        function zeichne(s) {
            liste.textContent = '';
            var dateien = Object.keys(s.modelle || {}).map(function (sp) { return s.modelle[sp].name + '.zip'; }).join(', ');
            hand.textContent = t('sprachmodell.hand', 'Oder von Hand: die Datei {datei} in den Ordner Download legen (z. B. per adb push nach /sdcard/Download), dann „Aus Download übernehmen“ tippen.')
                .replace('{datei}', dateien);
            var mo = s.modelle || {}, l = s.laden || {}, laeuft = l.phase === 'laedt' || l.phase === 'prueft' || l.phase === 'entpackt';
            Object.keys(mo).forEach(function (sp) {
                var m = mo[sp], zeile = el('div', 'margin-top:6px;');
                if (m.da) {
                    zeile.appendChild(el('div', 'font-size:16px;', (NAMEN[sp] || sp) + ': ✓ ' + t('sprachmodell.da', 'vorhanden')));
                } else {
                    var b = el('button', KNOPF, t('sprachmodell.laden', 'Sprachmodell laden ({sprache}, ca. {mb} MB)')
                        .replace('{sprache}', NAMEN[sp] || sp).replace('{mb}', m.mb));
                    b.type = 'button';
                    b.disabled = laeuft;
                    b.setAttribute('data-sprache', sp);
                    b.onclick = function () { laden(sp, m); };
                    zeile.appendChild(b);
                    if (m.handDatei) {
                        var h = el('button', KNOPF, t('sprachmodell.aus_download', 'Aus Download übernehmen ({sprache})').replace('{sprache}', NAMEN[sp] || sp));
                        h.type = 'button';
                        h.disabled = laeuft;
                        h.setAttribute('data-hand', sp);
                        h.onclick = function () { laden(sp, m, 'hand'); };
                        zeile.appendChild(h);
                    }
                }
                liste.appendChild(zeile);
            });
            if (laeuft) {
                var ab = el('button', KNOPF, t('sprachmodell.abbrechen', 'Abbrechen'));
                ab.type = 'button';
                ab.id = 'sprachModellAbbrechen';
                ab.onclick = function () { api('POST', { aktion: 'abbrechen' }); };
                liste.appendChild(ab);
            }
            balken.style.display = laeuft ? '' : 'none';
            var p = l.gesamt > 0 ? Math.min(100, Math.round(l.bytes * 100 / l.gesamt)) : 0;
            fuell.style.width = p + '%';
            if (l.phase === 'laedt') meld.textContent = t('sprachmodell.laedt', 'Lädt … {p} %').replace('{p}', p);
            else if (l.phase === 'prueft') meld.textContent = t('sprachmodell.prueft', 'Prüft die Prüfsumme …');
            else if (l.phase === 'entpackt') meld.textContent = t('sprachmodell.entpackt', 'Entpackt …');
            else if (l.phase === 'fertig') meld.textContent = t('sprachmodell.fertig', 'Fertig. Die Sprachbefehle erkennen jetzt ohne Internet.');
            else if (l.phase === 'abgebrochen') meld.textContent = t('sprachmodell.abgebrochen', 'Abgebrochen. Nichts wurde installiert.');
            else if (l.phase === 'fehler') meld.textContent = grundText(l.grund, mo[l.sprache]);
            meld.style.color = l.phase === 'fehler' ? 'var(--status-danger, var(--status-error))' : '';
            if (laeuft && !takt) takt = setInterval(abfragen, 1000);
            if (!laeuft && takt) { clearInterval(takt); takt = null; }
        }

        // Nur Stand lesen (Kern, kein Internet). Laden startet allein laden() – auf Tipp.
        function abfragen() {
            api('GET').then(function (x) {
                if (x.status === 200) zeichne(x.j);
                else { liste.textContent = ''; meld.textContent = grundText(x.j.grund); }
            }, function () { });
        }

        function laden(sp, m, quelle) {
            api('POST', { aktion: 'laden', sprache: sp, quelle: quelle === 'hand' ? 'hand' : 'netz' }).then(function (x) {
                if (x.status !== 200) meld.textContent = grundText(x.j.grund, m);
                abfragen();
            }, function () { meld.textContent = grundText('netz'); });
        }

        abfragen();
        // Die App meldet ihren Stand erst auf Nachfrage des Kerns (kein Netz): einmal kurz danach neu lesen.
        setTimeout(abfragen, 2000);
    }

    function boot() {
        var box = document.getElementById('sprachModell');
        if (box && !box.childNodes.length) mount(box);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
