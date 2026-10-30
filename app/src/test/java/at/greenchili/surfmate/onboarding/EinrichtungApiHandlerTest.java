package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;
import at.greenchili.surfmate.server.EinrichtungApiHandler.Antwort;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Einrichtung Teil B (0.1.10): §0-Format, Neuinstallation, Bestand, Abbruch/Fortsetzen, „neu“, 422.
 * Diese Antworten sind zugleich die Schein-Daten für die Oberfläche (Neubau 2).
 */
public class EinrichtungApiHandlerTest {

    private static final List<Feld> FELDER = Katalog.felder(new Katalog.Kontext());

    /** Simuliert den Daemon: Antwort anwenden wie updateValues je Abschnitt. */
    private static JSONObject anwenden(JSONObject root, Antwort a) throws Exception {
        assertEquals(a.body.toString(), 200, a.code);
        JSONObject r = new JSONObject(root.toString());
        for (Map.Entry<String, Map<String, Object>> s : a.schreiben.entrySet()) {
            JSONObject sec = r.optJSONObject(s.getKey());
            if (sec == null) { sec = new JSONObject(); r.put(s.getKey(), sec); }
            for (Map.Entry<String, Object> kv : s.getValue().entrySet()) sec.put(kv.getKey(), kv.getValue());
        }
        return r;
    }

    private static JSONObject sicht(JSONObject root) throws Exception {
        return EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
    }

    private static String status(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) {
            if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i).getString("status");
        }
        throw new AssertionError(id);
    }

    private static JSONObject schritt(JSONObject root, String id, JSONObject antwort) throws Exception {
        return anwenden(root, EinrichtungApiHandler.schritt(root, id, antwort, FELDER, false, 1_000L));
    }

    @Test
    public void formatLautVertrag() throws Exception {
        JSONObject s = sicht(new JSONObject());
        for (String k : new String[] {"version", "abgeschlossen", "aktuell", "schritte", "offen_spaeter",
                "faehigkeiten", "selbsttest"}) {
            assertTrue(k, s.has(k));
        }
        assertEquals(1, s.getInt("version"));
        assertEquals(EinrichtungApiHandler.SCHRITTE.size(), s.getJSONArray("schritte").length());
        JSONObject erster = s.getJSONArray("schritte").getJSONObject(0);
        assertEquals("sprache", erster.getString("id"));
        assertTrue(erster.getBoolean("pflicht"));
    }

    @Test
    public void neuinstallationAlleSchritte() throws Exception {
        JSONObject s = sicht(new JSONObject());
        assertEquals("neu", s.getString("art"));
        assertFalse(s.getBoolean("abgeschlossen"));
        assertEquals("sprache", s.getString("aktuell"));
        assertEquals("offen", status(s, "waechter"));
        assertEquals("offen", status(s, "autostart"));
        assertEquals("erledigt", status(s, "usb_freigabe"));   // der Dienst antwortet = Freigabe da
        // 0.1.32: „updates“ ist Pflichtfrage im Schnellstart; ohne Antwort ruft nichts ab
        assertEquals("offen", status(s, "updates"));
        assertTrue(s.getJSONArray("offen_spaeter").toString().contains("extra_telegram"));
    }

    private static JSONObject bestand(boolean mitWaechter) throws Exception {
        JSONObject root = new JSONObject()
                .put("nativeShell", new JSONObject().put("locale", "de").put("localeGewaehlt", true))
                .put("hinweise", new JSONObject().put("version", Katalog.HINWEISE_VERSION))
                .put("vehicle", new JSONObject().put("modelId", "atto3").put("modelSource", "user"))
                .put("karte", new JSONObject().put("quelle", "community"))
                .put("update", new JSONObject().put("pruefen", true).put("mobilfunk", true))
                .put("surveillance", new JSONObject().put("operatingMode", "onAndOff"));
        if (mitWaechter) root.getJSONObject("surveillance").put("triggerMode", "ai_everywhere");
        return root;
    }

    @Test
    public void bestandMitFehlenderWaechterWahl() throws Exception {
        JSONObject s = sicht(bestand(false));
        assertEquals("bestand", s.getString("art"));
        assertFalse(s.getBoolean("abgeschlossen"));
        // 0.1.10.1: Bestand bekommt den Assistenten nur für fehlende Pflichtantworten
        assertEquals("waechter", s.getString("aktuell"));
        assertEquals("offen", status(s, "waechter"));
        assertEquals("erledigt", status(s, "sprache"));
        assertEquals("erledigt", status(s, "karte"));
        assertEquals("entfaellt", status(s, "autostart"));   // gab es in 0.1.9 schon
        assertEquals("spaeter", status(s, "daten"));         // neu hinzugekommen → „Noch offen“, kein Assistent
    }

    /** Alfreds Auto 22.10.: Zustand mit alter Regel gespeichert, Sprache migriert (ohne localeGewaehlt). */
    @Test
    public void alfredsGespeicherterBestandWirdNachgeprueft() throws Exception {
        JSONObject root = bestand(true);
        root.getJSONObject("nativeShell").remove("localeGewaehlt");
        root.getJSONObject("nativeShell").put("localeMigriert", true);
        JSONObject alt = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) alt.put(id, "offen");
        alt.put("usb_freigabe", "erledigt").put("autostart", "entfaellt").put("ueberlagerung", "entfaellt")
                .put("kameras", "entfaellt").put("extra_fernzugang", "entfaellt");
        root.put("einrichtung", new JSONObject().put("version", 1).put("art", "bestand").put("neuZaehler", 0)
                .put("schritte", alt).put("gruende", new JSONObject()).put("werte", new JSONObject()));
        JSONObject s = sicht(root);
        assertEquals("erledigt", status(s, "sprache"));
        assertEquals("erledigt", status(s, "waechter"));
        assertEquals("spaeter", status(s, "rechte"));
        assertTrue(s.getBoolean("abgeschlossen"));
        assertEquals("kein Assistent", "fertig", s.getString("aktuell"));
        // Projektinhaber: update.* fehlen ganz (Standards) bzw. nur mobilfunk gesetzt → updates beantwortet
        JSONObject ohneUpdate = new JSONObject(root.toString());
        ohneUpdate.remove("update");
        assertEquals("erledigt", status(sicht(ohneUpdate), "updates"));
        ohneUpdate.put("update", new JSONObject().put("mobilfunk", true));
        assertEquals("erledigt", status(sicht(ohneUpdate), "updates"));
        assertFalse(OnboardingConfigPolicy.assistentStarten(s.getString("aktuell")));
        // ältere Haftungsversion bleibt offen (rechtlich: aktueller Text nicht bestätigt)
        root.getJSONObject("hinweise").put("version", "2026-01-01");
        JSONObject s2 = sicht(root);
        assertEquals("offen", status(s2, "haftung"));
        assertEquals("haftung", s2.getString("aktuell"));
        // nach „Einrichtung erneut starten“ (art=neu) keine Bestandsautomatik
        JSONObject r3 = anwenden(root, EinrichtungApiHandler.neu(root));
        assertEquals("offen", status(sicht(r3), "rechte"));
    }

    @Test
    public void bestandVollstaendig() throws Exception {
        JSONObject s = sicht(bestand(true));
        assertTrue(s.getBoolean("abgeschlossen"));
        for (String id : EinrichtungApiHandler.PFLICHT) assertEquals(id, "erledigt", status(s, id));
        assertFalse(s.getJSONArray("offen_spaeter").toString().contains("updates"));
    }

    @Test
    public void abbruchUndFortsetzen() throws Exception {
        JSONObject root = new JSONObject();
        root = schritt(root, "sprache", new JSONObject().put("locale", "de"));
        root = schritt(root, "haftung", new JSONObject().put("gelesen", true).put("version", Katalog.HINWEISE_VERSION));
        // Auto aus: Zustand liegt in der Config; ein neuer Start liest denselben Stand
        JSONObject nachNeustart = new JSONObject(root.toString());
        JSONObject s = sicht(nachNeustart);
        assertEquals("erledigt", status(s, "haftung"));
        assertEquals("autostart", s.getString("aktuell"));
        assertEquals(Katalog.HINWEISE_VERSION, root.getJSONObject("hinweise").getString("version"));
        assertTrue(root.getJSONObject("hinweise").has("bestaetigtAm"));   // Haftungszeitpunkt (Teil C)
        assertEquals(1_000L, s.getJSONObject("werte").getJSONObject("haftung").getLong("zeit"));
    }

    @Test
    public void pflichtOhneAntwort422() throws Exception {
        Antwort a = EinrichtungApiHandler.schritt(new JSONObject(), "waechter",
                new JSONObject().put("ton", false), FELDER, false, 1L);
        assertEquals(422, a.code);
        assertEquals("fehlt", a.body.getString("error"));
        assertEquals("[\"modus\"]", a.body.getJSONArray("fehlt").toString());
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "karte", null, FELDER, false, 1L).code);
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "updates",
                new JSONObject().put("mobilfunk", true), FELDER, false, 1L).code);
        // B4: Pflicht nie „später“
        Antwort sp = EinrichtungApiHandler.spaeter(new JSONObject(), "waechter");
        assertEquals(422, sp.code);
        assertEquals("pflicht", sp.body.getString("error"));
        assertEquals(200, EinrichtungApiHandler.spaeter(new JSONObject(), "extra_telegram").code);
        assertEquals(404, EinrichtungApiHandler.spaeter(new JSONObject(), "quatsch").code);
    }

    @Test
    public void antwortenSchreibenDieVertragsSchluessel() throws Exception {
        JSONObject root = new JSONObject();
        root = schritt(root, "waechter", new JSONObject().put("modus", "aus").put("ton", true));
        assertEquals("aus", root.getJSONObject("surveillance").getString("triggerMode"));
        assertFalse(root.getJSONObject("recording").getBoolean("audioEnabled"));   // Wächter aus: kein Ton
        root = schritt(root, "betrieb", new JSONObject().put("modus", "fahrt"));
        assertEquals("onOnly", root.getJSONObject("surveillance").getString("operatingMode"));
        root = schritt(root, "karte", new JSONObject().put("quelle", "osm"));
        assertEquals("osm", root.getJSONObject("karte").getString("quelle"));
        root = schritt(root, "updates", new JSONObject().put("pruefen", true).put("mobilfunk", JSONObject.NULL));
        assertTrue(root.getJSONObject("update").getBoolean("pruefen"));
        assertFalse("null = neue Einrichtung: nur WLAN", root.getJSONObject("update").getBoolean("mobilfunk"));
        Antwort sp = EinrichtungApiHandler.schritt(new JSONObject(), "sprache",
                new JSONObject().put("locale", "nl"), FELDER, false, 1L);
        assertEquals("nl", sp.locale);
    }

    /** 0.1.28 P10: Ereignis-Clip im Wächter-Schritt, „Wächter stoppt …“ im Betriebs-Schritt (beides optional im Vertrag). */
    @Test
    public void ereignisClipUndWaechterStopp() throws Exception {
        JSONObject root = schritt(new JSONObject(), "waechter",
                new JSONObject().put("modus", "shock").put("ton", false).put("ereignisClip", false));
        assertFalse(root.getJSONObject("recording").getBoolean("ereignisClip"));
        root = schritt(root, "waechter", new JSONObject().put("modus", "shock").put("ton", false).put("ereignisClip", true));
        assertTrue(root.getJSONObject("recording").getBoolean("ereignisClip"));
        // ältere Oberfläche ohne die Frage: Standard nach Land bleibt (nichts geschrieben)
        JSONObject alt = schritt(new JSONObject(), "waechter", new JSONObject().put("modus", "shock").put("ton", false));
        assertFalse(alt.optJSONObject("recording") != null && alt.getJSONObject("recording").has("ereignisClip"));
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "waechter",
                new JSONObject().put("modus", "shock").put("ton", false).put("ereignisClip", "ja"), FELDER, false, 1L).code);

        root = schritt(root, "betrieb", new JSONObject().put("modus", "immer").put("stopp", "frueh"));
        assertEquals(30, root.getJSONObject("guardStop").getInt("socBelow"));
        assertEquals(12, root.getJSONObject("guardStop").getInt("maxParkHours"));
        root = schritt(root, "betrieb", new JSONObject().put("modus", "immer").put("stopp", "lang"));
        assertEquals(15, root.getJSONObject("guardStop").getInt("socBelow"));
        assertEquals(48, root.getJSONObject("guardStop").getInt("maxParkHours"));
        // „Nur beim Fahren“: Panoptes läuft im Stand nicht – Wahl wird nicht geschrieben
        JSONObject fahrt = schritt(new JSONObject(), "betrieb", new JSONObject().put("modus", "fahrt").put("stopp", "frueh"));
        assertFalse(fahrt.has("guardStop"));
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "betrieb",
                new JSONObject().put("modus", "immer").put("stopp", "nie"), FELDER, false, 1L).code);
        // „normal“ = Katalog-Standard
        int[] normal = EinrichtungApiHandler.STOPP.get("normal");
        assertEquals(20, normal[0]);
        assertEquals(24, normal[1]);
    }

    @Test
    public void neuSetztAlleMerkerZurueck() throws Exception {
        JSONObject root = schritt(bestand(true), "extra_telegram", new JSONObject());
        Antwort a = EinrichtungApiHandler.neu(root);
        JSONObject r = anwenden(root, a);
        JSONObject s = sicht(r);
        for (String id : EinrichtungApiHandler.SCHRITTE) {
            if ("usb_freigabe".equals(id) || "extra_overdrive".equals(id)) continue;
            if (EinrichtungApiHandler.SCHNELLSTART.contains(id)) assertEquals(id, "offen", status(s, id));
            else assertFalse(id, "offen".equals(status(s, id)));   // 0.1.32: Rest bleibt in „Noch einrichten“
        }
        assertEquals(1, s.getInt("neuZaehler"));
        assertEquals("community", r.getJSONObject("karte").getString("quelle"));   // Wert bleibt als Vorschlag

        // App-Seite: neuer Zähler ⇒ ALLE lokalen Merker weg, auch Fernzugang/Update-Frage/Setup-Dialog
        FakePrefs onb = new FakePrefs();
        FakePrefs setup = new FakePrefs();
        OnboardingState st = new OnboardingState(onb, setup);
        st.setRemoteAccessDone(true);
        st.setUpdateFrageDone(true);
        st.setOnboardingComplete(true);
        setup.edit().putLong("last_seen_install_time", 5L).apply();
        assertTrue(st.abgleichen(1));
        assertFalse(st.getRemoteAccessDone());
        assertFalse(st.getUpdateFrageDone());
        assertFalse(st.getOnboardingComplete());
        assertTrue(setup.getAll().isEmpty());
        assertFalse("gleicher Zähler: nichts", st.abgleichen(1));
    }

    @Test
    public void vorlaufHaftungNachgereichtUndPruefSchritte() throws Exception {
        long zeit = 1_790_000_000_000L;   // 2026-09, vor dem Eingang
        JSONObject root = anwenden(new JSONObject(), EinrichtungApiHandler.schritt(new JSONObject(), "haftung",
                new JSONObject().put("gelesen", true).put("version", Katalog.HINWEISE_VERSION).put("zeit", zeit),
                FELDER, false, 1_800_000_000_000L));
        assertEquals(zeit, sicht(root).getJSONObject("werte").getJSONObject("haftung").getLong("zeit"));
        assertTrue(root.getJSONObject("hinweise").getString("bestaetigtAm").startsWith("2026-09"));
        // Zukunft bzw. Unsinn → Eingangszeitpunkt
        Antwort zukunft = EinrichtungApiHandler.schritt(new JSONObject(), "haftung",
                new JSONObject().put("gelesen", true).put("version", Katalog.HINWEISE_VERSION).put("zeit", Long.MAX_VALUE),
                FELDER, false, 1_800_000_000_000L);
        assertEquals(1_800_000_000_000L, sicht(anwenden(new JSONObject(), zukunft))
                .getJSONObject("werte").getJSONObject("haftung").getLong("zeit"));
        // Prüf-Schritt nicht ok → bleibt offen; ok → erledigt
        JSONObject r = schritt(new JSONObject(), "autostart", new JSONObject().put("geprueft", true).put("ok", false));
        assertEquals("offen", status(sicht(r), "autostart"));
        r = schritt(r, "autostart", new JSONObject().put("geprueft", true).put("ok", true));
        assertEquals("erledigt", status(sicht(r), "autostart"));
    }

    @Test
    public void overdriveSchrittNurMitDaten() throws Exception {
        JSONObject s = sicht(new JSONObject());
        assertEquals("entfaellt", status(s, "extra_overdrive"));
        assertTrue(s.toString().contains("einr.grund.kein_overdrive"));
        java.util.function.BooleanSupplier vorher = EinrichtungApiHandler.overdriveErkennung;
        try {
            EinrichtungApiHandler.overdriveErkennung = () -> true;
            assertEquals("spaeter", status(sicht(new JSONObject()), "extra_overdrive"));
            EinrichtungApiHandler.overdriveErkennung = () -> { throw new IllegalStateException("x"); };
            assertEquals("entfaellt", status(sicht(new JSONObject()), "extra_overdrive"));
        } finally {
            EinrichtungApiHandler.overdriveErkennung = vorher;
        }
        assertFalse(EinrichtungApiHandler.PFLICHT.contains("extra_overdrive"));
    }

    @Test
    public void startNurWennOffen() {
        assertTrue(OnboardingConfigPolicy.assistentStarten("waechter"));
        assertTrue(OnboardingConfigPolicy.assistentStarten(null));
        assertFalse(OnboardingConfigPolicy.assistentStarten("fertig"));
        assertFalse(OnboardingConfigPolicy.darfVorbelegen("surveillance.operatingMode"));
        assertFalse(OnboardingConfigPolicy.darfVorbelegen("update.mobilfunk"));
        assertTrue(OnboardingConfigPolicy.darfVorbelegen("locale"));
    }

    // ---------------------------------------------------------------- Test-Prefs

    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(m); }
        @Override public String getString(String k, String d) { return m.containsKey(k) ? (String) m.get(k) : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return m.containsKey(k) ? (Integer) m.get(k) : d; }
        @Override public long getLong(String k, long d) { return m.containsKey(k) ? (Long) m.get(k) : d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return m.containsKey(k) ? (Boolean) m.get(k) : d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> neu = new HashMap<>();
                final java.util.List<String> weg = new java.util.ArrayList<>();
                boolean leeren;
                @Override public Editor putString(String k, String v) { neu.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { neu.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { neu.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { return this; }
                @Override public Editor putBoolean(String k, boolean v) { neu.put(k, v); return this; }
                @Override public Editor remove(String k) { weg.add(k); return this; }
                @Override public Editor clear() { leeren = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (leeren) m.clear();
                    for (String k : weg) m.remove(k);
                    m.putAll(neu);
                }
            };
        }
    }

    @Test
    public void fakePrefsSelbstpruefung() {
        FakePrefs p = new FakePrefs();
        p.edit().putInt("a", 1).apply();
        assertNotNull(p.getAll().get("a"));
    }
}
