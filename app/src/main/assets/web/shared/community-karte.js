/*
 * Community-Karte (Hilfe & Info): Einladung zur Panoptes-Gruppe auf WhatsApp, als Knopf und als QR-Code zum Abscannen
 * mit dem Handy (am Auto-Display der Hauptweg). Der Link steht NUR hier. Der QR-Code wird lokal erzeugt
 * (vendor/qrcode-generator, MIT), es geht kein Aufruf an einen fremden Dienst. Läuft bis Chromium 74 (reines SVG, ES5).
 */
(function () {
    'use strict';
    var LINK = 'https://chat.whatsapp.com/IQRtn9JViH4FhypAQsS3yK';

    function zeichnen() {
        var knoepfe = document.querySelectorAll('[data-community-link]');
        for (var i = 0; i < knoepfe.length; i++) knoepfe[i].setAttribute('href', LINK);
        var boxen = document.querySelectorAll('[data-community-qr]');
        if (!boxen.length || typeof window.qrcode !== 'function') return;
        try {
            var q = window.qrcode(0, 'M');
            q.addData(LINK);
            q.make();
            var svg = q.createSvgTag(6, 0);
            for (var j = 0; j < boxen.length; j++) {
                // weißer Rand um den Code (Ruhezone), sonst liest ihn die Handykamera im dunklen Thema nicht
                try {
                    var alt = window.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t('communityGruppe.qr_alt') : null;
                    if (typeof alt === 'string' && alt.indexOf('communityGruppe.') !== 0) boxen[j].setAttribute('aria-label', alt);
                } catch (e2) { }
                boxen[j].innerHTML = '<div style="display:inline-block;background:#fff;padding:14px;border-radius:12px;line-height:0;">' + svg + '</div>';
            }
        } catch (e) { /* ohne QR bleibt der Knopf */ }
    }

    window.PanoptesCommunity = { LINK: LINK, zeichnen: zeichnen };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', zeichnen);
    else zeichnen();
})();
