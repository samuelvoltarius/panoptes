/*
 * 0.1.26 Klima-Sollwert als Stufe wie im Display des Surf (Foto 23.10.) – Gegenstück zu KlimaStufe.java.
 * Interne Einheit: Stufe 1–17 (= Strich, wie Display und BYD-App). Die API liefert den Sollwert in der SDK-Skala
 * 17–33 (SDK = Stufe + 16). Anzeige: Stufe 1 = „Kühlen Stufe 8“, 9 = „Neutral“, 10–17 = „Heizen Stufe 1–8“.
 * Nie „°C“ für diesen Wert (die echte Außen-/Innentemperatur bleibt in °C).
 *
 *   PanoptesKlimaStufe.text(27)                     → "Heizen Stufe 2"   (Eingabe: SDK-Wert)
 *   PanoptesKlimaStufe.text(27, {ohneStufe: true})  → "Heizen"           (Cloud-Weg, Skala noch nicht belegt)
 *   PanoptesKlimaStufe.balken(el, 27)               → zeichnet den Balken (17 Striche) in el
 */
(function (global) {
    'use strict';
    var SDK_MIN = 17, SDK_MITTE = 25, SDK_MAX = 33, VERSATZ = 16;

    function tr(key, fallback, vars) {
        // wie PanoptesShell.tr
        try {
            if (global.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key, vars);
                if (v && v !== key) return v;
            }
        } catch (e) { /* Fallback bleibt */ }
        var s = fallback;
        if (vars) Object.keys(vars).forEach(function (k) { s = s.split('{' + k + '}').join(vars[k]); });
        return s;
    }

    /** SDK-Wert → ganzzahliger SDK-Wert 17..33, sonst -1. */
    function sdk(v) {
        if (v === null || v === undefined || v === '') return -1;
        v = Number(v);
        if (!isFinite(v)) return -1;
        v = Math.round(v);
        return v < SDK_MIN || v > SDK_MAX ? -1 : v;
    }

    /** SDK-Wert → Stufe 1..17, sonst -1. */
    function stufe(v) {
        var w = sdk(v);
        return w < 0 ? -1 : w - VERSATZ;
    }

    /** Stufe 1..17 → SDK-Wert 17..33, sonst -1. */
    function zuSdk(s) {
        s = Number(s);
        return s >= 1 && s <= 17 && s === Math.round(s) ? s + VERSATZ : -1;
    }

    function art(v) {
        var w = sdk(v);
        if (w < 0) return null;
        return w > SDK_MITTE ? 'heizen' : w < SDK_MITTE ? 'kuehlen' : 'neutral';
    }

    // ---- Modell-Liste (Koordination 28.10.): Stufen nur bei belegten Modellen (heute Dolphin Surf).
    // /api/system/capabilities meldet klima.anzeige = "stufe" | "grad". Bis die Antwort da ist, gilt der zuletzt
    // gemerkte Wert, sonst "grad" (Anzeige wie bisher).
    var MERK = 'panoptes.klimaAnzeige';
    var anzeige = 'grad';
    try { var gm = localStorage.getItem(MERK); if (gm === 'stufe' || gm === 'grad') anzeige = gm; } catch (e) { /* egal */ }
    function laden() {
        if (!global.fetch) return;
        fetch('/api/system/capabilities', { cache: 'no-store' }).then(function (r) { return r.json(); })
            .then(function (c) {
                var a = c && c.klima && c.klima.anzeige;
                if (a !== 'stufe' && a !== 'grad') return;
                var neu = a !== anzeige;
                anzeige = a;
                try { localStorage.setItem(MERK, a); } catch (e) { /* egal */ }
                if (neu && global.document) document.dispatchEvent(new CustomEvent('panoptes:klima-anzeige', { detail: a }));
            }).catch(function () {});
    }
    laden();
    function stufen() { return anzeige === 'stufe'; }

    /** Cloud-Rohwert (mainSettingTemp) → SDK-Wert: Surf Roh = Stufe (Annahme), sonst pyBYD °C − 14. */
    function sdkAusCloudRoh(roh) {
        roh = Number(roh);
        if (!(roh >= 1 && roh <= 17) || roh !== Math.round(roh)) return -1;
        return stufen() ? roh + VERSATZ : roh + 14;
    }

    /** Vorklimatisieren nach Außentemperatur: <15 °C Stufe 12, 15–24 °C Stufe 10, >24 °C Stufe 6, unbekannt 12. */
    function vorklimaSdk(aussenC) {
        var a = Number(aussenC);
        var s = (aussenC === null || aussenC === undefined || !isFinite(a) || a < -60 || a > 70) ? 12
            : a < 15 ? 12 : a <= 24 ? 10 : 6;
        return s + VERSATZ;
    }

    /** Einmaliger Hinweis bei Modellen ohne belegte Stufen: „Zeigt dein Auto Stufen statt Grad? Bitte melden.“ */
    function hinweis(el) {
        if (!el) return;
        var MERK_H = 'panoptes.klimaStufeHinweisZu';
        var zu = false;
        try { zu = localStorage.getItem(MERK_H) === '1'; } catch (e) { /* egal */ }
        if (stufen() || zu) { el.hidden = true; return; }
        if (el.__klimaHinweis) { el.hidden = false; return; }   // schon gebaut
        el.__klimaHinweis = true;
        var sprache = global.BYD && BYD.i18n && typeof BYD.i18n.getLang === 'function' ? BYD.i18n.getLang() : 'de';
        var en = !!sprache && sprache !== 'de';
        el.hidden = false;
        el.innerHTML = '';
        el.appendChild(document.createTextNode(tr('klima_stufe.hint', 'Zeigt dein Auto Stufen statt Grad? Bitte melden.') + ' '));
        var a = document.createElement('a');
        a.href = en ? 'https://byd.serveone.at/en/#wunsch' : 'https://byd.serveone.at/#wunsch';
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = tr('klima_stufe.hint_link', 'Melden');
        a.addEventListener('click', function () { try { localStorage.setItem(MERK_H, '1'); } catch (e) { /* egal */ } });
        el.appendChild(a);
        var x = document.createElement('button');
        x.type = 'button';
        x.textContent = '×';
        x.setAttribute('aria-label', tr('klima_stufe.hint_close', 'Ausblenden'));
        x.style.cssText = 'margin-left:8px;background:none;border:0;color:inherit;font-size:1.1em;cursor:pointer';
        x.addEventListener('click', function () { try { localStorage.setItem(MERK_H, '1'); } catch (e) { /* egal */ } el.hidden = true; });
        el.appendChild(x);
    }

    function text(v, opt) {
        // Modelle ohne belegte Stufen: Anzeige wie bisher in °C
        if (!stufen()) {
            var g = sdk(v);
            return g < 0 ? tr('klima_stufe.unknown', 'unbekannt') : g + ' °C';
        }
        var a = art(v);
        if (!a) return tr('klima_stufe.unknown', 'unbekannt');
        if (a === 'neutral') return tr('klima_stufe.neutral', 'Neutral');
        if (opt && opt.ohneStufe) {
            return a === 'heizen' ? tr('klima_stufe.heat', 'Heizen') : tr('klima_stufe.cool', 'Kühlen');
        }
        var n = Math.abs(sdk(v) - SDK_MITTE);
        return a === 'heizen'
            ? tr('klima_stufe.heat_level', 'Heizen Stufe {n}', { n: n })
            : tr('klima_stufe.cool_level', 'Kühlen Stufe {n}', { n: n });
    }

    /** Balken mit 17 Strichen in el: 8 blau | Mitte | 8 rot; von der Mitte bis zum Wert leuchtet es. */
    function balken(el, v) {
        if (!el) return;
        el.hidden = !stufen();   // den Balken gibt es nur bei belegten Modellen
        if (el.hidden) return;
        var w = sdk(v);
        if (!el.__klimaBalken) {
            el.__klimaBalken = true;
            el.classList.add('klima-balken');
            el.setAttribute('role', 'img');
            var html = '';
            for (var i = SDK_MIN; i <= SDK_MAX; i++) {
                html += '<i data-w="' + i + '" class="' + (i < SDK_MITTE ? 'kalt' : i > SDK_MITTE ? 'warm' : 'mitte') + '"></i>';
            }
            el.innerHTML = html;
        }
        var striche = el.children;
        for (var j = 0; j < striche.length; j++) {
            var sw = Number(striche[j].getAttribute('data-w'));
            var an = w > 0 && ((w >= SDK_MITTE && sw >= SDK_MITTE && sw <= w) || (w < SDK_MITTE && sw <= SDK_MITTE && sw >= w));
            striche[j].classList.toggle('an', an);
            striche[j].classList.toggle('akt', sw === w);
        }
        el.setAttribute('aria-label', text(v));
    }

    function stil() {
        if (!global.document || document.getElementById('klima-stufe-stil')) return;
        var s = document.createElement('style');
        s.id = 'klima-stufe-stil';
        s.textContent =
            '.klima-balken{display:flex;gap:3px;align-items:flex-end;height:28px;max-width:420px;margin:8px 0}' +
            '.klima-balken i{flex:1;height:60%;border-radius:3px;opacity:.25;background:#8a94a6}' +
            '.klima-balken i.kalt{background:#2f80ed}.klima-balken i.warm{background:#e5484d}' +
            '.klima-balken i.mitte{background:#cfd6e4;height:100%}' +
            '.klima-balken i.an{opacity:1}.klima-balken i.akt{height:100%;box-shadow:0 0 0 2px currentColor}' +
            // große Knöpfe Kühler | Neutral | Wärmer (REGEL DAU)
            '.vc-stufe-knoepfe{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:6px}' +
            '.vc-stufe-knoepfe .vc-tile{min-height:56px;font-size:1.05em;font-weight:600}' +
            '.vc-klima-stufe .vc-readout{display:block;font-size:1.3em;font-weight:700;margin-top:4px}';
        (document.head || document.documentElement).appendChild(s);
    }
    stil();

    global.PanoptesKlimaStufe = {
        SDK_MIN: SDK_MIN, SDK_MITTE: SDK_MITTE, SDK_MAX: SDK_MAX, HEIZEN_AB_SDK: 26,
        sdk: sdk, stufe: stufe, zuSdk: zuSdk, art: art, text: text, balken: balken,
        stufen: stufen, sdkAusCloudRoh: sdkAusCloudRoh, vorklimaSdk: vorklimaSdk, hinweis: hinweis
    };
})(typeof window !== 'undefined' ? window : this);
