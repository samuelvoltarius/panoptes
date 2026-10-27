package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wallbox-Einstellungen (WALLBOX_VERTRAG §6), gespeichert in einer eigenen Datei – nie im Einstellungs-Schema,
 * damit gekoppelte Geräte und /api/einstellungen sie nie sehen. Geheimnisse ({@code evcc.apiKey}, {@code ha.token})
 * liegen nur verschlüsselt auf der Platte.
 * <pre>
 * {quelle: "evcc"|"ha"|null,
 *  evcc: {url, apiKey(verschlüsselt), oeffentlichErlaubt},
 *  ha:   {url, token(verschlüsselt), oeffentlichErlaubt, ladepunkte:[Zuordnung],
 *         refresh(verschlüsselt, 0.1.19 „Mit Home Assistant anmelden“), clientId},
 *  freigaben: ["evcc:1", …]}
 * </pre>
 */
public final class WallboxKonfig {

    /** Ver-/Entschlüsselung der Geheimnisse. Muss fail-closed sein: {@code ver} wirft, statt Klartext zu liefern. */
    public interface Geheim {
        String ver(String klar) throws WallboxFehler, JSONException;

        String ent(String gespeichert) throws JSONException;
    }

    static final int MAX_LADEPUNKTE = 8;

    private WallboxKonfig() {}

    public static JSONObject leer() throws JSONException {
        return new JSONObject()
                .put("quelle", JSONObject.NULL)
                .put("evcc", new JSONObject().put("url", "").put("oeffentlichErlaubt", false))
                .put("ha", new JSONObject().put("url", "").put("oeffentlichErlaubt", false).put("ladepunkte", new JSONArray()))
                .put("tuya", new JSONObject().put("zugang", "lan").put("geraete", new JSONArray())
                        .put("cloud", new JSONObject().put("region", "eu").put("clientId", "").put("einwilligung", false)
                                .put("einwilligungZeit", JSONObject.NULL)))
                .put("direkt", new JSONObject().put("geraete", new JSONArray()))
                .put("kette", new JSONObject().put("titel", "").put("wege", new JSONArray()))
                .put("freigaben", new JSONArray());
    }

    /** Für GET /api/wallbox/einstellungen: ohne Geheimnisse, nur „gesetzt“. */
    public static JSONObject oeffentlich(JSONObject k) throws JSONException {
        JSONObject o = new JSONObject(k.toString());
        JSONObject e = o.optJSONObject("evcc");
        if (e != null) {
            e.put("apiKeyGesetzt", !e.optString("apiKey", "").isEmpty());
            e.remove("apiKey");
        }
        JSONObject h = o.optJSONObject("ha");
        if (h != null) {
            h.put("tokenGesetzt", !h.optString("token", "").isEmpty());
            h.remove("token");
            h.put("angemeldet", !h.optString("refresh", "").isEmpty());
            h.remove("refresh");
        }
        JSONObject dk = o.optJSONObject("direkt");
        JSONArray dg = dk == null ? null : dk.optJSONArray("geraete");
        for (int i = 0; dg != null && i < dg.length(); i++) {
            JSONObject d = dg.getJSONObject(i);
            d.put("passwortGesetzt", !d.optString("passwort", "").isEmpty());
            d.remove("passwort");
        }
        JSONObject t = o.optJSONObject("tuya");
        if (t != null) {
            JSONArray g = t.optJSONArray("geraete");
            for (int i = 0; g != null && i < g.length(); i++) {
                JSONObject d = g.getJSONObject(i);
                d.put("localKeyGesetzt", !d.optString("localKey", "").isEmpty());
                d.remove("localKey");
            }
            JSONObject c = t.optJSONObject("cloud");
            if (c != null) {
                c.put("secretGesetzt", !c.optString("secret", "").isEmpty());
                c.remove("secret");
            }
        }
        return o;
    }

    public static String quelle(JSONObject k) throws JSONException {
        String q = k.isNull("quelle") ? "" : k.optString("quelle", "");
        return "evcc".equals(q) || "ha".equals(q) || "tuya".equals(q) || "direkt".equals(q) || "kette".equals(q) ? q : null;
    }

    /** 0.1.20 Rückfallkette: höchstens so viele Wege je Wallbox. */
    static final int MAX_WEGE = 4;
    /** Ladepunkt-Kennung der Kette (genau eine Wallbox, mehrere Wege). */
    public static final String KETTE_ID = "kette:haupt";

    /**
     * Rückfallkette (WALLBOX_FERN_KONZEPT §7): {@code {titel, wege:[{ladepunkt:"direkt:x"|"ha:y"|"tuya:z"}]}} – dieselbe
     * Wallbox über mehrere eingerichtete Wege, Reihenfolge = Vorrang (lokal → HA → Cloud). Die Wege selbst stehen in
     * ihren Abschnitten (direkt/ha/tuya); hier nur Verweise.
     */
    private static void kette(JSONObject alt, JSONObject neu, JSONObject k, boolean aktiv) throws WallboxFehler, JSONException {
        JSONObject altK = alt.optJSONObject("kette") == null ? new JSONObject() : alt.optJSONObject("kette");
        JSONObject neuK = neu.optJSONObject("kette") == null ? altK : neu.optJSONObject("kette");
        JSONArray w = neuK.optJSONArray("wege") == null ? new JSONArray() : neuK.optJSONArray("wege");
        if (w.length() > MAX_WEGE || (aktiv && w.length() < 2)) throw new WallboxFehler("kette_ungueltig");
        JSONArray sauber = new JSONArray();
        java.util.Set<String> gesehen = new java.util.HashSet<>();
        for (int i = 0; i < w.length(); i++) {
            JSONObject e = w.optJSONObject(i);
            String lp = e == null ? "" : e.optString("ladepunkt", "");
            int p = lp.indexOf(':');
            String q = p < 0 ? "" : lp.substring(0, p);
            if (!("direkt".equals(q) || "ha".equals(q) || "tuya".equals(q))
                    || !HaQuelle.SCHLUESSEL.matcher(lp.substring(p + 1)).matches() || !gesehen.add(lp)) {
                throw new WallboxFehler("kette_ungueltig");
            }
            sauber.put(new JSONObject().put("ladepunkt", lp));
        }
        String titel = neuK.optString("titel", "").trim();
        k.getJSONObject("kette").put("titel", titel.length() > 40 ? titel.substring(0, 40) : titel).put("wege", sauber);
    }

    /**
     * Übernimmt eine Änderung (POST /api/wallbox/einstellungen bzw. /test). Regeln (Vertrag §2/§6):
     * <ul>
     *   <li>Geheimnis-Feld fehlt → behalten, aber nur bei gleichem Ziel (Schema+Host+Port); {@code ""} → löschen.</li>
     *   <li>Quelle oder Ziel geändert → alle Freigaben weg.</li>
     *   <li>Freigaben werden hier nie gesetzt (nur über /freigabe mit Bestätigung).</li>
     * </ul>
     */
    public static JSONObject uebernimm(JSONObject alt, JSONObject neu, Geheim geheim) throws WallboxFehler, JSONException {
        return uebernimm(alt, neu, geheim, System.currentTimeMillis());
    }

    public static JSONObject uebernimm(JSONObject alt, JSONObject neu, Geheim geheim, long jetzt) throws WallboxFehler, JSONException {
        if (neu == null) throw new WallboxFehler("einstellung_ungueltig");
        JSONObject k = leer();
        String q = neu.has("quelle") ? (neu.isNull("quelle") ? "" : neu.optString("quelle", "")) : String.valueOf(quelle(alt));
        boolean bekannt = "evcc".equals(q) || "ha".equals(q) || "tuya".equals(q) || "direkt".equals(q) || "kette".equals(q);
        if (!q.isEmpty() && !"null".equals(q) && !bekannt) throw new WallboxFehler("einstellung_ungueltig");
        k.put("quelle", bekannt ? q : JSONObject.NULL);

        JSONObject altE = alt.optJSONObject("evcc") == null ? new JSONObject() : alt.optJSONObject("evcc");
        JSONObject neuE = neu.optJSONObject("evcc") == null ? altE : neu.optJSONObject("evcc");
        JSONObject e = k.getJSONObject("evcc");
        e.put("url", url(neuE));
        e.put("oeffentlichErlaubt", neuE.optBoolean("oeffentlichErlaubt", false));
        e.put("apiKey", geheimnis(altE, neuE, "apiKey", e.getString("url"), geheim));

        JSONObject altH = alt.optJSONObject("ha") == null ? new JSONObject() : alt.optJSONObject("ha");
        JSONObject neuH = neu.optJSONObject("ha") == null ? altH : neu.optJSONObject("ha");
        JSONObject h = k.getJSONObject("ha");
        h.put("url", url(neuH));
        h.put("oeffentlichErlaubt", neuH.optBoolean("oeffentlichErlaubt", false));
        h.put("token", geheimnis(altH, neuH, "token", h.getString("url"), geheim));
        // 0.1.19 OAuth: Refresh-Token wie ein Geheimnis (bleibt nur bei gleichem Ziel), client_id dazu
        String refresh = geheimnis(altH, neuH, "refresh", h.getString("url"), geheim);
        if (!refresh.isEmpty()) {
            String cid = neuH.has("clientId") ? neuH.optString("clientId", "") : altH.optString("clientId", "");
            if (cid.length() > 210) throw new WallboxFehler("einstellung_ungueltig");
            h.put("refresh", refresh).put("clientId", cid);
        }
        JSONArray lps = neuH.optJSONArray("ladepunkte") == null ? new JSONArray() : neuH.optJSONArray("ladepunkte");
        if (lps.length() > MAX_LADEPUNKTE) throw new WallboxFehler("einstellung_ungueltig");
        JSONArray sauber = new JSONArray();
        java.util.Set<String> schluessel = new java.util.HashSet<>();
        for (int i = 0; i < lps.length(); i++) {
            JSONObject z = HaQuelle.pruefeZuordnung(lps.optJSONObject(i));
            if (!schluessel.add(z.getString("schluessel"))) throw new WallboxFehler("einstellung_ungueltig");
            sauber.put(z);
        }
        h.put("ladepunkte", sauber);

        java.util.Map<String, String> tuyaIds = tuya(alt, neu, k.getJSONObject("tuya"), geheim, jetzt);
        java.util.Set<String> direktBleiben = direkt(alt, neu, k.getJSONObject("direkt"), geheim, jetzt);
        kette(alt, neu, k, "kette".equals(q));

        // Freigaben behalten nur, wenn Quelle und Ziel gleich bleiben; HA: nur für weiter vorhandene Schlüssel.
        JSONArray frei = new JSONArray();
        String altQ = quelle(alt);
        String neuQ = quelle(k);
        boolean gleichesZiel = "kette".equals(neuQ) ? zielUrl(alt).equals(zielUrl(k))
                : WallboxAdresse.ziel(zielUrl(alt)).equals(WallboxAdresse.ziel(zielUrl(k)));
        if (neuQ != null && neuQ.equals(altQ) && gleichesZiel) {
            JSONArray af = alt.optJSONArray("freigaben");
            for (int i = 0; af != null && i < af.length(); i++) {
                String id = af.optString(i);
                if ("ha".equals(neuQ) && !schluessel.contains(id.startsWith("ha:") ? id.substring(3) : "")) continue;
                if ("tuya".equals(neuQ) && !tuyaIds.containsKey(id)) continue;
                if ("direkt".equals(neuQ) && !direktBleiben.contains(id)) continue;
                // Kette: Wege unverändert (zielUrl gleich) – die Wege selbst dürfen sich nicht geändert haben
                if ("kette".equals(neuQ) && !ketteUnveraendert(alt, k, tuyaIds, direktBleiben, schluessel)) continue;
                frei.put(id);
            }
        }
        k.put("freigaben", frei);
        return k;
    }

    /** Jeder Weg der Kette ist noch derselbe (Gerät, Adresse, Zugang) – sonst Freigabe weg. */
    private static boolean ketteUnveraendert(JSONObject alt, JSONObject k, java.util.Map<String, String> tuyaIds,
                                             java.util.Set<String> direktBleiben, java.util.Set<String> haSchluessel) throws JSONException {
        JSONArray w = k.getJSONObject("kette").getJSONArray("wege");
        for (int i = 0; i < w.length(); i++) {
            String lp = w.getJSONObject(i).getString("ladepunkt");
            if (lp.startsWith("direkt:") && !direktBleiben.contains(lp)) return false;
            if (lp.startsWith("tuya:") && !tuyaIds.containsKey(lp)) return false;
            if (lp.startsWith("ha:")) {
                if (!haSchluessel.contains(lp.substring(3))) return false;
                JSONObject ah = alt.optJSONObject("ha") == null ? new JSONObject() : alt.optJSONObject("ha");
                if (!WallboxAdresse.ziel(ah.optString("url", "")).equals(WallboxAdresse.ziel(k.getJSONObject("ha").optString("url", "")))) return false;
            }
        }
        return true;
    }

    static String zielUrl(JSONObject k) throws JSONException {
        String q = quelle(k);
        if (q == null) return "";
        if ("direkt".equals(q)) return "direkt://";
        if ("kette".equals(q)) {
            StringBuilder sb = new StringBuilder("kette://");
            JSONArray w = k.optJSONObject("kette") == null ? null : k.getJSONObject("kette").optJSONArray("wege");
            for (int i = 0; w != null && i < w.length(); i++) sb.append(i == 0 ? "" : ",").append(w.getJSONObject(i).optString("ladepunkt"));
            return sb.toString();
        }
        if ("tuya".equals(q)) {
            // Tuya: „Ziel“ = Zugangsweg (+ Region und Client-ID bei Cloud); Geräte werden einzeln über die ID verglichen.
            JSONObject t = k.optJSONObject("tuya") == null ? new JSONObject() : k.optJSONObject("tuya");
            JSONObject c = t.optJSONObject("cloud") == null ? new JSONObject() : t.optJSONObject("cloud");
            boolean cloud = "cloud".equals(t.optString("zugang"));
            return "tuya://" + (cloud ? "cloud." + c.optString("region") + "." + c.optString("clientId").toLowerCase(java.util.Locale.ROOT) : "lan");
        }
        JSONObject s = k.optJSONObject(q);
        return s == null ? "" : s.optString("url", "");
    }

    private static String url(JSONObject o) throws WallboxFehler, JSONException {
        String u = o.optString("url", "").trim();
        if (u.length() > 300) throw new WallboxFehler("adresse_ungueltig");
        return u;
    }

    private static String geheimnis(JSONObject alt, JSONObject neu, String feld, String neueUrl, Geheim geheim) throws WallboxFehler, JSONException {
        // Abschnitt nicht mitgeschickt (neu == alt): gespeicherter Wert ist schon verschlüsselt – nie noch einmal verschlüsseln
        if (neu == alt) return alt.optString(feld, "");
        if (neu.has(feld) && !neu.isNull(feld)) {
            String v = neu.optString(feld, "").trim();
            if (v.isEmpty()) return "";
            if (v.length() > 1024 || v.matches(".*[\\s\\x00-\\x1f].*")) throw new WallboxFehler("einstellung_ungueltig");
            return geheim.ver(v);
        }
        String gespeichert = alt.optString(feld, "");
        if (gespeichert.isEmpty()) return "";
        // Ziel geändert, ohne das Geheimnis neu einzugeben → verwerfen (sonst ginge es an einen fremden Host).
        return WallboxAdresse.ziel(alt.optString("url", "")).equals(WallboxAdresse.ziel(neueUrl)) ? gespeichert : "";
    }

    /**
     * Tuya-Abschnitt (Vertrag §4b). Geräte-Schlüssel (local_key) bleiben nur bei gleicher Geräte-ID, das Cloud-Geheimnis
     * nur bei gleicher Region + Client-ID. Die Cloud-Einwilligung (Regel 2) wird mit Zeitpunkt gespeichert, Standard aus.
     * @return Freigabe-Kennungen der Geräte, deren Freigabe bestehen bleiben darf (gleicher Schlüssel und gleiche Geräte-ID)
     */
    private static java.util.Map<String, String> tuya(JSONObject alt, JSONObject neu, JSONObject t, Geheim geheim, long jetzt)
            throws WallboxFehler, JSONException {
        JSONObject altT = alt.optJSONObject("tuya") == null ? new JSONObject() : alt.optJSONObject("tuya");
        JSONObject neuT = neu.optJSONObject("tuya") == null ? altT : neu.optJSONObject("tuya");
        String zugang = neuT.optString("zugang", "lan");
        if (!"lan".equals(zugang) && !"cloud".equals(zugang)) throw new WallboxFehler("einstellung_ungueltig");
        t.put("zugang", zugang);

        JSONObject altC = altT.optJSONObject("cloud") == null ? new JSONObject() : altT.optJSONObject("cloud");
        JSONObject neuC = neuT.optJSONObject("cloud") == null ? altC : neuT.optJSONObject("cloud");
        JSONObject c = t.getJSONObject("cloud");
        String region = neuC.optString("region", "eu");
        if (!TuyaCloud.REGIONEN.containsKey(region)) throw new WallboxFehler("einstellung_ungueltig");
        String clientId = neuC.optString("clientId", "").trim();
        if (!clientId.isEmpty() && !clientId.matches("[A-Za-z0-9]{8,64}")) throw new WallboxFehler("einstellung_ungueltig");
        c.put("region", region).put("clientId", clientId);
        boolean gleicherZugang = region.equals(altC.optString("region", "eu")) && clientId.equals(altC.optString("clientId", ""));
        c.put("secret", geheimFeld(altC, neuC, "secret", gleicherZugang, 128, geheim));
        boolean einw = neuC.optBoolean("einwilligung", false);
        c.put("einwilligung", einw);
        Object altZeit = altC.isNull("einwilligungZeit") ? null : altC.opt("einwilligungZeit");
        c.put("einwilligungZeit", !einw ? JSONObject.NULL
                : altC.optBoolean("einwilligung", false) && altZeit != null ? altZeit : (Object) jetzt);

        JSONArray altG = altT.optJSONArray("geraete") == null ? new JSONArray() : altT.optJSONArray("geraete");
        JSONArray neuG = neuT.optJSONArray("geraete") == null ? new JSONArray() : neuT.optJSONArray("geraete");
        if (neuG.length() > MAX_LADEPUNKTE) throw new WallboxFehler("einstellung_ungueltig");
        JSONArray sauber = new JSONArray();
        java.util.Map<String, String> bleiben = new java.util.HashMap<>();
        java.util.Set<String> schl = new java.util.HashSet<>();
        for (int i = 0; i < neuG.length(); i++) {
            JSONObject n = neuG.optJSONObject(i);
            if (n == null) throw new WallboxFehler("einstellung_ungueltig");
            JSONObject d = new JSONObject();
            String s = n.optString("schluessel", "");
            if (!HaQuelle.SCHLUESSEL.matcher(s).matches() || !schl.add(s)) throw new WallboxFehler("einstellung_ungueltig");
            String titel = n.optString("titel", "").trim();
            String gid = n.optString("geraeteId", "").trim();
            if (!gid.matches("[A-Za-z0-9]{6,64}")) throw new WallboxFehler("einstellung_ungueltig");
            String ip = n.optString("ip", "").trim();
            if (ip.length() > 100 || ip.matches(".*[\\s/@?#].*")) throw new WallboxFehler("adresse_ungueltig");
            String version = n.optString("version", "3.3");
            if (!version.matches("3\\.[345]")) throw new WallboxFehler("einstellung_ungueltig");
            String modell = n.isNull("modell") ? "" : n.optString("modell", "").trim();
            if (!modell.isEmpty() && !modell.matches("[a-z0-9_-]{1,64}")) throw new WallboxFehler("einstellung_ungueltig");
            JSONObject zu = n.isNull("zuordnung") ? null : TuyaQuelle.pruefeZuordnung(n.optJSONObject("zuordnung"));
            d.put("schluessel", s).put("titel", titel.length() > 40 ? titel.substring(0, 40) : titel)
                    .put("geraeteId", gid).put("ip", ip).put("version", version)
                    .put("modell", modell.isEmpty() ? JSONObject.NULL : modell)
                    .put("zuordnung", zu == null ? JSONObject.NULL : zu);
            JSONObject a = null;
            for (int j = 0; j < altG.length(); j++) {
                JSONObject x = altG.optJSONObject(j);
                if (x != null && s.equals(x.optString("schluessel")) && gid.equals(x.optString("geraeteId"))) a = x;
            }
            if (n != a && n.has("localKey") && !n.isNull("localKey")) {
                String lk = n.optString("localKey", "").trim();
                if (!lk.isEmpty() && lk.length() != 16) throw new WallboxFehler("tuya_schluessel_format");
            }
            d.put("localKey", geheimFeld(a == null ? new JSONObject() : a, n, "localKey", a != null, 16, geheim));
            if (a != null) bleiben.put("tuya:" + s, gid);
            sauber.put(d);
        }
        t.put("geraete", sauber);
        return bleiben;
    }

    /**
     * Direkte Adapter (Stufe 2): Geräte mit {@code adapter} aus {@link DirektAdapter#KENNUNGEN}, Adresse (http/https, privat –
     * geprüft im Dienst), Shelly-Kanal und optional Benutzer/Passwort (Passwort verschlüsselt; bleibt nur bei gleichem
     * Schlüssel und gleicher Adresse). Freigaben bleiben nur bei gleichem Schlüssel, Adapter und gleicher Adresse.
     */
    private static java.util.Set<String> direkt(JSONObject alt, JSONObject neu, JSONObject d, Geheim geheim, long jetzt)
            throws WallboxFehler, JSONException {
        JSONObject altD = alt.optJSONObject("direkt") == null ? new JSONObject() : alt.optJSONObject("direkt");
        JSONObject neuD = neu.optJSONObject("direkt") == null ? altD : neu.optJSONObject("direkt");
        JSONArray altG = altD.optJSONArray("geraete") == null ? new JSONArray() : altD.optJSONArray("geraete");
        JSONArray neuG = neuD.optJSONArray("geraete") == null ? new JSONArray() : neuD.optJSONArray("geraete");
        if (neuG.length() > MAX_LADEPUNKTE) throw new WallboxFehler("einstellung_ungueltig");
        JSONArray sauber = new JSONArray();
        java.util.Set<String> schl = new java.util.HashSet<>();
        java.util.Set<String> bleiben = new java.util.HashSet<>();
        for (int i = 0; i < neuG.length(); i++) {
            JSONObject n = neuG.optJSONObject(i);
            if (n == null) throw new WallboxFehler("einstellung_ungueltig");
            String s = n.optString("schluessel", "");
            if (!HaQuelle.SCHLUESSEL.matcher(s).matches() || !schl.add(s)) throw new WallboxFehler("einstellung_ungueltig");
            String adapter = n.optString("adapter", "");
            if (!java.util.Arrays.asList(DirektAdapter.KENNUNGEN).contains(adapter)) throw new WallboxFehler("einstellung_ungueltig");
            boolean cloud = DirektAdapter.cloud(adapter);
            String adresse = cloud ? "" : n.optString("adresse", "").trim();
            if (!cloud && (adresse.isEmpty() || adresse.length() > 300)) throw new WallboxFehler("adresse_ungueltig");
            boolean bruecke = "bruecke".equals(adapter);
            String geraeteId = cloud || bruecke ? n.optString("geraeteId", "").trim() : "";
            if (cloud && !geraeteId.matches("[A-Za-z0-9-]{3,64}")) throw new WallboxFehler("einstellung_ungueltig");
            // 0.1.19 Wallbox-Brücke: Geräte-ID der Brücke (Tuya-gwId, Vertrag WALLBOX_BRUECKE_MQTT §2)
            if (bruecke && !geraeteId.matches(BrueckeAdapter.ID_MUSTER)) throw new WallboxFehler("einstellung_ungueltig");
            boolean einw = cloud && n.optBoolean("einwilligung", false);
            int kanal = n.optInt("kanal", 0);
            if (kanal < 0 || kanal > 3) throw new WallboxFehler("einstellung_ungueltig");
            String modell = n.isNull("modell") ? "" : n.optString("modell", "").trim();
            if (!modell.isEmpty() && !modell.matches("[a-z0-9_-]{1,64}")) throw new WallboxFehler("einstellung_ungueltig");
            if ("modbus".equals(adapter) && modell.isEmpty()) throw new WallboxFehler("modbus_modell_unbekannt");
            int port = n.isNull("port") ? 0 : n.optInt("port", 0);
            int nummer = n.isNull("nummer") ? 1 : n.optInt("nummer", 1);
            if (nummer < 1 || nummer > 8) throw new WallboxFehler("einstellung_ungueltig");
            int einheit = n.isNull("einheit") ? -1 : n.optInt("einheit", -1);
            if (port < 0 || port > 65535 || einheit < -1 || einheit > 255) throw new WallboxFehler("einstellung_ungueltig");
            String benutzer = n.optString("benutzer", "").trim();
            if (benutzer.length() > 64 || benutzer.matches(".*[\\s:].*")) throw new WallboxFehler("einstellung_ungueltig");
            String titel = n.optString("titel", "").trim();
            JSONObject a = null;
            for (int j = 0; j < altG.length(); j++) {
                JSONObject x = altG.optJSONObject(j);
                if (x != null && s.equals(x.optString("schluessel"))
                        && adresseKennung(adresse).equals(adresseKennung(x.optString("adresse")))
                        && geraeteId.equals(x.optString("geraeteId", "")) && (!cloud || benutzer.equals(x.optString("benutzer", "")))) a = x;
            }
            JSONObject e = new JSONObject().put("schluessel", s).put("titel", titel.length() > 40 ? titel.substring(0, 40) : titel)
                    .put("adapter", adapter).put("adresse", adresse).put("kanal", kanal).put("benutzer", benutzer)
                    .put("modell", modell.isEmpty() ? JSONObject.NULL : modell)
                    .put("port", port == 0 ? JSONObject.NULL : port).put("einheit", einheit < 0 ? JSONObject.NULL : einheit)
                    .put("nummer", nummer);
            if (cloud) {
                e.put("geraeteId", geraeteId).put("einwilligung", einw);
                Object altZeit = a == null || a.isNull("einwilligungZeit") ? null : a.opt("einwilligungZeit");
                e.put("einwilligungZeit", !einw ? JSONObject.NULL
                        : a != null && a.optBoolean("einwilligung", false) && altZeit != null ? altZeit : (Object) jetzt);
            }
            if (bruecke) e.put("geraeteId", geraeteId);
            e.put("passwort", geheimFeld(a == null ? new JSONObject() : a, n, "passwort", a != null, 128, geheim));
            if (a != null && adapter.equals(a.optString("adapter")) && modell.equals(a.isNull("modell") ? "" : a.optString("modell"))
                    && String.valueOf(e.opt("port")).equals(String.valueOf(a.opt("port")))
                    && String.valueOf(e.opt("einheit")).equals(String.valueOf(a.opt("einheit")))
                    && e.optInt("nummer", 1) == a.optInt("nummer", 1)) {
                bleiben.add("direkt:" + s);
            }
            sauber.put(e);
        }
        d.put("geraete", sauber);
        return bleiben;
    }

    /** Vergleichskennung einer Adresse: URL → Schema+Host+Port, sonst der Host/IP-Text selbst. */
    static String adresseKennung(String a) {
        String z = WallboxAdresse.ziel(a);
        return z.startsWith("://") || z.contains("://:") ? (a == null ? "" : a.trim().toLowerCase(java.util.Locale.ROOT)) : z;
    }

    /** Geheimnis-Feld: Text = neu (verschlüsselt), "" = löschen, fehlt = behalten nur wenn {@code behalten}. */
    private static String geheimFeld(JSONObject alt, JSONObject neu, String feld, boolean behalten, int maxLaenge, Geheim geheim)
            throws WallboxFehler, JSONException {
        if (neu == alt) return behalten ? alt.optString(feld, "") : "";   // unverändert übernommen (schon verschlüsselt)
        if (neu.has(feld) && !neu.isNull(feld)) {
            String v = neu.optString(feld, "").trim();
            if (v.isEmpty()) return "";
            if (v.length() > maxLaenge || v.matches(".*[\\s\\x00-\\x1f].*")) throw new WallboxFehler("einstellung_ungueltig");
            return geheim.ver(v);
        }
        return behalten ? alt.optString(feld, "") : "";
    }

    public static boolean freigegeben(JSONObject k, String id) throws JSONException {
        JSONArray f = k.optJSONArray("freigaben");
        for (int i = 0; f != null && i < f.length(); i++) if (id.equals(f.optString(i))) return true;
        return false;
    }

    public static void setzeFreigabe(JSONObject k, String id, boolean an) throws JSONException {
        JSONArray alt = k.optJSONArray("freigaben") == null ? new JSONArray() : k.optJSONArray("freigaben");
        JSONArray neu = new JSONArray();
        for (int i = 0; i < alt.length(); i++) if (!id.equals(alt.optString(i))) neu.put(alt.optString(i));
        if (an) neu.put(id);
        k.put("freigaben", neu);
    }
}
