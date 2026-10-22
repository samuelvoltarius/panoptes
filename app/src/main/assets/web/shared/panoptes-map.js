/**
 * Panoptes — gemeinsame Karte (MapLibre GL 4.7.1 + pmtiles 3.2.1, lokal in vendor/).
 *
 * Quelle wählt der Nutzer (shared/kartenquelle.js, Standard „keine“):
 *   community – die eigene Europakarte auf https://byd.serveone.at/tiles/ (PMTiles,
 *               Stil surfmate-dark.json, mit Gelände und Live-Belegung)
 *   osm       – Rasterkacheln von tile.openstreetmap.de, dunkel umgefärbt
 *   keine     – keine Kacheln, Hinweis mit Knopf zum Wählen
 * Ist die gewählte Quelle nicht erreichbar, erscheint „Karte nicht erreichbar“.
 * Es gibt keinen stillen Wechsel auf eine andere Quelle.
 *
 * Das Auto ist ein flacher 3D-Körper (fill-extrusion) in vehicle.color; Maße je
 * vehicle.modelId. Kein three.js-Layer: der bricht zusammen mit dem Gelände.
 *
 *   PanoptesMap.create(el, { center:[lng,lat], zoom, pitch, bearing, terrain:true,
 *                            interactive:true, onReady:function(api){} })
 *     → api.setCar({lng,lat,heading})   Position/Richtung des Autos
 *       api.setAppearance({modelId,color})
 *       api.setMarkers([{lng,lat,label,kind:'dc'|'ac'|'start'|'end'}])
 *       api.setLine([[lng,lat],…], {fit:true}) · api.setLines([{coords,color,opacity}], {fit:true})
 *       api.onClick(fn(lngLat)) · api.remove() · api.setMode('2d'|'3d') (Knopf in der Ecke, gemerkt)
 *       api.fit([[lng,lat],…]) · api.flyToCar() · api.source ('panoptes'|'osm'|null)
 *
 * ES5 geschrieben; MapLibre selbst braucht WebGL (am Auto: WebView 74 – geht).
 */
(function () {
    'use strict';

    var TILE_BASE = 'https://byd.serveone.at/tiles/';
    var OWN_STYLE = TILE_BASE + 'style/surfmate-dark.json';
    var OSM_TILES = 'https://tile.openstreetmap.de/{z}/{x}/{y}.png';
    var VENDOR = window.PANOPTES_VENDOR || '../shared/vendor/';
    var LIME = '#B5F23D';

    // Außenmaße in Metern (Herstellerangaben). Schlüssel = vehicle.modelId wie in
    // /api/models/selected. Unbekannte Modelle bekommen ein mittleres Auto.
    var MODELS = {
        seagull:  { L: 3.99, W: 1.72, H: 1.59 },   // Dolphin Surf
        dolphin:  { L: 4.29, W: 1.77, H: 1.57 },
        atto3:    { L: 4.46, W: 1.88, H: 1.62 },
        atto2:    { L: 4.31, W: 1.83, H: 1.67 },
        seal:     { L: 4.80, W: 1.88, H: 1.46 },
        sealu:    { L: 4.78, W: 1.89, H: 1.67 },
        sealion7: { L: 4.83, W: 1.93, H: 1.62 },
        han:      { L: 4.99, W: 1.91, H: 1.50 },
        tang:     { L: 4.97, W: 1.95, H: 1.75 }
    };
    var DEFAULT_MODEL = { L: 4.40, W: 1.82, H: 1.60 };

    // Bauteile im Grundmaß 4 × 1,72 × 1,6 m: [x von, x bis, halbe Breite, unten, oben, Farbe]
    var PARTS = [
        [-2.00, 2.00, 0.86, 0.30, 0.95, 'body'],
        [-1.80, 1.05, 0.80, 0.95, 1.50, '#071a2b'],
        [-1.62, 0.42, 0.80, 1.50, 1.60, 'body'],
        [-1.92, 1.92, 0.875, 0.58, 0.66, 'accent'],
        [ 1.96, 2.04, 0.70, 0.74, 0.90, '#E8FBFD'],
        [-2.04, -1.96, 0.70, 0.74, 0.90, '#FF8A6B']
    ];
    var WHEELS = [[1.27, 0.76], [1.27, -0.76], [-1.25, 0.76], [-1.25, -0.76]];
    var R = 6371000;

    var loading = null;

    function loadScript(src) {
        return new Promise(function (resolve, reject) {
            var s = document.createElement('script');
            s.src = src;
            s.onload = function () { resolve(); };
            s.onerror = function () { reject(new Error('load ' + src)); };
            document.head.appendChild(s);
        });
    }

    function loadCss(href) {
        if (document.querySelector('link[href="' + href + '"]')) return;
        var l = document.createElement('link');
        l.rel = 'stylesheet';
        l.href = href;
        document.head.appendChild(l);
    }

    function loadLibs() {
        if (loading) return loading;
        loadCss(VENDOR + 'maplibre-gl-4.7.1.css');
        loading = (window.maplibregl ? Promise.resolve() : loadScript(VENDOR + 'maplibre-gl-4.7.1.js'))
            .then(function () {
                return window.pmtiles ? null : loadScript(VENDOR + 'pmtiles-3.2.1.js');
            })
            .then(function () {
                // Gemeinsames Kartenmodul (B1, Karten-Sitzung): Live-Belegung + Namensnennung.
                return window.PanoptesKarte ? null : loadScript(VENDOR + 'panoptes-karte-1.1.0.js').catch(function () {});
            })
            .then(function () {
                if (!window.maplibregl) throw new Error('maplibre missing');
                if (window.pmtiles && !window.__panoptesPmtiles) {
                    var protocol = new window.pmtiles.Protocol();
                    window.maplibregl.addProtocol('pmtiles', protocol.tile);
                    window.__panoptesPmtiles = true;
                }
            });
        return loading;
    }

    // Ist der eigene Kartenserver da? Stil holen (klein) und eine Kachel-Byte-Spanne
    // anfragen; erst wenn beides klappt, wird die eigene Karte genommen.
    var ownCheck = null;
    function checkOwnTiles() {
        if (ownCheck) return ownCheck;
        function timeout(ms) {
            return new Promise(function (_, reject) { setTimeout(function () { reject(new Error('timeout')); }, ms); });
        }
        ownCheck = Promise.race([
            fetch(OWN_STYLE, { cache: 'no-cache', mode: 'cors' }).then(function (r) {
                if (!r.ok) throw new Error('style ' + r.status);
                return r.json();
            }).then(function (style) {
                return fetch(TILE_BASE + 'europe.pmtiles', { headers: { Range: 'bytes=0-15' }, mode: 'cors' })
                    .then(function (r) {
                        if (r.status !== 206 && r.status !== 200) throw new Error('pmtiles ' + r.status);
                        return style;
                    });
            }),
            timeout(5000)
        ]).then(function (style) { return { ok: true, style: style }; },
                function () { return { ok: false }; });
        return ownCheck;
    }

    // Rasterkarte OpenStreetMap: hell gelieferte Kacheln, per Helligkeits-Tausch
    // (min > max) umgekehrt und gedreht, damit sie zum dunklen Stil passen.
    function osmStyle() {
        return {
            version: 8,
            sources: {
                osm: {
                    type: 'raster', tiles: [OSM_TILES], tileSize: 256, maxzoom: 19,
                    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>'
                }
            },
            layers: [
                { id: 'background', type: 'background', paint: { 'background-color': '#0b2236' } },
                { id: 'osm', type: 'raster', source: 'osm',
                  paint: { 'raster-brightness-min': 0.78, 'raster-brightness-max': 0.06,
                           'raster-hue-rotate': 180, 'raster-saturation': -0.45, 'raster-contrast': 0.05 } }
            ]
        };
    }

    function quelle() {
        var KQ = window.PanoptesKartenquelle;
        return KQ ? KQ.get() : 'keine';
    }

    // Wartet auf den Abgleich mit der Kern-Einstellung karte.quelle.
    function quelleBereit() {
        var KQ = window.PanoptesKartenquelle;
        return KQ && KQ.bereit ? KQ.bereit() : Promise.resolve(quelle());
    }

    function zeigeHinweis(el, art) {
        var KQ = window.PanoptesKartenquelle;
        if (KQ) { KQ.hinweis(el, art); return; }
        el.classList.add('pn-map-unavailable');
        el.textContent = art === 'nicht_erreichbar' ? 'Karte nicht erreichbar' : 'Keine Kartenquelle gewählt (System → Kartenquelle)';
    }

    function hexColor(v, fallback) {
        v = String(v || '').replace(/^#/, '');
        return /^[0-9a-f]{6}$/i.test(v) ? '#' + v : fallback;
    }

    function carGeometry(pos, headingDeg, scale, car) {
        var m = MODELS[car.modelId] || DEFAULT_MODEL;
        var sx = m.L / 4.0, sy = m.W / 1.72, sz = m.H / 1.6;
        var h = headingDeg * Math.PI / 180, cosLat = Math.cos(pos[1] * Math.PI / 180);
        function ll(x, y) {
            x *= scale * sx; y *= scale * sy;
            var e = x * Math.sin(h) - y * Math.cos(h), n = x * Math.cos(h) + y * Math.sin(h);
            return [pos[0] + e / (R * cosLat) * 180 / Math.PI, pos[1] + n / R * 180 / Math.PI];
        }
        function box(x0, x1, w0, w1) { return [[ll(x0, w0), ll(x1, w0), ll(x1, w1), ll(x0, w1), ll(x0, w0)]]; }
        function col(c) { return c === 'body' ? car.color : c === 'accent' ? LIME : c; }
        var f = [];
        for (var i = 0; i < PARTS.length; i++) {
            var p = PARTS[i];
            var ring = i === 0
                ? [[ll(p[0], p[2]), ll(p[1] - 0.35, p[2]), ll(p[1], p[2] - 0.25), ll(p[1], -p[2] + 0.25),
                    ll(p[1] - 0.35, -p[2]), ll(p[0], -p[2]), ll(p[0], p[2])]]
                : box(p[0], p[1], -p[2], p[2]);
            f.push({ type: 'Feature', properties: { b: p[3] * scale * sz, t: p[4] * scale * sz, c: col(p[5]) },
                     geometry: { type: 'Polygon', coordinates: ring } });
        }
        for (var w = 0; w < WHEELS.length; w++) {
            var x = WHEELS[w][0], y = WHEELS[w][1];
            f.push({ type: 'Feature', properties: { b: 0, t: 0.66 * scale * sz, c: '#0b1218' },
                     geometry: { type: 'Polygon', coordinates: box(x - 0.33, x + 0.33, y - 0.12, y + 0.12) } });
        }
        var glow = [];
        for (var k = 0; k <= 40; k++) {
            var a = k / 40 * 2 * Math.PI;
            glow.push(ll(2.6 * Math.cos(a), 2.6 * Math.sin(a)));
        }
        return {
            body: { type: 'FeatureCollection', features: f },
            glow: { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [glow] } }
        };
    }

    function firstSymbolLayer(map) {
        var layers = map.getStyle().layers || [];
        for (var i = 0; i < layers.length; i++) if (layers[i].type === 'symbol') return layers[i].id;
        return undefined;
    }

    // ---------------------------------------------------------------- 2D / 3D
    var MODE_KEY = 'pn-map-mode';
    function storedMode() {
        try { return localStorage.getItem(MODE_KEY); } catch (e) { return null; }
    }
    function storeMode(m) {
        try { localStorage.setItem(MODE_KEY, m); } catch (e) {}
    }

    // Knopf „2D / 3D“ in der Kartenecke (MapLibre-Control-Schnittstelle).
    function ModeControl(api) { this.api = api; }
    ModeControl.prototype.onAdd = function () {
        var self = this;
        var box = document.createElement('div');
        box.className = 'maplibregl-ctrl maplibregl-ctrl-group pn-mode-ctrl';
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'pn-mode-btn';
        b.onclick = function () { self.api.setMode(self.api.mode === '3d' ? '2d' : '3d', true); };
        box.appendChild(b);
        this.btn = b;
        this.box = box;
        this.sync();
        return box;
    };
    ModeControl.prototype.sync = function () {
        if (!this.btn) return;
        // Der Knopf zeigt, wohin er umschaltet.
        this.btn.textContent = this.api.mode === '3d' ? '2D' : '3D';
        this.btn.title = this.api.mode === '3d' ? '2D-Ansicht (von oben)' : '3D-Ansicht (Gebäude, Gelände)';
        this.btn.setAttribute('aria-label', this.btn.title);
    };
    ModeControl.prototype.onRemove = function () {
        if (this.box && this.box.parentNode) this.box.parentNode.removeChild(this.box);
    };

    // ---------------------------------------------------------------- Live-Belegung
    // Gemeinsames Modul panoptes-karte 1.1.0 (Vertrag https://byd.serveone.at/tiles/lib/README.md,
    // gebündelte Kopie, sha256 per Unit-Test geprüft). Abfrage über die App-Weiterleitung
    // /api/navi/umkreis (der Planer hat kein CORS); Pflicht-Namensnennung (live_quellen) zeigt
    // das Modul unten links im Kartenfeld an.
    function setupLive(api) {
        var PK = window.PanoptesKarte;
        if (!PK || !api.map.getLayer('chargers')) return;   // nur im eigenen Stil
        var sprache = (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang() === 'en') ? 'en' : 'de';
        api._live = PK.liveEbene(api.map, {
            umkreisUrl: '/api/navi/umkreis',
            sprache: sprache,
            beiDaten: function (stand) { api.liveStand = stand || api._live && api._live.stand; }
        });
    }

    // Blitzer-Ebene: Standard aus (0.1.8). Sichtbar nur, wenn speedcam.enabled im Kern
    // eingeschaltet ist (dort mit Rechtshinweis bestätigt). Fehlt das Feld: aus.
    function blitzerNachEinstellung(api) {
        api.setBlitzer(false);
        if (!window.fetch) return;
        fetch('/api/einstellungen', { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                var f = d && d.werte && d.werte['speedcam.enabled'];
                if (f && f.wert === true) api.setBlitzer(true);
            })
            .catch(function () {});
    }

    /** Kreis als Polygon-Ring (64 Punkte), Radius in Metern, [lng, lat]-Koordinaten. */
    function kreisRing(lng, lat, meter) {
        var ring = [], n = 64, dLat = meter / 111320, dLng = meter / (111320 * Math.cos(lat * Math.PI / 180));
        for (var i = 0; i <= n; i++) {
            var w = i / n * 2 * Math.PI;
            ring.push([lng + dLng * Math.cos(w), lat + dLat * Math.sin(w)]);
        }
        return ring;
    }

    function create(el, opts) {
        opts = opts || {};
        var api = {
            map: null, source: null, ready: false,
            _car: null, _look: { modelId: '', color: '#5FD6E6' }, _markers: [], _line: null, _pending: [],
            mode: null, _terrain: null, _ctrl: null
        };
        var pitch3d = typeof opts.pitch === 'number' && opts.pitch > 0 ? opts.pitch : 55;
        // Darstellung „leicht“ (app-shell.js): nur 2D, ohne Gelände und 3D-Gebäude, kein 3D-Knopf.
        function leicht() { return !!(window.PanoptesDarstellung && PanoptesDarstellung.leicht()); }
        function wunschModus() { return leicht() ? '2d' : (storedMode() || (opts.pitch === 0 ? '2d' : '3d')); }
        api.mode = wunschModus();

        // 2D: von oben, ohne Gelände und ohne Gebäude-Körper. 3D: geneigt, mit beidem.
        api.setMode = function (m, remember) {
            api.mode = m === '2d' ? '2d' : '3d';
            if (remember) storeMode(api.mode);
            if (api._ctrl) api._ctrl.sync();
            whenReady(function () {
                var map = api.map, is3d = api.mode === '3d';
                (map.getStyle().layers || []).forEach(function (l) {
                    if (l.type === 'fill-extrusion' && l.id !== 'pn-car') {
                        try { map.setLayoutProperty(l.id, 'visibility', is3d ? 'visible' : 'none'); } catch (e) {}
                    }
                });
                try { map.setTerrain(is3d && api._terrain ? api._terrain : null); } catch (e) {}
                if (is3d) { map.dragRotate.enable(); map.touchPitch.enable(); }
                else { map.dragRotate.disable(); map.touchPitch.disable(); }
                map.easeTo(is3d ? { pitch: pitch3d, duration: 600 } : { pitch: 0, bearing: 0, duration: 600 });
            });
        };

        function whenReady(fn) { if (api.ready) fn(); else api._pending.push(fn); }

        function drawCar() {
            var map = api.map;
            if (!map || !api._car || !map.getSource('pn-car')) return;
            // Ab Zoom ~17 echte Größe; darunter bleibt das Auto etwa 12 px lang,
            // damit es sichtbar ist, aber die Karte nicht zudeckt.
            var scale = Math.max(1, Math.pow(2, 17.2 - map.getZoom()));
            var g = carGeometry([api._car.lng, api._car.lat], api._car.heading || 0, scale, api._look);
            map.getSource('pn-car').setData(g.body);
            map.getSource('pn-car-glow').setData(g.glow);
        }

        api.setCar = function (c) {
            if (!c || typeof c.lat !== 'number' || typeof c.lng !== 'number') return;
            var first = !api._car;
            api._car = { lng: c.lng, lat: c.lat, heading: typeof c.heading === 'number' ? c.heading : (api._car ? api._car.heading : 0) };
            whenReady(function () {
                drawCar();
                if (first && opts.followCar !== false) api.map.jumpTo({ center: [api._car.lng, api._car.lat] });
            });
        };
        api.setAppearance = function (look) {
            look = look || {};
            api._look = { modelId: look.modelId || api._look.modelId, color: hexColor(look.color, api._look.color) };
            whenReady(drawCar);
        };
        api.flyToCar = function () {
            if (api._car) whenReady(function () { api.map.easeTo({ center: [api._car.lng, api._car.lat], zoom: Math.max(api.map.getZoom(), 15) }); });
        };
        api.setMarkers = function (list) {
            whenReady(function () {
                for (var i = 0; i < api._markers.length; i++) api._markers[i].remove();
                api._markers = [];
                (list || []).forEach(function (m) {
                    if (typeof m.lat !== 'number' || typeof m.lng !== 'number') return;
                    var d = document.createElement('div');
                    d.className = 'pn-marker pn-marker-' + (m.kind || 'pt');
                    d.textContent = m.label || '';
                    if (m.title) d.title = m.title;
                    api._markers.push(new window.maplibregl.Marker({ element: d }).setLngLat([m.lng, m.lat]).addTo(api.map));
                });
            });
        };
        api.fit = function (coords, padding) {
            whenReady(function () {
                if (!coords || !coords.length) return;
                var b = new window.maplibregl.LngLatBounds(coords[0], coords[0]);
                for (var i = 1; i < coords.length; i++) b.extend(coords[i]);
                api.map.fitBounds(b, { padding: padding || 40, maxZoom: 16, duration: 0 });
            });
        };
        api.setLine = function (coords, o) {
            api._line = coords || null;
            whenReady(function () {
                var src = api.map.getSource('pn-line');
                var data = { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords || [] } };
                if (src) src.setData(data);
                if (o && o.fit && coords && coords.length > 1) api.fit(coords);
            });
        };
        // Mehrere Linien auf einmal: [{coords:[[lng,lat],…], color:'#…', opacity:1}]
        api.setLines = function (lines, o) {
            whenReady(function () {
                var all = [];
                var fc = { type: 'FeatureCollection', features: (lines || []).filter(function (l) {
                    return l && l.coords && l.coords.length > 1;
                }).map(function (l) {
                    all = all.concat(l.coords);
                    return { type: 'Feature', properties: { color: l.color || LIME, opacity: l.opacity == null ? 1 : l.opacity },
                             geometry: { type: 'LineString', coordinates: l.coords } };
                }) };
                api.map.getSource('pn-line').setData(fc);
                if (o && o.fit && all.length > 1) api.fit(all, o.padding);
            });
        };
        // Kreise mit Radius in Metern (z. B. Ruhezonen): [{lat, lng, radius, farbe?, gestrichelt?}] – 0.1.10, ersetzt L.circle
        api.setKreise = function (list) {
            whenReady(function () {
                var fc = { type: 'FeatureCollection', features: (list || []).filter(function (k) {
                    return k && typeof k.lat === 'number' && typeof k.lng === 'number' && k.radius > 0;
                }).map(function (k) {
                    return { type: 'Feature', properties: { farbe: k.farbe || LIME, strich: k.gestrichelt ? 1 : 0 },
                             geometry: { type: 'Polygon', coordinates: [kreisRing(k.lng, k.lat, k.radius)] } };
                }) };
                var src = api.map.getSource('pn-kreise');
                if (src) src.setData(fc);
            });
        };
        // Karte so zeigen, dass ein Kreis ganz sichtbar ist (pad = Anteil Rand, wie Leaflet .pad())
        api.fitKreis = function (k, pad) {
            if (!k || !(k.radius > 0)) return;
            var r = kreisRing(k.lng, k.lat, k.radius * (1 + (pad || 0.2)));
            api.fit(r, 20);
        };
        api.setView = function (lat, lng, zoom) {
            whenReady(function () { api.map.jumpTo({ center: [lng, lat], zoom: typeof zoom === 'number' ? zoom : api.map.getZoom() }); });
        };
        api.onClick = function (fn) {
            whenReady(function () { api.map.on('click', function (e) { fn(e.lngLat); }); });
        };
        api.remove = function () {
            api._pending = [];
            if (api._live && api._live.stop) { try { api._live.stop(); } catch (e) {} }
            try { if (api.map) api.map.remove(); } catch (e) {}
            api.map = null; api.ready = false;
        };
        api.resize = function () { if (api.map) api.map.resize(); };
        // Blitzer (Stil-Ebene 'cameras', OSM/ODbL, ab Zoom 10). Nur Anzeige, keine Warnung.
        // Ob sie an ist, entscheidet speedcam.enabled (blitzerNachEinstellung).
        api.setBlitzer = function (an) {
            whenReady(function () {
                if (window.PK && typeof window.PK.blitzerSichtbar === 'function') { window.PK.blitzerSichtbar(api.map, !!an); return; }
                if (api.map.getLayer('cameras')) api.map.setLayoutProperty('cameras', 'visibility', an ? 'visible' : 'none');
            });
        };

        function build(style, source) {
            api.source = source;
            var map = new window.maplibregl.Map({
                container: el,
                style: style,
                center: opts.center || [13.045, 47.80],
                zoom: typeof opts.zoom === 'number' ? opts.zoom : 14,
                pitch: api.mode === '2d' ? 0 : pitch3d,
                bearing: opts.bearing || 0,
                maxPitch: 75,
                interactive: opts.interactive !== false,
                // leicht: keine Überblend-Animation der Kacheln, höchstens 1 Bildpunkt je CSS-Pixel
                fadeDuration: leicht() ? 0 : 300,
                pixelRatio: leicht() ? Math.min(window.devicePixelRatio || 1, 1) : (window.devicePixelRatio || 1),
                attributionControl: { compact: true },
                cooperativeGestures: !!opts.cooperative
            });
            api.map = map;
            if (opts.interactive !== false) {
                map.addControl(new window.maplibregl.NavigationControl({ visualizePitch: !leicht(), showCompass: !leicht() }), 'top-right');
                api._ctrl = new ModeControl(api);
                map.addControl(api._ctrl, 'top-right');
                if (leicht() && api._ctrl.box) api._ctrl.box.style.display = 'none';
            }
            // Wechsel leicht/voll ohne Neuladen
            window.addEventListener('pn-darstellung', function () {
                if (!api.map) return;
                if (api._ctrl && api._ctrl.box) api._ctrl.box.style.display = leicht() ? 'none' : '';
                api.setMode(wunschModus(), false);
            });
            api._terrain = (typeof style === 'object' && style.terrain) ? style.terrain : null;
            if (source === 'osm') {
                // Keine Kachel kommt an → Hinweis, kein Wechsel auf eine andere Quelle.
                var kachelDa = false, kachelFehler = 0;
                map.on('data', function (e) { if (e && e.sourceId === 'osm' && e.tile) kachelDa = true; });
                map.on('error', function (e) {
                    if (kachelDa || !e || e.sourceId !== 'osm') return;
                    if (++kachelFehler === 4) zeigeHinweis(el, 'nicht_erreichbar');
                });
            }
            map.on('load', function () {
                var before = firstSymbolLayer(map);
                map.addSource('pn-line', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [] } } });
                map.addLayer({ id: 'pn-line-glow', type: 'line', source: 'pn-line',
                    layout: { 'line-cap': 'round', 'line-join': 'round' },
                    paint: { 'line-color': ['coalesce', ['get', 'color'], LIME], 'line-width': 12, 'line-opacity': 0.16, 'line-blur': 4 } }, before);
                map.addLayer({ id: 'pn-line', type: 'line', source: 'pn-line',
                    layout: { 'line-cap': 'round', 'line-join': 'round' },
                    paint: { 'line-color': ['coalesce', ['get', 'color'], LIME], 'line-width': 4,
                             'line-opacity': ['coalesce', ['get', 'opacity'], 1] } }, before);
                var empty = { type: 'FeatureCollection', features: [] };
                map.addSource('pn-kreise', { type: 'geojson', data: empty });
                map.addLayer({ id: 'pn-kreise-flaeche', type: 'fill', source: 'pn-kreise',
                    paint: { 'fill-color': ['get', 'farbe'], 'fill-opacity': 0.14 } }, before);
                map.addLayer({ id: 'pn-kreise-rand', type: 'line', source: 'pn-kreise',
                    paint: { 'line-color': ['get', 'farbe'], 'line-width': 2,
                             'line-dasharray': ['case', ['==', ['get', 'strich'], 1], ['literal', [2, 2]], ['literal', [1, 0]]] } }, before);
                map.addSource('pn-car', { type: 'geojson', data: empty });
                map.addSource('pn-car-glow', { type: 'geojson', data: empty });
                map.addLayer({ id: 'pn-car-glow', type: 'fill', source: 'pn-car-glow',
                    paint: { 'fill-color': LIME, 'fill-opacity': 0.22 } }, before);
                map.addLayer({ id: 'pn-car', type: 'fill-extrusion', source: 'pn-car',
                    paint: { 'fill-extrusion-color': ['get', 'c'], 'fill-extrusion-base': ['get', 'b'],
                             'fill-extrusion-height': ['get', 't'], 'fill-extrusion-opacity': 1,
                             'fill-extrusion-vertical-gradient': true } });
                map.on('zoomend', drawCar);
                api.ready = true;
                api.setMode(api.mode, false);
                if (opts.live !== false && source === 'panoptes') {
                    try { setupLive(api); } catch (e) { console.warn('[PanoptesMap] Live-Ebene', e); }
                }
                if (source === 'panoptes') blitzerNachEinstellung(api);
                var p = api._pending; api._pending = [];
                for (var i = 0; i < p.length; i++) { try { p[i](); } catch (e) { console.warn('[PanoptesMap]', e); } }
                if (typeof opts.onReady === 'function') opts.onReady(api);
            });
        }

        var q = 'keine';
        quelleBereit().then(function (wert) {
            q = wert;
            if (q === 'keine') {
                // Nichts laden, nichts senden – nur der Hinweis mit Knopf zum Wählen.
                zeigeHinweis(el, 'wahl');
                return null;
            }
            return loadLibs();
        }).then(function (libs) {
            if (q === 'keine') return 'keine';
            if (window.maplibregl.supported && !window.maplibregl.supported()) throw new Error('webgl');
            return q === 'community' ? checkOwnTiles() : { ok: true, style: null };
        }).then(function (own) {
            if (own === 'keine') return;
            if (q === 'osm') {
                build(osmStyle(), 'osm');
            } else if (own.ok) {
                var style = own.style;
                if (opts.terrain === false && style.terrain) {
                    style = JSON.parse(JSON.stringify(style));
                    delete style.terrain;
                }
                build(style, 'panoptes');
            } else {
                zeigeHinweis(el, 'nicht_erreichbar');
            }
        }).catch(function (e) {
            console.warn('[PanoptesMap] Karte nicht verfügbar:', e);
            el.classList.add('pn-map-unavailable');
            el.textContent = (window.BYD && BYD.i18n && BYD.i18n.t('map.unavailable') !== 'map.unavailable'
                ? BYD.i18n.t('map.unavailable') : 'Karte nicht verfügbar');
        });

        return api;
    }

    window.PanoptesMap = { create: create, models: MODELS, quelle: quelle,
                           checkOwnTiles: function () { return quelle() === 'community' ? checkOwnTiles() : Promise.resolve({ ok: false }); } };
})();
