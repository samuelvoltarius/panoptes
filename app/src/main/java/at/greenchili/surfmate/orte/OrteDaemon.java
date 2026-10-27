package at.greenchili.surfmate.orte;

import at.greenchili.surfmate.charging.TariffManager;
import at.greenchili.surfmate.charging.TariffProfile;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.surveillance.SafeLocation;
import at.greenchili.surfmate.surveillance.SafeLocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.1.11 (Kern 7): Orte im Daemon – echte alte Speicher + Abschnitt {@code orte}, Abgleich beim Start und alle 60 s
 * (alte Schreibwege landen so spätestens nach einer Minute in der Ortsliste und damit in jeder Sicherung).
 * Die Ortsliste verlässt das Auto nie (Regel 2): kein Netz, im Log nur Anzahlen.
 */
public final class OrteDaemon implements OrteDienst.Speicher {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Orte");
    public static final String ABSCHNITT = "orte";

    private static volatile OrteDienst dienst;
    private static volatile Thread takt;

    private OrteDaemon() {}

    public static synchronized OrteDienst dienst() {
        if (dienst == null) dienst = new OrteDienst(new OrteDaemon());
        return dienst;
    }

    /** Beim Daemon-Start nach SafeLocationManager.init(): erster Abgleich nach 20 s, dann alle 60 s. */
    public static synchronized void starten() {
        if (takt != null) return;
        takt = new Thread(() -> {
            try { Thread.sleep(20_000L); } catch (InterruptedException e) { return; }
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    JSONArray l = dienst().abgleichen(System.currentTimeMillis());
                    logger.debug("Orte-Abgleich: " + l.length() + " Ort(e)");
                } catch (Throwable t) {
                    logger.warn("Orte-Abgleich fehlgeschlagen: " + t.getMessage());
                }
                try { Thread.sleep(60_000L); } catch (InterruptedException e) { return; }
            }
        }, "OrteAbgleich");
        takt.setDaemon(true);
        takt.setPriority(Thread.MIN_PRIORITY);
        takt.start();
    }

    /** Liste ohne Abgleich (nur lesen, z. B. Monatsbericht); null, wenn noch nicht migriert. */
    public static JSONArray listeOhneAbgleich() {
        try {
            JSONObject sec = UnifiedConfigManager.loadConfig().optJSONObject(ABSCHNITT);
            return sec != null && sec.optBoolean("migriert", false) ? sec.optJSONArray("liste") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 0.1.19: ganzer Abschnitt ohne Abgleich (nur lesen; Leser SafeLocationManager/TariffManager); null bei Fehler. */
    public static JSONObject abschnittOhneAbgleich() {
        try {
            return UnifiedConfigManager.loadConfig().optJSONObject(ABSCHNITT);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Speicher

    @Override public JSONObject alteLesen() throws Exception {
        JSONArray ruhe = new JSONArray();
        for (SafeLocation z : SafeLocationManager.getInstance().getZones()) {
            ruhe.put(new JSONObject().put("id", z.getId()).put("name", z.getName()).put("lat", z.getLatitude())
                    .put("lon", z.getLongitude()).put("r", z.getRadiusMeters()).put("an", z.isEnabled()));
        }
        JSONArray privat = new JSONArray();
        JSONObject surv = UnifiedConfigManager.loadConfig().optJSONObject("surveillance");
        JSONArray pz = surv == null ? null : surv.optJSONArray("privateZones");
        for (int i = 0; pz != null && i < pz.length(); i++) {
            JSONObject z = pz.optJSONObject(i);
            if (z == null || Double.isNaN(z.optDouble("lat", Double.NaN))) continue;
            privat.put(new JSONObject().put("lat", z.optDouble("lat")).put("lon", z.optDouble("lon"))
                    .put("r", (int) Math.round(z.optDouble("radiusM", 0))));
        }
        JSONArray tarife = new JSONArray();
        for (TariffProfile p : TariffManager.getInstance().getProfiles()) {
            if (p.getLatitude() == 0 && p.getLongitude() == 0) continue;   // ohne Ort: bleibt reiner Tarif
            JSONObject j = p.toJson();
            boolean acG = j.optBoolean("acGratis", false), dcG = j.optBoolean("dcGratis", false);
            tarife.put(new JSONObject().put("id", p.getId()).put("label", p.getLabel() == null ? "" : p.getLabel())
                    .put("lat", p.getLatitude()).put("lon", p.getLongitude()).put("r", p.getRadiusMeters())
                    .put("acRate", p.getAcRate() > 0 || acG ? (Object) p.getAcRate() : JSONObject.NULL)
                    .put("dcRate", p.getDcRate() > 0 || dcG ? (Object) p.getDcRate() : JSONObject.NULL)
                    .put("acGratis", acG).put("dcGratis", dcG)
                    .put("waehrung", p.getCurrency() == null ? "" : p.getCurrency()));
        }
        return new JSONObject().put("ruhe", ruhe).put("privat", privat).put("tarife", tarife);
    }

    @Override public String ruheAnlegen(String name, double lat, double lon, int r, boolean an) throws Exception {
        SafeLocation z = SafeLocationManager.getInstance().addZone(name, lat, lon, r);
        if (z == null) return null;
        if (!an) SafeLocationManager.getInstance().updateZone(z.getId(), new JSONObject().put("enabled", false));
        return z.getId();
    }

    @Override public void ruheAendern(String id, String name, double lat, double lon, int r, boolean an) throws Exception {
        SafeLocationManager.getInstance().updateZone(id, new JSONObject().put("name", name).put("lat", lat).put("lng", lon)
                .put("radiusM", r).put("enabled", an));
    }

    @Override public void ruheEntfernen(String id) {
        SafeLocationManager.getInstance().removeZone(id);
    }

    @Override public void privatSetzen(JSONArray zonen) throws Exception {
        UnifiedConfigManager.updateSection("surveillance", new JSONObject().put("privateZones", zonen));
    }

    @Override public String tarifAnlegen(JSONObject t) throws Exception {
        TariffProfile p = TariffManager.getInstance().add(t.optString("label"), t.optDouble("lat"), t.optDouble("lon"),
                t.optInt("r"), rate(t, "acRate"), rate(t, "dcRate"), t.optString("waehrung", ""),
                t.optBoolean("acGratis"), t.optBoolean("dcGratis"));
        return p == null ? null : p.getId();
    }

    @Override public void tarifAendern(String id, JSONObject t) throws Exception {
        TariffManager.getInstance().update(id, new JSONObject().put("label", t.optString("label"))
                .put("lat", t.optDouble("lat")).put("lng", t.optDouble("lon")).put("radiusM", t.optInt("r"))
                .put("acRate", rate(t, "acRate")).put("dcRate", rate(t, "dcRate"))
                .put("acGratis", t.optBoolean("acGratis")).put("dcGratis", t.optBoolean("dcGratis"))
                .put("currency", t.optString("waehrung", "")));
    }

    @Override public void tarifEntfernen(String id) {
        TariffManager.getInstance().remove(id);
    }

    private static double rate(JSONObject t, String k) {
        return t.isNull(k) ? 0 : Math.max(0, t.optDouble(k, 0));
    }

    @Override public JSONObject abschnittLesen() throws Exception {
        JSONObject s = UnifiedConfigManager.loadConfig().optJSONObject(ABSCHNITT);
        return s == null ? new JSONObject() : new JSONObject(s.toString());
    }

    @Override public void abschnittSchreiben(JSONObject abschnitt) throws Exception {
        UnifiedConfigManager.updateSection(ABSCHNITT, abschnitt);
        logger.info("Orte gespeichert: " + abschnitt.optJSONArray("liste").length() + " Ort(e)");
    }
}
