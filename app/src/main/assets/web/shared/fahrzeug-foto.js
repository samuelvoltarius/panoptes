/**
 * Panoptes 0.1.43.4 – Foto statt 3D für Modelle ohne gutes 3D-Modell (13.11.).
 *
 * Eine Quelle mit dem Launcher: web/shared/models/manifest.json. Ein Eintrag mit "photo" und ohne "top": true
 * zeigt statt des 3D-Modells das Foto models/fotos/<id>.webp (dieselbe Datei wie im Launcher, erzeugt mit
 * tools/launcher_fahrzeugbilder.py). Darunter klein die Namensnennung (Urheber, Lizenz, „bearbeitet“) und die Bitte
 * um ein 3D-Modell mit Link (manifest.modellBitteUrl). Ohne dieses Modul oder ohne Foto bleibt alles wie bisher.
 *
 * ES5 (Chromium 74): var/function, keine Pfeile, kein let/const, keine Template-Strings.
 */
(function (root) {
    'use strict';
    if (root.PanoptesFahrzeugFoto) return;

    var LIZENZ = { 'CC-BY-SA-4.0': 'CC BY-SA 4.0', 'CC-BY-4.0': 'CC BY 4.0', 'CC0-1.0': 'CC0' };
    var BITTE_DE = 'Hast du ein 3D-Modell von diesem Auto, das du uns geben kannst? Wir wären dankbar.';

    function eintrag(manifest, id) {
        var liste = (manifest && manifest.models) || [];
        for (var i = 0; i < liste.length; i++) {
            var e = liste[i];
            if (e && e.id === id) {
                var f = e.photo;
                if (!f || e.top === true || !f.author || !LIZENZ[f.license]) return null;
                return e;
            }
        }
        return null;
    }

    function nennung(e) {
        var l = LIZENZ[e.photo.license];
        return 'Foto: ' + e.photo.author + ' · ' + l + (l.indexOf('CC BY') === 0 ? ' · bearbeitet' : '');
    }

    function bitteText() {
        var t = null;
        try { t = root.BYD && root.BYD.i18n && root.BYD.i18n.t ? root.BYD.i18n.t('fahrzeug_foto.bitte') : null; } catch (e) { t = null; }
        return (t && t !== 'fahrzeug_foto.bitte') ? t : BITTE_DE;
    }

    function entferne(host) {
        if (!host || !host.querySelector) return;
        var alt = host.querySelector('.pn-fahrzeug-foto');
        if (alt && alt.parentNode) alt.parentNode.removeChild(alt);
    }

    function zeige(host, e, manifest, basis) {
        if (!host || !e) return;
        entferne(host);
        var box = document.createElement('div');
        box.className = 'pn-fahrzeug-foto';
        box.setAttribute('data-modell', e.id);
        box.style.cssText = 'position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;' +
            'align-items:stretch;pointer-events:none;z-index:1';
        var img = document.createElement('img');
        img.alt = e.name || e.id;
        img.src = (basis || '../shared/models/fotos/') + e.id + '.webp?v=2';   // v2 (0.1.43.5): freigestellt
        img.style.cssText = 'flex:1 1 auto;min-height:0;width:100%;object-fit:contain';
        box.appendChild(img);
        var n = document.createElement('div');
        n.className = 'pn-fahrzeug-foto-nennung';
        n.textContent = nennung(e);
        n.style.cssText = 'font-size:10px;line-height:1.3;opacity:.7;text-align:right;padding:0 4px';
        box.appendChild(n);
        var url = manifest && manifest.modellBitteUrl;
        if (url && /^https:\/\//.test(url)) {
            var a = document.createElement('a');
            a.className = 'pn-fahrzeug-foto-bitte';
            a.href = url;
            a.target = '_blank';
            a.rel = 'noopener';
            a.textContent = bitteText();
            a.style.cssText = 'font-size:10px;line-height:1.3;opacity:.7;text-align:right;padding:0 4px 2px;' +
                'color:inherit;text-decoration:none;pointer-events:auto';
            box.appendChild(a);
        }
        try {
            if (root.getComputedStyle && root.getComputedStyle(host).position === 'static') host.style.position = 'relative';
        } catch (err) { /* Stil nicht lesbar: dann eben ohne */ }
        host.appendChild(box);
    }

    root.PanoptesFahrzeugFoto = { eintrag: eintrag, nennung: nennung, zeige: zeige, entferne: entferne };
})(window);
