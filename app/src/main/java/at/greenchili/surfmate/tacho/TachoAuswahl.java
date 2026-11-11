package at.greenchili.surfmate.tacho;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * „Was zeigt der Tacho?“ (0.1.37): EINE Auswahl fuer Web, Handy-Apps und Cockpit (nicht im Launcher).
 *
 * <p>Eintraege: die fuenf Panoptes-Designs ({@link TachoAnsicht#DESIGNS}), dann installierte Navi-Apps
 * und alle freigegebenen Apps aus {@link TachoNaviApp#status()}, dazu „Tacho wieder normal“. Keine eigene
 * Policy: Starten laeuft ausschliesslich ueber {@link TachoNaviApp#setze} (Hinweis, Freigabe, Stand,
 * Plattformurteil; Surf und DiLink 5 bleiben gesperrt) bzw. {@link TachoNaviApp#tachoNormal()}.
 * Ein Design zu wechseln, waehrend die Panoptes-Tacho-Ansicht schon laeuft, aendert nur das Aussehen
 * und startet nichts neu. Vertrag fuer Handy-Apps und Cockpit: {@code TACHO_AUSWAHL_API.md}.
 *
 * <p>IDs: {@code design:<name>}, {@code app:<paket>}, {@code normal}.
 */
public final class TachoAuswahl {

    public static final String NORMAL = "normal";
    static final String DESIGN = "design:", APP = "app:";

    /** Anzeigenamen (deutsch); Oberflaechen uebersetzen ueber {@code vehicle_control.cluster_design_<name>}. */
    static String designName(String d) {
        switch (d) {
            case TachoAnsicht.SPORT: return "Sport";
            case TachoAnsicht.NACHT: return "Nacht";
            case TachoAnsicht.HORIZONT: return "Horizont";
            case TachoAnsicht.ROUTE: return "Route";
            case TachoEigen.NAME: return "Eigenes";
            default: return "Klar";
        }
    }

    private TachoAuswahl() {}

    /** Aktuelle Auswahl. Nur lesend. */
    public static JSONObject liste() throws Exception {
        return liste(TachoNaviApp.status(), TachoAnsicht.design(), TachoEigen.gespeichert() != null);
    }

    static JSONObject liste(JSONObject status, String design) throws Exception {
        return liste(status, design, false);
    }

    /**
     * Baut die Liste aus dem Status der Appwahl (rein, testbar). Apps erscheinen, wenn sie Navigation
     * sind oder freigegeben wurden; alle anderen bleiben in der Freigabe-Verwaltung.
     */
    static JSONObject liste(JSONObject status, String design, boolean eigenDa) throws Exception {
        String aktiv = status.isNull("aktiv") ? "" : status.optString("aktiv", "");
        boolean angeboten = status.optBoolean("angeboten", false);
        JSONArray apps = status.optJSONArray("apps");
        JSONObject panoptes = null;
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a != null && TachoAnsicht.istAnsicht(a.optString("paket"))) { panoptes = a; break; }
            }
        }
        boolean ansichtFrei = panoptes != null && panoptes.optBoolean("freigegeben", false);
        boolean ansichtAktiv = TachoAnsicht.istAnsicht(aktiv);
        String dsg = TachoAnsicht.design(design);

        JSONArray eintraege = new JSONArray();
        // 0.1.41: das eigene Design steht als sechste Kachel da, sobald eines gespeichert ist.
        java.util.List<String> designs = new java.util.ArrayList<>(TachoAnsicht.DESIGNS);
        if (eigenDa || TachoEigen.NAME.equals(dsg)) designs.add(TachoEigen.NAME);
        for (String d : designs) {
            JSONObject e = new JSONObject()
                    .put("id", DESIGN + d).put("art", "design").put("design", d).put("name", designName(d))
                    .put("fahrt", true).put("video", false)
                    .put("gewaehlt", ansichtAktiv && d.equals(dsg))
                    .put("waehlbar", ansichtFrei || ansichtAktiv);
            if (!ansichtFrei && !ansichtAktiv) e.put("grund", angeboten ? "nicht_freigegeben" : grund(status));
            eintraege.put(e);
        }
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a == null) continue;
                String p = a.optString("paket", "");
                if (p.isEmpty() || TachoAnsicht.istAnsicht(p)) continue;
                boolean navi = a.optBoolean("navi", false);
                boolean frei = a.optBoolean("freigegeben", false);
                boolean gespeichert = a.optBoolean("gespeichertFreigegeben", frei);
                if (!navi && !gespeichert) continue;
                JSONObject e = new JSONObject()
                        .put("id", APP + p).put("art", "app").put("paket", p)
                        .put("name", a.optString("name", p))
                        .put("navi", navi).put("video", a.optBoolean("video", false))
                        .put("audio", a.optBoolean("audio", false))
                        .put("fahrt", a.optBoolean("fahrt", false))
                        .put("gewaehlt", p.equals(aktiv))
                        .put("waehlbar", frei)
                        .put("symbol", JSONObject.NULL);
                if (!frei) e.put("grund", angeboten ? "nicht_freigegeben" : grund(status));
                eintraege.put(e);
            }
        }
        JSONObject jetzt = new JSONObject();
        if (aktiv.isEmpty()) jetzt.put("id", NORMAL).put("art", NORMAL);
        else if (ansichtAktiv) jetzt.put("id", DESIGN + dsg).put("art", "design").put("design", dsg);
        else jetzt.put("id", APP + aktiv).put("art", "app").put("paket", aktiv);

        return new JSONObject().put("ok", true)
                .put("angeboten", angeboten)
                .put("grund", angeboten ? "" : grund(status))
                .put("hinweisVerstanden", status.optBoolean("hinweisVerstanden", false))
                .put("jetzt", jetzt)
                .put("design", dsg)
                .put("eintraege", eintraege)
                .put("normal", new JSONObject().put("id", NORMAL).put("name", "Tacho wieder normal"));
    }

    private static String grund(JSONObject status) {
        String g = status.optString("grund", "");
        return g.isEmpty() ? "auf_diesem_auto_nicht_angeboten" : g;
    }

    /** Zerlegt eine ID in {art, wert}; null bei ungueltiger ID. */
    static String[] ziel(String id) {
        String s = id == null ? "" : id.trim();
        if (s.equals(NORMAL)) return new String[] {NORMAL, ""};
        if (s.startsWith(DESIGN)) {
            String d = s.substring(DESIGN.length()).toLowerCase(Locale.ROOT);
            return TachoAnsicht.DESIGNS.contains(d) || TachoEigen.NAME.equals(d) ? new String[] {"design", d} : null;
        }
        if (s.startsWith(APP)) {
            String p = s.substring(APP.length());
            return TachoNaviApp.gueltigesFremdpaket(p) ? new String[] {"app", p.trim()} : null;
        }
        return null;
    }

    /**
     * Waehlt einen Eintrag. Design: Aussehen speichern; laeuft die Ansicht noch nicht, wird sie wie jede
     * App ueber {@link TachoNaviApp#setze} gestartet. App: nur ueber {@link TachoNaviApp#setze}.
     * {@code normal}: {@link TachoNaviApp#tachoNormal()}.
     */
    public static synchronized JSONObject waehle(String id) throws Exception {
        String[] z = ziel(id);
        if (z == null) return new JSONObject().put("ok", false).put("grund", "ungueltige_auswahl");
        JSONObject r;
        switch (z[0]) {
            case NORMAL:
                r = TachoNaviApp.tachoNormal();
                break;
            case "design": {
                boolean gespeichert = TachoAnsicht.setzeDesign(z[1]);
                if (!gespeichert) return new JSONObject().put("ok", false).put("grund", "speichern_fehlgeschlagen");
                boolean laeuft = false;
                try {
                    laeuft = at.greenchili.surfmate.launcher.ClusterCast.isActive()
                            && TachoAnsicht.istAnsicht(at.greenchili.surfmate.launcher.ClusterCast.getCastPackage());
                } catch (Throwable ignored) { }
                r = laeuft ? new JSONObject().put("ok", true) : TachoNaviApp.setze(true, TachoAnsicht.PAKET);
                break;
            }
            default:
                r = TachoNaviApp.setze(true, z[1]);
        }
        return r.put("id", id.trim());
    }
}
