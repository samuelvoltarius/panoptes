/**
 * Panoptes — Kompatibilität für alte WebViews (Zielbrowser Chromium 74, DiLink 3).
 *
 * Flexbox-`gap` kann Chromium erst ab 84. Dieses Skript prüft das einmal; fehlt es, bildet es die Abstände
 * der Flex-Container mit Rändern an den Kindern nach (auch für später eingefügte Inhalte). In Browsern mit
 * Flex-Gap tut es nichts. Zum Testen in neuem Chrome: ?kompat=test (simuliert das Fehlen).
 * Ändert nur Abstände, keine Inhalte. ES5.
 */
(function () {
    'use strict';
    var TEST = /[?&]kompat=test(&|$)/.test(location.search);

    function hatFlexGap() {
        var d = document.createElement('div');
        d.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden;';
        d.appendChild(document.createElement('div'));
        d.appendChild(document.createElement('div'));
        document.body.appendChild(d);
        var ok = d.scrollHeight === 1;
        d.parentNode.removeChild(d);
        return ok;
    }

    function px(v) { var n = parseFloat(v); return isNaN(n) ? 0 : n; }

    function sichtbareKinder(el) {
        var k = [];
        for (var i = 0; i < el.children.length; i++) {
            var c = el.children[i], cs = getComputedStyle(c);
            if (cs.display === 'none' || cs.position === 'absolute' || cs.position === 'fixed') continue;
            k.push(c);
        }
        return k;
    }

    function addRand(c, seite, wert) {
        var key = 'kfg' + seite;
        if (c.getAttribute('data-' + key)) return;          // schon ergänzt
        var basis = px(getComputedStyle(c)['margin' + seite]);
        c.style['margin' + seite] = (basis + wert) + 'px';
        c.setAttribute('data-' + key, '1');
    }

    function container(el) {
        var cs = getComputedStyle(el);
        if (cs.display !== 'flex' && cs.display !== 'inline-flex') return;
        var cg = TEST && el.getAttribute('data-kfg-cg') !== null ? px(el.getAttribute('data-kfg-cg')) : px(cs.columnGap);
        var rg = TEST && el.getAttribute('data-kfg-rg') !== null ? px(el.getAttribute('data-kfg-rg')) : px(cs.rowGap);
        if (!cg && !rg) return;
        var kinder = sichtbareKinder(el);
        if (kinder.length < 2) return;
        var dir = cs.flexDirection, wrap = cs.flexWrap !== 'nowrap';
        for (var i = 0; i < kinder.length; i++) {
            var c = kinder[i];
            if (dir === 'column' || dir === 'column-reverse') {
                if (i > 0 && rg) addRand(c, dir === 'column' ? 'Top' : 'Bottom', rg);
            } else if (wrap) {
                if (cg && i < kinder.length - 1) addRand(c, dir === 'row' ? 'Right' : 'Left', cg);
                if (rg) addRand(c, 'Bottom', rg);
            } else if (i > 0 && cg) {
                addRand(c, dir === 'row' ? 'Left' : 'Right', cg);
            }
        }
    }

    function alle(root) {
        var els = (root || document.body).querySelectorAll('*');
        for (var i = 0; i < els.length; i++) container(els[i]);
    }

    function start() {
        if (!TEST && hatFlexGap()) return;
        document.documentElement.className += ' kein-flex-gap';
        if (TEST) {
            // Gap-Werte merken, dann wie Chromium 74 ignorieren
            var els = document.body.querySelectorAll('*');
            for (var i = 0; i < els.length; i++) {
                var cs = getComputedStyle(els[i]);
                if (cs.display === 'flex' || cs.display === 'inline-flex') {
                    els[i].setAttribute('data-kfg-cg', cs.columnGap);
                    els[i].setAttribute('data-kfg-rg', cs.rowGap);
                }
            }
            var st = document.createElement('style');
            st.textContent = '*{column-gap:normal!important;row-gap:normal!important;}';
            document.head.appendChild(st);
        }
        alle();
        if (window.MutationObserver) {
            // Nur die geänderten Stellen nachziehen (Live-Seiten ändern oft nur Text).
            var offen = [], uhr = null;
            new MutationObserver(function (liste) {
                for (var i = 0; i < liste.length; i++) {
                    var m = liste[i];
                    for (var j = 0; j < m.addedNodes.length; j++) {
                        if (m.addedNodes[j].nodeType === 1) offen.push(m.addedNodes[j]);
                    }
                    if (m.addedNodes.length && m.target.nodeType === 1) offen.push(m.target);
                }
                if (!offen.length) return;
                clearTimeout(uhr);
                uhr = setTimeout(function () {
                    var ziele = offen; offen = [];
                    for (var k = 0; k < ziele.length; k++) {
                        if (!ziele[k].isConnected && ziele[k].isConnected !== undefined) continue;
                        container(ziele[k]);
                        if (ziele[k].children && ziele[k].children.length) alle(ziele[k]);
                    }
                }, 250);
            }).observe(document.body, { childList: true, subtree: true });
        }
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
