/*
 * 0.1.43.20 (P4): Pflichtfrage beim ersten Einschalten einer Tacho-Projektion.
 * „Was siehst du am Tacho? – Warnleuchten und Tempo sichtbar?“
 * Nein, „Weiß nicht“ oder keine Antwort (Frist) → Panoptes holt sofort die BYD-Anzeige zurück (18 → 0),
 * die Funktion bleibt aus. Der Server entscheidet (TachoSicht); diese Datei zeigt nur die Frage.
 * Ablauf nach DashCast 1.9.80/1.9.81 (MIT-Lizenz), Code eigen.
 */
(function () {
    'use strict';
    function t(key, fallback) {
        return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(key, fallback) : fallback;
    }
    var box = null, timer = null, aus = false, rest = 0;

    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }

    function knopf(text, farbe, antwort) {
        var b = el('button', 'flex:1;min-height:64px;font-size:20px;border-radius:12px;border:2px solid ' + farbe
            + ';background:transparent;color:' + farbe + ';margin:6px;cursor:pointer;', text);
        b.type = 'button';
        b.onclick = function () { senden(antwort); };
        return b;
    }

    function zeigen(d) {
        if (box) { aktualisieren(d); return; }
        box = el('div', 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:99999;background:rgba(0,0,0,.78);display:flex;'
            + 'align-items:center;justify-content:center;padding:16px;');
        box.setAttribute('role', 'alertdialog');
        box.setAttribute('aria-modal', 'true');
        var karte = el('div', 'max-width:640px;width:100%;background:var(--surface-1,#1c1c1e);color:var(--text-1,#fff);'
            + 'border-radius:16px;padding:22px;box-shadow:0 8px 40px rgba(0,0,0,.6);');
        karte.appendChild(el('div', 'font-size:22px;font-weight:600;margin-bottom:8px;',
            t('vehicle_control.sicht_frage', 'Was siehst du am Tacho?')));
        karte.appendChild(el('div', 'font-size:26px;font-weight:700;margin:10px 0 14px;',
            t('vehicle_control.sicht_pflicht', 'Warnleuchten und Tempo sichtbar?')));
        var reihe = el('div', 'display:flex;flex-wrap:wrap;');
        reihe.appendChild(knopf(t('vehicle_control.sicht_ja', 'Ja, beides sichtbar'), 'var(--status-ok,#34c759)', 'ja'));
        reihe.appendChild(knopf(t('vehicle_control.sicht_nein', 'Nein'), 'var(--status-danger,#ff3b30)', 'nein'));
        reihe.appendChild(knopf(t('vehicle_control.sicht_weiss_nicht', 'Weiß nicht'), 'var(--text-2,#aaa)', 'unbekannt'));
        karte.appendChild(reihe);
        var rz = el('div', 'margin-top:12px;font-size:16px;opacity:.85;');
        rz.id = 'pnSichtRest';
        karte.appendChild(rz);
        karte.appendChild(el('div', 'margin-top:6px;font-size:15px;opacity:.8;',
            t('vehicle_control.sicht_hinweis', 'Keine Antwort zählt als „unbekannt“: Panoptes holt dann die BYD-Anzeige zurück und lässt die Funktion aus.')));
        box.appendChild(karte);
        document.body.appendChild(box);
        aktualisieren(d);
    }

    function aktualisieren(d) {
        rest = d && d.restSekunden ? d.restSekunden : 0;
        var rz = document.getElementById('pnSichtRest');
        if (rz) rz.textContent = t('vehicle_control.sicht_rest', 'Noch {s} Sekunden.').replace('{s}', String(rest));
    }

    function schliessen() {
        if (box && box.parentNode) box.parentNode.removeChild(box);
        box = null;
    }

    function senden(antwort) {
        fetch('/api/tacho/sicht', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ antwort: antwort }) })
            .then(function (r) { return r.json(); })
            .catch(function () { return null; })
            .then(function () { schliessen(); pruefen(); });
    }

    function pruefen() {
        if (aus) return;
        fetch('/api/tacho/sicht', { cache: 'no-store' })
            .then(function (r) {
                if (r.status === 403 || r.status === 404) { aus = true; return null; }   // Handy-Web/alt: nichts tun
                return r.json();
            })
            .then(function (d) { if (d && d.offen) zeigen(d); else schliessen(); })
            .catch(function () {})
            .then(function () {
                if (aus) return;
                clearTimeout(timer);
                timer = setTimeout(pruefen, box ? 1000 : 4000);
            });
    }

    function post(pfad, daten) {
        return fetch(pfad, { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(daten || {}) })
            .then(function (r) { return r.json().catch(function () { return { ok: false, status: r.status }; }); })
            .catch(function () { return { ok: false }; });
    }

    // Einmaliger Hinweis nach dem Update: Tacho-Anzeige pausiert, weil das Auto noch nicht geprüft ist.
    function pausiertZeigen() {
        var h = el('div', 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:99998;background:rgba(0,0,0,.7);'
            + 'display:flex;align-items:center;justify-content:center;padding:16px;');
        h.setAttribute('role', 'alertdialog');
        var k = el('div', 'max-width:620px;width:100%;background:var(--surface-1,#1c1c1e);color:var(--text-1,#fff);'
            + 'border-radius:16px;padding:22px;');
        k.appendChild(el('div', 'font-size:22px;font-weight:600;margin-bottom:10px;',
            t('vehicle_control.pausiert_titel', 'Tacho-Anzeige ist aus Sicherheitsgründen pausiert')));
        k.appendChild(el('div', 'font-size:17px;line-height:1.45;',
            t('vehicle_control.pausiert_text', 'Panoptes zeigt nur noch dann etwas am Tacho, wenn sicher ist, dass Warnleuchten und Tempo sichtbar bleiben. Dein Auto ist dafür noch nicht geprüft. So schaltest du sie wieder ein: Apps & Display → Display, bei „Tacho“ auf „Für mein Auto freischalten“ tippen, im Stand. Panoptes fragt dann einmal, ob Warnleuchten und Tempo sichtbar sind.')));
        var ok = el('button', 'margin-top:16px;min-height:56px;font-size:18px;border-radius:12px;padding:0 24px;cursor:pointer;',
            t('vehicle_control.pausiert_ok', 'Verstanden'));
        ok.type = 'button';
        ok.className = 'btn btn-primary';
        ok.onclick = function () { post('/api/tacho/sicht/hinweis'); if (h.parentNode) h.parentNode.removeChild(h); };
        k.appendChild(ok);
        h.appendChild(k);
        document.body.appendChild(h);
    }

    // Tester-Freigabe auf der Display-Seite (nur wenn das Auto eine Projektion anbietet und nicht in der Liste steht).
    function testerZeigen(d) {
        var ort = document.getElementById('tachoTester');
        if (!ort) return;
        var code = d && d.kohorte && d.kohorte.urteil ? d.kohorte.urteil.code : '';
        var neuPruefen = code === 'sicht_nein' || code === 'sicht_unbekannt';
        if (!d || (!d.testerAngebot && !d.testerAktiv && !neuPruefen)) { ort.hidden = true; return; }
        ort.hidden = false;
        ort.textContent = '';
        ort.appendChild(el('div', 'font-weight:600;font-size:18px;margin-bottom:6px;',
            t('vehicle_control.tester_titel', 'Für dein Auto noch nicht geprüft')));
        var meldung = el('div', 'margin-top:8px;font-size:16px;');
        meldung.setAttribute('aria-live', 'polite');
        if (d.testerAktiv && !neuPruefen) {
            ort.appendChild(el('p', 'font-size:16px;', t('vehicle_control.tester_aktiv',
                'Du hast die Tacho-Anzeige für dein Auto freigeschaltet. Nach einem BYD-Update ist sie wieder aus.')));
            var aus = el('button', 'min-height:56px;font-size:16px;margin:4px 8px 4px 0;',
                t('vehicle_control.tester_zuruecknehmen', 'Freigabe zurücknehmen'));
            aus.type = 'button'; aus.className = 'btn';
            aus.onclick = function () { post('/api/tacho/sicht/tester', { an: false }).then(laden); };
            ort.appendChild(aus);
        } else if (neuPruefen) {
            ort.appendChild(el('p', 'font-size:16px;', t('vehicle_control.tester_gesperrt',
                'Beim letzten Mal waren Warnleuchten oder Tempo nicht sicher sichtbar. Deshalb ist die Tacho-Anzeige aus.')));
            var neu = el('button', 'min-height:56px;font-size:16px;margin:4px 8px 4px 0;',
                t('vehicle_control.sicht_neu', 'Im Stand neu prüfen'));
            neu.type = 'button'; neu.className = 'btn';
            neu.onclick = function () {
                post('/api/tacho/sicht/neu').then(function (r) {
                    meldung.textContent = r && r.ok ? t('vehicle_control.sicht_neu_ok', 'Beim nächsten Einschalten fragt Panoptes wieder.')
                        : t('vehicle_control.tester_nur_stand', 'Nur im Stand (P) möglich.');
                    laden();
                });
            };
            ort.appendChild(neu);
        } else {
            ort.appendChild(el('p', 'font-size:16px;line-height:1.45;', t('vehicle_control.tester_text',
                'Panoptes weiß nicht, ob bei deinem Auto Warnleuchten und Tempo sichtbar bleiben. Du kannst es selbst prüfen: im Stand, Blick auf den Tacho. Panoptes zeigt kurz eine leere Fläche am Tacho und fragt dich sofort. Bei „Nein“ oder ohne Antwort kommt die BYD-Anzeige zurück, und die Funktion bleibt aus.')));
            var lab = el('label', 'display:flex;align-items:center;gap:10px;font-size:16px;margin:6px 0;min-height:48px;');
            var haken = document.createElement('input');
            haken.type = 'checkbox';
            haken.style.cssText = 'width:28px;height:28px;';
            lab.appendChild(haken);
            lab.appendChild(document.createTextNode(t('vehicle_control.tester_haken', 'Ich stehe in P mit angezogener Feststellbremse.')));
            ort.appendChild(lab);
            var an = el('button', 'min-height:56px;font-size:16px;margin:4px 8px 4px 0;',
                t('vehicle_control.tester_knopf', 'Für mein Auto freischalten und prüfen'));
            an.type = 'button'; an.className = 'btn btn-primary';
            an.onclick = function () {
                if (!haken.checked) { meldung.textContent = t('vehicle_control.tester_haken_noetig', 'Bitte zuerst bestätigen: P und Feststellbremse.'); return; }
                post('/api/tacho/sicht/tester', { an: true }).then(function (r) {
                    if (!r || !r.ok) { meldung.textContent = t('vehicle_control.tester_nur_stand', 'Nur im Stand (P) möglich.'); return; }
                    meldung.textContent = r.pruefung === 'probe'
                        ? t('vehicle_control.tester_probe', 'Schau jetzt auf den Tacho. Panoptes fragt gleich.')
                        : t('vehicle_control.tester_erster_start', 'Freigeschaltet. Panoptes fragt beim ersten Start einer Tacho-Funktion.');
                    pruefen();
                });
            };
            ort.appendChild(an);
        }
        ort.appendChild(meldung);
    }

    function laden() {
        fetch('/api/tacho/sicht?voll=1', { cache: 'no-store' })
            .then(function (r) { if (r.status === 403 || r.status === 404) { aus = true; return null; } return r.json(); })
            .then(function (d) {
                if (!d) return;
                if (d.pausiertHinweis) pausiertZeigen();
                testerZeigen(d);
            })
            .catch(function () {});
    }

    function start() {
        pruefen();
        if (document.getElementById('tachoTester') || /index\.html|\/$/.test(location.pathname)) laden();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
