/**
 * Panoptes — Handy-Meldungen über Tailscale-HTTPS (0.1.9).
 *
 * Web-Push braucht eine sichere (HTTPS-)Adresse. Weg A: im eigenen Tailnet HTTPS einschalten
 * (Kern: GET/PUT /api/tailscale/https), dann die Seite über die ts.net-Adresse öffnen.
 * Weg B ohne Tailscale: eigene feste HTTPS-Adresse (Reverse-Proxy). Telegram geht immer.
 *
 * Kern (kern-0190-cleanroom e44bd95): GET { tailscale, httpsMoeglich, an, adresse, grund },
 *   PUT { an } → dasselbe nach dem Zurücklesen (Einschalten bis ~60 s, Zertifikat wird geholt).
 *   grund: kein_tailscale | https_im_tailnet_aus | zertifikat_fehlt | belegt (Port 443 fremd belegt).
 *   Gekoppelte Geräte bekommen 403.
 * Fehlt die Schnittstelle (404), zeigt der Baustein nur den ehrlichen Hinweis.
 *
 *   PanoptesTsHttps.zeichne(container, { sicher: bool })   sicher = Seite läuft schon über HTTPS
 * ES5 (WebView 74).
 */
(function () {
    'use strict';

    var DE = {
        titel: 'Handy-Meldungen: sichere Adresse',
        sicher: 'Diese Seite ist über HTTPS offen. Du kannst die Meldungen hier einschalten.',
        ts_aus_text: 'Mit Tailscale kannst du HTTPS in deinem eigenen Tailnet einschalten. Dein Auto bekommt eine HTTPS-Adresse im eigenen Tailnet (ts.net). Sie ist nicht öffentlich. Über diese Adresse gehen Handy-Meldungen.',
        ts_warten: 'Das Zertifikat wird geholt. Das kann bis zu einer Minute dauern …',
        ts_belegt: 'Port 443 wird in deinem Tailnet schon von etwas anderem verwendet. Panoptes verändert diese fremde Einstellung nicht.',
        ts_zertifikat_fehlt: 'Das Zertifikat konnte nicht geholt werden. Versuch es später noch einmal.',
        ts_verboten: 'Das geht nur am Bildschirm im Auto oder nach der Anmeldung mit dem Zugangscode, nicht von einem gekoppelten Gerät.',
        ts_hinweis: 'Gut zu wissen: Das Zertifikat kommt von Let’s Encrypt. Dabei werden der Gerätename und der Tailnet-Name in öffentlichen Zertifikats-Verzeichnissen sichtbar.',
        ts_einschalten: 'HTTPS im eigenen Tailnet einschalten',
        ts_an_text: 'HTTPS im Tailnet ist an. Öffne diese Seite am Handy über die sichere Adresse und schalte dort die Meldungen ein:',
        ts_oeffnen: 'Sichere Adresse öffnen',
        ts_ausschalten: 'HTTPS im Tailnet ausschalten',
        ts_im_tailnet_aus: 'In deinem Tailnet sind HTTPS-Zertifikate noch nicht erlaubt. Schalte im Tailscale-Adminbereich unter DNS „MagicDNS“ und „HTTPS Certificates“ ein und komm dann zurück.',
        ts_fehlt: 'Ohne Tailscale brauchen Handy-Meldungen eine eigene feste HTTPS-Adresse, zum Beispiel über einen Reverse-Proxy.',
        telegram: 'Telegram geht immer, auch ohne HTTPS.',
        fehler: 'Das hat nicht geklappt. Am Auto hat sich nichts geändert.',
        laedt: 'Einen Moment …'
    };

    function t(k) {
        var key = 'notif.ts.' + k;
        try {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                var v = BYD.i18n.t(key);
                if (v && v !== key) return v;
            }
        } catch (e) {}
        return DE[k] || k;
    }

    function el(tag, cls, txt) {
        var n = document.createElement(tag);
        if (cls) n.className = cls;
        if (txt != null) n.textContent = txt;
        return n;
    }

    function knopf(txt, haupt, fn) {
        var b = el('button', 'pn-ts-btn' + (haupt ? ' haupt' : ''), txt);
        b.type = 'button';
        b.onclick = fn;
        return b;
    }

    var cssDone = false;
    function css() {
        if (cssDone) return;
        cssDone = true;
        var s = document.createElement('style');
        s.textContent =
            '.pn-ts{margin-bottom:14px;padding:14px 16px;border-radius:12px;border:1px solid var(--outline,#2a6474);line-height:1.45}' +
            '.pn-ts h4{margin:0 0 8px;font-size:15px}' +
            '.pn-ts p{margin:0 0 8px}' +
            '.pn-ts .klein{font-size:12px;opacity:.75}' +
            '.pn-ts .adr{font-family:monospace;word-break:break-all}' +
            '.pn-ts-btn{min-height:48px;padding:0 18px;border-radius:12px;border:1px solid var(--outline,#2a6474);' +
            'background:transparent;color:inherit;font-size:15px;font-weight:600;cursor:pointer;margin:4px 8px 4px 0}' +
            '.pn-ts-btn.haupt{background:var(--primary,#5FD6E6);color:var(--on-primary,#06283a)}' +
            '.pn-ts .fehler{color:var(--danger,#e5484d)}';
        document.head.appendChild(s);
    }

    function lesen() {
        return fetch('/api/tailscale/https', { cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) {
                if (r.status === 404) return { fehlt: true };
                if (!r.ok) throw new Error('http ' + r.status);
                return r.json();
            });
    }

    function schreiben(an) {
        return fetch('/api/tailscale/https', {
            method: 'PUT', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ an: !!an })
        }).then(function (r) {
            if (r.status === 403) { var e = new Error('verboten'); e.verboten = true; throw e; }
            if (!r.ok) throw new Error('http ' + r.status);
            return lesen();
        });
    }

    function zeichne(box, opts) {
        opts = opts || {};
        css();
        box.innerHTML = '';
        var karte = el('div', 'pn-ts');
        karte.appendChild(el('h4', null, t('titel')));
        box.appendChild(karte);
        if (opts.sicher) {
            karte.appendChild(el('p', null, t('sicher')));
            return;
        }
        var warte = el('p', 'klein', t('laedt'));
        karte.appendChild(warte);

        function rest(d, meldung) {
            karte.innerHTML = '';
            karte.appendChild(el('h4', null, t('titel')));
            if (!d || d.fehlt || d.tailscale !== true) {
                karte.appendChild(el('p', null, t('ts_fehlt')));
            } else if (d.an === true && d.adresse) {
                karte.appendChild(el('p', null, t('ts_an_text')));
                var url = String(d.adresse).replace(/\/+$/, '') + '/notifications.html';
                karte.appendChild(el('p', 'adr', url));
                var a = el('a', 'pn-ts-btn haupt', t('ts_oeffnen'));
                a.href = url; a.target = '_blank'; a.rel = 'noopener';
                a.style.display = 'inline-flex'; a.style.alignItems = 'center'; a.style.textDecoration = 'none';
                karte.appendChild(a);
                karte.appendChild(knopf(t('ts_ausschalten'), false, function () { umschalten(false); }));
            } else if (d.grund === 'belegt') {
                karte.appendChild(el('p', null, t('ts_belegt')));
            } else if (d.httpsMoeglich === false || d.grund === 'https_im_tailnet_aus') {
                karte.appendChild(el('p', null, t('ts_im_tailnet_aus')));
            } else {
                if (d.grund === 'zertifikat_fehlt') karte.appendChild(el('p', 'fehler', t('ts_zertifikat_fehlt')));
                karte.appendChild(el('p', null, t('ts_aus_text')));
                karte.appendChild(el('p', 'klein', t('ts_hinweis')));
                karte.appendChild(knopf(t('ts_einschalten'), true, function () { umschalten(true); }));
            }
            if (meldung) karte.appendChild(el('p', 'fehler', meldung));
            karte.appendChild(el('p', 'klein', t('telegram')));
        }

        function umschalten(an) {
            Array.prototype.forEach.call(karte.querySelectorAll('button'), function (b) { b.disabled = true; });
            if (an) karte.appendChild(el('p', 'klein', t('ts_warten')));
            schreiben(an).then(function (d) {
                // zertifikat_fehlt/belegt zeigt rest() selbst; sonst: Wunsch und Ergebnis vergleichen
                var eigen = d && (d.grund === 'zertifikat_fehlt' || d.grund === 'belegt');
                rest(d, d && !d.fehlt && ((d.an === true) === an || eigen) ? null : t('fehler'));
            }, function (e) {
                var m = e && e.verboten ? t('ts_verboten') : t('fehler');
                lesen().then(function (d) { rest(d, m); }, function () { rest(null, m); });
            });
        }

        lesen().then(function (d) { rest(d); }, function () { rest(null); });
    }

    window.PanoptesTsHttps = { zeichne: zeichne };
})();
