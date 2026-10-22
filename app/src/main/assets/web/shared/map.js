/**
 * BYD Champ - Map Module
 * 
 * Features:
 * - Leaflet.js map with car position marker
 * - User location with route to car
 * - Google Maps directions integration
 * - Real-time GPS updates from consolidated /status endpoint
 */

window.BYD = window.BYD || {};

BYD.map = {
    // Leaflet instances
    map: null,
    
    // State
    carPosition: { lat: 0, lng: 0, heading: 0, speed: 0, accuracy: 0 },
    userPosition: null,
    isInitialized: false,
    
    // Default center (will be updated with car position)
    DEFAULT_ZOOM: 18,
    
    /**
     * Initialize the map
     */
    // 0.1.10: MapLibre (PanoptesMap) statt Leaflet – eine Kartenbibliothek, dieselbe Kartenquelle/Einwilligung.
    init() {
        const container = document.getElementById('mapContainer');
        if (!container || this.isInitialized) return;
        if (typeof PanoptesMap === 'undefined') { setTimeout(() => this.init(), 400); return; }
        this.startGpsTracking();
        this.map = PanoptesMap.create(container, { zoom: 2, pitch: 0, terrain: false, live: false, followCar: false });
        this.isInitialized = true;
        if (!this._viewportListenerBound) {
            this._viewportListenerBound = true;
            window.addEventListener('resize', () => {
                if (this._mapResizeTimer) clearTimeout(this._mapResizeTimer);
                this._mapResizeTimer = setTimeout(() => { if (this.map) this.map.resize(); }, 120);
            });
        }
        if (BYD.core && BYD.core.lastStatus && BYD.core.lastStatus.gps) this.updateFromStatus(BYD.core.lastStatus.gps);
    },

    /** Punkte auf der Karte: eigene Position und Entfernungs-Beschriftung (Auto zeichnet die Karte selbst). */
    _punkte() {
        if (!this.map) return;
        const list = [];
        if (this.userPosition) list.push({ lat: this.userPosition.lat, lng: this.userPosition.lng, kind: 'user', title: BYD.i18n.t('map.you_are_here') });
        if (this._abstand) list.push({ lat: this._abstand.lat, lng: this._abstand.lng, kind: 'label', label: this._abstand.text });
        this.map.setMarkers(list);
    },

    /**
     * Start GPS tracking on the backend
     */
    async startGpsTracking() {
        try {
            await fetch('/api/gps/start', { method: 'POST' });
            console.log('[Map] GPS tracking started');
        } catch (e) {
            console.error('[Map] Failed to start GPS tracking:', e);
        }
    },
    
    /**
     * Update from consolidated status call (called by core.js)
     */
    updateFromStatus(gpsData) {
        if (!gpsData) return;
        
        const lastUpdateEl = document.getElementById('lastGpsUpdate');
        // First resolved paint only, including "No GPS" — that is an answer, not loading.
        if (window.BYD && BYD.skeleton) {
            BYD.skeleton.resolve('gpsFreshness');
            BYD.skeleton.resolve('gpsDistance');
        }
        
        // Show location even if stale/cached (last known position when ACC was on)
        if (gpsData.hasLocation) {
            this.updateCarPosition(gpsData.lat, gpsData.lng, gpsData.heading, gpsData.speed);
            
            // Update last GPS update time with staleness indicator
            if (lastUpdateEl && gpsData.lastUpdate) {
                const ago = this.formatTimeAgo(gpsData.lastUpdate);
                if (gpsData.isCached) {
                    // Location is from cache (ACC likely off, no recent GPS)
                    this._setFreshness(lastUpdateEl, this._tx('liveview.gps_cached', '{ago} (zwischengespeichert)', { ago: ago }), 'is-stale');
                } else if (gpsData.isStale) {
                    // Location is stale but not too old
                    this._setFreshness(lastUpdateEl, this._tx('liveview.gps_stale', '{ago} (veraltet)', { ago: ago }), 'is-stale');
                } else {
                    this._setFreshness(lastUpdateEl, ago, 'is-live');
                }
            }
        } else {
            if (lastUpdateEl) {
                this._setFreshness(lastUpdateEl, this._tx('liveview.gps_none', 'Kein GPS'), 'is-down');
            }
        }
    },

    /** Katalogtext mit deutschem Fallback und {platzhalter}. */
    _tx(key, fallback, vars) {
        const v = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, vars) : null;
        if (v && v !== key) return v;
        return fallback.replace(/\{(\w+)\}/g, (m, n) => (vars && vars[n] != null ? vars[n] : m));
    },

    /** Freshness reads as a state dot plus a plain label, never coloured text. */
    _setFreshness(labelEl, text, dotState) {
        labelEl.textContent = text;
        labelEl.classList.remove('stale');
        const row = labelEl.closest
            ? labelEl.closest('.location-freshness')
            : document.querySelector('.location-freshness');
        const dot = row && row.querySelector('.location-freshness-dot');
        if (dot) dot.className = 'location-freshness-dot ' + dotState;
    },
    
    formatTimeAgo(timestamp) {
        const seconds = Math.floor((Date.now() - timestamp) / 1000);
        function ago(key, n, fallback) {
            var phrase = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, { n: n }) : null;
            if (phrase && phrase !== key) return phrase;
            return fallback;
        }
        if (seconds < 60) return ago('dashboard.time_seconds_ago', seconds, seconds + 's ago');
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return ago('dashboard.time_minutes_ago', minutes, minutes + 'm ago');
        const hours = Math.floor(minutes / 60);
        return ago('dashboard.time_hours_ago', hours, hours + 'h ago');
    },
    
    /**
     * Update car position on map
     */
    updateCarPosition(lat, lng, heading = 0, speed = 0) {
        if (!this.map) return;
        this.carPosition = { lat, lng, heading, speed };
        this.map.setCar({ lat: lat, lng: lng, heading: heading });
        const pulse = document.querySelector('.car-pulse');
        if (pulse) pulse.classList.add('active');
        if (!this.hasInitialPosition) {
            this.map.setView(lat, lng, this.DEFAULT_ZOOM);
            this.hasInitialPosition = true;
        }
        if (this.userPosition) this.drawRoute();
    },

    /**
     * Show user's current location
     */
    showMyLocation() {
        if (!navigator.geolocation) {
            BYD.utils && BYD.utils.toast(
                BYD.i18n.t('map.geo_not_supported') || 'Dieser Browser kann deinen Standort nicht finden', 'error');
            return;
        }
        
        // Show loading state
        const btn = document.querySelector('.btn-my-location');
        if (btn) btn.classList.add('loading');
        
        navigator.geolocation.getCurrentPosition(
            (pos) => {
                const { latitude, longitude, accuracy } = pos.coords;
                
                // Filter out very inaccurate positions (> 500m radius)
                if (accuracy > 500) {
                    console.warn('[Map] Location too inaccurate:', accuracy, 'm');
                    if (btn) btn.classList.remove('loading');
                    BYD.utils && BYD.utils.toast(
                        BYD.i18n.t('map.location_inaccurate', {m: Math.round(accuracy)})
                            || ('Location inaccurate (' + Math.round(accuracy) + 'm). Try enabling GPS.'),
                        'warning');
                    return;
                }
                
                this.userPosition = { lat: latitude, lng: longitude };
                
                this._punkte();
                
                // Draw route to car
                this.drawRoute();
                
                // Fit bounds to show both markers
                this.fitBounds();
                
                if (btn) btn.classList.remove('loading');
                BYD.utils && BYD.utils.toast(
                    BYD.i18n.t('map.location_found') || 'Standort gefunden', 'success');
            },
            (err) => {
                console.error('[Map] Geolocation error:', err);
                if (btn) btn.classList.remove('loading');
                BYD.utils && BYD.utils.toast(
                    BYD.i18n.t('map.location_failed') || 'Dein Standort ist nicht abrufbar', 'error');
            },
            {
                enableHighAccuracy: true,
                timeout: 15000,
                maximumAge: 10000
            }
        );
    },
    
    /**
     * Draw route line from user to car
     */
    drawRoute() {
        if (!this.map || !this.userPosition || !this.carPosition.lat) return;
        // Einfache Verbindung (kein Routing), Farbe wie bisher
        this.map.setLines([{ coords: [[this.userPosition.lng, this.userPosition.lat], [this.carPosition.lng, this.carPosition.lat]], color: '#5FD6E6', opacity: 0.7 }]);
        const distance = this.calculateDistance(this.userPosition.lat, this.userPosition.lng, this.carPosition.lat, this.carPosition.lng);
        const distanceText = this.formatDistance(distance);
        this._abstand = { lat: (this.userPosition.lat + this.carPosition.lat) / 2, lng: (this.userPosition.lng + this.carPosition.lng) / 2, text: distanceText };
        this._punkte();
        const distanceEl = document.getElementById('distanceToCar');
        if (distanceEl) distanceEl.textContent = distanceText;
    },

    fitBounds() {
        if (!this.map) return;
        const pts = [];
        if (this.carPosition.lat) pts.push([this.carPosition.lng, this.carPosition.lat]);
        if (this.userPosition) pts.push([this.userPosition.lng, this.userPosition.lat]);
        if (pts.length > 1) this.map.fit(pts, 50);
        else if (pts.length === 1) this.map.setView(pts[0][1], pts[0][0], this.DEFAULT_ZOOM);
    },

    centerOnCar() {
        if (!this.map || !this.carPosition.lat) return;
        this.map.setView(this.carPosition.lat, this.carPosition.lng, this.DEFAULT_ZOOM);
    },

    /**
     * Open Google Maps with directions to car
     */
    openDirections() {
        if (!this.carPosition.lat) {
            BYD.utils && BYD.utils.toast(
                BYD.i18n.t('map.car_location_unavailable') || 'Wo das Auto steht, ist gerade unbekannt', 'error');
            return;
        }
        
        if (window.PanoptesNaviLink) PanoptesNaviLink.oeffnen(this.carPosition.lat, this.carPosition.lng, 'ziel');
    },
    
    /**
     * Calculate distance between two points (Haversine formula)
     */
    calculateDistance(lat1, lng1, lat2, lng2) {
        const R = 6371e3; // Earth radius in meters
        const φ1 = lat1 * Math.PI / 180;
        const φ2 = lat2 * Math.PI / 180;
        const Δφ = (lat2 - lat1) * Math.PI / 180;
        const Δλ = (lng2 - lng1) * Math.PI / 180;
        
        const a = Math.sin(Δφ/2) * Math.sin(Δφ/2) +
                  Math.cos(φ1) * Math.cos(φ2) *
                  Math.sin(Δλ/2) * Math.sin(Δλ/2);
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        
        return R * c; // Distance in meters
    },
    
    /**
     * Format distance for display. Sub-1km values stay in meters/feet for
     * precision; longer distances go through BYD.units to honor mi mode.
     */
    formatDistance(meters) {
        if (typeof BYD !== 'undefined' && BYD.units && BYD.units.mode === 'mi') {
            // 1 ft = 0.3048 m, 1 mi = 1609.344 m
            if (meters < 1609.344) {
                return Math.round(meters / 0.3048) + ' ft';
            }
            return BYD.units.dist(meters / 1000, 1);
        }
        if (meters < 1000) {
            return Math.round(meters) + ' m';
        }
        return (meters / 1000).toFixed(1) + ' km';
    },
    
    /**
     * Cleanup
     */
    destroy() {
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
        this.isInitialized = false;
    }
};
