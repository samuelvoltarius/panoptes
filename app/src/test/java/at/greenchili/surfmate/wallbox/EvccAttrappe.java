package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Nachgebautes evcc (Form wie 0.317.2, demo.evcc.io): Carport, Garage (mit Fahrzeug), Wärmepumpe. */
final class EvccAttrappe implements WallboxHttp {

    final JSONObject zustand = new JSONObject();
    final List<String> anfragen = new ArrayList<>();
    String erwarteterSchluessel;
    boolean alteModi;

    EvccAttrappe() throws Exception {
        JSONArray lps = new JSONArray();
        lps.put(lp("Carport", "smart", "off", 95, false, 0, "", false));
        lps.put(lp("Garage", "now", "off", 100, true, 3680, "vehicle_1", false));
        lps.put(lp("Heat pump", "smart", "off", 70, true, 3832.4, "", true));
        zustand.put("loadpoints", lps).put("version", "0.317.2");
    }

    private static JSONObject lp(String t, String mode, String always, int limit, boolean charging, double p, String fz, boolean heiz) throws Exception {
        return new JSONObject().put("title", t).put("mode", mode).put("alwaysCharge", always).put("limitSoc", limit)
                .put("charging", charging).put("connected", true).put("chargePower", p).put("vehicleName", fz)
                .put("vehicleTitle", fz.isEmpty() ? "" : "blue e-Golf").put("vehicleSoc", fz.isEmpty() ? 0 : 54)
                .put("chargerFeatureHeating", heiz).put("chargerFeatureIntegratedDevice", heiz)
                .put("effectivePlanTime", JSONObject.NULL).put("effectivePlanSoc", 0);
    }

    JSONObject lp(int n) throws Exception {
        return zustand.getJSONArray("loadpoints").getJSONObject(n - 1);
    }

    @Override
    public Antwort anfrage(String methode, String url, Map<String, String> kopf, String rumpf) {
        try {
            return beantworte(methode, url, kopf, rumpf);
        } catch (Exception e) {
            return new Antwort(500, String.valueOf(e));
        }
    }

    private Antwort beantworte(String methode, String url, Map<String, String> kopf, String rumpf) throws Exception {
        String pfad = url.substring(url.indexOf("/api/"));
        anfragen.add(methode + " " + pfad);
        if (erwarteterSchluessel != null && !("Bearer " + erwarteterSchluessel).equals(kopf.get("Authorization"))) {
            return new Antwort(401, "unauthorized");
        }
        if (methode.equals("GET") && pfad.equals("/api/state")) {
            JSONObject s = new JSONObject(zustand.toString());
            if (alteModi) {
                JSONArray lps = s.getJSONArray("loadpoints");
                for (int i = 0; i < lps.length(); i++) lps.getJSONObject(i).remove("alwaysCharge");
            }
            return new Antwort(200, new JSONObject().put("result", s).toString());
        }
        String[] t = pfad.split("/");
        if (methode.equals("POST") && t.length == 6 && t[2].equals("loadpoints")) {
            JSONObject lp = lp(Integer.parseInt(t[3]));
            switch (t[4]) {
                case "mode":
                    if (alteModi) {
                        lp.put("mode", t[5]);
                    } else if (t[5].equals("pv") || t[5].equals("minpv")) {
                        lp.put("mode", "smart");
                        if (t[5].equals("minpv")) lp.put("alwaysCharge", "on");
                    } else {
                        lp.put("mode", t[5]);
                    }
                    return new Antwort(200, "\"" + t[5] + "\"");
                case "alwayscharge":
                    lp.put("alwaysCharge", t[5]);
                    return new Antwort(200, "\"" + t[5] + "\"");
                case "limitsoc":
                    lp.put("limitSoc", Integer.parseInt(t[5]));
                    return new Antwort(200, t[5]);
                default:
                    return new Antwort(404, "");
            }
        }
        if (t.length >= 6 && t[2].equals("vehicles") && t[4].equals("plan")) {
            for (int i = 1; i <= 3; i++) {
                JSONObject lp = lp(i);
                if (!lp.getString("vehicleName").equals(t[3])) continue;
                if (methode.equals("DELETE")) {
                    lp.put("effectivePlanTime", JSONObject.NULL).put("effectivePlanSoc", 0);
                } else {
                    lp.put("effectivePlanSoc", Integer.parseInt(t[6])).put("effectivePlanTime", java.net.URLDecoder.decode(t[7]));
                }
                return new Antwort(200, "{}");
            }
            return new Antwort(400, "vehicle not found");
        }
        return new Antwort(404, "");
    }
}
