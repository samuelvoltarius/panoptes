/*
 * Panoptes – Zwischenstopps und Etappen in der Navi-Seite (0.1.38.10, Paket N1).
 *
 * Reine Rechnung ohne Oberfläche (ES5, Chromium 74): die Liste der Zwischenstopps (höchstens 5, wie der Planer
 * MAX_VIA), der `via`-Teil der Plan-Anfrage und die Etappen aus der Planer-Antwort (`zwischenziele` + `stopps`
 * nach `km_auf_route`, also Z und L in Fahrreihenfolge). Gespeichert wird nichts, die Liste lebt nur in der Seite.
 */
(function (root) {
    'use strict';

    /** Höchstens so viele Zwischenstopps nimmt der Planer (planer.py MAX_VIA). */
    var MAX = 5;
    /** Aufenthalt zum Durchtippen, in Minuten. */
    var AUFENTHALT = [0, 15, 30, 60, 120];

    function zahl(x) { var n = Number(x); return isFinite(n) ? n : null; }
    function runden5(x) { return Math.round(x * 100000) / 100000; }

    /** Neuer Stopp aus einem Ort {name, lat, lng|lon}; null, wenn die Koordinate fehlt. */
    function stopp(o) {
        if (!o) return null;
        var lat = zahl(o.lat), lng = zahl(o.lng != null ? o.lng : o.lon);
        if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
        var auf = zahl(o.aufenthalt_min);
        return { name: String(o.name || '').slice(0, 80), lat: lat, lng: lng,
                 aufenthalt_min: auf !== null && auf >= 0 && auf <= 1440 ? Math.round(auf) : 0, laden: !!o.laden };
    }

    /** Liste aus einer fremden Quelle (z. B. `via` von „Neu planen“): nur gültige Stopps, höchstens MAX. */
    function ausListe(roh) {
        var out = [];
        (Array.isArray(roh) ? roh : []).forEach(function (o) {
            var s = stopp(o);
            if (s && out.length < MAX) out.push(s);
        });
        return out;
    }

    function hinzu(liste, o) {
        var s = stopp(o);
        if (!s || liste.length >= MAX) return false;
        liste.push(s);
        return true;
    }

    /** Stopp i um d (−1 hoch, +1 runter) verschieben; false, wenn es nicht geht. */
    function schieben(liste, i, d) {
        var j = i + d;
        if (i < 0 || i >= liste.length || j < 0 || j >= liste.length) return false;
        var x = liste[i]; liste[i] = liste[j]; liste[j] = x;
        return true;
    }

    function weg(liste, i) {
        if (i < 0 || i >= liste.length) return false;
        liste.splice(i, 1);
        return true;
    }

    /** Nächster Aufenthalt nach dem jetzigen (0 → 15 → 30 → 60 → 120 → 0). */
    function naechsterAufenthalt(min) {
        for (var i = 0; i < AUFENTHALT.length; i++) if (AUFENTHALT[i] > (min || 0)) return AUFENTHALT[i];
        return 0;
    }

    /** `via` für POST /api/navi/plan (Planer via_lesen): {lat, lon, name, aufenthalt_min, laden?}; Position auf 5 Stellen wie das Ziel. */
    function viaBody(liste) {
        return (liste || []).map(function (s) {
            var v = { lat: runden5(s.lat), lon: runden5(s.lng), name: s.name, aufenthalt_min: s.aufenthalt_min || 0 };
            if (s.laden) v.laden = true;
            return v;
        });
    }

    /**
     * Etappen aus der Planer-Antwort: jede Etappe endet an einem Zwischenziel (art 'z'), einem Ladestopp (art 'l')
     * oder am Ziel (art 'ziel'). km = Länge der Etappe, akku = Ankunft, weiter = Akku bei der Abfahrt (nach dem Laden).
     * Ohne Zwischenziele: leere Liste (dann bleibt die bisherige Ladestopp-Liste).
     */
    function etappen(d) {
        var zz = (d && Array.isArray(d.zwischenziele)) ? d.zwischenziele : [];
        if (!zz.length) return [];
        var punkte = [];
        zz.forEach(function (z, i) {
            var km = zahl(z.km_auf_route);
            if (km === null) return;
            punkte.push({ art: 'z', nr: z.nr || i + 1, name: z.name || '', km: km, lat: z.lat, lon: z.lon,
                          akku: zahl(z.soc_ankunft), weiter: zahl(z.soc_abfahrt), aufenthalt_min: zahl(z.aufenthalt_min) || 0,
                          laden: z.laden || null, ankunft: z.ankunft_ca || null, ord: 0 });
        });
        ((d && d.stopps) || []).forEach(function (st, i) {
            var s = st && st.saeule;
            var km = s ? zahl(s.km_auf_route) : null;
            if (km === null) return;
            punkte.push({ art: 'l', nr: i + 1, name: s.name || '', km: km, lat: s.lat, lon: s.lon,
                          akku: zahl(st.soc_vorher), weiter: zahl(st.soc_nachher), ladedauer_min: zahl(st.ladedauer_min),
                          stopp: st, ord: 1 });
        });
        // gleiche Stelle: erst ankommen (Zwischenziel), dann laden - wie plan_vertrag._etappen des Planers
        punkte.sort(function (a, b) { return a.km - b.km || a.ord - b.ord; });
        var gesamt = zahl(d.route && d.route.km);
        punkte.push({ art: 'ziel', name: '', km: gesamt !== null ? gesamt : (punkte.length ? punkte[punkte.length - 1].km : 0),
                      akku: zahl(d.soc_ankunft), ord: 2 });
        var vorher = 0;
        punkte.forEach(function (p) {
            p.etappe_km = Math.max(0, p.km - vorher);
            vorher = p.km;
            delete p.ord;
        });
        return punkte;
    }

    var api = { MAX: MAX, AUFENTHALT: AUFENTHALT, stopp: stopp, ausListe: ausListe, hinzu: hinzu, schieben: schieben,
                weg: weg, naechsterAufenthalt: naechsterAufenthalt, viaBody: viaBody, etappen: etappen };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.PanoptesEtappen = api;
})(this);
