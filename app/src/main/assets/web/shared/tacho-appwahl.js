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
            auf_diesem_auto_nicht_angeboten: t('vehicle_control.cluster_app_unavailable', 'Auf diesem Auto ist die sichere Projektion nicht bestätigt.'),
            dilink5_totwinkel_nicht_belegt: t('vehicle_control.cluster_app_dilink5_blocked', 'DiLink 5 bleibt gesperrt, bis beide Kameraseiten, Paket-/Geometrie-Restore und Fehler-Fallback am Fahrzeug belegt sind.'),
            start_fehlgeschlagen: t('vehicle_control.cluster_app_failed', 'Die App konnte nicht auf dem Fahrerdisplay gestartet werden.'),
            hinweis_noetig: t('vehicle_control.cluster_app_hinweis_noetig', 'Lies zuerst den Hinweis und tippe auf „Verstanden“.'),
            fahrt_hinweis_noetig: t('vehicle_control.cluster_app_fahrt_hinweis_noetig', 'Lies zuerst den Hinweis zur Fahrt und tippe auf „Verstanden, ich entscheide selbst“.')
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
            o.textContent = (apps[i].name || apps[i].paket) + (apps[i].video ? ' · ' + t('vehicle_control.cluster_fahrt_video', 'Video-App') : '');
            sel.appendChild(o);
        }
        if (old && appFor(old)) sel.value = old;
        else if (data.paket && appFor(data.paket)) sel.value = data.paket;
        if (data.angeboten === false) message(reason(data.grund), true);
        update();
    }
    function update() {
        var pkg = selected(), app = appFor(pkg), approved = !!(app && app.freigegeben);
        var saved = !!(app && (app.gespeichertFreigegeben || app.freigegeben));
        $('clusterAppState').textContent = approved
            ? t('vehicle_control.cluster_app_approved', 'Freigegeben')
            : t('vehicle_control.cluster_app_blocked', 'Gesperrt');
        $('clusterAppApprove').disabled = !pkg || saved || data.angeboten === false;
        $('clusterAppEnable').disabled = !approved || data.angeboten === false;
        $('clusterAppRevoke').disabled = !saved;
        $('clusterAppStop').disabled = !(data && data.aktiv);
        var fahrt = !!(app && app.fahrt);
        var fb = $('clusterAppFahrt');
        fb.textContent = fahrt ? t('vehicle_control.cluster_app_fahrt_an', 'Auch beim Fahren: an')
            : t('vehicle_control.cluster_app_fahrt_aus', 'Auch beim Fahren: aus');
        fb.disabled = !saved;
        fb.setAttribute('aria-pressed', fahrt ? 'true' : 'false');
        var ansicht = data && data.ansicht && pkg === data.ansicht.paket;
        if ($('clusterAnsicht')) $('clusterAnsicht').style.display = ansicht ? '' : 'none';
    }
    // Einmaliger Hinweis vor der ersten Freigabe bzw. dem ersten Start (kein Schalter, keine Probe).
    var nachHinweis = null;
    function mitHinweis(weiter) {
        if (data && data.hinweisVerstanden) { weiter(); return; }
        nachHinweis = weiter;
        $('clusterAppHinweis').style.display = '';
    }
    // Eigener Haftungshinweis vor „auch beim Fahren“; bei Video-Apps zusätzlich deutlich „Video-App“.
    var nachFahrt = null;
    function mitFahrtHinweis(app, weiter) {
        $('clusterFahrtVideo').style.display = app && app.video ? 'block' : 'none';
        if (data && data.fahrtHinweis && data.fahrtHinweis.bestaetigt && !(app && app.video)) { weiter(); return; }
        nachFahrt = weiter;
        $('clusterFahrtHinweis').style.display = '';
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
                if (!x.ok) {
                    message(reason(x.j && x.j.grund), true);
                    if (x.j && x.j.grund === 'hinweis_noetig') $('clusterAppHinweis').style.display = '';
                }
                else if (done) done(x.j);
                return load();
            }, function () { message(reason(''), true); });
    }
    function boot() {
        if (!$('clusterAppApproval')) return;
        $('clusterAppPackage').onchange = update;
        $('clusterAppHinweisOk').onclick = function () {
            $('clusterAppHinweis').style.display = 'none';
            send('/api/tacho/navi/hinweis', 'PUT', {verstanden:true}, function () {
                if (data) data.hinweisVerstanden = true;
                var w = nachHinweis; nachHinweis = null; if (w) w();
            });
        };
        // Freigeben ohne Probe: App wählen, einmal den Hinweis bestätigen, fertig.
        $('clusterAppApprove').onclick = function () {
            var p = selected(); mitHinweis(function () { send('/api/tacho/navi/warnleuchten', 'PUT', {frei:'ja', paket:p}); });
        };
        $('clusterAppRevoke').onclick = function () {
            send('/api/tacho/navi/warnleuchten', 'PUT', {frei:'nein', paket:selected()});
        };
        $('clusterAppEnable').onclick = function () {
            var p = selected(); mitHinweis(function () { send('/api/tacho/navi', 'PUT', {an:true, paket:p}); });
        };
        $('clusterAppStop').onclick = function () { send('/api/tacho/navi', 'PUT', {an:false}); };
        $('clusterAppFahrt').onclick = function () {
            var p = selected(), app = appFor(p);
            if (app && app.fahrt) { send('/api/tacho/navi/fahrt', 'PUT', {paket:p, an:false}); return; }
            mitFahrtHinweis(app, function () { send('/api/tacho/navi/fahrt', 'PUT', {paket:p, an:true}); });
        };
        $('clusterFahrtOk').onclick = function () {
            $('clusterFahrtHinweis').style.display = 'none';
            var version = data && data.fahrtHinweis ? data.fahrtHinweis.version : '';
            send('/api/tacho/navi/fahrt/hinweis', 'PUT', {version:version}, function () {
                if (data && data.fahrtHinweis) data.fahrtHinweis.bestaetigt = true;
                var w = nachFahrt; nachFahrt = null; if (w) w();
            });
        };
        $('clusterFahrtAbbrechen').onclick = function () {
            $('clusterFahrtHinweis').style.display = 'none'; nachFahrt = null;
        };
        // Sicherheitsknopf: ohne Rückfrage, stoppt jede Projektion.
        $('clusterTachoNormal').onclick = function () {
            send('/api/tacho/normal', 'POST', {}, function () {
                message(t('vehicle_control.cluster_tacho_normal_ok', 'Der Tacho zeigt wieder die normale Anzeige.'));
            });
        };
        load();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
