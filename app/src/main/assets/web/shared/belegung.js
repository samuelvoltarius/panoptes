/*
 * Panoptes – Live-Belegung der Ladesäulen in der Navi-Seite (03.11.2026).
 *
 * Datenschutz: Die Umkreissuche schickt die Autoposition nur auf 2 Nachkommastellen (~1 km) an den eigenen
 * Planer, mit 1 km mehr Radius. Welche Säulen die nächsten sind, rechnet erst das Gerät mit der genauen
 * Position aus (Feinsortierung lokal). Keine Daten → keine Anzeige.
 */
(function (root) {
    'use strict';

    /** Zusätzlicher Radius, der den Rundungsversatz abdeckt (max. ~0,7 km bei 2 Nachkommastellen). */
    var RUNDUNG_KM = 1;
    /** So viele Stationen holen, damit die nächsten sicher dabei sind (der Planer sortiert nach Preis). */
    var LIMIT = 200;

    function runden2(x) { return Math.round(x * 100) / 100; }

    /**
     * Body für POST /api/navi/umkreis: gerundet, Radius + 1 km, genug Stationen für die lokale Auswahl.
     * extra.kwh = Lademenge für die Preise (Standard 20), extra.meineKarten = Preise mit „Meine Ladekarten“ (der Kern
     * setzt dann die Tarif-IDs ein, der Merker geht nicht hinaus).
     */
    function anfrage(lat, lon, radiusKm, extra) {
        var b = { lat: runden2(+lat), lon: runden2(+lon), radius: (radiusKm || 10) + RUNDUNG_KM, limit: LIMIT,
                  kwh: extra && extra.kwh > 0 ? +extra.kwh : 20, nur_frei: 0, nur_mit_preis: 0, min_kw: 0 };
        if (extra && extra.meineKarten) b.meine_karten = true;
        return b;
    }

    function distKm(lat1, lon1, lat2, lon2) {
        var r = Math.PI / 180, dLat = (lat2 - lat1) * r, dLon = (lon2 - lon1) * r;
        var a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 12742 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Die nächsten Stationen zur GENAUEN Position (lokal), höchstens radiusKm, nach Abstand sortiert. */
    function naechste(stationen, lat, lon, radiusKm, max) {
        var rad = radiusKm || 10, out = [];
        (stationen || []).forEach(function (s) {
            if (!s || typeof s.lat !== 'number' || typeof s.lon !== 'number') return;
            var km = distKm(lat, lon, s.lat, s.lon);
            if (km <= rad) out.push({ s: s, km: km });
        });
        out.sort(function (a, b) { return a.km - b.km; });
        return out.slice(0, max || 60).map(function (x) { return x.s; });
    }

    /** Höchstens so viele Nachfragen, wenn die Antwort ausgedünnt ist (dichte Städte). */
    var NACHFRAGEN = 2;
    /** Ziel einer Nachfrage: so viele Standorte im Kreis, deutlich unter LIMIT (Stadtmitten sind dichter als der Schnitt). */
    var ZIEL_STANDORTE = 100;

    /** Hat der Planer ausgedünnt (mehr Standorte im Radius als geliefert)? */
    function abgeschnitten(antwort) {
        if (!antwort) return false;
        var n = (antwort.stationen || []).length, gesamt = +antwort.standorte_im_umkreis;
        return n >= LIMIT || (gesamt > 0 && gesamt > n);
    }

    /**
     * Radius der nächsten Nachfrage (ohne Rundungsaufschlag): so eng, dass etwa ZIEL_STANDORTE im Kreis liegen
     * (Dichte aus standorte_im_umkreis), mindestens 1 km, immer kleiner als vorher.
     */
    function engerRadius(antwort, gesendetKm) {
        var gesamt = +(antwort && antwort.standorte_im_umkreis);
        var r = gesamt > 0 ? gesendetKm * Math.sqrt(ZIEL_STANDORTE / gesamt) - RUNDUNG_KM : gesendetKm / 2 - RUNDUNG_KM;
        r = Math.min(r, gesendetKm - RUNDUNG_KM - 0.5);
        return Math.max(1, Math.round(r * 10) / 10);
    }

    /** Stationen mehrerer Antworten ohne Doppelte (IDs sind je Anfrage nicht stabil → Name + Lage). */
    function zusammen(antworten) {
        var seen = {}, out = [];
        (antworten || []).forEach(function (a) {
            ((a && a.stationen) || []).forEach(function (s) {
                if (!s || typeof s.lat !== 'number' || typeof s.lon !== 'number') return;
                var k = (s.name || '') + '|' + s.lat.toFixed(5) + '|' + s.lon.toFixed(5);
                if (seen[k]) return;
                seen[k] = 1;
                out.push(s);
            });
        });
        return out;
    }

    /**
     * Umkreissuche mit lokaler Feinsortierung. frag(body) → Promise der Planer-Antwort. Hat der Planer ausgedünnt
     * (dichte Städte: er verteilt dann die Auswahl über den Kreis statt die nächsten zu liefern), fragt das Gerät
     * enger nach, bis der Kreis vollständig ist – höchstens NACHFRAGEN Mal, jede Anfrage gerundet. Fehler bei einer
     * Nachfrage: was da ist, zählt. Ergebnis: { stationen: die nächsten max Säulen zur genauen Position, antworten }.
     */
    function holen(frag, lat, lon, radiusKm, max, extra) {
        var antworten = [], rad = radiusKm || 10;
        function fertig() {
            return { stationen: naechste(zusammen(antworten), lat, lon, rad, max || 60), antworten: antworten };
        }
        function weiter(i, gesendetKm) {
            var letzte = antworten[antworten.length - 1];
            if (i >= NACHFRAGEN || !abgeschnitten(letzte) || gesendetKm <= 1 + RUNDUNG_KM) return fertig();
            var r = engerRadius(letzte, gesendetKm), body = anfrage(lat, lon, r, extra);
            return Promise.resolve(frag(body)).then(function (d) {
                antworten.push(d);
                return weiter(i + 1, body.radius);
            }, function () { return fertig(); });
        }
        var erste = anfrage(lat, lon, rad, extra);
        return Promise.resolve(frag(erste)).then(function (d) {
            antworten.push(d);
            return weiter(0, erste.radius);
        });
    }

    /** Belegung einer Säule, nur wenn sie wirklich bekannt ist, sonst null (dann nichts anzeigen). */
    function belegung(s) {
        if (!s || s.frei == null || !isFinite(+s.frei)) return null;
        if (s.status_bekannt === false) return null;
        var p = +s.ladepunkte;
        if (!(p > 0)) return null;
        return { frei: Math.max(0, +s.frei), ladepunkte: p };
    }

    // ---------------------------------------------------------------- Günstigste in der Nähe (03.11.2026)
    // Rechnung nur im Gerät, mit den Antworten der gerundeten Umkreissuche – keine weiteren Anfragen.

    /** Weiter weg wird nichts empfohlen. */
    var EMPFEHLUNG_KM = 10;
    /** Luftlinie → Straße (Hin- und Rückweg werden über die Luftlinie geschätzt). */
    var UMWEG_FAKTOR = 1.3;
    /** kWh/100 km, wenn das Auto keinen plausiblen Durchschnitt meldet. */
    var VERBRAUCH_RUECKFALL = 16;
    var STANDARD_KWH = 30;

    /**
     * Ladepreis einer Säule je kWh: eigener Preis „Laut Säule“ (preis_eigen), dann eigene Ladekarte (preis_meine),
     * sonst ad hoc (preis_adhoc). Kein Preis, ein
     * auffälliger Preis (Meldefehler unter 0,10 €) oder ein reiner Minutentarif → null (dann keine Empfehlung).
     * kosten = Planer-Betrag für die angefragte Menge (mit Grundgebühren), falls vorhanden.
     */
    function preisKwh(s) {
        if (!s) return null;
        // 0.1.36: eigener Preis „Laut Säule“ bei diesem Betreiber und dieser Stromart zuerst (setzt das Auto als
        // preis_eigen in die Antwort; Planer-Meldefehler betreffen ihn nicht).
        var ei = s.preis_eigen;
        if (ei && +ei.preis_kwh > 0 && +ei.preis_kwh <= 10) return { preis: +ei.preis_kwh, quelle: 'eigen', kosten: null };
        if (s.preis_auffaellig) return null;
        var m = s.preis_meine, a = s.preis_adhoc;
        if (m && +m.preis_kwh > 0) return { preis: +m.preis_kwh, quelle: 'karte', karte: m.karte || null, kosten: +m.kosten > 0 ? +m.kosten : null };
        if (a && +a.preis_kwh > 0) return { preis: +a.preis_kwh, quelle: 'adhoc', kosten: +a.kosten > 0 ? +a.kosten : null };
        return null;
    }

    /** Effektive Kosten: Ladung (kwh) + Hin- und Rückweg (2 × km × Umweg × Verbrauch) zum selben Preis. */
    function effektiv(s, km, kwh, verbrauch) {
        var p = preisKwh(s);
        if (!p) return null;
        var laden = p.kosten != null ? p.kosten : p.preis * kwh;
        var wegKwh = 2 * km * UMWEG_FAKTOR * verbrauch / 100;
        return { preis: p.preis, quelle: p.quelle, karte: p.karte || null, laden: laden, weg: wegKwh * p.preis,
                 gesamt: laden + wegKwh * p.preis };
    }

    /**
     * „Günstigste in der Nähe“: Säulen bis EMPFEHLUNG_KM mit Preis, nach effektiven Kosten. Ersparnis gegenüber der
     * nächsten Säule mit Preis. o = { kwh, verbrauch, max }. Ohne Preis keine Empfehlung (leere Liste).
     */
    function guenstigste(stationen, lat, lon, o) {
        o = o || {};
        var kwh = o.kwh > 0 ? +o.kwh : STANDARD_KWH;
        var v = +o.verbrauch >= 5 && +o.verbrauch <= 60 ? +o.verbrauch : VERBRAUCH_RUECKFALL;
        var mit = [];
        naechste(stationen, lat, lon, EMPFEHLUNG_KM, 1000).forEach(function (s) {
            var km = distKm(lat, lon, s.lat, s.lon), e = effektiv(s, km, kwh, v);
            if (e) mit.push({ s: s, km: km, e: e, belegung: belegung(s) });
        });
        if (!mit.length) return [];
        var bezug = mit[0];   // nächste Säule mit Preis (naechste() ist nach Abstand sortiert)
        mit.sort(function (a, b) { return a.e.gesamt - b.e.gesamt || a.km - b.km; });
        return mit.slice(0, o.max || 5).map(function (x) {
            x.istNaechste = x === bezug;
            x.ersparnis = Math.round((bezug.e.gesamt - x.e.gesamt) * 100) / 100;
            return x;
        });
    }

    /** Zeitpunkt (ms) der Daten aus einem Alter in Sekunden und dem Empfangszeitpunkt, sonst null. */
    function standMs(alterS, empfangenMs) {
        if (alterS == null || alterS === '' || !isFinite(+alterS) || +alterS < 0) return null;
        return (empfangenMs || Date.now()) - Math.round(+alterS * 1000);
    }

    /** „Stand vor x min“ (t = Übersetzer der Seite). */
    function standText(datenMs, t, jetztMs) {
        if (datenMs == null || !isFinite(+datenMs)) return '';
        var s = Math.max(0, ((jetztMs || Date.now()) - +datenMs) / 1000);
        if (s < 60) return t('navi.stand_jetzt', 'Stand gerade eben');
        var min = Math.round(s / 60);
        if (min < 120) return t('navi.stand_min', 'Stand vor {n} min', { n: min });
        return t('navi.stand_h', 'Stand vor {n} h', { n: Math.round(min / 60) });
    }

    /** Alle Elemente mit data-stand neu beschriften (läuft alle 30 s). */
    function auffrischen(doc, t) {
        var els = (doc || document).querySelectorAll('[data-stand]');
        for (var i = 0; i < els.length; i++) els[i].textContent = standText(+els[i].getAttribute('data-stand'), t);
    }

    var api = { RUNDUNG_KM: RUNDUNG_KM, LIMIT: LIMIT, anfrage: anfrage, distKm: distKm, naechste: naechste,
                abgeschnitten: abgeschnitten, engerRadius: engerRadius, zusammen: zusammen, holen: holen,
                belegung: belegung, standMs: standMs, standText: standText, auffrischen: auffrischen,
                EMPFEHLUNG_KM: EMPFEHLUNG_KM, UMWEG_FAKTOR: UMWEG_FAKTOR, VERBRAUCH_RUECKFALL: VERBRAUCH_RUECKFALL,
                STANDARD_KWH: STANDARD_KWH, preisKwh: preisKwh, effektiv: effektiv, guenstigste: guenstigste };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.PanoptesBelegung = api;
})(this);
