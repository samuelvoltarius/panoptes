/*! panoptes-karte 1.1.0 – gemeinsames Kartenmodul für /ev/, EV-Cockpit und Panoptes
 *  Vertrag: https://byd.serveone.at/tiles/lib/README.md  (Repo: deploy/server/tiles/lib/README.md)
 *  Diese Datei ist unveränderlich. Jede Änderung = neue Versionsnummer = neue Datei.
 *  Keine Abhängigkeiten. Browser: window.PanoptesKarte, Node/CommonJS: require(...).
 */
(function (wurzel, fabrik) {
  if (typeof module === 'object' && module.exports) module.exports = fabrik();
  else wurzel.PanoptesKarte = fabrik();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var VERSION = '1.1.0';
  var BASIS = 'https://byd.serveone.at';

  // ---------------------------------------------------------------- Stil / Karte
  var STIL_URL = BASIS + '/tiles/style/surfmate-dark.json';

  /** pmtiles-Protokoll einmalig bei MapLibre anmelden (doppelt aufrufen schadet nicht). */
  function pmtilesAnmelden(maplibregl, pmtiles) {
    if (!maplibregl || !pmtiles) throw new Error('maplibregl und pmtiles nötig');
    if (maplibregl.__panoptesPmtiles) return;
    maplibregl.addProtocol('pmtiles', new pmtiles.Protocol().tile);
    maplibregl.__panoptesPmtiles = true;
  }

  /** Grundoptionen für new maplibregl.Map(...) im gemeinsamen Stil; `extra` überschreibt. */
  function kartenOptionen(extra) {
    var o = { style: STIL_URL, center: [13.045, 47.80], zoom: 13, maxPitch: 85 };
    for (var k in (extra || {})) o[k] = extra[k];
    return o;
  }

  /** Blitzer-Ebene ein/aus (Alfred: auf /ev/ und im Cockpit aus). */
  function blitzerSichtbar(map, an) {
    if (map.getLayer('cameras')) map.setLayoutProperty('cameras', 'visibility', an ? 'visible' : 'none');
  }

  // ---------------------------------------------------------------- Faktor
  var FAKTOR = Object.freeze({
    MIN: 0.70, MAX: 1.40,       // Grenzen des Faktors
    K: 4,                       // Schrumpfung: Gewicht n/(n+K)
    PLAUS_MIN: 0.4, PLAUS_MAX: 2.5, // Verhältnis je Fahrt außerhalb = verwerfen
    MAX_JE_PROFIL: 200          // so viele Verhältnisse je Profil lokal behalten
  });

  function runden(x, stellen) { var f = Math.pow(10, stellen); return Math.round(x * f) / f; }

  /** Verhältnis echt/Modell einer Fahrt oder null (unplausibel/unvollständig).
   *  fahrt: {modell_kwh, kwh} oder {modell_kwh, akku_kwh, soc_start, soc_ende} */
  function verhaeltnis(fahrt) {
    if (!fahrt || !(fahrt.modell_kwh > 0)) return null;
    var kwh = fahrt.kwh;
    if (kwh == null) {
      if (!(fahrt.akku_kwh > 0) || fahrt.soc_start == null || fahrt.soc_ende == null) return null;
      kwh = fahrt.akku_kwh * (fahrt.soc_start - fahrt.soc_ende) / 100;
    }
    var v = kwh / fahrt.modell_kwh;
    if (!isFinite(v) || v < FAKTOR.PLAUS_MIN || v > FAKTOR.PLAUS_MAX) return null;
    return runden(v, 4);
  }

  /** Faktor aus Verhältnissen: Median, zu 1,0 geschrumpft mit n/(n+4), begrenzt 0,70–1,40. Ungerundet. */
  function faktorAus(verhaeltnisse) {
    var v = (verhaeltnisse || []).filter(function (x) { return typeof x === 'number' && isFinite(x); })
      .slice().sort(function (a, b) { return a - b; });
    var n = v.length;
    if (!n) return 1.0;
    var med = n % 2 ? v[(n - 1) / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    var f = 1.0 + (med - 1.0) * (n / (n + FAKTOR.K));
    return Math.max(FAKTOR.MIN, Math.min(FAKTOR.MAX, f));
  }

  /** Neues Verhältnis an die lokale Liste hängen (höchstens MAX_JE_PROFIL, älteste fallen raus).
   *  Liefert eine NEUE Liste; die alte bleibt unverändert. */
  function verhaeltnisAnhaengen(liste, v) {
    var alt = (liste || []).slice(-(FAKTOR.MAX_JE_PROFIL - 1));
    alt.push(v);
    return alt;
  }

  /** Body für POST /ev/api/mein-faktor. Enthält NIE Fahrten, nur den fertigen Faktor. */
  function faktorMeldung(o) {
    if (!o || !o.fahrzeug) throw new Error('fahrzeug (Profilname aus GET fahrzeuge) fehlt');
    if (!o.quelle) throw new Error('quelle fehlt (panoptes | ev-cockpit | ev-planer)');
    var liste = (o.verhaeltnisse || []).filter(function (x) { return typeof x === 'number' && isFinite(x); });
    var d = { fahrzeug: String(o.fahrzeug), faktor: runden(faktorAus(liste), 3), fahrten: liste.length, quelle: String(o.quelle) };
    if (o.auto) d.auto = String(o.auto);
    return d;
  }

  // ---------------------------------------------------------------- Live-Belegung (chargers-live v2/v2.1)
  var LIVE = Object.freeze({
    RASTER: 0.05,          // Grad – genauer wird nie abgefragt
    RADIUS_KM: 8,          // ≤ 12 laut Planer-Vertrag
    LIMIT: 200,
    TAKT_MS: 120000,       // alle 2 min frisch
    OSM_ERSATZ_M: 120,     // OSM-Säulen in diesem Umkreis einer Planer-Station ausblenden
    DE_FREI_AUS_S: 24 * 3600, // DE: frei/außer Betrieb gilt bis 24 h
    DE_BELEGT_S: 3 * 3600     // DE: lädt/blockiert gilt bis 3 h
  });

  var TEXTE = {
    de: { frei: '{frei}/{ladepunkte} frei', aus: 'außer Betrieb', unbekannt: 'Status unbekannt',
          min: 'vor {n} min', h: 'vor {n} h', quellen: 'Live-Belegung' },
    en: { frei: '{frei}/{ladepunkte} free', aus: 'out of order', unbekannt: 'status unknown',
          min: '{n} min ago', h: '{n} h ago', quellen: 'Live status' }
  };
  function texte(sprache) { return TEXTE[sprache] || TEXTE.de; }
  function fuellen(t, w) { return t.replace(/\{(\w+)\}/g, function (_, k) { return w[k] == null ? '' : w[k]; }); }

  /** Abfrage-Body für POST /ev/api/umkreis: Position auf 0,05° gerundet. Nie in die URL. */
  function umkreisAnfrage(lat, lon, radiusKm) {
    var r = LIVE.RASTER;
    return { lat: +(Math.round(lat / r) * r).toFixed(2), lon: +(Math.round(lon / r) * r).toFixed(2),
             radius: Math.min(12, radiusKm || LIVE.RADIUS_KM), limit: LIVE.LIMIT };
  }

  /** Zustand einer Station: 'frei' | 'belegt' | 'aus' | 'unbekannt'.
   *  Enthält als Rückfall die DE-Gültigkeitsregeln (der Planer wendet sie selbst schon an). */
  function liveZustand(s) {
    if (!s || !s.status_bekannt) return 'unbekannt';
    var z = s.frei > 0 ? 'frei' : ((s.belegt_n || 0) > 0 || s.belegt_n == null ? 'belegt' : 'aus');
    if (s.land === 'DE' && s.live_alter_s != null) {
      var a = +s.live_alter_s;
      if (z === 'belegt' ? a > LIVE.DE_BELEGT_S : a > LIVE.DE_FREI_AUS_S) return 'unbekannt';
    }
    return z;
  }

  function istDC(s) { return (s.kw || 0) >= 50; }

  /** Beschriftung (ab z15): „2/4 frei · 150 kW · vor 12 min“ usw. */
  function liveText(s, sprache) {
    var t = texte(sprache), z = liveZustand(s);
    var kw = s.kw ? ' · ' + s.kw + ' kW' : '';
    var a = +s.live_alter_s || 0;
    var alt = z === 'unbekannt' || a <= 300 ? '' :
      ' · ' + (a > 7200 ? fuellen(t.h, { n: Math.round(a / 3600) }) : fuellen(t.min, { n: Math.round(a / 60) }));
    if (z === 'aus') return t.aus + alt;
    if (z === 'unbekannt') return t.unbekannt + kw;
    return fuellen(t.frei, s) + kw + alt;
  }

  /** Stationen der Antwort im Radius (mit Lage), unverändert. */
  function liveStationen(antwort, radiusKm) {
    var rad = radiusKm || LIVE.RADIUS_KM;
    return ((antwort && antwort.stationen) || []).filter(function (s) {
      return s.lat != null && s.lon != null && (s.entfernung_km == null ? 0 : s.entfernung_km) <= rad;
    });
  }

  /** Umkreis-Antwort → GeoJSON für die Quelle 'live'. Alle Stationen im Radius (auch „unbekannt“). */
  function liveFeatures(antwort, radiusKm, sprache) {
    var rad = radiusKm || LIVE.RADIUS_KM;
    var f = liveStationen(antwort, rad).map(function (s) {
      var z = liveZustand(s);
      return { type: 'Feature', geometry: { type: 'Point', coordinates: [s.lon, s.lat] },
        properties: { id: s.id == null ? null : s.id, dc: istDC(s) ? 1 : 0, zustand: z, belegt: z === 'belegt' ? 1 : 0,
                      kw: s.kw || 0, lbl: liveText(s, sprache) } };
    });
    return { type: 'FeatureCollection', features: f };
  }

  /** Filter für die OSM-Ebene 'chargers': 8-Eck mit 120 m um jede Live-Station ausblenden. */
  function osmFilter(fc) {
    var basis = ['==', ['get', 't'], 'charger'];
    var feats = (fc && fc.features) || [];
    if (!feats.length) return basis;
    var R = LIVE.OSM_ERSATZ_M / 1000;
    var ecken = feats.map(function (x) {
      var x0 = x.geometry.coordinates[0], y0 = x.geometry.coordinates[1], ring = [];
      for (var i = 0; i <= 8; i++) {
        var w = i / 8 * 2 * Math.PI;
        ring.push([x0 + R / (111.32 * Math.cos(y0 * Math.PI / 180)) * Math.cos(w), y0 + R / 111.32 * Math.sin(w)]);
      }
      ring[8] = ring[0].slice();   // Ring exakt schließen
      return [ring];
    });
    return ['all', basis, ['!', ['within', { type: 'MultiPolygon', coordinates: ecken }]]];
  }

  /** Pflicht-Namensnennung aus live_quellen, z. B. „Live-Belegung: DE: EnBW AG (CC BY 4.0) · AT: E-Control“. '' wenn leer. */
  function quellenText(liveQuellen, sprache) {
    var q = liveQuellen || [];
    if (!q.length) return '';
    return texte(sprache).quellen + ': ' + q.map(function (x) {
      return (x.land ? x.land + ': ' : '') + x.quelle + (x.lizenz ? ' (' + x.lizenz + ')' : '');
    }).join(' · ');
  }

  // Ebenen-Definitionen (Vertrag /tiles/ README, chargers-live v2)
  var OSM_UNBEKANNT_ICON = ['case', ['==', ['get', 'dc'], 1], 'charge-dc-unknown', 'charge-unknown'];
  function liveLayer() {
    return { id: 'chargers-live', type: 'symbol', source: 'live', minzoom: 11, layout: {
      'icon-image': ['concat', ['case', ['==', ['get', 'dc'], 1], 'charge-dc', 'charge'],
                    ['match', ['get', 'zustand'], 'belegt', '-busy', 'aus', '-off', 'unbekannt', '-unknown', '']],
      'icon-size': ['interpolate', ['linear'], ['zoom'], 11, 0.55, 15, 0.9],
      'symbol-sort-key': ['-', 0, ['+', ['*', ['get', 'dc'], 1000], ['get', 'kw']]],
      'icon-allow-overlap': ['step', ['zoom'], false, 15, true],
      'text-field': ['step', ['zoom'], '', 15, ['get', 'lbl']], 'text-font': ['Noto Sans Medium'], 'text-size': 11,
      'text-offset': [0, 1.5], 'text-anchor': 'top', 'text-optional': true },
      paint: { 'text-color': ['match', ['get', 'zustand'], 'belegt', '#FF5A4E', ['aus', 'unbekannt'], '#8FA6B0', '#B5F23D'],
               'text-halo-color': '#071a2b', 'text-halo-width': 1.4 } };
  }

  /** Live-Ebene an eine MapLibre-Karte hängen (nach map 'load' oder davor – wartet selbst).
   *  optionen: umkreisUrl, fetch, radiusKm, sprache, quellen ('auto' | function(text) | false),
   *            beiDaten(stand), beiFehler(e)
   *  Liefert {laden(), stop(), stand}. */
  function liveEbene(map, optionen) {
    var o = optionen || {};
    var url = o.umkreisUrl || BASIS + '/ev/api/umkreis';
    var hol = o.fetch || (typeof fetch !== 'undefined' ? function (u, i) { return fetch(u, i); } : null);
    var rad = o.radiusKm || LIVE.RADIUS_KM;
    var zelle = null, warte = null, takt = null, aktiv = true, quellenEl = null, osmIconAlt;
    var steuer = { stand: null };

    function quellenZeigen(text) {
      if (o.quellen === false) return;
      if (typeof o.quellen === 'function') { o.quellen(text); return; }
      if (typeof document === 'undefined') return;
      if (!quellenEl) {
        quellenEl = document.createElement('div');
        quellenEl.className = 'panoptes-livequellen';
        quellenEl.style.cssText = 'position:absolute;left:12px;bottom:8px;z-index:2;font:11px system-ui;color:#9fc4cc;' +
          'background:rgba(10,34,56,.8);padding:2px 6px;border-radius:6px;max-width:60%;pointer-events:auto';
        map.getContainer().appendChild(quellenEl);
      }
      quellenEl.textContent = text;
      quellenEl.style.display = text ? '' : 'none';
    }

    function laden() {
      if (!aktiv || !hol || map.getZoom() < 11) return Promise.resolve(null);
      var c = map.getCenter();
      var body = umkreisAnfrage(c.lat, c.lng, rad);
      var z = body.lat + ',' + body.lon;
      if (z === zelle) return Promise.resolve(steuer.stand);
      zelle = z;
      return hol(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
        .then(function (r) { if (!r.ok) throw new Error('umkreis ' + r.status); return r.json(); })
        .then(function (d) {
          if (!aktiv) return null;
          var fc = liveFeatures(d, rad, o.sprache);
          map.getSource('live').setData(fc);
          if (map.getLayer('chargers')) map.setFilter('chargers', osmFilter(fc));
          quellenZeigen(quellenText(d.live_quellen, o.sprache));
          var zaehl = function (k) { return fc.features.filter(function (x) { return x.properties.zustand === k; }).length; };
          steuer.stand = { rasterfeld: z, stationen: fc.features.length, frei: zaehl('frei'), belegt: zaehl('belegt'),
            aus: zaehl('aus'), unbekannt: zaehl('unbekannt'), quellen: d.live_quellen || [],
            // 1.1.0: Rohstationen im Radius (Planer-Felder + zustand), z. B. für Klickfenster
            liste: liveStationen(d, rad).map(function (s) {
              var k = {}; for (var x in s) k[x] = s[x]; k.zustand = liveZustand(s); return k; }) };
          if (o.beiDaten) o.beiDaten(steuer.stand);
          return steuer.stand;
        })
        .catch(function (e) { zelle = null; if (o.beiFehler) o.beiFehler(e); return null; });
    }

    // v2.1: „?“ auf chargers nur, solange die Live-Ebene sichtbar ist (ab Zoom 11), sonst Stilsymbol (wie /ev/ osmSymbol)
    function osmSymbol() {
      if (!aktiv || !map.getLayer('chargers')) return;
      map.setLayoutProperty('chargers', 'icon-image', map.getZoom() >= 11 ? OSM_UNBEKANNT_ICON : osmIconAlt);
    }

    function beiBewegung() { clearTimeout(warte); warte = setTimeout(laden, 600); }

    function start() {
      if (map.getLayer('chargers')) {
        osmIconAlt = map.getLayoutProperty('chargers', 'icon-image');
        osmSymbol();
      }
      if (!map.getSource('live')) map.addSource('live', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      if (!map.getLayer('chargers-live')) map.addLayer(liveLayer(), map.getLayer('signals') ? 'signals' : undefined);
      map.on('moveend', beiBewegung);
      map.on('zoomend', osmSymbol);
      takt = setInterval(function () { zelle = null; laden(); }, LIVE.TAKT_MS);
      laden();
    }

    steuer.laden = function () { zelle = null; return laden(); };
    steuer.stop = function () {
      aktiv = false; clearTimeout(warte); clearInterval(takt);
      map.off('moveend', beiBewegung);
      map.off('zoomend', osmSymbol);
      if (map.getLayer('chargers-live')) map.removeLayer('chargers-live');
      if (map.getSource('live')) map.removeSource('live');
      if (map.getLayer('chargers')) {
        map.setFilter('chargers', ['==', ['get', 't'], 'charger']);
        map.setLayoutProperty('chargers', 'icon-image', osmIconAlt);   // zurück zum Stil-Wert
      }
      if (quellenEl && quellenEl.parentNode) quellenEl.parentNode.removeChild(quellenEl);
    };

    if (map.loaded && map.loaded() || map.isStyleLoaded && map.isStyleLoaded()) start(); else map.once('load', start);
    return steuer;
  }

  return Object.freeze({
    VERSION: VERSION, STIL_URL: STIL_URL, FAKTOR: FAKTOR, LIVE: LIVE,
    pmtilesAnmelden: pmtilesAnmelden, kartenOptionen: kartenOptionen, blitzerSichtbar: blitzerSichtbar,
    verhaeltnis: verhaeltnis, faktorAus: faktorAus, verhaeltnisAnhaengen: verhaeltnisAnhaengen, faktorMeldung: faktorMeldung,
    umkreisAnfrage: umkreisAnfrage, liveStationen: liveStationen, liveZustand: liveZustand, liveText: liveText, liveFeatures: liveFeatures,
    osmFilter: osmFilter, quellenText: quellenText, liveLayer: liveLayer, OSM_UNBEKANNT_ICON: OSM_UNBEKANNT_ICON,
    liveEbene: liveEbene
  });
});
