(function () {
    'use strict';
    function $(id) { return document.getElementById(id); }
    function t(key, fallback) {
        return window.PanoptesShell && PanoptesShell.tr ? PanoptesShell.tr(key, fallback) : fallback;
    }
    var data = null;
    function selected() { return $('clusterAppPackage') ? $('clusterAppPackage').value : ''; }
    function appFor(pkg) {
        var apps = data && (data.apps || data.erlaubt) || [];
        for (var i = 0; i < apps.length; i++) if (apps[i].paket === pkg) return apps[i];
        return null;
    }
    function message(text, bad) {
        var el = $('clusterAppMessage'); if (!el) return;
        el.textContent = text || ''; el.style.color = bad ? 'var(--status-danger)' : '';
    }
    function reason(code) {
        var map = {
            nur_im_stand: t('vehicle_control.cluster_app_park', 'Nur in P möglich.'),
            nicht_freigegeben: t('vehicle_control.cluster_app_not_approved', 'Diese App ist noch nicht freigegeben.'),
            nicht_installiert: t('vehicle_control.cluster_app_uninstalled', 'Die App ist nicht mehr installiert oder nicht startbar.'),
            ungueltiges_paket: t('vehicle_control.cluster_app_invalid', 'Diese App kann nicht gewählt werden.'),
            probe_noetig: t('vehicle_control.cluster_app_probe_needed', 'Prüfe diese App zuerst in P.'),
            auf_diesem_auto_nicht_angeboten: t('vehicle_control.cluster_app_unavailable', 'Auf diesem Auto ist die sichere Projektion nicht bestätigt.'),
            dilink5_totwinkel_nicht_belegt: t('vehicle_control.cluster_app_dilink5_blocked', 'DiLink 5 bleibt gesperrt, bis beide Kameraseiten, Paket-/Geometrie-Restore und Fehler-Fallback am Fahrzeug belegt sind.'),
            start_fehlgeschlagen: t('vehicle_control.cluster_app_failed', 'Die App konnte nicht auf dem Fahrerdisplay gestartet werden.')
        };
        return map[code] || t('vehicle_control.cluster_app_failed', 'Das hat nicht geklappt.');
    }
    function render(d) {
        data = d || {};
        var sel = $('clusterAppPackage'), old = sel.value, apps = data.apps || data.erlaubt || [];
        sel.textContent = '';
        for (var i = 0; i < apps.length; i++) {
            if (!apps[i].installiert) continue;
            var o = document.createElement('option'); o.value = apps[i].paket;
            o.textContent = apps[i].name || apps[i].paket; sel.appendChild(o);
        }
        if (old && appFor(old)) sel.value = old;
        else if (data.paket && appFor(data.paket)) sel.value = data.paket;
        if (data.angeboten === false) message(reason(data.grund), true);
        update();
    }
    function update() {
        var pkg = selected(), app = appFor(pkg), approved = !!(app && app.freigegeben);
        $('clusterAppState').textContent = approved
            ? t('vehicle_control.cluster_app_approved', 'Freigegeben')
            : t('vehicle_control.cluster_app_blocked', 'Gesperrt');
        $('clusterAppProbe').disabled = !pkg || data.angeboten === false || !!(data && data.probeLaeuft);
        $('clusterAppEnable').disabled = !approved || data.angeboten === false;
        $('clusterAppRevoke').disabled = !approved;
        $('clusterAppStop').disabled = !(data && data.aktiv);
    }
    function load() {
        fetch('/api/tacho/navi', { cache: 'no-store' }).then(function (r) { return r.json(); })
            .then(render, function () { message(t('vehicle_control.cluster_app_failed', 'Das hat nicht geklappt.'), true); });
    }
    function send(url, method, body, done) {
        message('');
        return fetch(url, { method: method, headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) })
            .then(function (r) { return r.json().then(function (j) { return {ok:r.ok && j.ok !== false, j:j}; }); })
            .then(function (x) {
                if (!x.ok) message(reason(x.j && x.j.grund), true);
                else if (done) done(x.j);
                return load();
            }, function () { message(reason(''), true); });
    }
    function boot() {
        if (!$('clusterAppApproval')) return;
        $('clusterAppPackage').onchange = update;
        $('clusterAppProbe').onclick = function () {
            var p = selected(); send('/api/tacho/navi/probe', 'POST', {paket:p}, function () {
                $('clusterAppQuestion').style.display = ''; message(t('vehicle_control.cluster_app_watch', 'Prüfe jetzt Tempo, Warnleuchten und den Rückweg zur Tacho-Anzeige.'));
            });
        };
        $('clusterAppYes').onclick = function () {
            var p = selected(); $('clusterAppQuestion').style.display = 'none';
            send('/api/tacho/navi/warnleuchten', 'PUT', {frei:'ja', paket:p});
        };
        $('clusterAppNo').onclick = function () {
            var p = selected(); $('clusterAppQuestion').style.display = 'none';
            send('/api/tacho/navi/warnleuchten', 'PUT', {frei:'nein', paket:p});
        };
        $('clusterAppRevoke').onclick = function () {
            send('/api/tacho/navi/warnleuchten', 'PUT', {frei:'nein', paket:selected()});
        };
        $('clusterAppEnable').onclick = function () {
            send('/api/tacho/navi', 'PUT', {an:true, paket:selected()});
        };
        $('clusterAppStop').onclick = function () { send('/api/tacho/navi', 'PUT', {an:false}); };
        load();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
