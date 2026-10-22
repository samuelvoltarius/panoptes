package at.greenchili.surfmate.ha;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * mDNS-Ankündigung {@code _panoptes._tcp} Port 8180 für Home Assistant (HA_VERTRAG.md §6).
 * <b>Standard aus</b> (Koordination 21.10.): nur mit {@code ha.mdns = true}, wirkt nach Neustart.
 * TXT nur {@code id} (eigene Zufallskennung {@code ha.mdnsId}, nicht die autoId, nie VIN), {@code api=1}, {@code ver}.
 * Android kündigt auf allen Multicast-fähigen Schnittstellen an; das Tailscale-Tunnelgerät ist nicht multicastfähig.
 */
public final class HaMdns {

    private static final DaemonLogger logger = DaemonLogger.getInstance("HA-mDNS");
    public static final String TYP = "_panoptes._tcp";
    public static final int PORT = 8180;

    private HaMdns() {}

    static void start() {
        JSONObject ha = UnifiedConfigManager.loadConfig().optJSONObject("ha");
        if (ha == null || !ha.optBoolean("mdns", false)) return;
        String id = ha.optString("mdnsId", "");
        if (!id.matches("^[0-9a-f]{8}$")) {
            byte[] b = new byte[4];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            id = sb.toString();
            java.util.Map<String, Object> v = new java.util.HashMap<>();
            v.put("mdnsId", id);
            UnifiedConfigManager.updateValues("ha", v);
        }
        android.content.Context c = at.greenchili.surfmate.daemon.DaemonBootstrap.getContext();
        if (c == null) {
            logger.warn("kein Context – keine Ankündigung");
            return;
        }
        android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) c.getSystemService(android.content.Context.NSD_SERVICE);
        if (nsd == null) return;
        android.net.nsd.NsdServiceInfo s = new android.net.nsd.NsdServiceInfo();
        s.setServiceName("Panoptes " + id.substring(0, 4));
        s.setServiceType(TYP);
        s.setPort(PORT);
        s.setAttribute("id", id);
        s.setAttribute("api", "1");
        s.setAttribute("ver", at.greenchili.surfmate.BuildConfig.VERSION_NAME);
        nsd.registerService(s, android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
                new android.net.nsd.NsdManager.RegistrationListener() {
                    @Override public void onServiceRegistered(android.net.nsd.NsdServiceInfo i) {
                        logger.info("angekündigt: " + i.getServiceName());
                    }
                    @Override public void onRegistrationFailed(android.net.nsd.NsdServiceInfo i, int code) {
                        logger.warn("Ankündigung fehlgeschlagen: " + code);
                    }
                    @Override public void onServiceUnregistered(android.net.nsd.NsdServiceInfo i) { }
                    @Override public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo i, int code) { }
                });
    }
}
