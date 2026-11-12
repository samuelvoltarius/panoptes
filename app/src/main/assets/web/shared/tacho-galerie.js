/**
 * Panoptes — Tacho-Galerie (0.1.42, T2). 15 weitere eigene Designs für den Panoptes-Tacho, zusammen mit den fünf
 * aus tacho-designs.js also 20. Eigene Gestaltung; nur die Idee „viele Designs zur Auswahl“ ist übernommen.
 * Jedes Design meldet sich über PanoptesTacho.registriere an und zeichnet mit denselben Bausteinen (H).
 *
 * Regeln (wie tacho-designs.js): ruhig (kein Blinken, kein Pulsieren, keine Animation außer dem Gleiten der Werte),
 * Text mindestens 4,5:1 zum Hintergrund (fg, dim und ring gegen bg0 und bg1; im Test geprüft), nur Canvas 2D,
 * kein Netz, keine Worte im Bild (nur Zahlen und Einheiten, darum keine Übersetzung nötig).
 * Musik-Ansicht gibt es bewusst nicht: Panoptes hat für den Tacho keine Quelle für Titel und Interpret.
 * Kamera und Video gehören nicht in dieses Paket (Fahrsperre).
 * Laden NACH tacho-designs.js. ES5 (WebView 74 am Auto).
 */
(function () {
    'use strict';
    var T = window.PanoptesTacho;
    if (!T || !T.registriere) return;

    var SERIF = "Georgia,'Times New Roman',serif";
    var BREIT = "Verdana,'DejaVu Sans','Roboto',sans-serif";

    function thema(o) {
        var t = {
            bg0: '#0E1620', bg1: '#05090D', fg: '#EEF4F8', dim: '#9AA8B5', leise: '#2A3846', spur: '#18232E',
            ring: '#B5F23D', ring2: '#B5F23D', reku: '#3DD6C6', warn: '#FFB547', glanz: 0, ziffer: '600', kursiv: false,
            stil: 'glatt'
        };
        for (var k in o) if (o.hasOwnProperty(k)) t[k] = o[k];
        return t;
    }

    function flaeche(c, w, h, th) {
        var g = c.createLinearGradient(0, 0, 0, h);
        g.addColorStop(0, th.bg0);
        g.addColorStop(1, th.bg1);
        c.fillStyle = g;
        c.fillRect(0, 0, w, h);
    }

    /** Werte sicher lesen. */
    function kmh(H, d) { return H.zahl(d.kmh) ? d.kmh : 0; }
    function kmhText(H, d) { return H.zahl(d.kmh) ? String(Math.round(d.kmh)) : '–'; }
    function kwText(H, d) { return H.zahl(d.kw) ? String(Math.round(Math.abs(d.kw))) : '–'; }
    function socText(H, d) { return H.zahl(d.soc) ? Math.round(d.soc) + ' %' : '– %'; }
    function rwText(H, d) { return H.zahl(d.reichweiteKm) ? Math.round(d.reichweiteKm) + ' km' : '– km'; }
    function gang(d) { return d.gang || '–'; }

    /** Kopfzeile ohne leise Schrift: Uhr links, aktiver Gang Mitte, Außentemperatur rechts. */
    function kopf(c, w, u, th, d, jetzt, H, fam) {
        var y = u * 7, o = { familie: fam || H.COND, gewicht: '600' };
        H.text(c, H.uhr(jetzt), u * 4, y, u * 6, th.fg, { ausrichtung: 'left', familie: o.familie, gewicht: '600' });
        H.text(c, gang(d), w / 2, y, u * 7, th.ring, { familie: o.familie, gewicht: '700' });
        if (H.zahl(d.aussenC)) H.text(c, d.aussenC + '°C', w - u * 4, y, u * 6, th.dim,
            { ausrichtung: 'right', familie: o.familie, gewicht: '600' });
    }

    /** Waagrechter Balken mit Anteil 0..1 (ab nullA, negativ = Gegenrichtung). */
    function balken(c, x, y, bw, bh, th, H, anteil, farbe, nullA) {
        nullA = nullA || 0;
        H.rund(c, x, y, bw, bh, bh / 2);
        c.fillStyle = th.spur;
        c.fill();
        var a = H.clamp(nullA + anteil, 0, 1), von = Math.min(nullA, a), bis = Math.max(nullA, a);
        if (bis - von > 0.002) {
            H.rund(c, x + bw * von, y, Math.max(bh, bw * (bis - von)), bh, bh / 2);
            c.fillStyle = farbe;
            c.fill();
        }
    }

    /** Rundes Zifferblatt mit Zahlen und Nadel (klassisch). */
    function zifferblatt(c, x, y, r, th, H, o) {
        var von = Math.PI * 0.75, spanne = Math.PI * 1.5, i;
        c.save();
        c.strokeStyle = th.leise;
        c.lineWidth = Math.max(2, r * 0.03);
        c.beginPath();
        c.arc(x, y, r, 0, H.TAU);
        c.stroke();
        var n = Math.round((o.max - o.min) / o.schritt);
        for (i = 0; i <= n * 2; i++) {
            var a = von + spanne * i / (n * 2), gross = i % 2 === 0;
            var r1 = r * 0.93, r2 = r * (gross ? 0.8 : 0.86);
            c.strokeStyle = gross ? th.dim : th.leise;
            c.lineWidth = Math.max(1, r * (gross ? 0.025 : 0.015));
            c.beginPath();
            c.moveTo(x + Math.cos(a) * r1, y + Math.sin(a) * r1);
            c.lineTo(x + Math.cos(a) * r2, y + Math.sin(a) * r2);
            c.stroke();
            if (gross) {
                var z = o.min + o.schritt * (i / 2);
                H.text(c, (z < 0 ? '\u2212' : '') + String(Math.abs(z)), x + Math.cos(a) * r * 0.66, y + Math.sin(a) * r * 0.66, r * 0.13, th.dim,
                    { familie: o.familie || H.COND, gewicht: '600' });
            }
        }
        var wert = H.clamp((o.wert - o.min) / (o.max - o.min), 0, 1), w = von + spanne * wert;
        c.strokeStyle = o.farbe || th.ring;
        c.lineCap = 'round';
        c.lineWidth = Math.max(3, r * 0.045);
        c.beginPath();
        c.moveTo(x - Math.cos(w) * r * 0.12, y - Math.sin(w) * r * 0.12);
        c.lineTo(x + Math.cos(w) * r * 0.86, y + Math.sin(w) * r * 0.86);
        c.stroke();
        c.fillStyle = th.spur;
        c.beginPath();
        c.arc(x, y, r * 0.09, 0, H.TAU);
        c.fill();
        c.restore();
        if (o.text !== undefined) H.text(c, o.text, x, y + r * 0.42, r * 0.24, th.fg, { familie: o.familie || H.COND, gewicht: '700' });
        if (o.einheit) H.text(c, o.einheit, x, y + r * 0.62, r * 0.12, th.dim, { familie: o.familie || H.COND });
    }

    function kwMin(H, st, d) { var m = H.kwSkala(st, d.kw); return { max: m, min: -Math.round(m / 3 / 10) * 10 }; }

    // ------------------------------------------------------------ 1 Analog: zwei Rundinstrumente mit Zahlen
    T.registriere('analog', {
        arten: ['analog'], zeiger: 'nadel', schrift: 'normal',
        thema: thema({ bg0: '#15181B', bg1: '#08090B', fg: '#F1EFEA', dim: '#A9ADB1', leise: '#3A3F45', spur: '#24282D',
            ring: '#FF7A3D', ring2: '#FF7A3D', reku: '#4FD1A5', stil: 'fein' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            H.hintergrund(c, w, h, th, 'analog');
            kopf(c, w, u, th, d, jetzt, H, H.SANS);
            var r = Math.min(h * 0.36, w * 0.17), cy = h * 0.53, lx = w * 0.22, rx = w * 0.78, k = kwMin(H, st, d);
            zifferblatt(c, lx, cy, r, th, H, { min: k.min, max: k.max, schritt: Math.max(10, Math.round((k.max - k.min) / 8 / 10) * 10),
                wert: H.zahl(d.kw) ? d.kw : 0, text: kwText(H, d), einheit: 'kW', familie: H.SANS,
                farbe: H.zahl(d.kw) && d.kw < 0 ? th.reku : th.ring });
            zifferblatt(c, rx, cy, r, th, H, { min: 0, max: 200, schritt: 20, wert: kmh(H, d), text: kmhText(H, d),
                einheit: 'km/h', familie: H.SANS });
            H.mitte(c, w / 2, cy, (rx - lx) - r * 2.4, h * 0.6, u, th, d);
            H.fusszeile(c, w, h, u, th, d);
        }
    });

    // ------------------------------------------------------------ 2 Retro: Bandtacho und drei kleine Uhren
    T.registriere('retro', {
        arten: ['analog'], zeiger: 'nadel', schrift: 'normal',
        thema: thema({ bg0: '#221912', bg1: '#0D0A07', fg: '#F5E9D3', dim: '#C9B597', leise: '#4A3B2C', spur: '#33271C',
            ring: '#FFA23A', ring2: '#FFA23A', reku: '#A6D86E', ziffer: '700', stil: 'fein' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H, SERIF);
            // Band: Fenster mit Skala 0..200, der Balken wächst von links
            var bx = w * 0.08, bw = w * 0.84, by = h * 0.22, bh = h * 0.2, i;
            c.save();
            H.rund(c, bx, by, bw, bh, bh * 0.25);
            c.fillStyle = '#100C08';
            c.fill();
            c.strokeStyle = th.leise;
            c.lineWidth = Math.max(2, u * 0.6);
            c.stroke();
            var fx = bx + bw * 0.03, fw = bw * 0.94, fy = by + bh * 0.62, fh = bh * 0.22;
            c.fillStyle = th.ring;
            c.fillRect(fx, fy, fw * H.clamp(kmh(H, d) / 200, 0, 1), fh);
            for (i = 0; i <= 20; i++) {
                var x = fx + fw * i / 20;
                c.fillStyle = i % 2 === 0 ? th.dim : th.leise;
                c.fillRect(x - 1, fy - bh * (i % 2 === 0 ? 0.14 : 0.08), 2, bh * (i % 2 === 0 ? 0.14 : 0.08));
                if (i % 2 === 0) H.text(c, String(i * 10), x, by + bh * 0.3, bh * 0.22, th.dim, { familie: SERIF, gewicht: '700' });
            }
            c.restore();
            H.text(c, kmhText(H, d), w / 2, h * 0.56, u * 15, th.fg, { familie: SERIF, gewicht: '700' });
            H.text(c, 'km/h', w / 2 + u * 15, h * 0.58, u * 4.5, th.dim, { familie: SERIF, ausrichtung: 'left' });
            // drei kleine Uhren: Akku, Leistung, Reichweite
            var r = Math.min(h * 0.14, w * 0.07), y = h * 0.8, k = kwMin(H, st, d);
            zifferblatt(c, w * 0.2, y, r, th, H, { min: 0, max: 100, schritt: 25, wert: H.zahl(d.soc) ? d.soc : 0, familie: SERIF,
                farbe: H.zahl(d.soc) && d.soc <= 15 ? th.warn : th.ring });
            H.text(c, socText(H, d), w * 0.2 + r * 1.3, y, u * 6, th.fg, { familie: SERIF, ausrichtung: 'left', gewicht: '700' });
            zifferblatt(c, w * 0.62, y, r, th, H, { min: k.min, max: k.max, schritt: Math.max(25, Math.round((k.max - k.min) / 4 / 25) * 25),
                wert: H.zahl(d.kw) ? d.kw : 0, familie: SERIF, farbe: H.zahl(d.kw) && d.kw < 0 ? th.reku : th.ring });
            H.text(c, kwText(H, d) + ' kW', w * 0.62 + r * 1.3, y, u * 6, th.fg, { familie: SERIF, ausrichtung: 'left', gewicht: '700' });
            H.text(c, rwText(H, d), w - u * 4, h - u * 5, u * 5.5, th.dim, { familie: SERIF, ausrichtung: 'right' });
        }
    });

    // ------------------------------------------------------------ 3 Minimal: eine Zahl
    T.registriere('minimal', {
        arten: ['digital', 'ruhig'], zeiger: 'zahl', schrift: 'normal',
        plaetze: { links: 'leer', mitte: 'tempo', rechts: 'leer', oben_links: 'leer', oben_rechts: 'leer', unten_rechts: 'leer' },
        thema: thema({ bg0: '#000000', bg1: '#000000', fg: '#FFFFFF', dim: '#A0A0A0', leise: '#2A2A2A', spur: '#1C1C1C',
            ring: '#FFFFFF', ring2: '#FFFFFF', reku: '#7FE3C4', ziffer: '300' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            c.fillStyle = th.bg0;
            c.fillRect(0, 0, w, h);
            H.text(c, gang(d), w / 2, u * 8, u * 6, th.dim, { familie: H.SANS, gewicht: '500' });
            H.text(c, kmhText(H, d), w / 2, h * 0.5, u * 46, th.fg, { familie: H.SANS, gewicht: '300' });
            H.text(c, 'km/h', w / 2, h * 0.75, u * 5, th.dim, { familie: H.SANS });
            balken(c, w * 0.36, h - u * 11, w * 0.28, u * 1.2, th, H, H.zahl(d.soc) ? d.soc / 100 : 0,
                H.zahl(d.soc) && d.soc <= 15 ? th.warn : th.dim);
            H.text(c, socText(H, d) + '   ' + rwText(H, d), w / 2, h - u * 5, u * 4.6, th.dim, { familie: H.SANS });
        }
    });

    // ------------------------------------------------------------ 4 Futur: eckige Rahmen, gestapelte Stufen
    function stufen(c, x, y, bw, bh, n, anteil, th, farbe, schief) {
        var sh = bh / n, f = Math.round(anteil * n);
        for (var i = 0; i < n; i++) {
            var yy = y + bh - (i + 1) * sh;
            c.beginPath();
            c.moveTo(x + schief, yy + sh * 0.15);
            c.lineTo(x + bw + schief, yy + sh * 0.15);
            c.lineTo(x + bw, yy + sh * 0.85);
            c.lineTo(x, yy + sh * 0.85);
            c.closePath();
            c.fillStyle = i < f ? farbe : th.spur;
            c.fill();
        }
    }
    T.registriere('futur', {
        arten: ['digital'], zeiger: 'balken', schrift: 'breit',
        thema: thema({ bg0: '#06101F', bg1: '#02040A', fg: '#E6F7FF', dim: '#86BCD8', leise: '#1C3550', spur: '#0F2236',
            ring: '#22E5FF', ring2: '#8C6BFF', reku: '#3DFFB0', ziffer: '700' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H, BREIT);
            // Rahmen: Sechseck um das Tempo
            var cx = w / 2, cy = h * 0.52, rr = Math.min(h * 0.34, w * 0.16), i;
            c.save();
            c.strokeStyle = th.ring;
            c.lineWidth = Math.max(2, u * 0.7);
            c.beginPath();
            for (i = 0; i < 6; i++) {
                var a = Math.PI / 6 + i * Math.PI / 3;
                var px = cx + Math.cos(a) * rr * 1.1, py = cy + Math.sin(a) * rr;
                if (i === 0) c.moveTo(px, py); else c.lineTo(px, py);
            }
            c.closePath();
            c.stroke();
            c.restore();
            H.text(c, kmhText(H, d), cx, cy - u * 2, rr * 0.72, th.fg, { familie: BREIT, gewicht: '700' });
            H.text(c, 'km/h', cx, cy + rr * 0.48, u * 4.6, th.dim, { familie: BREIT });
            // links Leistung als Stufen, rechts Akku
            var k = H.kwSkala(st, d.kw), kw = H.zahl(d.kw) ? d.kw : 0, sh = h * 0.56, sy = h * 0.22;
            stufen(c, w * 0.1, sy, w * 0.12, sh, 12, H.clamp(Math.abs(kw) / k, 0, 1), th, kw < 0 ? th.reku : th.ring, u * 2);
            H.text(c, kwText(H, d) + ' kW', w * 0.16, sy + sh + u * 6, u * 5.5, kw < -0.5 ? th.reku : th.fg, { familie: BREIT, gewicht: '700' });
            stufen(c, w * 0.78, sy, w * 0.12, sh, 12, H.zahl(d.soc) ? d.soc / 100 : 0, th, H.zahl(d.soc) && d.soc <= 15 ? th.warn : th.ring2, -u * 2);
            H.text(c, socText(H, d), w * 0.84, sy + sh + u * 6, u * 5.5, th.fg, { familie: BREIT, gewicht: '700' });
            if (d.navi) {
                H.pfeil(c, w * 0.28, h * 0.46, u * 12, d.navi.typ, th.fg, false);
                H.text(c, H.entfernung(d.navi.distanzM), w * 0.28, h * 0.68, u * 5, th.fg, { familie: BREIT });
            }
            H.text(c, rwText(H, d), w * 0.72, h * 0.52, u * 5, th.dim, { familie: BREIT });
        }
    });

    // ------------------------------------------------------------ 5 Rennen: Leistungsleiste oben, Kästen
    function kasten(c, x, y, bw, bh, th, H, wert, einheit, farbe, fam) {
        H.rund(c, x, y, bw, bh, bh * 0.12);
        c.fillStyle = th.spur;
        c.fill();
        H.text(c, wert, x + bw / 2, y + bh * 0.44, bh * 0.46, farbe || th.fg, { familie: fam, gewicht: '700' });
        if (einheit) H.text(c, einheit, x + bw / 2, y + bh * 0.82, bh * 0.14, th.dim, { familie: fam });
    }
    T.registriere('rennen', {
        arten: ['digital'], zeiger: 'balken', schrift: 'breit',
        thema: thema({ bg0: '#0C0C0C', bg1: '#050505', fg: '#FFFFFF', dim: '#B4B4B4', leise: '#333333', spur: '#1E1E1E',
            ring: '#FF5A4D', ring2: '#FFD23F', reku: '#36E58E', ziffer: '800' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            // Leiste: 24 Felder, Farbe fest nach Platz (grün → gelb → rot), gefüllt nach Leistung
            var n = 24, lx = w * 0.06, lw = w * 0.88, ly = u * 4, lh = u * 7, k = H.kwSkala(st, d.kw), kw = H.zahl(d.kw) ? d.kw : 0;
            var voll = Math.round(H.clamp(Math.max(0, kw) / k, 0, 1) * n);
            for (var i = 0; i < n; i++) {
                var f = i / (n - 1), farbe = f < 0.55 ? th.reku : f < 0.85 ? th.ring2 : th.ring;
                c.fillStyle = i < voll ? farbe : th.spur;
                c.fillRect(lx + lw * i / n + 1, ly, lw / n - 3, lh);
            }
            var by = u * 17, bh = h - by - u * 16;
            kasten(c, w * 0.05, by, w * 0.24, bh, th, H, kwText(H, d), kw < -0.5 ? 'kW ↺' : 'kW', kw < -0.5 ? th.reku : th.fg, BREIT);
            kasten(c, w * 0.32, by, w * 0.36, bh, th, H, kmhText(H, d), 'km/h', th.fg, BREIT);
            kasten(c, w * 0.71, by, w * 0.24, bh, th, H, gang(d), '', th.ring2, BREIT);
            var y = h - u * 7;
            H.text(c, socText(H, d), w * 0.05, y, u * 6, th.fg, { familie: BREIT, ausrichtung: 'left', gewicht: '700' });
            H.text(c, rwText(H, d), w * 0.5, y, u * 6, th.fg, { familie: BREIT, gewicht: '700' });
            H.text(c, H.uhr(jetzt), w * 0.95, y, u * 6, th.dim, { familie: BREIT, ausrichtung: 'right' });
        }
    });

    // ------------------------------------------------------------ 6 Eco: Waage Verbrauch/Rekuperation, Reichweite groß
    T.registriere('eco', {
        arten: ['digital', 'ruhig'], zeiger: 'balken', schrift: 'normal',
        plaetze: { links: 'tempo', mitte: 'reichweite', rechts: 'leistung' },
        thema: thema({ bg0: '#08160F', bg1: '#020806', fg: '#E8F5EC', dim: '#9CC2AB', leise: '#24402F', spur: '#132A1D',
            ring: '#F2C14E', ring2: '#F2C14E', reku: '#3DDC84', ziffer: '500' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H, H.SANS);
            var cx = w / 2, cy = h * 0.78, r = Math.min(h * 0.56, w * 0.26), k = H.kwSkala(st, d.kw), kw = H.zahl(d.kw) ? d.kw : 0;
            c.save();
            c.lineCap = 'butt';
            c.lineWidth = r * 0.12;
            c.strokeStyle = th.spur;
            c.beginPath();
            c.arc(cx, cy, r, Math.PI, Math.PI * 2);
            c.stroke();
            // Mitte oben = 0; rechts Verbrauch, links Rekuperation (Rekuperation reicht bis ein Drittel der Skala)
            var a = H.clamp(kw / (kw < 0 ? k / 3 : k), -1, 1) * Math.PI / 2, mitte0 = Math.PI * 1.5;
            if (Math.abs(a) > 0.01) {
                c.strokeStyle = kw < 0 ? th.reku : th.ring;
                c.beginPath();
                c.arc(cx, cy, r, Math.min(mitte0, mitte0 + a), Math.max(mitte0, mitte0 + a));
                c.stroke();
            }
            c.fillStyle = th.dim;
            c.fillRect(cx - 1, cy - r - r * 0.1, 2, r * 0.2);
            c.restore();
            H.text(c, rwText(H, d), cx, cy - r * 0.42, r * 0.36, th.fg, { familie: H.SANS, gewicht: '500' });
            H.text(c, socText(H, d), cx, cy - r * 0.1, r * 0.16, th.dim, { familie: H.SANS });
            H.text(c, kmhText(H, d), w * 0.12, h * 0.55, u * 16, th.fg, { familie: H.SANS, gewicht: '500' });
            H.text(c, 'km/h', w * 0.12, h * 0.72, u * 4.6, th.dim, { familie: H.SANS });
            H.text(c, kwText(H, d), w * 0.88, h * 0.55, u * 12, kw < -0.5 ? th.reku : th.fg, { familie: H.SANS, gewicht: '500' });
            H.text(c, kw < -0.5 ? 'kW ↺' : 'kW', w * 0.88, h * 0.72, u * 4.6, kw < -0.5 ? th.reku : th.dim, { familie: H.SANS });
        }
    });

    // ------------------------------------------------------------ 7 Groß & klar: riesige Zahl, höchster Kontrast
    T.registriere('gross', {
        arten: ['digital', 'ruhig'], zeiger: 'zahl', schrift: 'breit',
        plaetze: { links: 'akku', mitte: 'leer', rechts: 'tempo' },
        thema: thema({ bg0: '#000000', bg1: '#000000', fg: '#FFFFFF', dim: '#E0E0E0', leise: '#404040', spur: '#262626',
            ring: '#FFD400', ring2: '#FFD400', reku: '#5CF2B5', warn: '#FF9E3D', ziffer: '800' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            c.fillStyle = '#000000';
            c.fillRect(0, 0, w, h);
            H.text(c, kmhText(H, d), w * 0.36, h * 0.5, u * 58, th.fg, { familie: BREIT, gewicht: '700' });
            H.text(c, 'km/h', w * 0.36, h * 0.88, u * 8, th.fg, { familie: BREIT, gewicht: '700' });
            var low = H.zahl(d.soc) && d.soc <= 15;
            H.text(c, gang(d), w * 0.82, h * 0.2, u * 16, th.ring, { familie: BREIT, gewicht: '700' });
            H.text(c, socText(H, d), w * 0.82, h * 0.52, u * 15, low ? th.warn : th.ring, { familie: BREIT, gewicht: '700' });
            H.text(c, rwText(H, d), w * 0.82, h * 0.78, u * 10, th.fg, { familie: BREIT, gewicht: '700' });
        }
    });

    // ------------------------------------------------------------ 8 Sonne: hell, dicke Balken, große Zahl
    T.registriere('sonne', {
        arten: ['digital'], zeiger: 'balken', schrift: 'breit',
        thema: thema({ bg0: '#FFFFFF', bg1: '#EEF2F5', fg: '#0A0F14', dim: '#3B4752', leise: '#B7C2CC', spur: '#D5DDE4',
            ring: '#0047A8', ring2: '#0047A8', reku: '#006B45', warn: '#9E3C00', ziffer: '800' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H, BREIT);
            H.text(c, kmhText(H, d), w * 0.27, h * 0.5, u * 40, th.fg, { familie: BREIT, gewicht: '700' });
            H.text(c, 'km/h', w * 0.27, h * 0.8, u * 6, th.dim, { familie: BREIT, gewicht: '700' });
            var x = w * 0.54, bw = w * 0.4, bh = u * 6, k = H.kwSkala(st, d.kw), kw = H.zahl(d.kw) ? d.kw : 0;
            var low = H.zahl(d.soc) && d.soc <= 15;
            H.text(c, socText(H, d), x, h * 0.25, u * 7, low ? th.warn : th.fg, { familie: BREIT, ausrichtung: 'left', gewicht: '700' });
            H.text(c, rwText(H, d), x + bw, h * 0.25, u * 7, th.fg, { familie: BREIT, ausrichtung: 'right', gewicht: '700' });
            balken(c, x, h * 0.32, bw, bh, th, H, H.zahl(d.soc) ? d.soc / 100 : 0, low ? th.warn : th.ring);
            H.text(c, kwText(H, d) + (kw < -0.5 ? ' kW ↺' : ' kW'), x, h * 0.55, u * 7, kw < -0.5 ? th.reku : th.fg,
                { familie: BREIT, ausrichtung: 'left', gewicht: '700' });
            balken(c, x, h * 0.62, bw, bh, th, H, kw / (k * 4 / 3), kw < 0 ? th.reku : th.ring, 0.25);
            if (d.navi) {
                H.pfeil(c, x + u * 6, h * 0.84, u * 10, d.navi.typ, th.fg, false);
                H.text(c, H.entfernung(d.navi.distanzM), x + u * 14, h * 0.84, u * 7, th.fg, { familie: BREIT, ausrichtung: 'left', gewicht: '700' });
            }
        }
    });

    // ------------------------------------------------------------ 9 Karte: Routen-Skizze groß
    T.registriere('karte', {
        arten: ['navi'], zeiger: 'zahl', schrift: 'schmal',
        thema: thema({ bg0: '#091119', bg1: '#030608', fg: '#EEF4F8', dim: '#9AB0C0', spur: '#16222C', ring: '#4FD1FF', ring2: '#4FD1FF' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H);
            var x0 = u * 4, y0 = u * 13, bw = w - u * 8, bh = h - u * 26;
            H.routeSkizze(c, x0, y0, bw, bh, u, th, d);
            // Tempo links unten, Akku rechts unten, Ziel in der Mitte
            var y = h - u * 7;
            H.text(c, kmhText(H, d), u * 4, y, u * 11, th.fg, { ausrichtung: 'left', gewicht: '700' });
            H.text(c, 'km/h', u * 4 + u * 22, y + u * 1.5, u * 4.5, th.dim, { ausrichtung: 'left' });
            H.text(c, socText(H, d) + '  ' + rwText(H, d), w - u * 4, y, u * 6, th.fg, { ausrichtung: 'right', gewicht: '600' });
            var n = d.navi;
            if (n && (H.zahl(n.restM) || H.zahl(n.restS))) {
                var rest = [];
                if (H.zahl(n.restM)) rest.push(H.entfernung(n.restM));
                if (H.zahl(n.restS)) rest.push(H.dauer(n.restS));
                H.text(c, rest.join('  ·  '), w / 2, y, u * 5.5, th.dim);
            }
        }
    });

    // ------------------------------------------------------------ 10 Abbiegen: großer Pfeil, Entfernung, Tempo
    T.registriere('abbiegen', {
        arten: ['navi', 'ruhig'], zeiger: 'zahl', schrift: 'normal',
        plaetze: { links: 'navi', mitte: 'leer', rechts: 'tempo' },
        thema: thema({ bg0: '#0B1218', bg1: '#04070A', fg: '#F2F6F9', dim: '#9DAAB5', spur: '#18232D', ring: '#7FE3C4', ring2: '#7FE3C4' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H, H.SANS);
            var n = d.navi;
            if (n) {
                H.pfeil(c, w * 0.2, h * 0.5, Math.min(h * 0.52, w * 0.22), n.typ, th.ring, true);
                H.text(c, H.entfernung(n.distanzM), w * 0.44, h * 0.46, u * 15, th.fg, { familie: H.SANS, ausrichtung: 'left', gewicht: '600' });
                var rest = [];
                if (H.zahl(n.restM)) rest.push(H.entfernung(n.restM));
                if (H.zahl(n.restS)) rest.push(H.dauer(n.restS));
                if (rest.length) H.text(c, rest.join('  ·  '), w * 0.44, h * 0.66, u * 6, th.dim, { familie: H.SANS, ausrichtung: 'left' });
            } else {
                H.text(c, '—', w * 0.3, h * 0.5, u * 16, th.dim, { familie: H.SANS });
            }
            H.text(c, kmhText(H, d), w - u * 6, h * 0.5, u * 20, th.fg, { familie: H.SANS, ausrichtung: 'right', gewicht: '600' });
            H.text(c, 'km/h', w - u * 6, h * 0.7, u * 5, th.dim, { familie: H.SANS, ausrichtung: 'right' });
            H.text(c, socText(H, d) + '   ' + rwText(H, d), w / 2, h - u * 6, u * 5.5, th.dim, { familie: H.SANS });
        }
    });

    // ------------------------------------------------------------ 11 Bogen: ein breiter Halbkreis über die ganze Fläche
    T.registriere('bogen', {
        arten: ['analog'], zeiger: 'balken', schrift: 'schmal',
        plaetze: { links: 'leistung', mitte: 'tempo', rechts: 'akku' },
        thema: thema({ bg0: '#101722', bg1: '#04070C', fg: '#F0F5FA', dim: '#9FB1C4', leise: '#33465A', spur: '#1A2634',
            ring: '#6FA8FF', ring2: '#B28CFF', reku: '#5CF2B5', ziffer: '600' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            H.hintergrund(c, w, h, th, 'bogen');
            var cx = w / 2, cy = h * 0.9, r = Math.min(w * 0.4, h * 0.74), i;
            c.save();
            c.lineCap = 'butt';
            c.lineWidth = r * 0.1;
            c.strokeStyle = th.spur;
            c.beginPath();
            c.arc(cx, cy, r, Math.PI, Math.PI * 2);
            c.stroke();
            var a = H.clamp(kmh(H, d) / 200, 0, 1);
            if (a > 0.002) {
                var g = c.createLinearGradient(cx - r, 0, cx + r, 0);
                g.addColorStop(0, th.ring);
                g.addColorStop(1, th.ring2);
                c.strokeStyle = g;
                c.beginPath();
                c.arc(cx, cy, r, Math.PI, Math.PI + Math.PI * a);
                c.stroke();
            }
            for (i = 0; i <= 10; i++) {
                var wi = Math.PI + Math.PI * i / 10;
                H.text(c, String(i * 20), cx + Math.cos(wi) * r * 0.82, cy + Math.sin(wi) * r * 0.82, r * 0.06, th.dim, { gewicht: '600' });
            }
            // innen: Leistung als dünner Bogen (Rekuperation nach links)
            var k = H.kwSkala(st, d.kw), kw = H.zahl(d.kw) ? d.kw : 0, ri = r * 0.68, mitte0 = Math.PI * 1.5;
            var b = H.clamp(kw / k, -0.33, 1) * Math.PI / 2;
            c.lineWidth = r * 0.035;
            c.strokeStyle = th.spur;
            c.beginPath();
            c.arc(cx, cy, ri, Math.PI, Math.PI * 2);
            c.stroke();
            if (Math.abs(b) > 0.01) {
                c.strokeStyle = kw < 0 ? th.reku : th.ring;
                c.beginPath();
                c.arc(cx, cy, ri, Math.min(mitte0, mitte0 + b), Math.max(mitte0, mitte0 + b));
                c.stroke();
            }
            c.restore();
            H.text(c, kmhText(H, d), cx, cy - r * 0.3, r * 0.3, th.fg, { gewicht: '700' });
            H.text(c, 'km/h', cx, cy - r * 0.1, r * 0.07, th.dim);
            H.text(c, kwText(H, d) + (kw < -0.5 ? ' kW ↺' : ' kW'), u * 4, u * 7, u * 6, kw < -0.5 ? th.reku : th.fg, { ausrichtung: 'left', gewicht: '600' });
            H.text(c, gang(d), cx, u * 7, u * 7, th.ring, { gewicht: '700' });
            H.text(c, socText(H, d) + '  ' + rwText(H, d), w - u * 4, u * 7, u * 6, th.fg, { ausrichtung: 'right', gewicht: '600' });
            H.text(c, H.uhr(jetzt), u * 4, h - u * 5, u * 5.5, th.dim, { ausrichtung: 'left' });
            if (d.navi) H.text(c, H.entfernung(d.navi.distanzM), w - u * 4, h - u * 5, u * 5.5, th.dim, { ausrichtung: 'right' });
        }
    });

    // ------------------------------------------------------------ 12 Säulen: drei stehende Balken
    function saeule(c, x, y, bw, bh, th, H, anteil, farbe, wert, einheit) {
        H.rund(c, x, y, bw, bh, bw * 0.2);
        c.fillStyle = th.spur;
        c.fill();
        var a = H.clamp(anteil, 0, 1);
        if (a > 0.01) {
            H.rund(c, x, y + bh * (1 - a), bw, Math.max(bw * 0.4, bh * a), bw * 0.2);
            c.fillStyle = farbe;
            c.fill();
        }
        H.text(c, wert, x + bw * 1.25, y + bh * 0.42, bw * 0.75, th.fg, { ausrichtung: 'left', familie: H.SANS, gewicht: '600' });
        H.text(c, einheit, x + bw * 1.25, y + bh * 0.42 + bw * 0.62, bw * 0.28, th.dim, { ausrichtung: 'left', familie: H.SANS });
    }
    T.registriere('saeulen', {
        arten: ['digital'], zeiger: 'balken', schrift: 'normal',
        thema: thema({ bg0: '#121212', bg1: '#070707', fg: '#F5F5F5', dim: '#ABABAB', spur: '#242424',
            ring: '#FF8C42', ring2: '#FF8C42', reku: '#4FD1A5' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            kopf(c, w, u, th, d, jetzt, H, H.SANS);
            var y = h * 0.2, bh = h * 0.62, bw = Math.min(w * 0.07, h * 0.16), k = H.kwSkala(st, d.kw), kw = H.zahl(d.kw) ? d.kw : 0;
            saeule(c, w * 0.06, y, bw, bh, th, H, kmh(H, d) / 200, th.fg, kmhText(H, d), 'km/h');
            saeule(c, w * 0.39, y, bw, bh, th, H, Math.abs(kw) / k, kw < 0 ? th.reku : th.ring, kwText(H, d), kw < -0.5 ? 'kW ↺' : 'kW');
            var low = H.zahl(d.soc) && d.soc <= 15;
            saeule(c, w * 0.7, y, bw, bh, th, H, H.zahl(d.soc) ? d.soc / 100 : 0, low ? th.warn : th.reku,
                H.zahl(d.soc) ? String(Math.round(d.soc)) : '–', '%  ·  ' + rwText(H, d));
        }
    });

    // ------------------------------------------------------------ 13 Chrono: ein großes Zifferblatt mit zwei kleinen darin
    T.registriere('chrono', {
        arten: ['analog'], zeiger: 'nadel', schrift: 'normal',
        plaetze: { links: 'reichweite', mitte: 'tempo', rechts: 'uhr' },
        thema: thema({ bg0: '#1A1C1F', bg1: '#0A0B0C', fg: '#F4F1EA', dim: '#B0ADA6', leise: '#3E4146', spur: '#2A2D31',
            ring: '#E9C46A', ring2: '#E9C46A', reku: '#6CD4A8', stil: 'fein' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            H.hintergrund(c, w, h, th, 'chrono');
            var cx = w / 2, cy = h * 0.52, r = Math.min(h * 0.45, w * 0.22);
            zifferblatt(c, cx, cy, r, th, H, { min: 0, max: 200, schritt: 20, wert: kmh(H, d), familie: SERIF });
            var rk = r * 0.18, k = kwMin(H, st, d);
            zifferblatt(c, cx - r * 0.3, cy + r * 0.2, rk, th, H, { min: 0, max: 100, schritt: 50, wert: H.zahl(d.soc) ? d.soc : 0,
                familie: SERIF, farbe: H.zahl(d.soc) && d.soc <= 15 ? th.warn : th.reku });
            zifferblatt(c, cx + r * 0.3, cy + r * 0.2, rk, th, H, { min: k.min, max: k.max, schritt: k.max, wert: H.zahl(d.kw) ? d.kw : 0,
                familie: SERIF, farbe: H.zahl(d.kw) && d.kw < 0 ? th.reku : th.ring });
            H.text(c, kmhText(H, d), cx, cy + r * 0.55, r * 0.16, th.fg, { familie: SERIF, gewicht: '700' });
            var lx = u * 5, o = { familie: SERIF, ausrichtung: 'left', gewicht: '700' };
            H.text(c, socText(H, d), lx, h * 0.38, u * 8, th.fg, o);
            H.text(c, rwText(H, d), lx, h * 0.54, u * 6, th.dim, { familie: SERIF, ausrichtung: 'left' });
            H.text(c, kwText(H, d) + ' kW', lx, h * 0.68, u * 6, th.dim, { familie: SERIF, ausrichtung: 'left' });
            var rx = w - u * 5, p = { familie: SERIF, ausrichtung: 'right', gewicht: '700' };
            H.text(c, H.uhr(jetzt), rx, h * 0.38, u * 8, th.fg, p);
            H.text(c, gang(d), rx, h * 0.54, u * 7, th.ring, p);
            if (H.zahl(d.aussenC)) H.text(c, d.aussenC + '°C', rx, h * 0.68, u * 6, th.dim, { familie: SERIF, ausrichtung: 'right' });
        }
    });

    // ------------------------------------------------------------ 14 Kacheln: vier ruhige Karten
    function karte(c, x, y, bw, bh, th, H) {
        H.rund(c, x, y, bw, bh, Math.min(bw, bh) * 0.08);
        c.fillStyle = th.spur;
        c.fill();
    }
    T.registriere('kacheln', {
        arten: ['digital', 'ruhig'], zeiger: 'zahl', schrift: 'normal',
        thema: thema({ bg0: '#0F141A', bg1: '#0F141A', fg: '#F3F6F9', dim: '#A3AFBB', spur: '#1D2630', ring: '#9AD0FF', ring2: '#9AD0FF', reku: '#7FE3C4' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            c.fillStyle = th.bg0;
            c.fillRect(0, 0, w, h);
            kopf(c, w, u, th, d, jetzt, H, H.SANS);
            var y = u * 15, bh = h - u * 20, g = u * 2.5, x = u * 4, frei = w - u * 8 - g * 3;
            var b1 = frei * 0.34, b = frei * 0.22, o = { familie: H.SANS, gewicht: '600' };
            karte(c, x, y, b1, bh, th, H);
            H.text(c, kmhText(H, d), x + b1 / 2, y + bh * 0.45, bh * 0.42, th.fg, o);
            H.text(c, 'km/h', x + b1 / 2, y + bh * 0.8, bh * 0.1, th.dim, { familie: H.SANS });
            x += b1 + g;
            karte(c, x, y, b, bh, th, H);
            var low = H.zahl(d.soc) && d.soc <= 15;
            H.text(c, H.zahl(d.soc) ? String(Math.round(d.soc)) : '–', x + b / 2, y + bh * 0.42, bh * 0.28, low ? th.warn : th.fg, o);
            H.text(c, '%', x + b / 2, y + bh * 0.66, bh * 0.1, th.dim, { familie: H.SANS });
            balken(c, x + b * 0.15, y + bh * 0.8, b * 0.7, bh * 0.05, th, H, H.zahl(d.soc) ? d.soc / 100 : 0, low ? th.warn : th.reku);
            x += b + g;
            karte(c, x, y, b, bh, th, H);
            H.text(c, H.zahl(d.reichweiteKm) ? String(Math.round(d.reichweiteKm)) : '–', x + b / 2, y + bh * 0.42, bh * 0.28, th.fg, o);
            H.text(c, 'km', x + b / 2, y + bh * 0.66, bh * 0.1, th.dim, { familie: H.SANS });
            x += b + g;
            karte(c, x, y, b, bh, th, H);
            if (d.navi) {
                H.pfeil(c, x + b / 2, y + bh * 0.38, Math.min(b * 0.5, bh * 0.4), d.navi.typ, th.ring, false);
                H.text(c, H.entfernung(d.navi.distanzM), x + b / 2, y + bh * 0.78, bh * 0.12, th.fg, o);
            } else {
                H.text(c, kwText(H, d), x + b / 2, y + bh * 0.42, bh * 0.28, th.fg, o);
                H.text(c, 'kW', x + b / 2, y + bh * 0.66, bh * 0.1, th.dim, { familie: H.SANS });
            }
        }
    });

    // ------------------------------------------------------------ 15 Tunnel: Fahrbahn in die Tiefe, Pfeil im Fluchtpunkt
    T.registriere('tunnel', {
        arten: ['navi'], zeiger: 'zahl', schrift: 'breit',
        plaetze: { links: 'akku', mitte: 'navi', rechts: 'tempo' },
        thema: thema({ bg0: '#0A0E1A', bg1: '#020308', fg: '#EEF2FF', dim: '#A3ACD0', leise: '#2A3156', spur: '#151A30',
            ring: '#9C8CFF', ring2: '#9C8CFF', reku: '#5CF2B5', ziffer: '700' }),
        zeichne: function (c, w, h, u, th, d, st, jetzt, H) {
            flaeche(c, w, h, th);
            var fx = w / 2, fy = h * 0.3, i;
            c.save();
            c.strokeStyle = th.leise;
            c.lineWidth = Math.max(1, u * 0.5);
            for (i = -3; i <= 3; i++) {
                c.beginPath();
                c.moveTo(fx + i * w * 0.004, fy);
                c.lineTo(fx + i * w * 0.16, h);
                c.stroke();
            }
            for (i = 1; i <= 5; i++) {
                var t = Math.pow(i / 5, 2), y = fy + (h - fy) * t, b = w * 0.48 * t;
                c.beginPath();
                c.moveTo(fx - b, y);
                c.lineTo(fx + b, y);
                c.stroke();
            }
            c.restore();
            if (d.navi) {
                H.pfeil(c, fx, fy - u * 2, u * 18, d.navi.typ, th.ring, true);
                H.text(c, H.entfernung(d.navi.distanzM), fx, fy + u * 14, u * 7, th.fg, { familie: BREIT, gewicht: '700' });
            }
            H.text(c, kmhText(H, d), w - u * 6, h * 0.62, u * 20, th.fg, { familie: BREIT, ausrichtung: 'right', gewicht: '700' });
            H.text(c, 'km/h', w - u * 6, h * 0.82, u * 5, th.dim, { familie: BREIT, ausrichtung: 'right' });
            var low = H.zahl(d.soc) && d.soc <= 15;
            H.text(c, socText(H, d), u * 6, h * 0.62, u * 12, low ? th.warn : th.fg, { familie: BREIT, ausrichtung: 'left', gewicht: '700' });
            H.text(c, rwText(H, d), u * 6, h * 0.82, u * 5, th.dim, { familie: BREIT, ausrichtung: 'left' });
            H.text(c, H.uhr(jetzt), u * 4, u * 7, u * 5.5, th.dim, { familie: BREIT, ausrichtung: 'left' });
            H.text(c, gang(d), w - u * 4, u * 7, u * 6.5, th.ring, { familie: BREIT, ausrichtung: 'right', gewicht: '700' });
        }
    });
})();
