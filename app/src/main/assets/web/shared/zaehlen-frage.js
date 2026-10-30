/**
 * Panoptes 0.1.31 – einmalige Frage an alle, die schon anonym mitzählen (Regel 2): Alte Zustimmungen gelten nicht für
 * Modell und DiLink. Der Kern sagt, ob die Frage offen ist und ob sie jetzt kommen darf (nur im Stand).
 * Solange nicht gefragt werden darf, bleibt der Merker aus und die nächste Seite prüft neu. Nichts am Auto wird
 * ausgelöst. ES5, deutscher Rückfall eingebaut.
 */
(function () {
    'use strict';

    var URL = '/api/rueckmeldung/zaehlen';
    var MERKER = 'pn.zaehlfrage.ok';

    function t(k, fb) {
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(k);
                if (v && v !== k) return v;
            }
        } catch (e) {}
        return fb;
    }

    function fertig() {
        try { window.sessionStorage.setItem(MERKER, '1'); } catch (e) {}
    }

    function knopf(text, haupt) {
        var b = document.createElement('button');
        b.type = 'button';
        b.textContent = text;
        b.style.cssText = 'flex:1 1 220px;min-height:64px;border:0;border-radius:12px;font:inherit;font-size:19px;font-weight:700;cursor:pointer;'
            + (haupt ? 'color:#06202f;background:var(--brand-primary);' : 'color:var(--text-primary);background:var(--bg-hover);');
        return b;
    }

    function zeige() {
        var hg = document.createElement('div');
        hg.id = 'pnZaehlFrage';
        hg.setAttribute('role', 'dialog');
        hg.setAttribute('aria-modal', 'true');
        hg.style.cssText = 'position:fixed;inset:0;z-index:9000;display:flex;align-items:center;justify-content:center;padding:16px;background:rgba(0,0,0,.6)';
        var box = document.createElement('div');
        box.style.cssText = 'max-width:620px;width:100%;box-sizing:border-box;padding:24px;border-radius:16px;color:var(--text-primary);background:var(--bg-elevated);border:1px solid var(--border-subtle)';
        var h = document.createElement('h2');
        h.style.cssText = 'margin:0 0 12px;font-size:24px';
        h.textContent = t('rueckmeldung.zaehlfrage.titel', 'Automodell mitschicken?');
        var p = document.createElement('p');
        p.style.cssText = 'margin:0 0 20px;font-size:18px;line-height:1.5';
        p.textContent = t('rueckmeldung.zaehlfrage.text', 'Du zählst schon anonym mit. Neu: Panoptes kann auch dein Automodell (zum Beispiel Seal) und die DiLink-Stufe mitschicken. Keine Fahrgestellnummer, kein Kennzeichen, kein Ort, keine Kilometer. So sehen wir, welche Autos Panoptes nutzen. Ausschalten geht jederzeit und löscht es auch am Server.');
        var reihe = document.createElement('div');
        reihe.style.cssText = 'display:flex;flex-wrap:wrap;gap:12px';
        var ja = knopf(t('rueckmeldung.zaehlfrage.ja', 'Ja, Modell mitschicken'), true);
        var nein = knopf(t('rueckmeldung.zaehlfrage.nein', 'Nein, so lassen'), false);
        function antwort(wert) {
            ja.disabled = nein.disabled = true;
            fetch(URL, {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ antwort: wert })
            }).then(function (r) {
                if (r.ok) fertig();
                hg.parentNode.removeChild(hg);
            }).catch(function () {
                hg.parentNode.removeChild(hg);
            });
        }
        ja.onclick = function () { antwort('ja'); };
        nein.onclick = function () { antwort('nein'); };
        reihe.appendChild(ja);
        reihe.appendChild(nein);
        box.appendChild(h);
        box.appendChild(p);
        box.appendChild(reihe);
        hg.appendChild(box);
        document.body.appendChild(hg);
    }

    function pruefen() {
        fetch(URL, { credentials: 'same-origin', cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (!d) return;
                if (!d.offen) { fertig(); return; }
                if (d.fragen && !document.getElementById('pnZaehlFrage')) zeige();
            })
            .catch(function () {});
    }

    window.PanoptesZaehlFrage = { pruefen: pruefen };
})();
