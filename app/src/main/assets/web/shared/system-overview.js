/**
 * Panoptes System page navigation.
 *
 * Keeps every existing card and its behaviour, but shows one plain-language
 * group at a time. ES5 on purpose for older BYD WebViews.
 */
(function (global) {
    'use strict';

    var GROUPS = {
        connections: ['tailscaleKarte', 'wireguardSetupCard', 'kopplung', 'protokoll', 'homeassistant', 'planerKarte'],
        vehicle: ['manualVehicleCard', 'nativeVehicleTools', 'anzeige', 'kartenquelle', 'blitzer'],
        privacy: ['datenschutz', 'energie', 'updates'],
        advanced: ['einrichtung', 'notzugang', 'eingriffe', 'umzug', 'tunnelKarte', 'coreServicesCard']
    };
    var STORAGE_KEY = 'pn.system.group';

    function t(key, fallback) {
        try {
            var value = global.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : '';
            return value && value !== key ? value : fallback;
        } catch (e) { return fallback; }
    }

    function groupForHash() {
        var id = (global.location.hash || '').replace(/^#/, '').split('=')[0];
        var group;
        for (group in GROUPS) {
            if (Object.prototype.hasOwnProperty.call(GROUPS, group) && GROUPS[group].indexOf(id) >= 0) return group;
        }
        return '';
    }

    function storedGroup() {
        try {
            var value = global.sessionStorage.getItem(STORAGE_KEY);
            return GROUPS[value] ? value : '';
        } catch (e) { return ''; }
    }

    function remember(group) {
        try { global.sessionStorage.setItem(STORAGE_KEY, group); } catch (e) {}
    }

    function setGroup(group, moveFocus) {
        if (!GROUPS[group]) group = 'connections';
        var id, card, key;
        for (key in GROUPS) {
            if (!Object.prototype.hasOwnProperty.call(GROUPS, key)) continue;
            for (var i = 0; i < GROUPS[key].length; i++) {
                id = GROUPS[key][i];
                card = document.getElementById(id);
                if (card) card.hidden = key !== group;
            }
        }
        var buttons = document.querySelectorAll('[data-system-group]');
        for (var b = 0; b < buttons.length; b++) {
            var selected = buttons[b].getAttribute('data-system-group') === group;
            buttons[b].setAttribute('aria-selected', selected ? 'true' : 'false');
            buttons[b].setAttribute('tabindex', selected ? '0' : '-1');
        }
        var label = t('system.group_' + group, group);
        var status = document.getElementById('systemGroupStatus');
        if (status) status.textContent = label;
        var tablist = document.querySelector('.system-groups');
        if (tablist) tablist.setAttribute('aria-label', t('system.overview_aria', 'System areas'));
        remember(group);
        if (moveFocus) {
            var first = null;
            for (var f = 0; f < GROUPS[group].length && !first; f++) {
                var candidate = document.getElementById(GROUPS[group][f]);
                if (candidate && candidate.style.display !== 'none') first = candidate;
            }
            if (first) first.scrollIntoView();
        }
    }

    function init() {
        var buttons = document.querySelectorAll('[data-system-group]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].onclick = function () { setGroup(this.getAttribute('data-system-group'), true); };
        }
        setGroup(groupForHash() || storedGroup() || 'connections', false);
        global.addEventListener('hashchange', function () {
            var group = groupForHash();
            if (group) setGroup(group, false);
        });
        if (global.BYD && BYD.i18n && BYD.i18n.onChange) {
            BYD.i18n.onChange(function () { setGroup(storedGroup() || 'connections', false); });
        }
    }

    global.PanoptesSystemOverview = { init: init, setGroup: setGroup, groups: GROUPS };
})(window);
