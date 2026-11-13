/**
 * Panoptes — Tacho-Designs (0.1.37). Ein Renderer für das Fahrerdisplay
 * (local/tacho-ansicht.html in der TachoAnsichtActivity) und die Vorschau in der
 * Web-Oberfläche (vehicle-control.html). Eigene Grafik, nur Canvas 2D, kein Netz.
 *
 *   var t = PanoptesTacho.create(canvas, { design: 'klar' });
 *   t.setze({ kmh, kw, soc, reichweiteKm, gang, aussenC, kmStand, design,
 *             navi: { typ, distanzM, restM, restS, richtung, skizze:[x,y,x,y,…] } });
 *   t.design('sport');  t.stop();
 *   PanoptesTacho.demo(sekunden) → Beispieldaten (Vorschau, Browser-Test)
 *   PanoptesTacho.DESIGNS → ['klar','sport','nacht','horizont','route']
 *   0.1.41: Design 'eigen' mit d.eigen (Farben, Schrift, Zeigerform, feste Plätze), t.eigen(obj) für die
 *   Live-Vorschau, PanoptesTacho.pruefeEigen/vorlage/kontrast (Regeln wie TachoEigen.java).
 *
 * Werte gleiten pro Bild zum Ziel (Daten kommen alle 250 ms, 0.1.38). Es wird nur
 * gezeichnet, solange sich etwas bewegt (höchstens ~30 Bilder/s); sonst einmal pro Minute für die Uhr.
 * Kein Blinken, kein Pulsieren: das Fahrerdisplay soll ruhig bleiben.
 * Skizze = Route voraus in Metern {x rechts, y voraus}, relativ zum Auto.
 * ES5 (WebView 74 am Auto).
 */
(function () {
    'use strict';

    var DESIGNS = ['klar', 'sport', 'nacht', 'horizont', 'route'];
    var EIGEN = 'eigen';   // 0.1.41 „Tacho gestalten“, Daten in d.eigen (geprüft wie TachoEigen.java)
    var TAU = Math.PI * 2;
    var COND = "'Roboto Condensed','sans-serif-condensed','Arial Narrow','Helvetica Neue',sans-serif";
    var SANS = "'Roboto','sans-serif',Arial,sans-serif";

    // Farben je Design. ring = Grundton, ring2 = Verlauf zum Ende, reku = Rekuperation.
    var THEMEN = {
        klar: {
            bg0: '#0E1620', bg1: '#05090D', fg: '#EEF4F8', dim: '#7D8D9C', leise: '#2A3846',
            spur: '#18232E', ring: '#B5F23D', ring2: '#7FE3C4', reku: '#3DD6C6', warn: '#FFB547',
            glanz: 0.35, ziffer: '600', kursiv: false, stil: 'glatt'
        },
        sport: {
            bg0: '#17110F', bg1: '#060505', fg: '#FFFFFF', dim: '#9C8F8A', leise: '#3A2A26',
            spur: '#241A17', ring: '#FF3B1F', ring2: '#FFB020', reku: '#33E0A1', warn: '#FFB020',
            glanz: 0.5, ziffer: '700', kursiv: true, stil: 'segment'
        },
        nacht: {
            bg0: '#000000', bg1: '#000000', fg: '#A6A29C', dim: '#5E5A55', leise: '#24211E',
            spur: '#141210', ring: '#C4581F', ring2: '#C4581F', reku: '#4E8F86', warn: '#C4581F',
            glanz: 0, ziffer: '500', kursiv: false, stil: 'fein'
        },
        horizont: {
            bg0: '#0B2230', bg1: '#03070C', fg: '#F2F7FF', dim: '#8AA3B8', leise: '#24394A',
            spur: '#132634', ring: '#4FD1FF', ring2: '#A57CFF', reku: '#5CF2B5', warn: '#FFC069',
            glanz: 0.45, ziffer: '300', kursiv: false, stil: 'horizont'
        },
        route: {
            bg0: '#0A1218', bg1: '#030507', fg: '#EAF1F6', dim: '#7A8B98', leise: '#22313C',
            spur: '#16212A', ring: '#B5F23D', ring2: '#4FD1FF', reku: '#3DD6C6', warn: '#FFB547',
            glanz: 0.3, ziffer: '600', kursiv: false, stil: 'glatt'
        }
    };

    function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
    function zahl(v) { return typeof v === 'number' && isFinite(v); }
    function tausender(n) {
        var s = String(Math.round(n));
        return s.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    }
    function design(d) { return DESIGNS.indexOf(d) >= 0 || d === EIGEN ? d : 'klar'; }

    // 0.1.42 (T2 Tacho-Galerie): weitere Designs melden sich aus tacho-galerie.js an (eigene Datei, eigene Zeichnung).
    // Filter der Galerie: analog, digital, navi, ruhig.
    var ARTEN_LISTE = ['analog', 'digital', 'navi', 'ruhig'];
    var ARTEN = { klar: ['analog'], sport: ['analog'], nacht: ['analog', 'ruhig'], horizont: ['digital'], route: ['navi'] };
    var EXTRA = {};

    /** def = { thema, arten, zeichne(c, w, h, u, th, d, st, jetzt, H), zeiger?, schrift?, plaetze? } */
    function registriere(name, def) {
        if (!/^[a-z]{3,16}$/.test(name) || DESIGNS.indexOf(name) >= 0 || name === EIGEN) return false;
        if (!def || !def.thema || typeof def.zeichne !== 'function') return false;
        THEMEN[name] = def.thema;
        EXTRA[name] = def;
        ARTEN[name] = (def.arten || []).filter(function (a) { return ARTEN_LISTE.indexOf(a) >= 0; });
        DESIGNS.push(name);
        if (window.PanoptesTacho) window.PanoptesTacho.DESIGNS.push(name);
        return true;
    }

    // ------------------------------------------------------------ Manöver

    /** Valhalla-Typ → {art, winkel}. winkel in Grad, rechts positiv. */
    function manoeverArt(typ) {
        switch (typ) {
            case 4: case 5: case 6: return { art: 'ziel', winkel: 0 };
            case 9: case 23: return { art: 'abbiegen', winkel: 40 };
            case 16: case 24: return { art: 'abbiegen', winkel: -40 };
            case 10: case 18: case 20: case 2: return { art: 'abbiegen', winkel: 90 };
            case 15: case 19: case 21: case 3: return { art: 'abbiegen', winkel: -90 };
            case 11: return { art: 'abbiegen', winkel: 135 };
            case 14: return { art: 'abbiegen', winkel: -135 };
            case 12: return { art: 'wende', winkel: 1 };
            case 13: return { art: 'wende', winkel: -1 };
            case 26: case 27: return { art: 'kreisel', winkel: 0 };
            default: return { art: 'abbiegen', winkel: 0 };
        }
    }

    function entfernung(m) {
        if (!zahl(m)) return '';
        if (m >= 10000) return Math.round(m / 1000) + ' km';
        if (m >= 1000) return (Math.round(m / 100) / 10).toFixed(1).replace('.', ',') + ' km';
        if (m >= 100) return Math.round(m / 50) * 50 + ' m';
        return Math.max(0, Math.round(m / 10) * 10) + ' m';
    }

    function dauer(s) {
        if (!zahl(s)) return '';
        var min = Math.round(s / 60);
        if (min < 60) return min + ' min';
        return Math.floor(min / 60) + ' h ' + ('0' + (min % 60)).slice(-2);
    }

    /** Eigener Abbiegepfeil, mittig bei (x,y), Größe g. */
    function pfeil(c, x, y, g, typ, farbe, grund) {
        var m = manoeverArt(typ);
        c.save();
        c.translate(x, y);
        c.lineCap = 'round';
        c.lineJoin = 'round';
        c.strokeStyle = farbe;
        c.fillStyle = farbe;
        var b = g * 0.16;
        c.lineWidth = b;
        if (m.art === 'ziel') {
            c.beginPath();
            c.arc(0, -g * 0.12, g * 0.26, 0, TAU);
            c.stroke();
            c.beginPath();
            c.arc(0, -g * 0.12, g * 0.09, 0, TAU);
            c.fill();
            c.beginPath();
            c.moveTo(0, g * 0.14);
            c.lineTo(0, g * 0.45);
            c.stroke();
            c.restore();
            return;
        }
        if (m.art === 'kreisel') {
            c.globalAlpha = 0.45;
            c.beginPath();
            c.arc(0, -g * 0.02, g * 0.22, 0, TAU);
            c.stroke();
            c.globalAlpha = 1;
            c.beginPath();
            c.moveTo(0, g * 0.45);
            c.lineTo(0, g * 0.2);
            c.arc(0, -g * 0.02, g * 0.22, Math.PI / 2, -Math.PI * 0.05, true);
            c.stroke();
            spitze(c, g * 0.22 * Math.cos(-Math.PI * 0.05), -g * 0.02 + g * 0.22 * Math.sin(-Math.PI * 0.05),
                -Math.PI * 0.05 - Math.PI / 2 + Math.PI, g);
            c.restore();
            return;
        }
        if (m.art === 'wende') {
            var s = m.winkel;
            c.beginPath();
            c.moveTo(-s * g * 0.16, g * 0.45);
            c.lineTo(-s * g * 0.16, -g * 0.08);
            c.arc(0, -g * 0.08, g * 0.16, Math.PI, 0, s < 0);
            c.lineTo(s * g * 0.16, g * 0.12);
            c.stroke();
            spitze(c, s * g * 0.16, g * 0.2, Math.PI / 2, g);
            c.restore();
            return;
        }
        // Abbiegen: Stamm von unten zur Mitte, dann Ast im Winkel.
        var w = m.winkel * Math.PI / 180;
        var kx = 0, ky = 0;
        var len = g * (Math.abs(m.winkel) > 100 ? 0.3 : 0.38);
        var ex = kx + Math.sin(w) * len, ey = ky - Math.cos(w) * len;
        if (grund) {
            // Weggelassene Geradeaus-Spur andeuten.
            c.globalAlpha = 0.22;
            c.beginPath();
            c.moveTo(0, g * 0.45);
            c.lineTo(0, -g * 0.42);
            c.stroke();
            c.globalAlpha = 1;
        }
        c.beginPath();
        c.moveTo(0, g * 0.45);
        c.lineTo(kx, ky);
        c.lineTo(ex, ey);
        c.stroke();
        spitze(c, ex, ey, w - Math.PI / 2, g);
        c.restore();
    }

    function spitze(c, x, y, richtung, g) {
        var a = g * 0.2;
        c.save();
        c.translate(x, y);
        c.rotate(richtung);
        c.lineWidth = 1;
        c.beginPath();
        c.moveTo(a * 0.75, 0);
        c.lineTo(-a * 0.35, -a * 0.62);
        c.lineTo(-a * 0.35, a * 0.62);
        c.closePath();
        c.fill();
        c.restore();
    }

    // ------------------------------------------------------------ Bausteine

    function text(c, s, x, y, px, farbe, opt) {
        opt = opt || {};
        c.font = (opt.kursiv ? 'italic ' : '') + (opt.gewicht || '500') + ' ' + Math.max(1, Math.round(px)) + 'px ' + (opt.familie || COND);
        c.fillStyle = farbe;
        c.textAlign = opt.ausrichtung || 'center';
        c.textBaseline = opt.basis || 'middle';
        c.fillText(s, x, y);
    }

    function hintergrund(c, w, h, th, dsg) {
        var g = c.createRadialGradient(w / 2, h * 0.55, h * 0.1, w / 2, h * 0.55, w * 0.62);
        g.addColorStop(0, th.bg0);
        g.addColorStop(1, th.bg1);
        c.fillStyle = g;
        c.fillRect(0, 0, w, h);
        if (dsg === 'sport') {
            // Feine Diagonalschraffur (Karbon-Anmutung), sehr leise.
            c.save();
            c.globalAlpha = 0.07;
            c.strokeStyle = '#FFFFFF';
            c.lineWidth = 1;
            var step = Math.max(4, Math.round(h / 90));
            c.beginPath();
            for (var x = -h; x < w; x += step) { c.moveTo(x, h); c.lineTo(x + h, 0); }
            c.stroke();
            c.restore();
        }
        if (dsg === 'horizont') {
            // Horizontlinie mit weichem Schein.
            var hy = h * 0.66;
            var lg = c.createLinearGradient(0, 0, w, 0);
            lg.addColorStop(0, 'rgba(79,209,255,0)');
            lg.addColorStop(0.3, 'rgba(79,209,255,0.55)');
            lg.addColorStop(0.7, 'rgba(165,124,255,0.55)');
            lg.addColorStop(1, 'rgba(165,124,255,0)');
            c.fillStyle = lg;
            c.fillRect(0, hy, w, Math.max(1, h * 0.004));
            var sg = c.createLinearGradient(0, hy - h * 0.25, 0, hy);
            sg.addColorStop(0, 'rgba(79,209,255,0)');
            sg.addColorStop(1, 'rgba(79,209,255,0.08)');
            c.fillStyle = sg;
            c.fillRect(0, hy - h * 0.25, w, h * 0.25);
        }
    }

    /**
     * Ring-Instrument. von/bis in Bogenmaß (Uhrzeigersinn), anteil 0..1 ab nullAnteil
     * (negativ = in Gegenrichtung, z. B. Rekuperation).
     */
    function ring(c, x, y, r, th, anteil, nullAnteil, opt) {
        opt = opt || {};
        var von = Math.PI * 0.75, bis = Math.PI * 2.25, spanne = bis - von;
        var bw = r * (th.stil === 'fein' ? 0.05 : th.stil === 'segment' ? 0.13 : 0.1);
        c.save();
        c.lineCap = th.stil === 'segment' ? 'butt' : 'round';
        // Spur
        c.strokeStyle = th.spur;
        c.lineWidth = bw;
        c.beginPath();
        c.arc(x, y, r, von, bis);
        c.stroke();
        // Skala
        var striche = opt.striche || 10;
        c.strokeStyle = th.leise;
        c.lineWidth = Math.max(1, r * 0.012);
        for (var i = 0; i <= striche; i++) {
            var a = von + spanne * i / striche;
            var r1 = r - bw * 0.9, r2 = r - bw * (i % 2 === 0 ? 1.9 : 1.4);
            c.beginPath();
            c.moveTo(x + Math.cos(a) * r1, y + Math.sin(a) * r1);
            c.lineTo(x + Math.cos(a) * r2, y + Math.sin(a) * r2);
            c.stroke();
        }
        // Wert
        var a0 = von + spanne * nullAnteil;
        var a1 = von + spanne * clamp(nullAnteil + anteil, 0, 1);
        var farbe = opt.reku ? th.reku : th.ring;
        if (Math.abs(a1 - a0) > 0.002) {
            var anf = Math.min(a0, a1), ende = Math.max(a0, a1);
            if (th.stil === 'segment') {
                var seg = 36, lueck = spanne / seg;
                for (var s = 0; s < seg; s++) {
                    var sa = von + lueck * s, sb = sa + lueck * 0.72;
                    if (sb < anf - 0.001 || sa > ende + 0.001) continue;
                    var f = s / seg;
                    c.strokeStyle = opt.reku ? th.reku : mische(th.ring2, th.ring, f);
                    c.lineWidth = bw;
                    c.beginPath();
                    c.arc(x, y, r, Math.max(sa, anf), Math.min(sb, ende));
                    c.stroke();
                }
            } else {
                if (th.glanz > 0) {
                    c.save();
                    c.shadowColor = farbe;
                    c.shadowBlur = r * 0.18 * th.glanz;
                }
                if (th.ring2 !== th.ring && !opt.reku) {
                    var gx = c.createLinearGradient(x - r, y + r, x + r, y - r);
                    gx.addColorStop(0, th.ring2);
                    gx.addColorStop(1, th.ring);
                    c.strokeStyle = gx;
                } else {
                    c.strokeStyle = farbe;
                }
                c.lineWidth = bw;
                c.beginPath();
                c.arc(x, y, r, anf, ende);
                c.stroke();
                if (th.glanz > 0) c.restore();
            }
            // Zeigerkopf
            var kx = x + Math.cos(a1) * r, ky = y + Math.sin(a1) * r;
            c.fillStyle = th.stil === 'fein' ? farbe : th.fg;
            c.beginPath();
            c.arc(kx, ky, bw * (th.stil === 'fein' ? 0.9 : 0.42), 0, TAU);
            c.fill();
        }
        // Nullmarke bei geteilten Skalen
        if (nullAnteil > 0) {
            var an = von + spanne * nullAnteil;
            c.strokeStyle = th.dim;
            c.lineWidth = Math.max(1, r * 0.02);
            c.beginPath();
            c.moveTo(x + Math.cos(an) * (r - bw), y + Math.sin(an) * (r - bw));
            c.lineTo(x + Math.cos(an) * (r + bw), y + Math.sin(an) * (r + bw));
            c.stroke();
        }
        c.restore();
    }

    function hex(h) {
        var n = parseInt(h.slice(1), 16);
        return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    }
    function mische(a, b, f) {
        var x = hex(a), y = hex(b);
        return 'rgb(' + Math.round(x[0] + (y[0] - x[0]) * f) + ',' + Math.round(x[1] + (y[1] - x[1]) * f) + ',' +
            Math.round(x[2] + (y[2] - x[2]) * f) + ')';
    }

    function akkuBalken(c, x, y, w, h, th, soc) {
        var r = h / 2;
        c.save();
        rund(c, x, y, w, h, r);
        c.fillStyle = th.spur;
        c.fill();
        if (zahl(soc)) {
            var f = clamp(soc / 100, 0, 1);
            var farbe = soc <= 15 ? th.warn : th.ring;
            if (f > 0) {
                rund(c, x, y, Math.max(h, w * f), h, r);
                if (th.ring2 !== th.ring && soc > 15) {
                    var g = c.createLinearGradient(x, 0, x + w, 0);
                    g.addColorStop(0, th.ring2);
                    g.addColorStop(1, th.ring);
                    c.fillStyle = g;
                } else {
                    c.fillStyle = farbe;
                }
                c.fill();
            }
        }
        // Viertelmarken
        c.fillStyle = th.bg1;
        for (var i = 1; i < 4; i++) c.fillRect(x + w * i / 4 - 1, y, 2, h);
        c.restore();
    }

    function rund(c, x, y, w, h, r) {
        r = Math.min(r, h / 2, w / 2);
        c.beginPath();
        c.moveTo(x + r, y);
        c.arcTo(x + w, y, x + w, y + h, r);
        c.arcTo(x + w, y + h, x, y + h, r);
        c.arcTo(x, y + h, x, y, r);
        c.arcTo(x, y, x + w, y, r);
        c.closePath();
    }

    function uhr(jetzt) {
        return ('0' + jetzt.getHours()).slice(-2) + ':' + ('0' + jetzt.getMinutes()).slice(-2);
    }

    // ------------------------------------------------------------ Teile der Fläche

    function kopfzeile(c, w, h, u, th, d, jetzt) {
        var y = u * 7;
        text(c, uhr(jetzt), u * 4, y, u * 6.5, th.fg, { ausrichtung: 'left', gewicht: '500' });
        // Gang: alle vier, der aktive hervorgehoben.
        var gaenge = ['P', 'R', 'N', 'D'], gx = w / 2 - u * 10.5;
        for (var i = 0; i < gaenge.length; i++) {
            var an = d.gang === gaenge[i] || (gaenge[i] === 'D' && (d.gang === 'S' || d.gang === 'M'));
            text(c, an && gaenge[i] === 'D' && d.gang !== 'D' ? d.gang : gaenge[i], gx + i * u * 7, y,
                u * (an ? 6.5 : 5), an ? th.ring : th.leise, { gewicht: an ? '700' : '500' });
        }
        var rechts = [];
        if (d.navi && d.navi.richtung) rechts.push(d.navi.richtung);
        if (zahl(d.aussenC)) rechts.push(d.aussenC + '°C');
        text(c, rechts.join('   '), w - u * 4, y, u * 6, th.dim, { ausrichtung: 'right', gewicht: '500' });
    }

    function fusszeile(c, w, h, u, th, d) {
        var y = h - u * 8, bw = w * 0.36, bx = (w - bw) / 2;
        akkuBalken(c, bx, y - u * 1.6, bw, u * 3.2, th, d.soc);
        text(c, zahl(d.soc) ? Math.round(d.soc) + ' %' : '– %', bx - u * 3, y, u * 6, th.fg,
            { ausrichtung: 'right', gewicht: '600' });
        text(c, zahl(d.reichweiteKm) ? Math.round(d.reichweiteKm) + ' km' : '– km', bx + bw + u * 3, y, u * 6, th.fg,
            { ausrichtung: 'left', gewicht: '600' });
        if (zahl(d.kmStand)) {
            text(c, tausender(d.kmStand) + ' km', w - u * 4, y, u * 5.2, th.dim, { ausrichtung: 'right' });
        }
    }

    /** Mitte ohne Karte: Pfeil und Entfernung, sonst ruhige Reichweiten-Anzeige. */
    function mitte(c, x, y, bw, bh, u, th, d) {
        var n = d.navi;
        if (n) {
            pfeil(c, x, y - bh * 0.12, Math.min(bh * 0.55, bw * 0.5), n.typ, th.fg, true);
            text(c, entfernung(n.distanzM), x, y + bh * 0.25, u * 10, th.fg, { gewicht: th.ziffer, kursiv: th.kursiv });
            var rest = [];
            if (zahl(n.restM)) rest.push(entfernung(n.restM));
            if (zahl(n.restS)) rest.push(dauer(n.restS));
            if (rest.length) text(c, 'Ziel  ' + rest.join('  ·  '), x, y + bh * 0.42, u * 5.4, th.dim);
        } else {
            text(c, 'PANOPTES', x, y - bh * 0.05, u * 5, th.leise, { gewicht: '600', familie: SANS });
            text(c, 'keine Route', x, y + bh * 0.12, u * 4.2, th.leise);
        }
    }

    /** Leistungs-Skala, wächst mit dem gesehenen Höchstwert (alle BYD-Modelle). */
    function kwSkala(st, kw) {
        if (zahl(kw) && Math.abs(kw) > st.kwMax * 0.95) st.kwMax = Math.min(600, Math.ceil(Math.abs(kw) * 1.1 / 50) * 50);
        return st.kwMax;
    }

    function ringKw(c, x, y, r, u, th, d, st) {
        var max = kwSkala(st, d.kw), regMax = max / 3, nullA = regMax / (max + regMax);
        var kw = zahl(d.kw) ? d.kw : 0;
        ring(c, x, y, r, th, kw / (max + regMax), nullA, { reku: kw < 0, striche: 8 });
        text(c, zahl(d.kw) ? String(Math.round(Math.abs(kw))) : '–', x, y - r * 0.05, r * 0.62, th.fg,
            { gewicht: th.ziffer, kursiv: th.kursiv });
        text(c, kw < -0.5 ? 'kW  ↺' : 'kW', x, y + r * 0.42, r * 0.2, kw < -0.5 ? th.reku : th.dim);
    }

    function ringTempo(c, x, y, r, u, th, d) {
        var kmh = zahl(d.kmh) ? d.kmh : 0;
        ring(c, x, y, r, th, kmh / 200, 0, { striche: 10 });
        text(c, zahl(d.kmh) ? String(Math.round(kmh)) : '–', x, y - r * 0.05, r * 0.78, th.fg,
            { gewicht: th.ziffer, kursiv: th.kursiv });
        text(c, 'km/h', x, y + r * 0.45, r * 0.2, th.dim);
    }

    // ------------------------------------------------------------ Route (Mitte als Karte)

    function routeSkizze(c, x0, y0, bw, bh, u, th, d) {
        c.save();
        c.beginPath();
        rund(c, x0, y0, bw, bh, u * 4);
        c.clip();
        var bg = c.createLinearGradient(0, y0, 0, y0 + bh);
        bg.addColorStop(0, '#05080B');
        bg.addColorStop(1, '#0E1922');
        c.fillStyle = bg;
        c.fillRect(x0, y0, bw, bh);
        // Perspektive: Kamera hinter dem Auto, Blick voraus.
        // Kamera 120 m hinter dem Auto: das Auto sitzt bei 78 % unter dem Horizont, ±55 m sind auf seiner Höhe sichtbar.
        var cx = x0 + bw / 2, hor = y0 + bh * 0.16, abst = 120, ky0 = bh * 0.78 * abst, kx0 = bw * 1.1;
        function proj(x, y) {
            var z = y + abst;
            if (z < 1) z = 1;
            return [cx + x * kx0 / z, hor + ky0 / z];
        }
        // Boden-Raster
        c.strokeStyle = th.leise;
        c.globalAlpha = 0.5;
        c.lineWidth = 1;
        for (var gy = 0; gy <= 600; gy += 50) {
            var a = proj(-400, gy), b = proj(400, gy);
            c.beginPath(); c.moveTo(a[0], a[1]); c.lineTo(b[0], b[1]); c.stroke();
        }
        for (var gx = -400; gx <= 400; gx += 50) {
            var p = proj(gx, -abst + 2), q = proj(gx, 900);
            c.beginPath(); c.moveTo(p[0], p[1]); c.lineTo(q[0], q[1]); c.stroke();
        }
        c.globalAlpha = 1;
        var sk = d.navi && d.navi.skizze;
        if (sk && sk.length >= 4) {
            var pts = [];
            for (var i = 0; i + 1 < sk.length; i += 2) pts.push(proj(sk[i], sk[i + 1]));
            // Band: breiter Schatten, dann Linie im Verlauf.
            c.lineCap = 'round';
            c.lineJoin = 'round';
            c.strokeStyle = 'rgba(0,0,0,0.6)';
            c.lineWidth = u * 6;
            linie(c, pts);
            var lg = c.createLinearGradient(0, y0 + bh, 0, hor);
            lg.addColorStop(0, th.ring);
            lg.addColorStop(1, th.ring2);
            c.strokeStyle = lg;
            c.lineWidth = u * 3.2;
            if (th.glanz) { c.shadowColor = th.ring; c.shadowBlur = u * 3 * th.glanz; }
            linie(c, pts);
            c.shadowBlur = 0;
        } else if (!d.navi) {
            text(c, 'keine Route', cx, y0 + bh * 0.45, u * 4.4, th.dim);
        }
        // Auto-Pfeil unten
        var ap = proj(0, 0);
        c.fillStyle = th.fg;
        c.beginPath();
        c.moveTo(ap[0], ap[1] - u * 4.2);
        c.lineTo(ap[0] + u * 2.8, ap[1] + u * 2.4);
        c.lineTo(ap[0], ap[1] + u * 1.2);
        c.lineTo(ap[0] - u * 2.8, ap[1] + u * 2.4);
        c.closePath();
        c.fill();
        // Abdunkelung zum Horizont
        var fog = c.createLinearGradient(0, y0, 0, hor + bh * 0.12);
        fog.addColorStop(0, 'rgba(3,5,7,1)');
        fog.addColorStop(1, 'rgba(3,5,7,0)');
        c.fillStyle = fog;
        c.fillRect(x0, y0, bw, hor + bh * 0.12 - y0);
        c.restore();
        // Manöver-Karte oben in der Karte
        if (d.navi) {
            var kh = bh * 0.26, kw2 = Math.min(bw - u * 5, kh * 3.4), kx = x0 + u * 2.5, ky = y0 + u * 2.5;
            c.save();
            rund(c, kx, ky, kw2, kh, u * 3);
            c.fillStyle = 'rgba(12,20,26,0.92)';
            c.fill();
            c.strokeStyle = th.leise;
            c.lineWidth = 1;
            c.stroke();
            c.restore();
            pfeil(c, kx + kh * 0.6, ky + kh / 2, kh * 0.8, d.navi.typ, th.ring, false);
            text(c, entfernung(d.navi.distanzM), kx + kh * 1.2, ky + kh * 0.42, kh * 0.42, th.fg,
                { ausrichtung: 'left', gewicht: '700' });
            var r2 = [];
            if (zahl(d.navi.restM)) r2.push(entfernung(d.navi.restM));
            if (zahl(d.navi.restS)) r2.push(dauer(d.navi.restS));
            if (r2.length) text(c, r2.join(' · '), kx + kh * 1.2, ky + kh * 0.78, kh * 0.24, th.dim, { ausrichtung: 'left' });
        }
        c.strokeStyle = th.leise;
        c.lineWidth = 1;
        rund(c, x0, y0, bw, bh, u * 4);
        c.stroke();
    }

    function linie(c, pts) {
        c.beginPath();
        c.moveTo(pts[0][0], pts[0][1]);
        for (var i = 1; i < pts.length; i++) c.lineTo(pts[i][0], pts[i][1]);
        c.stroke();
    }

    // ------------------------------------------------------------ Horizont-Layout

    function horizont(c, w, h, u, th, d, st) {
        var hy = h * 0.66;
        // Großer Bogen über die ganze Breite: Tempo.
        // Kuppel: Mittelpunkt unter dem Horizont, die Enden liegen auf dem Horizont.
        var R = Math.min(w * 0.6, (hy - u * 14) / 0.38), cx = w / 2, cy = hy + R * 0.62;
        var halb = Math.acos(0.62), von = Math.PI * 1.5 - halb, bis = Math.PI * 1.5 + halb;
        c.save();
        c.lineCap = 'round';
        c.strokeStyle = th.spur;
        c.lineWidth = u * 2;
        c.beginPath(); c.arc(cx, cy, R, von, bis); c.stroke();
        var kmh = zahl(d.kmh) ? d.kmh : 0, f = clamp(kmh / 200, 0, 1);
        if (f > 0.001) {
            var g = c.createLinearGradient(cx - R, 0, cx + R, 0);
            g.addColorStop(0.2, th.ring);
            g.addColorStop(0.8, th.ring2);
            c.strokeStyle = g;
            c.shadowColor = th.ring;
            c.shadowBlur = u * 4 * th.glanz;
            c.beginPath(); c.arc(cx, cy, R, von, von + (bis - von) * f); c.stroke();
            c.shadowBlur = 0;
        }
        // Skalenpunkte alle 20 km/h
        c.fillStyle = th.dim;
        for (var i = 0; i <= 10; i++) {
            var a = von + (bis - von) * i / 10;
            c.beginPath();
            c.arc(cx + Math.cos(a) * (R - u * 3.4), cy + Math.sin(a) * (R - u * 3.4), u * (i % 5 === 0 ? 0.7 : 0.4), 0, TAU);
            c.fill();
        }
        c.restore();
        // Tempo groß in der Mitte
        text(c, zahl(d.kmh) ? String(Math.round(kmh)) : '–', cx, hy - h * 0.19, h * 0.3, th.fg,
            { gewicht: th.ziffer, familie: SANS });
        text(c, 'km/h', cx, hy - h * 0.035, u * 5.4, th.dim);
        // Linker Flügel: Leistung als Balken auf dem Horizont.
        var max = kwSkala(st, d.kw), kw = zahl(d.kw) ? d.kw : 0;
        var lx = w * 0.06, lw = w * 0.26, ly = hy + u * 5;
        c.fillStyle = th.spur;
        c.fillRect(lx, ly, lw, u * 1.4);
        var nullX = lx + lw * 0.25;
        var px = kw >= 0 ? nullX + (lw * 0.75) * clamp(kw / max, 0, 1) : nullX - lw * 0.25 * clamp(-kw / (max / 3), 0, 1);
        c.fillStyle = kw < 0 ? th.reku : th.ring;
        c.fillRect(Math.min(nullX, px), ly, Math.abs(px - nullX), u * 1.4);
        c.fillStyle = th.dim;
        c.fillRect(nullX - 1, ly - u * 1, 2, u * 3.4);
        text(c, (zahl(d.kw) ? Math.round(kw) : '–') + ' kW', lx, ly + u * 7, u * 6, kw < -0.5 ? th.reku : th.fg,
            { ausrichtung: 'left', gewicht: '500' });
        // Rechter Flügel: Akku und Reichweite.
        var rx = w * 0.68, rw = w * 0.26;
        akkuBalken(c, rx, ly, rw, u * 1.4, th, d.soc);
        text(c, (zahl(d.soc) ? Math.round(d.soc) : '–') + ' %', rx + rw, ly + u * 7, u * 6, th.fg,
            { ausrichtung: 'right', gewicht: '500' });
        text(c, zahl(d.reichweiteKm) ? Math.round(d.reichweiteKm) + ' km' : '', rx, ly + u * 7, u * 5.4, th.dim,
            { ausrichtung: 'left' });
        // Navi unter dem Horizont, mittig.
        if (d.navi) {
            pfeil(c, cx - u * 9, hy + u * 12, u * 13, d.navi.typ, th.ring, false);
            text(c, entfernung(d.navi.distanzM), cx - u * 1, hy + u * 12, u * 7.5, th.fg,
                { ausrichtung: 'left', gewicht: '500' });
        } else if (zahl(d.kmStand)) {
            text(c, tausender(d.kmStand) + ' km', cx, hy + u * 12, u * 5.2, th.dim);
        }
    }

    // ------------------------------------------------------------ Eigenes Design (0.1.41 „Tacho gestalten“)
    // Gleiche Regeln wie TachoEigen.java: feste Erlaubnislisten, Tempo Pflicht auf großem Platz,
    // Ziffern (Tempo, Gang) mit mindestens 4,5:1 Kontrast, sonst Standardfarben.

    var E = {
        FORMAT: 'panoptes-tacho-design',
        VERSION: 1,
        MIN_KONTRAST: 4.5,
        MAX_ZEICHEN: 40000,
        FARBEN: ['hintergrund', 'ziffern', 'zeiger', 'akzent'],
        SCHRIFTEN: ['schmal', 'normal', 'breit'],
        ZEIGER: ['nadel', 'balken', 'zahl'],
        GROSS: ['links', 'mitte', 'rechts'],
        KLEIN: ['oben_links', 'oben_mitte', 'oben_rechts', 'unten_links', 'unten_mitte', 'unten_rechts'],
        ANZEIGEN: ['tempo', 'akku', 'reichweite', 'leistung', 'gang', 'uhr', 'aussen', 'navi', 'kmstand', 'leer'],
        NUR_GROSS: ['tempo', 'navi'],
        // 0.1.44 (E1) Version 2 (freier Editor), gleiche Werte wie TachoFrei.java
        VERSION_FREI: 2,
        BREITE: 1000,
        HOEHE: 450,
        MIN_GROESSE: 4,
        MAX_ELEMENTE: 40,
        MAX_BILD: 24000,
        SCHLEIER: 0.7,
        TEMPO_MIN_HOEHE: 90,
        TEMPO_MIN_BREITE: 150,
        TYPEN: ['rund', 'zahl', 'balken', 'gang', 'navi', 'uhr', 'ring', 'linie', 'flaeche', 'verlauf'],
        FORMEN: ['ring', 'linie', 'flaeche', 'verlauf'],
        RUND_WERTE: ['tempo', 'leistung', 'akku'],
        ZAHL_WERTE: ['tempo', 'leistung', 'akku', 'reichweite', 'aussen', 'kmstand'],
        BALKEN_WERTE: ['leistung', 'akku'],
        ZEIGERFORMEN: ['nadel', 'balken', 'ring'],
        RICHTUNGEN: ['waagrecht', 'senkrecht'],
        AUSRICHTUNGEN: ['links', 'mitte', 'rechts'],
        HG_ARTEN: ['farbe', 'verlauf']
    };
    var FAMILIEN = {
        schmal: COND,
        normal: SANS,
        breit: "Verdana,'DejaVu Sans','Roboto',sans-serif"
    };
    var HEXFARBE = /^#[0-9A-Fa-f]{6}$/;

    function standardPlaetze() {
        return {
            links: 'leistung', mitte: 'navi', rechts: 'tempo',
            oben_links: 'uhr', oben_mitte: 'gang', oben_rechts: 'aussen',
            unten_links: 'akku', unten_mitte: 'reichweite', unten_rechts: 'kmstand'
        };
    }

    /** Ausgangspunkt „Kopie bearbeiten“: Farben eines fertigen Designs, Standard-Anordnung. */
    function vorlage(dsg) {
        var n = DESIGNS.indexOf(dsg) >= 0 ? dsg : 'klar', th = THEMEN[n], x = EXTRA[n] || {};
        var p = standardPlaetze(), k;
        if (x.plaetze) for (k in x.plaetze) if (p.hasOwnProperty(k)) p[k] = x.plaetze[k];
        return {
            format: E.FORMAT, version: E.VERSION, basis: n,
            farben: { hintergrund: th.bg0, ziffern: th.fg, zeiger: th.ring, akzent: th.ring2 === th.ring ? th.reku : th.ring2 },
            schrift: x.schrift || (n === 'horizont' ? 'normal' : 'schmal'),
            zeiger: x.zeiger || (n === 'nacht' ? 'nadel' : 'balken'),
            plaetze: p
        };
    }

    function standardEigen() { return vorlage('klar'); }

    function helligkeit(h) {
        var x = hex(h), s = 0, gew = [0.2126, 0.7152, 0.0722];
        for (var i = 0; i < 3; i++) {
            var k = x[i] / 255;
            s += gew[i] * (k <= 0.03928 ? k / 12.92 : Math.pow((k + 0.055) / 1.055, 2.4));
        }
        return s;
    }

    /** WCAG-Kontrast zweier Farben #RRGGBB (1..21). */
    function kontrast(a, b) {
        if (!HEXFARBE.test(a || '') || !HEXFARBE.test(b || '')) return 1;
        var la = helligkeit(a), lb = helligkeit(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    function wahl(v, liste, sonst) {
        var s = typeof v === 'string' ? v.trim().toLowerCase() : '';
        return liste.indexOf(s) >= 0 ? s : sonst;
    }

    /**
     * Prüft ein Design gegen die Erlaubnislisten. null = kein Design (falsches Format/Version).
     * Sonst { eigen, warnungen } mit vollständigem, sicherem Design.
     */
    function pruefeEigen(roh) {
        if (!roh || typeof roh !== 'object') return null;
        if (roh.format !== undefined && roh.format !== E.FORMAT) return null;
        if (roh.version === E.VERSION_FREI) return pruefeFrei(roh);   // 0.1.44 (E1)
        if (roh.version !== undefined && roh.version !== E.VERSION) return null;
        var std = standardEigen(), warn = [], i;
        var f = {}, rf = roh.farben && typeof roh.farben === 'object' ? roh.farben : null, falsch = false;
        for (i = 0; i < E.FARBEN.length; i++) {
            var k = E.FARBEN[i], v = rf ? rf[k] : undefined;
            if (typeof v === 'string' && HEXFARBE.test(v)) f[k] = v.toUpperCase();
            else { f[k] = std.farben[k]; if (rf && v !== undefined) falsch = true; }
        }
        if (falsch) warn.push('farbe_ungueltig');
        if (kontrast(f.ziffern, f.hintergrund) < E.MIN_KONTRAST) {
            f = {};
            for (i = 0; i < E.FARBEN.length; i++) f[E.FARBEN[i]] = std.farben[E.FARBEN[i]];
            warn.push('kontrast');
        }
        var rp = roh.plaetze && typeof roh.plaetze === 'object' ? roh.plaetze : {};
        var plaetze = {}, belegt = {}, alle = E.GROSS.concat(E.KLEIN);
        for (i = 0; i < alle.length; i++) {
            var p = alle[i], a = wahl(rp[p], E.ANZEIGEN, 'leer');
            if (E.KLEIN.indexOf(p) >= 0 && E.NUR_GROSS.indexOf(a) >= 0) a = 'leer';
            if (a !== 'leer' && belegt[a]) a = 'leer';
            belegt[a] = true;
            plaetze[p] = a;
        }
        if (!belegt.tempo) { plaetze.rechts = 'tempo'; warn.push('tempo_pflicht'); }
        return {
            eigen: {
                format: E.FORMAT, version: E.VERSION, basis: wahl(roh.basis, DESIGNS, 'klar'), farben: f,
                schrift: wahl(roh.schrift, E.SCHRIFTEN, std.schrift), zeiger: wahl(roh.zeiger, E.ZEIGER, std.zeiger),
                plaetze: plaetze
            },
            warnungen: warn
        };
    }

    /** Farbsatz für den Renderer aus den vier gewählten Farben. */
    function eigenThema(e) {
        var f = e.farben;
        return {
            bg0: f.hintergrund, bg1: f.hintergrund, fg: f.ziffern,
            dim: mische(f.ziffern, f.hintergrund, 0.42), leise: mische(f.ziffern, f.hintergrund, 0.72),
            spur: mische(f.ziffern, f.hintergrund, 0.86), ring: f.zeiger, ring2: f.zeiger, reku: f.akzent,
            warn: '#FFB547', glanz: 0, ziffer: e.schrift === 'breit' ? '700' : '600', kursiv: false,
            stil: 'glatt', familie: FAMILIEN[e.schrift] || COND
        };
    }

    /** Wert, Einheit und Anteil (0..1, null = ohne Skala) einer Anzeige. */
    function anzeigeWert(a, d, st, jetzt) {
        switch (a) {
            case 'tempo': return { t: zahl(d.kmh) ? String(Math.round(d.kmh)) : '–', e: 'km/h', anteil: (zahl(d.kmh) ? d.kmh : 0) / 200, nullA: 0 };
            case 'akku': return { t: zahl(d.soc) ? String(Math.round(d.soc)) : '–', e: '%', anteil: (zahl(d.soc) ? d.soc : 0) / 100, nullA: 0 };
            case 'leistung': {
                var max = kwSkala(st, d.kw), reg = max / 3, kw = zahl(d.kw) ? d.kw : 0;
                return { t: zahl(d.kw) ? String(Math.round(Math.abs(kw))) : '–', e: kw < -0.5 ? 'kW  ↺' : 'kW',
                    anteil: kw / (max + reg), nullA: reg / (max + reg), reku: kw < 0 };
            }
            case 'reichweite': return { t: zahl(d.reichweiteKm) ? String(Math.round(d.reichweiteKm)) : '–', e: 'km', anteil: null };
            case 'gang': return { t: d.gang || '–', e: '', anteil: null, gang: true };
            case 'uhr': return { t: uhr(jetzt), e: '', anteil: null };
            case 'aussen': return { t: zahl(d.aussenC) ? d.aussenC + '°' : '–', e: 'C', anteil: null };
            case 'kmstand': return { t: zahl(d.kmStand) ? tausender(d.kmStand) : '–', e: 'km', anteil: null };
            default: return null;
        }
    }

    function nadel(c, x, y, r, th, anteil, nullA) {
        var von = Math.PI * 0.75, spanne = Math.PI * 1.5;
        c.save();
        c.lineCap = 'round';
        c.strokeStyle = th.spur;
        c.lineWidth = Math.max(2, r * 0.035);
        c.beginPath();
        c.arc(x, y, r, von, von + spanne);
        c.stroke();
        c.strokeStyle = th.leise;
        c.lineWidth = Math.max(1, r * 0.02);
        for (var i = 0; i <= 10; i++) {
            var a = von + spanne * i / 10, r2 = r - r * (i % 2 === 0 ? 0.14 : 0.08);
            c.beginPath();
            c.moveTo(x + Math.cos(a) * r, y + Math.sin(a) * r);
            c.lineTo(x + Math.cos(a) * r2, y + Math.sin(a) * r2);
            c.stroke();
        }
        var w = von + spanne * clamp(nullA + anteil, 0, 1);
        c.strokeStyle = th.ring;
        c.lineWidth = Math.max(3, r * 0.05);
        c.beginPath();
        c.moveTo(x + Math.cos(w) * r * 0.12, y + Math.sin(w) * r * 0.12);
        c.lineTo(x + Math.cos(w) * r * 0.92, y + Math.sin(w) * r * 0.92);
        c.stroke();
        c.fillStyle = th.ring;
        c.beginPath();
        c.arc(x, y, r * 0.07, 0, TAU);
        c.fill();
        c.restore();
    }

    function grossPlatz(c, a, x, y, r, bw, bh, u, th, d, st, jetzt, zeiger) {
        if (a === 'leer') return;
        if (a === 'navi') { mitte(c, x, y, bw, bh, u, th, d); return; }
        var v = anzeigeWert(a, d, st, jetzt);
        if (!v) return;
        var opt = { gewicht: th.ziffer, familie: th.familie };
        var skala = v.anteil !== null && zeiger !== 'zahl';
        if (skala && zeiger === 'nadel') {
            nadel(c, x, y, r, th, v.anteil, v.nullA || 0);
            text(c, v.t, x, y + r * 0.42, r * 0.42, th.fg, opt);
            text(c, v.e, x, y + r * 0.72, r * 0.17, v.reku ? th.reku : th.dim, { familie: th.familie });
            return;
        }
        if (skala) ring(c, x, y, r, th, v.anteil, v.nullA || 0, { reku: v.reku, striche: 10 });
        text(c, v.t, x, y - r * 0.05, r * (v.gang ? 0.9 : v.t.length > 4 ? 0.48 : 0.74), th.fg, opt);
        if (v.e) text(c, v.e, x, y + r * 0.45, r * 0.2, v.reku ? th.reku : th.dim, { familie: th.familie });
    }

    function kleinPlatz(c, a, x, y, aus, u, th, d, st, jetzt) {
        if (a === 'leer') return;
        var v = anzeigeWert(a, d, st, jetzt);
        if (!v) return;
        var s = v.e ? v.t + (v.e === 'C' ? 'C' : ' ' + v.e) : v.t;
        // Gang in Ziffernfarbe (Kontrast geprüft), alles andere ruhiger.
        text(c, s, x, y, u * (v.gang ? 7 : 6), v.gang || a === 'uhr' ? th.fg : th.dim,
            { ausrichtung: aus, gewicht: v.gang ? '700' : '600', familie: th.familie });
    }

    function zeichneEigen(c, w, h, u, roh, d, st, jetzt) {
        // roh ist schon in setze()/eigen() geprüft; ohne Design gilt der Standard.
        var e = roh && roh.farben && roh.plaetze ? roh : standardEigen(), th = eigenThema(e), p = e.plaetze;
        c.fillStyle = th.bg0;
        c.fillRect(0, 0, w, h);
        var oben = u * 7, unten = h - u * 8, rand = u * 4;
        var xs = [rand, w / 2, w - rand], aus = ['left', 'center', 'right'];
        for (var i = 0; i < 3; i++) {
            kleinPlatz(c, p[E.KLEIN[i]], xs[i], oben, aus[i], u, th, d, st, jetzt);
            kleinPlatz(c, p[E.KLEIN[i + 3]], xs[i], unten, aus[i], u, th, d, st, jetzt);
        }
        var cy = h * 0.53, r = Math.min(h * 0.31, w * 0.135);
        var lx = Math.max(r * 1.25, w * 0.17), rx = w - lx;
        var mittelBreite = (rx - lx) - r * 2.6;
        grossPlatz(c, p.links, lx, cy, r, r * 2.4, h * 0.62, u, th, d, st, jetzt, e.zeiger);
        grossPlatz(c, p.mitte, w / 2, cy, Math.min(r, mittelBreite / 2.2), mittelBreite, h * 0.62, u, th, d, st, jetzt, e.zeiger);
        grossPlatz(c, p.rechts, rx, cy, r, r * 2.4, h * 0.62, u, th, d, st, jetzt, e.zeiger);
    }

    // ------------------------------------------------------------ 0.1.44 (E1) Freier Editor: Version 2
    // Elemente frei auf einer Fläche E.BREITE × E.HOEHE (Format des Fahrerdisplays). Gleiche Regeln wie
    // TachoFrei.java (Vertragstest TachoFreiTest): Erlaubnislisten, Tempo genau einmal und groß genug,
    // Kontrast je Element zur Fläche dahinter, nichts außerhalb des Bildes, Bild nur klein und mit Schleier.

    function liste2(v, l, sonst) { return typeof v === 'string' && l.indexOf(v.trim().toLowerCase()) >= 0 ? v.trim().toLowerCase() : sonst; }
    function klemme(v, a, b) { return v < a ? a : v > b ? b : v; }
    function ganz(o, k, sonst) {
        var v = o[k];
        if (typeof v !== 'number' || !isFinite(v)) return sonst;
        return Math.max(-100000, Math.min(100000, Math.round(v)));
    }
    function farbe2(o, k, sonst, warn) {
        var v = o ? o[k] : undefined;
        if (typeof v === 'string' && HEXFARBE.test(v)) return v.toUpperCase();
        if (o && v !== undefined && warn.indexOf('farbe_ungueltig') < 0) warn.push('farbe_ungueltig');
        return sonst;
    }
    /** Lineare Mischung #RRGGBB (wie TachoFrei.mische). */
    function mischeHex(a, b, t) {
        t = clamp(t, 0, 1);
        var x = hex(a), y = hex(b), s = '#';
        for (var i = 0; i < 3; i++) {
            var k = Math.floor(x[i] + (y[i] - x[i]) * t + 0.5).toString(16).toUpperCase();
            s += k.length < 2 ? '0' + k : k;
        }
        return s;
    }
    function skalaStd(w) { return w === 'tempo' ? [0, 200] : w === 'leistung' ? [-50, 150] : [0, 100]; }
    function infoTyp(t) { return E.FORMEN.indexOf(t) < 0; }

    var BILD_RE = /^data:image\/(jpeg|png);base64,[A-Za-z0-9+\/]+={0,2}$/;
    function bildOk(s) {
        if (typeof s !== 'string' || s.length > E.MAX_BILD || !BILD_RE.test(s)) return false;
        try {
            var d = atob(s.slice(s.indexOf(',') + 1));
            if (s.indexOf('data:image/jpeg') === 0) return d.length > 3 && d.charCodeAt(0) === 0xFF && d.charCodeAt(1) === 0xD8 && d.charCodeAt(2) === 0xFF;
            return d.length > 8 && d.charCodeAt(0) === 0x89 && d.charAt(1) === 'P' && d.charAt(2) === 'N' && d.charAt(3) === 'G';
        } catch (x) { return false; }
    }

    function elementPruefen(r, warn) {
        if (!r || typeof r !== 'object') return null;
        var typ = liste2(r.typ, E.TYPEN, '');
        if (!typ) return null;
        var e = { typ: typ };
        if (typ === 'rund' || typ === 'zahl' || typ === 'balken') {
            var w = liste2(r.wert, typ === 'rund' ? E.RUND_WERTE : typ === 'zahl' ? E.ZAHL_WERTE : E.BALKEN_WERTE, '');
            if (!w) return null;
            e.wert = w;
        }
        var b = klemme(ganz(r, 'b', 200), E.MIN_GROESSE, E.BREITE), h = klemme(ganz(r, 'h', 120), E.MIN_GROESSE, E.HOEHE);
        e.x = klemme(ganz(r, 'x', 0), 0, E.BREITE - b);
        e.y = klemme(ganz(r, 'y', 0), 0, E.HOEHE - h);
        e.b = b; e.h = h;
        e.farbe = farbe2(r, 'farbe', '#FFFFFF', warn);
        if (typ === 'rund' || typ === 'balken') {
            if (typ === 'rund') e.form = liste2(r.form, E.ZEIGERFORMEN, 'ring');
            else e.richtung = liste2(r.richtung, E.RICHTUNGEN, 'waagrecht');
            var s = skalaStd(e.wert), mn = klemme(ganz(r, 'min', s[0]), -1000, 1000), mx = klemme(ganz(r, 'max', s[1]), -1000, 1000);
            if (mx <= mn) { mn = s[0]; mx = s[1]; }
            e.min = mn; e.max = mx;
            if (typ === 'rund') e.drehung = klemme(ganz(r, 'drehung', 0), -180, 180);
            e.akzent = farbe2(r, 'akzent', '#B5F23D', warn);
            if (typ === 'rund') e.schrift = liste2(r.schrift, E.SCHRIFTEN, 'schmal');
        } else if (typ === 'zahl' || typ === 'gang' || typ === 'uhr') {
            e.schrift = liste2(r.schrift, E.SCHRIFTEN, 'schmal');
            e.ausrichtung = liste2(r.ausrichtung, E.AUSRICHTUNGEN, 'mitte');
        } else if (typ === 'navi') {
            e.schrift = liste2(r.schrift, E.SCHRIFTEN, 'schmal');
        } else if (typ === 'ring' || typ === 'linie') {
            e.dicke = klemme(ganz(r, 'dicke', 4), 1, 60);
        } else if (typ === 'flaeche') {
            e.ecken = klemme(ganz(r, 'ecken', 0), 0, 100);
        } else if (typ === 'verlauf') {
            e.farbe2 = farbe2(r, 'farbe2', '#000000', warn);
            e.richtung = liste2(r.richtung, E.RICHTUNGEN, 'waagrecht');
        }
        return e;
    }

    /** Farben hinter dem Mittelpunkt von Element i (oberste deckende Form, sonst Hintergrund; mit Bild beide Grenzen). */
    function dahinter(el, i, hg, mitBild) {
        var e = el[i], cx = e.x + e.b / 2, cy = e.y + e.h / 2;
        for (var j = i - 1; j >= 0; j--) {
            var f = el[j];
            if (f.typ !== 'flaeche' && f.typ !== 'verlauf') continue;
            if (cx < f.x || cx > f.x + f.b || cy < f.y || cy > f.y + f.h) continue;
            if (f.typ === 'flaeche') return [f.farbe];
            return [mischeHex(f.farbe, f.farbe2, f.richtung === 'senkrecht' ? (cy - f.y) / f.h : (cx - f.x) / f.b)];
        }
        var grund = hg.farbe;
        if (hg.art === 'verlauf') grund = mischeHex(hg.farbe, hg.farbe2, hg.richtung === 'waagrecht' ? cx / E.BREITE : cy / E.HOEHE);
        if (!mitBild) return [grund];
        return [mischeHex('#000000', grund, E.SCHLEIER), mischeHex('#FFFFFF', grund, E.SCHLEIER)];
    }
    function minKontrast(f, hinten) {
        var m = 21;
        for (var i = 0; i < hinten.length; i++) m = Math.min(m, kontrast(f, hinten[i]));
        return m;
    }

    /** Prüft ein Design der Version 2. Ergebnis wie pruefeEigen: { eigen, warnungen }. */
    function pruefeFrei(roh) {
        var warn = [], i;
        var rh = roh.hintergrund && typeof roh.hintergrund === 'object' ? roh.hintergrund : null;
        var hg = {
            art: liste2(rh ? rh.art : '', E.HG_ARTEN, 'farbe'),
            farbe: farbe2(rh, 'farbe', '#0E1620', warn),
            farbe2: farbe2(rh, 'farbe2', '#05090D', warn),
            richtung: liste2(rh ? rh.richtung : '', E.RICHTUNGEN, 'senkrecht')
        };
        var bild = null;
        if (typeof roh.bild === 'string' && roh.bild.length > 0) { if (bildOk(roh.bild)) bild = roh.bild; else warn.push('bild_ungueltig'); }
        var re = Object.prototype.toString.call(roh.elemente) === '[object Array]' ? roh.elemente : [], el = [];
        if (re.length > E.MAX_ELEMENTE) warn.push('zu_viele');
        for (i = 0; i < re.length && el.length < E.MAX_ELEMENTE; i++) {
            var e = elementPruefen(re[i], warn);
            if (e) el.push(e);
        }
        var tempo = -1;
        for (i = 0; i < el.length; i++) {
            if (el[i].wert !== 'tempo') continue;
            if (tempo < 0) { tempo = i; continue; }
            el.splice(i--, 1);
            if (warn.indexOf('tempo_einmal') < 0) warn.push('tempo_einmal');
        }
        if (tempo < 0) {
            el.push({ typ: 'zahl', wert: 'tempo', x: 700, y: 150, b: 260, h: 150, farbe: '#FFFFFF', schrift: 'schmal', ausrichtung: 'mitte' });
            warn.push('tempo_pflicht');
        } else {
            var t = el[tempo];
            if (t.h < E.TEMPO_MIN_HOEHE || t.b < E.TEMPO_MIN_BREITE) {
                var nb = Math.max(t.b, E.TEMPO_MIN_BREITE), nh = Math.max(t.h, E.TEMPO_MIN_HOEHE);
                var cx = t.x + Math.floor(t.b / 2), cy = t.y + Math.floor(t.h / 2);
                t.b = nb; t.h = nh;
                t.x = klemme(cx - Math.floor(nb / 2), 0, E.BREITE - nb);
                t.y = klemme(cy - Math.floor(nh / 2), 0, E.HOEHE - nh);
                warn.push('tempo_groesse');
            }
        }
        var kw = false;
        for (var runde = 0; runde < 2; runde++) {
            var weg = false;
            for (i = 0; i < el.length; i++) {
                var x = el[i];
                if (!infoTyp(x.typ)) continue;
                var feld = x.typ === 'balken' ? 'akzent' : 'farbe', hinten = dahinter(el, i, hg, bild !== null);
                if (minKontrast(x[feld], hinten) >= E.MIN_KONTRAST) continue;
                var best = minKontrast('#FFFFFF', hinten) >= minKontrast('#000000', hinten) ? '#FFFFFF' : '#000000';
                if (minKontrast(best, hinten) < E.MIN_KONTRAST) { weg = true; break; }
                x[feld] = best;
                kw = true;
            }
            if (!weg) break;
            bild = null;
            warn.push('bild_kontrast');
        }
        if (kw) warn.push('kontrast');
        var o = { format: E.FORMAT, version: E.VERSION_FREI, basis: liste2(roh.basis, DESIGNS, 'klar'), hintergrund: hg };
        if (bild) o.bild = bild;
        o.elemente = el;
        return { eigen: o, warnungen: warn };
    }

    /** Leerer Start: nur die Pflicht-Geschwindigkeit. */
    function leerFrei() {
        return pruefeFrei({ hintergrund: { art: 'farbe', farbe: '#0E1620' }, elemente: [
            { typ: 'zahl', wert: 'tempo', x: 370, y: 150, b: 260, h: 150, farbe: '#FFFFFF' }] }).eigen;
    }

    /**
     * Version 1 (feste Plätze) oder ein Galerie-Design in Elemente zerlegen. Ergebnis { eigen, zerlegt }:
     * zerlegt = false, wenn das Design eine eigene Zeichnung hat, die sich nicht zerlegen lässt (ehrlicher Hinweis).
     */
    function zuElementen(v1, dsg) {
        var f = v1.farben, p = v1.plaetze, el = [], i;
        var zeiger = v1.zeiger === 'nadel' ? 'nadel' : 'ring';
        var gross = { links: [35, 103, 270, 270], mitte: [335, 103, 330, 270], rechts: [695, 103, 270, 270] };
        var klein = { oben_links: [18, 14, 300, 'links'], oben_mitte: [350, 14, 300, 'mitte'], oben_rechts: [682, 14, 300, 'rechts'],
            unten_links: [18, 396, 300, 'links'], unten_mitte: [350, 396, 300, 'mitte'], unten_rechts: [682, 396, 300, 'rechts'] };
        var th = THEMEN[dsg] || null;
        var hg = th && dsg !== EIGEN ? { art: 'verlauf', farbe: th.bg0, farbe2: th.bg1, richtung: 'senkrecht' }
            : { art: 'farbe', farbe: f.hintergrund, farbe2: f.hintergrund, richtung: 'senkrecht' };
        for (i = 0; i < E.GROSS.length; i++) {
            var g = gross[E.GROSS[i]], a = p[E.GROSS[i]];
            var basis = { x: g[0], y: g[1], b: g[2], h: g[3], farbe: f.ziffern, akzent: f.zeiger, schrift: v1.schrift };
            if (a === 'leer' || !a) continue;
            if (a === 'navi' || a === 'gang' || a === 'uhr') { basis.typ = a; el.push(basis); continue; }
            if (E.RUND_WERTE.indexOf(a) >= 0 && v1.zeiger !== 'zahl') { basis.typ = 'rund'; basis.wert = a; basis.form = zeiger; el.push(basis); continue; }
            basis.typ = 'zahl'; basis.wert = a === 'leistung' ? 'leistung' : a; basis.y = g[1] + 75; basis.h = 120;
            el.push(basis);
        }
        for (var k in klein) if (klein.hasOwnProperty(k)) {
            var q = klein[k], b = p[k];
            if (!b || b === 'leer') continue;
            var z = { x: q[0], y: q[1], b: q[2], h: 40, farbe: b === 'gang' || b === 'uhr' ? f.ziffern : f.ziffern, schrift: v1.schrift, ausrichtung: q[3] };
            if (b === 'gang' || b === 'uhr') z.typ = b; else { z.typ = 'zahl'; z.wert = b; }
            el.push(z);
        }
        var g2 = pruefeFrei({ basis: dsg || v1.basis, hintergrund: hg, elemente: el });
        var eigeneZeichnung = !!EXTRA[dsg] || dsg === 'horizont' || dsg === 'route';
        return { eigen: g2.eigen, zerlegt: !eigeneZeichnung, warnungen: g2.warnungen };
    }

    // ---- Zeichnen (Version 2)
    var BILDER = {}, bildGeladen = false;
    function bildVon(s) {
        if (BILDER[s]) return BILDER[s];
        var keys = Object.keys(BILDER);
        if (keys.length > 2) BILDER = {};   // höchstens wenige Bilder im Speicher
        var im = new Image();
        im.onload = function () { bildGeladen = true; };
        im.src = s;
        BILDER[s] = im;
        return im;
    }

    function schriftFam(s) { return FAMILIEN[s] || COND; }

    function wertVon(w, d) {
        switch (w) {
            case 'tempo': return { v: zahl(d.kmh) ? d.kmh : null, t: zahl(d.kmh) ? String(Math.round(d.kmh)) : '–', e: 'km/h' };
            case 'leistung': return { v: zahl(d.kw) ? d.kw : null, t: zahl(d.kw) ? (d.kw < -0.5 ? '−' : '') + Math.abs(Math.round(d.kw)) : '–', e: 'kW' };
            case 'akku': return { v: zahl(d.soc) ? d.soc : null, t: zahl(d.soc) ? String(Math.round(d.soc)) : '–', e: '%' };
            case 'reichweite': return { v: zahl(d.reichweiteKm) ? d.reichweiteKm : null, t: zahl(d.reichweiteKm) ? String(Math.round(d.reichweiteKm)) : '–', e: 'km' };
            case 'aussen': return { v: null, t: zahl(d.aussenC) ? Math.round(d.aussenC) + '°' : '–', e: 'C' };
            case 'kmstand': return { v: null, t: zahl(d.kmStand) ? tausender(d.kmStand) : '–', e: 'km' };
            default: return { v: null, t: '–', e: '' };
        }
    }

    /** Text so groß wie möglich in die Box (Höhe px, schrumpft auf die Breite). */
    function textBox(c, s, e, x, y, b, h, farbe, fam, aus) {
        var px = h * 0.78, pe = e ? px * 0.34 : 0;
        c.font = '700 ' + Math.round(px) + 'px ' + fam;
        var bw = c.measureText(s).width;
        c.font = '600 ' + Math.max(1, Math.round(pe)) + 'px ' + fam;
        var ew = e ? c.measureText(' ' + e).width : 0;
        var k = (bw + ew) > b ? b / (bw + ew) : 1;
        px *= k; pe *= k; bw *= k; ew *= k;
        var x0 = aus === 'links' ? x : aus === 'rechts' ? x + b - bw - ew : x + (b - bw - ew) / 2, cy = y + h / 2;
        text(c, s, x0, cy, px, farbe, { ausrichtung: 'left', gewicht: '700', familie: fam });
        if (e) text(c, ' ' + e, x0 + bw, cy + px * 0.22, pe, farbe, { ausrichtung: 'left', gewicht: '600', familie: fam });
    }

    function instrument(c, e, d) {
        var cx = e.x + e.b / 2, cy = e.y + e.h / 2, r = Math.min(e.b, e.h) / 2 * 0.9, w = wertVon(e.wert, d);
        var von = Math.PI * 0.75 + e.drehung * Math.PI / 180, spanne = Math.PI * 1.5, sp = e.max - e.min;
        var n0 = clamp((0 - e.min) / sp, 0, 1), a = w.v === null ? null : clamp((w.v - e.min) / sp, 0, 1);
        var fam = schriftFam(e.schrift);
        c.save();
        c.lineCap = 'round';
        c.globalAlpha = 0.25;
        c.strokeStyle = e.farbe;
        c.lineWidth = r * 0.08;
        c.beginPath();
        c.arc(cx, cy, r, von, von + spanne);
        c.stroke();
        c.globalAlpha = 0.6;
        c.lineWidth = Math.max(1, r * 0.018);
        for (var i = 0; i <= 10; i++) {
            var wi = von + spanne * i / 10, r2 = r - r * (i % 2 === 0 ? 0.16 : 0.1);
            c.beginPath();
            c.moveTo(cx + Math.cos(wi) * (r - r * 0.06), cy + Math.sin(wi) * (r - r * 0.06));
            c.lineTo(cx + Math.cos(wi) * r2, cy + Math.sin(wi) * r2);
            c.stroke();
        }
        c.globalAlpha = 1;
        if (a !== null) {
            var wa = von + spanne * n0, wb = von + spanne * a;
            c.strokeStyle = e.akzent;
            c.fillStyle = e.akzent;
            if (e.form === 'nadel') {
                c.lineWidth = Math.max(2, r * 0.05);
                c.beginPath();
                c.moveTo(cx - Math.cos(wb) * r * 0.1, cy - Math.sin(wb) * r * 0.1);
                c.lineTo(cx + Math.cos(wb) * r * 0.88, cy + Math.sin(wb) * r * 0.88);
                c.stroke();
                c.beginPath();
                c.arc(cx, cy, r * 0.07, 0, TAU);
                c.fill();
            } else if (Math.abs(wb - wa) > 0.002) {
                var lo = Math.min(wa, wb), hi = Math.max(wa, wb);
                if (e.form === 'balken') {
                    c.lineCap = 'butt';
                    c.lineWidth = r * 0.12;
                    var seg = 30, schritt = spanne / seg;
                    for (var s = 0; s < seg; s++) {
                        var sa = von + schritt * s, sb = sa + schritt * 0.7;
                        if (sb < lo - 0.001 || sa > hi + 0.001) continue;
                        c.beginPath();
                        c.arc(cx, cy, r, Math.max(sa, lo), Math.min(sb, hi));
                        c.stroke();
                    }
                } else {
                    c.lineWidth = r * 0.1;
                    c.beginPath();
                    c.arc(cx, cy, r, lo, hi);
                    c.stroke();
                }
            }
        }
        c.restore();
        var unten = e.form === 'nadel';
        text(c, w.t, cx, unten ? cy + r * 0.45 : cy - r * 0.04, r * (unten ? 0.4 : w.t.length > 4 ? 0.42 : 0.62), e.farbe, { gewicht: '700', familie: fam });
        text(c, w.e, cx, unten ? cy + r * 0.75 : cy + r * 0.45, r * 0.18, e.farbe, { gewicht: '600', familie: fam });
    }

    function balkenZeichnen(c, e, d) {
        var w = wertVon(e.wert, d), sp = e.max - e.min, n0 = clamp((0 - e.min) / sp, 0, 1);
        c.save();
        c.globalAlpha = 0.25;
        c.fillStyle = e.farbe;
        c.fillRect(e.x, e.y, e.b, e.h);
        c.globalAlpha = 1;
        if (w.v !== null) {
            var a = clamp((w.v - e.min) / sp, 0, 1), lo = Math.min(n0, a), hi = Math.max(n0, a);
            c.fillStyle = e.akzent;
            if (e.richtung === 'senkrecht') c.fillRect(e.x, e.y + e.h * (1 - hi), e.b, e.h * (hi - lo));
            else c.fillRect(e.x + e.b * lo, e.y, e.b * (hi - lo), e.h);
        }
        c.restore();
    }

    function hgFuellen(c, x, y, b, h, hg) {
        if (hg.art === 'verlauf') {
            var g = hg.richtung === 'waagrecht' ? c.createLinearGradient(x, 0, x + b, 0) : c.createLinearGradient(0, y, 0, y + h);
            g.addColorStop(0, hg.farbe);
            g.addColorStop(1, hg.farbe2);
            c.fillStyle = g;
        } else {
            c.fillStyle = hg.farbe;
        }
        c.fillRect(x, y, b, h);
    }

    function elementZeichnen(c, e, d, jetzt) {
        var fam = schriftFam(e.schrift);
        switch (e.typ) {
            case 'rund': instrument(c, e, d); return;
            case 'zahl': { var w = wertVon(e.wert, d); textBox(c, w.t, w.e, e.x, e.y, e.b, e.h, e.farbe, fam, e.ausrichtung); return; }
            case 'balken': balkenZeichnen(c, e, d); return;
            case 'gang': textBox(c, d.gang || '–', '', e.x, e.y, e.b, e.h, e.farbe, fam, e.ausrichtung); return;
            case 'uhr': textBox(c, uhr(jetzt), '', e.x, e.y, e.b, e.h, e.farbe, fam, e.ausrichtung); return;
            case 'navi': {
                // Nur aus der Panoptes-Navigation; ohne Route bleibt das Feld leer (nichts vortäuschen).
                var n = d.navi;
                if (!n) return;
                var g = Math.min(e.b, e.h * 0.72);
                pfeil(c, e.x + e.b / 2, e.y + e.h * 0.38, g, n.typ, e.farbe, true);
                text(c, entfernung(n.distanzM), e.x + e.b / 2, e.y + e.h * 0.86, e.h * 0.2, e.farbe, { gewicht: '700', familie: fam });
                return;
            }
            case 'ring': {
                var r = Math.max(1, Math.min(e.b, e.h) / 2 - e.dicke / 2);
                c.save(); c.strokeStyle = e.farbe; c.lineWidth = e.dicke;
                c.beginPath(); c.arc(e.x + e.b / 2, e.y + e.h / 2, r, 0, TAU); c.stroke(); c.restore();
                return;
            }
            case 'linie': {
                c.save(); c.strokeStyle = e.farbe; c.lineWidth = e.dicke; c.lineCap = 'round'; c.beginPath();
                if (e.b >= e.h) { c.moveTo(e.x, e.y + e.h / 2); c.lineTo(e.x + e.b, e.y + e.h / 2); }
                else { c.moveTo(e.x + e.b / 2, e.y); c.lineTo(e.x + e.b / 2, e.y + e.h); }
                c.stroke(); c.restore();
                return;
            }
            case 'flaeche':
                c.save(); rund(c, e.x, e.y, e.b, e.h, Math.min(e.b, e.h) / 2 * e.ecken / 100); c.fillStyle = e.farbe; c.fill(); c.restore();
                return;
            case 'verlauf': {
                c.save();
                var gr = e.richtung === 'senkrecht' ? c.createLinearGradient(0, e.y, 0, e.y + e.h) : c.createLinearGradient(e.x, 0, e.x + e.b, 0);
                gr.addColorStop(0, e.farbe); gr.addColorStop(1, e.farbe2);
                c.fillStyle = gr; c.fillRect(e.x, e.y, e.b, e.h); c.restore();
                return;
            }
            default: return;
        }
    }

    /** Version 2 zeichnen: Fläche mittig, Hintergrund über das ganze Bild. Nichts blinkt, nichts pulsiert. */
    function zeichneFrei(c, w, h, e, d, jetzt) {
        var s = Math.min(w / E.BREITE, h / E.HOEHE), ox = (w - E.BREITE * s) / 2, oy = (h - E.HOEHE * s) / 2;
        hgFuellen(c, 0, 0, w, h, e.hintergrund);
        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        if (e.bild) {
            var im = bildVon(e.bild);
            if (im.complete && im.naturalWidth) {
                var k = Math.max(E.BREITE / im.naturalWidth, E.HOEHE / im.naturalHeight);
                var iw = im.naturalWidth * k, ih = im.naturalHeight * k;
                c.save();
                c.beginPath(); c.rect(0, 0, E.BREITE, E.HOEHE); c.clip();
                c.drawImage(im, (E.BREITE - iw) / 2, (E.HOEHE - ih) / 2, iw, ih);
                c.globalAlpha = E.SCHLEIER;
                hgFuellen(c, 0, 0, E.BREITE, E.HOEHE, e.hintergrund);
                c.restore();
            }
        }
        for (var i = 0; i < e.elemente.length; i++) elementZeichnen(c, e.elemente[i], d, jetzt);
        c.restore();
    }

    /** Farbsatz für die Ansicht-Leiste (Kamera/Video) bei Version 2: Ziffern weiß oder schwarz, je nach Hintergrund. */
    function freiThema(e) {
        var bg = e.hintergrund.farbe, fg = kontrast('#FFFFFF', bg) >= kontrast('#000000', bg) ? '#FFFFFF' : '#000000';
        return { bg0: bg, bg1: bg, fg: fg, familie: COND };
    }

    // ------------------------------------------------------------ Gesamtbild

    // ------------------------------------------------------------ Einhängestelle „Ansicht“ (0.1.42, für T3 Kamera/Video)
    // Ist eine Ansicht an (t.ansicht(true)), zeichnet das Design nur eine ruhige Leiste unten (Tempo, Gang, Akku, Uhr)
    // und lässt darüber das Feld ansichtBereich(w, h) frei (schwarz). Das Kamera-/Videobild legt tacho-ansichten.js
    // als eigenes Element genau über dieses Feld; Tempo bleibt immer sichtbar. Das Design selbst bleibt gewählt.

    function leisteHoehe(w, h) { return Math.round(Math.min(h, w / 2.2) * 0.22); }

    /** Freies Feld für eine Ansicht in CSS-Pixeln der Zeichenfläche: {x, y, w, h}. */
    function ansichtBereich(w, h) { return { x: 0, y: 0, w: w, h: Math.max(0, h - leisteHoehe(w, h)) }; }

    function zeichneAnsichtLeiste(c, w, h, u, th, d, jetzt) {
        var lh = leisteHoehe(w, h), y0 = h - lh, y = y0 + lh / 2;
        c.fillStyle = '#000000';
        c.fillRect(0, 0, w, y0);
        c.fillStyle = th.bg1;
        c.fillRect(0, y0, w, lh);
        var fam = th.familie || COND;
        text(c, zahl(d.kmh) ? String(Math.round(d.kmh)) : '–', w * 0.42, y, lh * 0.72, th.fg, { ausrichtung: 'right', gewicht: '700', familie: fam });
        text(c, 'km/h', w * 0.43, y + lh * 0.12, lh * 0.2, th.fg, { ausrichtung: 'left', familie: fam });
        text(c, d.gang || '–', w * 0.56, y, lh * 0.5, th.fg, { gewicht: '700', familie: fam });
        text(c, zahl(d.soc) ? Math.round(d.soc) + ' %' : '– %', w - u * 4, y, lh * 0.3, th.fg, { ausrichtung: 'right', gewicht: '600', familie: fam });
        text(c, uhr(jetzt), u * 4, y, lh * 0.3, th.fg, { ausrichtung: 'left', familie: fam });
    }

    function zeichne(c, w, h, d, st, jetzt) {
        var dsg = design(d.design);
        // Einheit: 1 u = 1 % der Höhe, aber nie breiter als das Bild bei 2,2:1 trägt.
        var u = Math.min(h, w / 2.2) / 100;
        if (d.ansicht) {
            var ath = dsg !== EIGEN ? THEMEN[dsg] : d.eigen && d.eigen.version === E.VERSION_FREI ? freiThema(d.eigen)
                : eigenThema(d.eigen && d.eigen.farben ? d.eigen : standardEigen());
            zeichneAnsichtLeiste(c, w, h, u, ath, d, jetzt);
            return;
        }
        if (dsg === EIGEN && d.eigen && d.eigen.version === E.VERSION_FREI) { zeichneFrei(c, w, h, d.eigen, d, jetzt); return; }
        if (dsg === EIGEN) { zeichneEigen(c, w, h, u, d.eigen, d, st, jetzt); return; }
        var th = THEMEN[dsg];
        if (EXTRA[dsg]) { EXTRA[dsg].zeichne(c, w, h, u, th, d, st, jetzt, HILFE); return; }
        hintergrund(c, w, h, th, dsg);
        kopfzeile(c, w, h, u, th, d, jetzt);
        if (dsg === 'horizont') {
            horizont(c, w, h, u, th, d, st);
            return;
        }
        var cy = h * 0.53;
        var r = Math.min(h * 0.33, w * 0.14);
        var lx = Math.max(r * 1.25, w * 0.17), rx = w - lx;
        if (dsg === 'route') {
            r *= 0.9;
            lx = Math.max(r * 1.2, w * 0.13); rx = w - lx;
            var mx = lx + r * 1.35, mw = rx - r * 1.35 - mx;
            routeSkizze(c, mx, u * 14, mw, h - u * 30, u, th, d);
        } else {
            mitte(c, w / 2, cy, (rx - lx) - r * 2.6, h * 0.62, u, th, d);
        }
        ringKw(c, lx, cy, r, u, th, d, st);
        ringTempo(c, rx, cy, r, u, th, d);
        fusszeile(c, w, h, u, th, d);
    }

    // ------------------------------------------------------------ Laufzeit

    var GLEITEND = ['kmh', 'kw', 'soc'];
    var GLEITEN = 9;   // 1/s
    var MIN_BILD_MS = 32;   // Obergrenze ~30 Bilder/s, schont schwache Head-Units

    function geprueft(e) {
        var g = e ? pruefeEigen(e) : null;
        return g ? g.eigen : null;
    }

    function create(canvas, opt) {
        opt = opt || {};
        var c = canvas.getContext('2d');
        var ziel = { design: design(opt.design) };
        var anz = { design: ziel.design, eigen: geprueft(opt.eigen) };
        var st = { kwMax: 150 };
        var lauf = true, letzt = 0, letztesBild = 0, letzteUhr = '', dpr = 1, w = 0, h = 0, neu = true;

        function groesse() {
            dpr = Math.min(2, window.devicePixelRatio || 1);
            var bw = canvas.clientWidth || canvas.width, bh = canvas.clientHeight || canvas.height;
            if (Math.round(bw * dpr) !== canvas.width || Math.round(bh * dpr) !== canvas.height) {
                canvas.width = Math.round(bw * dpr);
                canvas.height = Math.round(bh * dpr);
            }
            w = bw; h = bh;
        }

        function bild(t) {
            if (!lauf) return;
            var dt = letzt ? Math.min(0.1, (t - letzt) / 1000) : 0.016;
            letzt = t;
            var bewegt = neu;
            neu = false;
            // 0.1.38: Daten kommen alle 250 ms; Zeitkonstante ~110 ms, damit der Wert vor dem nächsten Takt ankommt.
            var k = 1 - Math.exp(-dt * GLEITEN);
            for (var i = 0; i < GLEITEND.length; i++) {
                var n = GLEITEND[i], zv = ziel[n];
                if (!zahl(zv)) { if (anz[n] !== zv) { anz[n] = zv; bewegt = true; } continue; }
                if (!zahl(anz[n])) { anz[n] = zv; bewegt = true; continue; }
                var dif = zv - anz[n];
                if (Math.abs(dif) > 0.05) { anz[n] += dif * k; bewegt = true; } else if (dif !== 0) { anz[n] = zv; bewegt = true; }
            }
            var jetzt = opt.uhr ? opt.uhr() : new Date();
            var u = uhr(jetzt);
            if (u !== letzteUhr) { letzteUhr = u; bewegt = true; }
            if (bildGeladen) { bildGeladen = false; bewegt = true; }   // 0.1.44 (E1): Hintergrundbild ist da
            if (bewegt && t - letztesBild < MIN_BILD_MS) {
                neu = true;   // höchstens ~30 Bilder pro Sekunde; der Rest kommt im nächsten Bild
            } else if (bewegt) {
                letztesBild = t;
                groesse();
                c.setTransform(dpr, 0, 0, dpr, 0, 0);
                zeichne(c, w, h, anz, st, jetzt);
            }
            window.requestAnimationFrame(bild);
        }

        function setze(d) {
            if (!d) return;
            var schl = ['kmh', 'kw', 'soc', 'reichweiteKm', 'gang', 'aussenC', 'kmStand', 'navi'];
            for (var i = 0; i < schl.length; i++) {
                var n = schl[i];
                ziel[n] = d[n];
                if (GLEITEND.indexOf(n) < 0) anz[n] = d[n];
            }
            if (d.design) anz.design = ziel.design = design(d.design);
            if (d.eigen !== undefined) anz.eigen = geprueft(d.eigen);
            neu = true;
        }

        window.addEventListener('resize', function () { neu = true; });
        window.requestAnimationFrame(bild);
        return {
            setze: setze,
            design: function (dsg) { anz.design = ziel.design = design(dsg); neu = true; },
            /** 0.1.41 Live-Vorschau beim Gestalten: eigenes Design setzen (wird geprüft). */
            eigen: function (e) { anz.eigen = geprueft(e); anz.design = ziel.design = EIGEN; neu = true; },
            /** 0.1.42 Einhängestelle „Ansicht“ (T3): an = nur Leiste unten, Feld darüber frei. */
            ansicht: function (an) { anz.ansicht = !!an; neu = true; },
            /** Freies Feld {x, y, w, h} in CSS-Pixeln des Canvas (gilt, solange die Ansicht an ist). */
            ansichtBereich: function () { groesse(); return ansichtBereich(w, h); },
            jetztZeichnen: function () {
                for (var i = 0; i < GLEITEND.length; i++) anz[GLEITEND[i]] = ziel[GLEITEND[i]];
                groesse();
                c.setTransform(dpr, 0, 0, dpr, 0, 0);
                zeichne(c, w, h, anz, st, opt.uhr ? opt.uhr() : new Date());
            },
            stop: function () { lauf = false; }
        };
    }

    /** Beispieldaten: Stadtfahrt mit Anfahren, Rekuperation und Rechtskurve voraus. */
    function demo(s) {
        var p = (s % 40) / 40;
        var kmh = Math.max(0, Math.round(62 + 46 * Math.sin(p * TAU) + 8 * Math.sin(p * TAU * 3)));
        var kw = Math.round(28 + 70 * Math.cos(p * TAU) + 12 * Math.sin(p * TAU * 3));
        var dist = Math.round(420 - (s * 9) % 380);
        var sk = [];
        for (var i = 0; i <= 24; i++) {
            var y = i * 30, x = y > 260 ? Math.pow((y - 260) / 10, 1.55) : 0;
            sk.push(Math.round(x), y);
        }
        return {
            kmh: kmh, kw: kw, soc: 64, reichweiteKm: 287, gang: 'D', aussenC: 14, kmStand: 18342,
            navi: { typ: 10, distanzM: dist, restM: 12400, restS: 900, richtung: 'NO', skizze: sk }
        };
    }

    // 0.1.42: Bausteine für die Galerie-Designs (gleiche Zeichnung, gleiche Ruhe-Regeln)
    var HILFE = {
        TAU: TAU, COND: COND, SANS: SANS, clamp: clamp, zahl: zahl, tausender: tausender, entfernung: entfernung,
        dauer: dauer, uhr: uhr, text: text, ring: ring, nadel: nadel, pfeil: pfeil, akkuBalken: akkuBalken, rund: rund,
        mische: mische, hintergrund: hintergrund, kopfzeile: kopfzeile, fusszeile: fusszeile, mitte: mitte,
        routeSkizze: routeSkizze, kwSkala: kwSkala, ringKw: ringKw, ringTempo: ringTempo
    };

    window.PanoptesTacho = {
        DESIGNS: DESIGNS.slice(),
        ARTEN: ARTEN_LISTE.slice(),
        arten: function (dsg) { return (ARTEN[dsg] || []).slice(); },
        registriere: registriere,
        ansichtBereich: ansichtBereich,
        thema: function (dsg) { return THEMEN[dsg] || null; },
        create: create,
        demo: demo,
        manoeverArt: manoeverArt,
        entfernung: entfernung,
        // 0.1.41 „Tacho gestalten“
        EIGEN: EIGEN,
        REGELN: E,
        vorlage: vorlage,
        standardEigen: standardEigen,
        pruefeEigen: pruefeEigen,
        kontrast: kontrast,
        // 0.1.44 (E1) freier Editor
        pruefeFrei: pruefeFrei,
        leerFrei: leerFrei,
        zuElementen: zuElementen,
        mischeHex: mischeHex,
        elementPruefen: elementPruefen,
        /** Kontrast von Element i zur Fläche dahinter (wie beim Speichern); null bei reinen Formen. */
        kontrastElement: function (e, i) {
            var x = e.elemente[i];
            if (!x || !infoTyp(x.typ)) return null;
            return minKontrast(x.typ === 'balken' ? x.akzent : x.farbe, dahinter(e.elemente, i, e.hintergrund, !!e.bild));
        }
    };
})();
