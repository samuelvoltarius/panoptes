/**
 * System page: tunnels/daemons on-off and the Tailscale node.
 * Backend: server/SystemApiHandler.java (/api/system/*).
 */
const SystemPage = {
    pollTimer: null,
    wireGuardDenied: false,

    init() {
        this.refresh();
        this.pollTimer = setInterval(() => this.refresh(), 10000);
    },

    async refresh() {
        await Promise.all([this.loadTailscale(), this.loadDaemons(), this.loadWireGuard(), this.loadManualVehicle()]);
    },

    async loadManualVehicle() {
        const badge = document.getElementById('manualVehicleBadge');
        if (!badge) return;
        try {
            const resp = await fetch('/api/system/manual-vehicle', { cache: 'no-store' });
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const data = await resp.json();
            const name = document.getElementById('manualVehicleName');
            const battery = document.getElementById('manualVehicleBattery');
            const soc = document.getElementById('manualVehicleSoc');
            if (document.activeElement !== name) name.value = data.name || '';
            if (document.activeElement !== battery) battery.value = data.batteryKwh > 0 ? data.batteryKwh : '';
            if (document.activeElement !== soc) {
                const value = typeof data.liveSoc === 'number' ? data.liveSoc : data.manualSoc;
                soc.value = typeof value === 'number' && isFinite(value) ? Math.round(value) : '';
            }
            badge.textContent = data.socSource === 'vehicle'
                ? BYD.i18n.t('system.manual_vehicle_live')
                : (data.name ? BYD.i18n.t('system.manual_vehicle_saved') : BYD.i18n.t('system.manual_vehicle_new'));
        } catch (e) {
            badge.textContent = '--';
        }
    },

    async saveManualVehicle() {
        const name = document.getElementById('manualVehicleName').value.trim();
        const battery = Number(String(document.getElementById('manualVehicleBattery').value).replace(',', '.'));
        const soc = Number(String(document.getElementById('manualVehicleSoc').value).replace(',', '.'));
        const error = document.getElementById('manualVehicleError');
        const button = document.getElementById('manualVehicleSave');
        error.style.display = 'none';
        if (!name || name.length > 40 || !isFinite(battery) || battery < 5 || battery > 120
                || !isFinite(soc) || soc < 0 || soc > 100) {
            error.textContent = BYD.i18n.t('system.manual_vehicle_invalid');
            error.style.display = '';
            return;
        }
        button.disabled = true;
        try {
            const resp = await fetch('/api/system/manual-vehicle', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name, batteryKwh: battery, soc: soc })
            });
            const data = await resp.json().catch(() => ({}));
            if (!resp.ok || data.success === false) throw new Error(data.error || 'HTTP ' + resp.status);
            BYD.utils.toast(BYD.i18n.t('system.manual_vehicle_saved_toast'), 'success');
            if (window.SurfMateAppShell && SurfMateAppShell.refreshVehicle) SurfMateAppShell.refreshVehicle();
            await this.loadManualVehicle();
        } catch (e) {
            error.textContent = BYD.i18n.t('system.not_saved', { error: e.message });
            error.style.display = '';
        } finally {
            button.disabled = false;
        }
    },

    async loadTailscale() {
        const badge = document.getElementById('tsBadge');
        try {
            const resp = await fetch('/api/system/tailscale');
            const ts = await resp.json();
            if (!ts.success) {
                badge.textContent = BYD.i18n.t('system.off');
                document.getElementById('tsIp').textContent = '--';
                document.getElementById('tsDns').textContent = ts.error || '--';
                document.getElementById('tsPeers').textContent = '--';
                return;
            }
            const running = ts.backendState === 'Running';
            badge.textContent = running
                ? (ts.online ? BYD.i18n.t('system.connected') : BYD.i18n.t('system.running'))
                : ts.backendState;
            const ip4 = (ts.ips || []).find(ip => ip.indexOf(':') < 0) || (ts.ips || [])[0] || '--';
            document.getElementById('tsIp').textContent = ip4;
            document.getElementById('tsDns').textContent = ts.dnsName || ts.hostName || '--';
            document.getElementById('tsPeers').textContent = ts.peers;
            const input = document.getElementById('tsHostname');
            if (document.activeElement !== input && ts.hostName) input.value = ts.hostName;
            const login = document.getElementById('tsLogin');
            if (ts.authUrl) {
                document.getElementById('tsLoginUrl').href = ts.authUrl;
                login.style.display = '';
            } else {
                login.style.display = 'none';
            }
        } catch (e) {
            badge.textContent = '--';
            console.warn('[System] tailscale status failed:', e);
        }
    },

    async loadDaemons() {
        try {
            const resp = await fetch('/api/system/status');
            const data = await resp.json();
            if (!data.success) return;
            const switchable = data.daemons.filter(d => d.switchable);
            const core = data.daemons.filter(d => !d.switchable);
            document.getElementById('daemonList').innerHTML = switchable.map(d => this.row(d, true)).join('');
            document.getElementById('coreList').innerHTML = core.map(d => this.row(d, false)).join('');
        } catch (e) {
            console.warn('[System] status failed:', e);
        }
    },

    async loadWireGuard() {
        if (this.wireGuardDenied) return;
        const card = document.getElementById('wireguardSetupCard');
        if (!card) return;
        const badge = document.getElementById('wireguardSetupBadge');
        const state = document.getElementById('wireguardSetupState');
        const detail = document.getElementById('wireguardSetupDetail');
        const error = document.getElementById('wireguardSetupError');
        const open = document.getElementById('wireguardSetupOpen');
        const retry = document.getElementById('wireguardSetupRetry');
        try {
            const resp = await fetch('/api/wireguard', { cache: 'no-store' });
            if (resp.status === 401 || resp.status === 403) {
                this.wireGuardDenied = true;
                card.style.display = 'none';
                return;
            }
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const data = await resp.json();
            card.style.display = '';
            error.style.display = 'none';
            retry.style.display = 'none';

            const configured = data.configured === true;
            const running = data.running === true;
            badge.textContent = !configured ? BYD.i18n.t('system.wireguard_not_configured')
                : (running ? BYD.i18n.t('system.running') : BYD.i18n.t('system.off'));
            state.textContent = !configured ? BYD.i18n.t('system.wireguard_not_configured_text')
                : (running ? BYD.i18n.t('system.wireguard_running_text')
                    : BYD.i18n.t('system.wireguard_stopped_text'));
            open.textContent = BYD.i18n.t(configured
                ? 'system.wireguard_edit_action' : 'system.wireguard_setup_action');

            const summary = data.summary || {};
            const parts = [];
            if (summary.addresses && summary.addresses.length) {
                parts.push(BYD.i18n.t('system.wireguard_addresses', {
                    value: summary.addresses.join(', ')
                }));
            }
            if (summary.endpoints && summary.endpoints.length) {
                parts.push(BYD.i18n.t('system.wireguard_endpoints', {
                    value: summary.endpoints.join(', ')
                }));
            }
            const status = data.status || {};
            if (status.error) parts.push(BYD.i18n.t('system.wireguard_problem', {
                error: String(status.error)
            }));
            detail.textContent = parts.join(' · ');
            detail.style.display = parts.length ? '' : 'none';
        } catch (e) {
            card.style.display = '';
            badge.textContent = '--';
            state.textContent = BYD.i18n.t('system.wireguard_status_unknown');
            detail.style.display = 'none';
            error.textContent = BYD.i18n.t('system.wireguard_load_failed', {
                error: e && e.message ? e.message : '?'
            });
            error.style.display = '';
            retry.style.display = '';
        }
    },

    row(d, switchable) {
        // Enabled but not up yet = starting (yellow); a stop sentinel wins over "enabled".
        const dot = d.running ? 'on' : (switchable && d.enabled && !d.stopped ? 'wait' : '');
        const state = d.running ? BYD.i18n.t('system.running')
            : (switchable && d.enabled && !d.stopped
                ? BYD.i18n.t('system.starting') : BYD.i18n.t('system.off'));
        const toggle = switchable
            ? `<label class="toggle-switch"><input type="checkbox" ${d.enabled && !d.stopped ? 'checked' : ''}
                 onchange="SystemPage.toggle('${d.type}', this.checked, this)"><span class="toggle-slider"></span></label>`
            : '';
        return `<div class="sys-row">
            <span class="sys-dot ${dot}"></span>
            <div class="sys-info"><div class="sys-name">${this.label(d)}</div>
                 <div class="sys-meta">${d.process} · ${state}</div></div>
            ${toggle}</div>`;
    },

    label(d) {
        const names = {
            TAILSCALE_TUNNEL: BYD.i18n.t('system.daemon_tailscale'),
            TELEGRAM_DAEMON: BYD.i18n.t('system.daemon_telegram'),
            WIREGUARD_TUNNEL: 'WireGuard',
            CAMERA_DAEMON: BYD.i18n.t('system.daemon_camera'),
            SENTRY_DAEMON: BYD.i18n.t('system.daemon_guard'),
            ACC_SENTRY_DAEMON: BYD.i18n.t('system.daemon_acc_guard'),
        };
        return names[d.type] || d.name;
    },

    pendingTsInput: null,

    async toggle(type, enabled, input) {
        if (type === 'TAILSCALE_TUNNEL' && !enabled) {
            this.pendingTsInput = input;
            document.getElementById('tsConfirm').style.display = '';
            return;
        }
        await this.send(type, enabled, false, input);
    },

    cancelTsOff() {
        document.getElementById('tsConfirm').style.display = 'none';
        if (this.pendingTsInput) this.pendingTsInput.checked = true;
        this.pendingTsInput = null;
    },

    async confirmTsOff() {
        document.getElementById('tsConfirm').style.display = 'none';
        await this.send('TAILSCALE_TUNNEL', false, true, this.pendingTsInput);
        this.pendingTsInput = null;
    },

    async send(type, enabled, confirm, input) {
        try {
            const resp = await fetch('/api/system/daemon', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ type, enabled, confirm })
            });
            const res = await resp.json();
            if (!res.success) throw new Error(res.error || BYD.i18n.t('system.error'));
            BYD.utils.toast(enabled ? BYD.i18n.t('system.daemon_enabled')
                : BYD.i18n.t('system.daemon_disabled'), 'success');
        } catch (e) {
            if (input) input.checked = !enabled;
            BYD.utils.toast(BYD.i18n.t('system.not_saved', { error: e.message }), 'error');
        }
        setTimeout(() => this.loadDaemons(), 1500);
    },

    async saveHostname() {
        const name = document.getElementById('tsHostname').value.trim().toLowerCase();
        if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
            BYD.utils.toast(BYD.i18n.t('system.hostname_invalid'), 'error');
            return;
        }
        try {
            const resp = await fetch('/api/system/tailscale/hostname', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ hostname: name })
            });
            const res = await resp.json();
            if (!res.success) throw new Error(res.error || BYD.i18n.t('system.error'));
            BYD.utils.toast(BYD.i18n.t('system.hostname_saved', { name: name }), 'success');
            setTimeout(() => this.loadTailscale(), 1500);
        } catch (e) {
            BYD.utils.toast(BYD.i18n.t('system.not_saved', { error: e.message }), 'error');
        }
    }
};
