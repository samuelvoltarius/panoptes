/**
 * Panoptes – Haftungshinweis für Assistenzsysteme (0.1.37, Alfred 03.11.2026: „das sollen sie selbst entscheiden“).
 *
 *   PanoptesAssistenz.pruefe(antwort)   → Promise<bool>: zeigt den Hinweis, wenn die Antwort grund "hinweis_fehlt"
 *                                         trägt; true = bestätigt (dann den Vorgang wiederholen)
 *   PanoptesAssistenz.zeigen()          → Promise<bool>: Hinweis zeigen und bestätigen (POST /api/assistenz/hinweis)
 *   PanoptesAssistenz.schaltetAb(regel) → Schlüssel des Systems, wenn die Regel ein Assistenzsystem ausschaltet
 *
 * Ausschalten ist erlaubt, aber nur nach einmaliger Bestätigung (Textversion vom Kern). Einschalten braucht nie
 * einen Hinweis. SCHALTER muss zu AssistenzHinweis.SCHALTER im Kern passen (AssistenzHinweisTest). ES5, Chromium 74.
 */
(function () {
    'use strict';

    var SCHALTER = ['adas_slw', 'adas_islc', 'adas_tsr', 'adas_bsd', 'adas_rcta', 'adas_fcta', 'adas_tla', 'adas_dow',
        'adas_rcw', 'adas_elka', 'adas_rctb', 'adas_fctb', 'esp_control', 'itac', 'setting_esp_assist',
        'setting_lane_keeping'];
    var NUR_EIN = ['adas_aeb', 'setting_aeb'];

    function T(k, fb) {
        try { if (window.BYD && BYD.i18n && BYD.i18n.t) { var v = BYD.i18n.t(k); if (v && v !== k) return v; } } catch (e) {}
        return fb;
    }
    function drin(a, x) { for (var i = 0; i < a.length; i++) if (a[i] === x) return true; return false; }

    /** Wie AssistenzHinweis.abschaltungAktion im Kern. */
    function aktionSchaltetAb(typ, v) {
        v = v || {};
        var p = String(v.payload == null ? '' : v.payload).trim();
        if (drin(SCHALTER, typ) || drin(NUR_EIN, typ)) return p.toLowerCase() === 'on' ? null : typ;
        if (typ === 'lane_assist') return p === '3' ? null : typ;
        if (typ === 'adas_fcw') return (p === '1' || p === '2' || p === '3') ? null : typ;
        if (typ === 'cpd' || typ === 'adas_cpd') {
            var c = String((typ === 'cpd' ? v.action : v.payload) == null ? '' : (typ === 'cpd' ? v.action : v.payload)).trim();
            return (c === '1' || c === '3' || c.toLowerCase() === 'on') ? null : 'cpd';
        }
        return null;
    }

    function liste(a, gruppen, gesehen, tiefe) {
        if (!a || !a.length) return null;
        if (tiefe > 12) return 'zu_tief';
        for (var i = 0; i < a.length; i++) {
            var x = a[i];
            if (!x) continue;
            var s = aktionSchaltetAb(String(x.type || '').trim(), x.variables);
            if (s) return s;
            s = liste(x.childActions, gruppen, gesehen, tiefe + 1) || liste(x.elseActions, gruppen, gesehen, tiefe + 1);
            if (s) return s;
            if (String(x.type || '').toLowerCase() === 'actiongroup' && gruppen && x.variables && x.variables.groupId) {
                var gid = x.variables.groupId;
                if (!gesehen[gid] && gruppen[gid]) {
                    gesehen[gid] = 1;
                    s = liste(gruppen[gid].actions, gruppen, gesehen, tiefe + 1);
                    if (s) return s;
                }
            }
        }
        return null;
    }

    function schaltetAb(regel, gruppen) {
        if (!regel) return null;
        var g = {};
        return liste(regel.actions, gruppen, g, 0) || liste(regel.elseActions, gruppen, g, 0);
    }

    function braucht(antwort) {
        return !!(antwort && antwort.grund === 'hinweis_fehlt');
    }

    var offen = null;

    function zeigen() {
        if (offen) return offen;
        offen = new Promise(function (fertig) {
            var version = '';
            fetch('/api/assistenz/hinweis', { cache: 'no-store', credentials: 'same-origin' })
                .then(function (r) { return r.json(); })
                .then(function (s) { version = (s && s.hinweisVersion) || ''; if (s && s.hinweisBestaetigt) { schliessen(true); } else { bauen(); } })
                .catch(function () { schliessen(false); });

            var huelle = null;
            function schliessen(ok) {
                try { if (huelle && huelle.parentNode) huelle.parentNode.removeChild(huelle); } catch (e) {}
                offen = null;
                fertig(!!ok);
            }
            function bauen() {
                huelle = document.createElement('div');
                huelle.className = 'assistenz-hinweis';
                huelle.setAttribute('role', 'dialog');
                huelle.setAttribute('aria-modal', 'true');
                huelle.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:10050;background:rgba(0,0,0,.72);overflow:auto;';
                var box = document.createElement('div');
                box.style.cssText = 'max-width:640px;margin:8vh auto 0;background:var(--bg-elevated,#10293a);color:var(--text-primary,#fff);'
                    + 'border-radius:12px;padding:22px;font-size:18px;line-height:1.5;';
                var titel = document.createElement('div');
                titel.style.cssText = 'font-weight:700;font-size:20px;margin-bottom:10px;';
                titel.textContent = T('assistenz.hinweis_titel', 'Sicherheitssystem ausschalten');
                var text = document.createElement('div');
                text.textContent = T('assistenz.hinweis_text', 'Du schaltest ein Sicherheitssystem deines Autos aus. '
                    + 'In der EU muss es sich bei jedem Start wieder einschalten. Schaltest du es dauerhaft oder automatisch aus, '
                    + 'kann das die Zulassung, deine Versicherung und deine Haftung betreffen. Du entscheidest und bist selbst '
                    + 'verantwortlich. Panoptes übernimmt keine Haftung.');
                var fehler = document.createElement('div');
                fehler.style.cssText = 'display:none;margin-top:10px;font-weight:700;color:var(--status-warning,#FFC266);';
                fehler.textContent = T('assistenz.hinweis_fehler', 'Konnte nicht gespeichert werden. Bitte nochmal versuchen.');
                var ja = document.createElement('button');
                ja.type = 'button';
                ja.className = 'btn btn-primary';
                ja.style.cssText = 'display:block;width:100%;min-height:56px;margin-top:16px;font-size:18px;font-weight:700;';
                ja.textContent = T('assistenz.hinweis_knopf', 'Verstanden, ich entscheide selbst');
                var nein = document.createElement('button');
                nein.type = 'button';
                nein.className = 'btn';
                nein.style.cssText = 'display:block;width:100%;min-height:56px;margin-top:10px;font-size:18px;';
                nein.textContent = T('common.cancel', 'Abbrechen');
                ja.onclick = function () {
                    ja.disabled = true;
                    fetch('/api/assistenz/hinweis', {
                        method: 'POST', credentials: 'same-origin',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ version: version })
                    }).then(function (r) { return r.json(); }).then(function (r) {
                        if (r && r.ok) schliessen(true);
                        else { ja.disabled = false; fehler.style.display = 'block'; }
                    }).catch(function () { ja.disabled = false; fehler.style.display = 'block'; });
                };
                nein.onclick = function () { schliessen(false); };
                box.appendChild(titel);
                box.appendChild(text);
                box.appendChild(fehler);
                box.appendChild(ja);
                box.appendChild(nein);
                huelle.appendChild(box);
                document.body.appendChild(huelle);
            }
        });
        return offen;
    }

    function pruefe(antwort) {
        return braucht(antwort) ? zeigen() : Promise.resolve(false);
    }

    window.PanoptesAssistenz = {
        SCHALTER: SCHALTER,
        NUR_EIN: NUR_EIN,
        schaltetAb: schaltetAb,
        braucht: braucht,
        zeigen: zeigen,
        pruefe: pruefe
    };
})();
