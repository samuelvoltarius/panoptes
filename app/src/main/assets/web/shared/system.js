/**
 * System page: tunnels/daemons on-off and the Tailscale node.
 * Backend: server/SystemApiHandler.java (/api/system/*).
 */
const SystemPage = {
    pollTimer: null,

    init() {
        this.refresh();
        this.pollTimer = setInterval(() => this.refresh(), 10000);
    },

    async refresh() {
        await Promise.all([this.loadTailscale(), this.loadDaemons()]);
    },

    async loadTailscale() {
        const badge = document.getElementById('tsBadge');
        try {
            const resp = await fetch('/api/system/tailscale');
            const ts = await resp.json();
            if (!ts.success) {
                badge.textContent = 'aus';
                document.getElementById('tsIp').textContent = '--';
                document.getElementById('tsDns').textContent = ts.error || '--';
                document.getElementById('tsPeers').textContent = '--';
                return;
            }
            const running = ts.backendState === 'Running';
            badge.textContent = running ? (ts.online ? 'verbunden' : 'läuft') : ts.backendState;
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

    row(d, switchable) {
        // Enabled but not up yet = starting (yellow); a stop sentinel wins over "enabled".
        const dot = d.running ? 'on' : (switchable && d.enabled && !d.stopped ? 'wait' : '');
        const state = d.running ? 'läuft' : (switchable && d.enabled && !d.stopped ? 'startet …' : 'aus');
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
            TAILSCALE_TUNNEL: 'Tailscale (Fernzugang)',
            TELEGRAM_DAEMON: 'Telegram-Bot',
            WIREGUARD_TUNNEL: 'WireGuard',
            CAMERA_DAEMON: 'Kamera & Weboberfläche',
            SENTRY_DAEMON: 'Wächter',
            ACC_SENTRY_DAEMON: 'ACC-Wächter (Zündung)',
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
            if (!res.success) throw new Error(res.error || 'Fehler');
            BYD.utils.toast(enabled ? 'Eingeschaltet – startet in etwa 30 s' : 'Ausgeschaltet', 'success');
        } catch (e) {
            if (input) input.checked = !enabled;
            BYD.utils.toast('Nicht gespeichert: ' + e.message, 'error');
        }
        setTimeout(() => this.loadDaemons(), 1500);
    },

    async saveHostname() {
        const name = document.getElementById('tsHostname').value.trim().toLowerCase();
        if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
            BYD.utils.toast('Nur a–z, 0–9 und Bindestrich', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/system/tailscale/hostname', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ hostname: name })
            });
            const res = await resp.json();
            if (!res.success) throw new Error(res.error || 'Fehler');
            BYD.utils.toast('Gerätename: ' + name, 'success');
            setTimeout(() => this.loadTailscale(), 1500);
        } catch (e) {
            BYD.utils.toast('Nicht gespeichert: ' + e.message, 'error');
        }
    }
};
