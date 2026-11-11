package at.greenchili.surfmate.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.trips.TripRecord;
import at.greenchili.surfmate.util.MessageFormatSafe;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Abschaltbericht (03.11.2026): Inhalt ohne Ort, Schwellen gegen Rangierfahrten, Schalter, Fahrt-Auswahl,
 * Texte in allen 5 Sprachen. Testort Wien, synthetische Zahlen.
 */
public class AbschaltBerichtTest {

    private static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};
    private static final long MIN = 60_000L;

    /** Fahrt mit Start/Ziel in Wien – die Koordinaten dürfen nie im Bericht landen. */
    private static TripRecord fahrt(long id, long start, double km, int sek, double socStart, double socEnd,
                                    double kwhStart, double kwhEnd) {
        TripRecord t = new TripRecord();
        t.id = id;
        t.startTime = start;
        t.endTime = start + sek * 1000L;
        t.distanceKm = km;
        t.durationSeconds = sek;
        t.socStart = socStart;
        t.socEnd = socEnd;
        t.kwhStart = kwhStart;
        t.kwhEnd = kwhEnd;
        t.startLat = 48.20849; t.startLon = 16.37208;
        t.endLat = 48.18512; t.endLon = 16.31234;
        return t;
    }

    private static AbschaltBericht.Texte katalog(String sprache) throws Exception {
        JSONObject root = new JSONObject(lies("app/src/main/assets/server-i18n/" + sprache + ".json"));
        JSONObject tg = root.getJSONObject("telegram");
        Locale loc = Locale.forLanguageTag(sprache);
        return (key, args) -> {
            Object cur = tg;
            for (String p : key.split("\\.")) cur = ((JSONObject) cur).opt(p);
            return MessageFormatSafe.format((String) cur, loc, args);
        };
    }

    private static AbschaltBericht.Daten voll() {
        AbschaltBericht.Daten d = AbschaltBericht.zusammenfassen(Arrays.asList(
                fahrt(7, 1_000_000L, 8.0, 900, 80, 77, 40.0, 38.8),
                fahrt(8, 1_000_000L + 20 * MIN, 4.4, 480, 77, 74, 38.8, 38.1)));
        d.reichweiteKm = 245;
        d.volt12 = 12.62;
        d.scharf = true;
        d.verriegelt = Boolean.FALSE;
        return d;
    }

    @Test
    public void berichtEnthaeltFahrtAkkuReichweite12VWaechterSchloss() throws Exception {
        String text = AbschaltBericht.text(AbschaltBericht.zeilen(voll(), Locale.GERMAN), katalog("de"));
        assertTrue(text, text.startsWith("🅿️ *Abgestellt*"));
        // 12,4 km, 23 min, 1,9 kWh / 12,4 km = 15,3 kWh/100 km
        assertTrue(text, text.contains("Fahrt: 12,4 km · 23 min · 15,3 kWh/100 km"));
        assertTrue(text, text.contains("Akku: 80 % → 74 %"));
        assertTrue(text, text.contains("Reichweite laut Auto: 245 km"));
        assertTrue(text, text.contains("Starterbatterie (12 V): 12,6 V"));
        assertTrue(text, text.contains("Wächter wach: ja · Zugesperrt: nein"));
        assertFalse("keine offenen Platzhalter", text.contains("{"));
    }

    @Test
    public void berichtOhneOrtUndOhneKoordinaten() throws Exception {
        for (String l : SPRACHEN) {
            String text = AbschaltBericht.text(AbschaltBericht.zeilen(voll(), Locale.forLanguageTag(l)), katalog(l));
            for (String verboten : new String[] {"48", "16,3", "16.3", "20849", "37208", "18512", "31234",
                    "Wien", "http", "openstreetmap", "maps", "geo:"}) {
                assertFalse(l + ": Ort/Koordinate im Bericht (" + verboten + "): " + text,
                        text.toLowerCase(Locale.ROOT).contains(verboten.toLowerCase(Locale.ROOT)));
            }
            assertFalse(l + ": Zahl mit Koordinaten-Genauigkeit: " + text,
                    java.util.regex.Pattern.compile("\\d[.,]\\d{3,}").matcher(text).find());
        }
        // Die Datenstruktur hat gar kein Feld für einen Ort.
        for (java.lang.reflect.Field f : AbschaltBericht.Daten.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            assertFalse("Ortsfeld in Daten: " + n, n.contains("lat") || n.contains("lon") || n.contains("ort")
                    || n.contains("gps") || n.contains("place"));
        }
    }

    @Test
    public void fehlendeWerteLassenZeileWegUnbekanntBleibtEhrlich() throws Exception {
        AbschaltBericht.Daten d = AbschaltBericht.zusammenfassen(Collections.singletonList(
                fahrt(3, 5_000L, 3.0, 400, 0, 0, 0, 0)));
        String text = AbschaltBericht.text(AbschaltBericht.zeilen(d, Locale.GERMAN), katalog("de"));
        assertTrue(text, text.contains("Fahrt: 3,0 km · 6 min"));
        assertFalse("kein erfundener Verbrauch", text.contains("kWh/100"));
        assertFalse("kein erfundener Akku", text.contains("Akku"));
        assertFalse(text.contains("Reichweite"));
        assertFalse(text.contains("12 V"));
        // 0.1.36.1: Schloss unbekannt -> „Zugesperrt“ fällt weg, Wächter bleibt ehrlich „unbekannt“.
        assertTrue(text, text.contains("Wächter wach: unbekannt"));
        assertFalse(text, text.contains("Zugesperrt"));
    }

    @Test
    public void englischMitPunktAndereMitKomma() throws Exception {
        String en = AbschaltBericht.text(AbschaltBericht.zeilen(voll(), Locale.ENGLISH), katalog("en"));
        assertTrue(en, en.contains("Drive: 12.4 km · 23 min · 15.3 kWh/100 km"));
        assertTrue(en, en.contains("Guardian awake: yes · Locked: no"));
        String fr = AbschaltBericht.text(AbschaltBericht.zeilen(voll(), Locale.FRENCH), katalog("fr"));
        assertTrue(fr, fr.contains("12,4 km"));
    }

    @Test
    public void alleSprachenHabenAlleSchluessel() throws Exception {
        AbschaltBericht.Daten d = voll();
        AbschaltBericht.Daten ohne = AbschaltBericht.zusammenfassen(Collections.singletonList(fahrt(1, 1L, 3, 400, 0, 50, 0, 0)));
        for (String l : SPRACHEN) {
            for (AbschaltBericht.Daten x : new AbschaltBericht.Daten[] {d, ohne}) {
                String text = AbschaltBericht.text(AbschaltBericht.zeilen(x, Locale.forLanguageTag(l)), katalog(l));
                assertFalse(l + ": " + text, text.contains("{") || text.contains("abschalt."));
            }
        }
    }

    @Test
    public void schwellenGegenRangierfahrten() {
        int m = AbschaltBericht.STANDARD_MIN_METER, min = AbschaltBericht.STANDARD_MIN_MINUTEN;
        assertEquals(300, m);
        assertEquals(2, min);
        assertTrue("299 m", AbschaltBericht.zuKurz(0.299, 600, m, min));
        assertTrue("119 s", AbschaltBericht.zuKurz(5.0, 119, m, min));
        assertTrue("beides kurz", AbschaltBericht.zuKurz(0.05, 30, m, min));
        assertFalse("genau 300 m und 2 min", AbschaltBericht.zuKurz(0.3, 120, m, min));
        assertFalse(AbschaltBericht.zuKurz(12.4, 1380, m, min));
        assertFalse("Schwellen 0 = alles melden", AbschaltBericht.zuKurz(0.0, 0, 0, 0));
        assertTrue("ungültige Strecke", AbschaltBericht.zuKurz(Double.NaN, 600, m, min));
    }

    @Test
    public void schalterStandardAnUndSchwellenEinstellbar() throws Exception {
        assertTrue("Standard an", AbschaltBericht.schalter(null));
        assertTrue(AbschaltBericht.schalter(new JSONObject()));
        assertTrue(AbschaltBericht.schalter(new JSONObject().put("telegram", new JSONObject())));
        assertFalse(AbschaltBericht.schalter(new JSONObject().put("telegram",
                new JSONObject().put("abschaltBericht", false))));
        assertEquals(300, AbschaltBericht.minMeter(new JSONObject()));
        assertEquals(2, AbschaltBericht.minMinuten(new JSONObject()));
        JSONObject root = new JSONObject().put("telegram", new JSONObject()
                .put("abschaltBerichtMinMeter", 800).put("abschaltBerichtMinMinuten", 5));
        assertEquals(800, AbschaltBericht.minMeter(root));
        assertEquals(5, AbschaltBericht.minMinuten(root));
        JSONObject wild = new JSONObject().put("telegram", new JSONObject()
                .put("abschaltBerichtMinMeter", -5).put("abschaltBerichtMinMinuten", 999));
        assertEquals(0, AbschaltBericht.minMeter(wild));
        assertEquals(30, AbschaltBericht.minMinuten(wild));
    }

    @Test
    public void auswahlNurFahrtenSeitEinschaltenUndNichtDoppelt() {
        long an = 10_000_000L;
        long aus = an + 60 * MIN;
        TripRecord vorher = fahrt(1, an - 120 * MIN, 20, 1800, 90, 80, 0, 0);
        TripRecord a = fahrt(2, an + 2 * MIN, 8, 900, 80, 77, 0, 0);
        TripRecord b = fahrt(3, an + 30 * MIN, 4, 600, 77, 74, 0, 0);
        TripRecord offen = fahrt(4, an + 50 * MIN, 1, 60, 74, 74, 0, 0);
        offen.endTime = 0;
        List<TripRecord> alle = Arrays.asList(b, offen, a, vorher);
        List<TripRecord> w = AbschaltBericht.auswahl(alle, aus, an, -1);
        assertEquals(2, w.size());
        assertTrue(w.contains(a) && w.contains(b));
        assertEquals("schon gemeldet", 1, AbschaltBericht.auswahl(alle, aus, an, 2).size());
        assertEquals("ohne bekanntes Einschalten: Fenster 6 h", 3, AbschaltBericht.auswahl(alle, aus, -1, -1).size());
        AbschaltBericht.Daten d = AbschaltBericht.zusammenfassen(w);
        assertEquals(12.0, d.km, 1e-9);
        assertEquals(1500, d.sekunden);
        assertEquals(80.0, d.socVorher, 0);
        assertEquals(74.0, d.socNachher, 0);
        assertEquals(3, d.letzteFahrtId);
    }

    @Test
    public void schlossWieStartseite() {
        int[] alt = {0, 0, 0, 0, 0, 0, 1};
        assertEquals(Boolean.TRUE, AbschaltBericht.verriegelt(alt, false));
        alt[6] = 2;
        assertEquals(Boolean.FALSE, AbschaltBericht.verriegelt(alt, false));
        alt[6] = 0;
        assertNull(AbschaltBericht.verriegelt(alt, false));
        int[] di5 = {0, 0, 0, 0, 0, 0, 2};
        assertEquals(Boolean.TRUE, AbschaltBericht.verriegelt(di5, true));
        di5[6] = 1;
        assertEquals(Boolean.FALSE, AbschaltBericht.verriegelt(di5, true));
        assertNull(AbschaltBericht.verriegelt(null, false));
        assertNull(AbschaltBericht.verriegelt(new int[3], false));
    }

    @Test
    public void einstellungenImKatalog() throws Exception {
        String k = lies("app/src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java");
        assertTrue(k.contains("new Feld(\"telegram.abschaltBericht\", Feld.Typ.BOOL)"));
        assertTrue(k.contains("abschalt.standard = true"));
        assertTrue(k.contains("abschaltM.standard = 300"));
        assertTrue(k.contains("abschaltMin.standard = 2"));
    }

    // ---------------- 0.1.36.1 ----------------

    /** Daemon-Start, Neustart und Update: erste Meldung „aus“ ohne gesehenes „an“ -> kein Bericht. */
    @Test
    public void neustartOhneBericht() {
        AbschaltBericht.zuruecksetzenFuerTest();
        try {
            assertFalse("Start/Update meldet aus", AbschaltBericht.merkeAus(1_000_000L));
            assertFalse("Heartbeat aus nach Start", AbschaltBericht.merkeAus(1_005_000L));
            AbschaltBericht.beiAccAn(2_000_000L);
            assertTrue("echter Übergang an -> aus", AbschaltBericht.merkeAus(3_000_000L));
            // Neuer Prozess (Neustart): Zustand weg, wieder kein Bericht
            AbschaltBericht.zuruecksetzenFuerTest();
            assertFalse(AbschaltBericht.merkeAus(4_000_000L));
            assertFalse(AbschaltBericht.berichtVorgesehen(0, 5_000L));
            assertFalse("an nach aus zählt nicht", AbschaltBericht.berichtVorgesehen(6_000L, 5_000L));
            assertTrue(AbschaltBericht.berichtVorgesehen(5_000L, 5_000L));
        } finally {
            AbschaltBericht.zuruecksetzenFuerTest();
        }
    }

    @Test
    public void neustartMeldetDieFahrtVorDemUpdateNie() {
        long an = 10_000_000L;
        long aus = an + 60 * MIN;
        TripRecord a = fahrt(11, an + 2 * MIN, 2.4, 600, 80, 78, 0, 0);
        List<TripRecord> alle = Collections.singletonList(a);
        assertTrue("Neustart: nichts senden",
                AbschaltBericht.zuMelden(alle, aus, an, -1, false).isEmpty());
        // ... aber still merken, damit sie später nicht nachkommt
        long gemerkt = AbschaltBericht.hoechsteId(AbschaltBericht.auswahl(alle, aus, an, -1));
        assertEquals(11, gemerkt);
        assertTrue(AbschaltBericht.zuMelden(alle, aus + 5 * MIN, -1, gemerkt, true).isEmpty());
    }

    @Test
    public void einBerichtJeFahrt() {
        long an = 10_000_000L;
        long aus = an + 60 * MIN;
        TripRecord a = fahrt(21, an + 2 * MIN, 8, 900, 80, 77, 0, 0);
        TripRecord b = fahrt(22, an + 30 * MIN, 4, 600, 77, 74, 0, 0);
        List<TripRecord> alle = Arrays.asList(a, b);
        List<TripRecord> erst = AbschaltBericht.zuMelden(alle, aus, an, -1, true);
        assertEquals(2, erst.size());
        long gemeldet = AbschaltBericht.zusammenfassen(erst).letzteFahrtId;
        assertEquals(22, gemeldet);
        // kurz an (ohne neue Fahrt) und wieder aus: nichts doppelt
        long an2 = aus + 5 * MIN;
        assertTrue(AbschaltBericht.zuMelden(alle, an2 + MIN, an2, gemeldet, true).isEmpty());
        // auch ohne bekanntes „an“ (6-h-Fenster) nicht doppelt
        assertTrue(AbschaltBericht.zuMelden(alle, aus + MIN, -1, gemeldet, true).isEmpty());
        // neue Fahrt danach: nur sie
        TripRecord c = fahrt(23, an2 + 2 * MIN, 5, 700, 74, 71, 0, 0);
        List<TripRecord> neu = AbschaltBericht.zuMelden(Arrays.asList(a, b, c), an2 + 20 * MIN, an2, gemeldet, true);
        assertEquals(1, neu.size());
        assertEquals(23, neu.get(0).id);
    }

    @Test
    public void kategorieIstNormaleParkInfo() throws Exception {
        assertEquals("PARKING", AbschaltBericht.KATEGORIE);
        assertEquals(TelegramNotifier.Category.PARKING, TelegramNotifier.Category.valueOf(AbschaltBericht.KATEGORIE));
        String src = lies("app/src/main/java/at/greenchili/surfmate/telegram/AbschaltBericht.java");
        // 0.1.38.14: synchron mit Ergebnis (erst nach Senden/Puffern als gemeldet)
        assertTrue(src.contains("TelegramNotifier.sendMessageJetzt(text, KATEGORIE)"));
        assertFalse("keine Warnstufe mehr", src.contains("sendMessage(text, \"CRITICAL\")"));
        // Ruhezeit gilt für PARKING (nur Alarm-Kategorien kommen nachts durch)
        String tg = lies("app/src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        String quiet = tg.substring(tg.indexOf("static boolean passesQuiet("));
        quiet = quiet.substring(0, quiet.indexOf("default:"));
        assertFalse(quiet.contains("\"PARKING\""));
    }

    @Test
    public void schlossAusSperrStandUndNachfassen() throws Exception {
        assertEquals(Boolean.TRUE, AbschaltBericht.schlossAusSperrStand(new JSONObject().put("overall", 1)));
        assertEquals(Boolean.FALSE, AbschaltBericht.schlossAusSperrStand(new JSONObject().put("overall", 2)));
        assertNull(AbschaltBericht.schlossAusSperrStand(new JSONObject().put("overall", -1)));
        assertNull(AbschaltBericht.schlossAusSperrStand(new JSONObject()));
        assertNull(AbschaltBericht.schlossAusSperrStand(null));

        int[] n = {0};
        Boolean[] folge = {null, null, Boolean.TRUE};
        assertEquals(Boolean.TRUE, AbschaltBericht.mitNachfassen(() -> folge[n[0]++], 2, 1, () -> false));
        assertEquals("einmal + zweimal nachfassen", 3, n[0]);

        int[] m = {0};
        assertNull(AbschaltBericht.mitNachfassen(() -> { m[0]++; return null; }, 2, 1, () -> false));
        assertEquals(3, m[0]);

        int[] k = {0};
        assertNull("Zündung wieder an: abbrechen",
                AbschaltBericht.mitNachfassen(() -> { k[0]++; return null; }, 2, 1, () -> true));
        assertEquals(1, k[0]);

        assertEquals(Boolean.FALSE, AbschaltBericht.mitNachfassen(() -> Boolean.FALSE, 2, 1, () -> false));
        assertNull("Ausnahme = unbekannt", AbschaltBericht.mitNachfassen(() -> { throw new IllegalStateException(); }, 0, 1, null));
        assertEquals(15_000L, AbschaltBericht.NACHFASSEN_MS);
        assertEquals(2, AbschaltBericht.NACHFASSEN_VERSUCHE);

        String src = lies("app/src/main/java/at/greenchili/surfmate/telegram/AbschaltBericht.java");
        assertTrue("Quelle wie Fahrzeugseite, ohne Cloud-Abruf", src.contains("VehicleControlApiHandler.sperrStand(v, false)"));
    }

    @Test
    public void ohneSchlossFaelltZugesperrtWegInAllenSprachen() throws Exception {
        String[] wort = {"Zugesperrt", "Locked", "Verrouill", "Chiusa", "Vergrendeld"};
        for (int i = 0; i < SPRACHEN.length; i++) {
            AbschaltBericht.Daten d = voll();
            d.verriegelt = null;
            String text = AbschaltBericht.text(AbschaltBericht.zeilen(d, Locale.forLanguageTag(SPRACHEN[i])),
                    katalog(SPRACHEN[i]));
            assertFalse(SPRACHEN[i] + ": " + text, text.contains(wort[i]));
            assertFalse(SPRACHEN[i] + ": " + text, text.contains("{") || text.contains("abschalt."));
            d.verriegelt = Boolean.TRUE;
            text = AbschaltBericht.text(AbschaltBericht.zeilen(d, Locale.forLanguageTag(SPRACHEN[i])), katalog(SPRACHEN[i]));
            assertTrue(SPRACHEN[i] + ": " + text, text.contains(wort[i]));
        }
    }

    static String lies(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path p : new Path[] {current.resolve(relativePath), current.resolve(relativePath.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Nicht gefunden: " + relativePath);
    }
}
