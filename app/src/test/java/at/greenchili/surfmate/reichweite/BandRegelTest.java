package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** F1 Band am Mittelschirm: Takt (8 s bei Wechsel, alle 10 min), Texte, Ansage Standard aus. */
public class BandRegelTest {

    /** Texte wie de.json, mit einfachem {n}-Ersetzen. */
    static final BandRegel.Texte DE = new BandRegel.Texte() {
        final Map<String, String> m = new HashMap<>();
        {
            m.put("notifications.f1_ziel", "Ziel");
            m.put("notifications.f1_passt_titel", "Reicht bis {0}");
            m.put("notifications.f1_ankunft", "Ankunft mit {0} %");
            m.put("notifications.f1_knapp_titel", "Wird knapp bis {0}");
            m.put("notifications.f1_knapp_zeile", "Ankunft mit {0} % · etwas langsamer fahren hilft");
            m.put("notifications.f1_reicht_nicht_titel", "Reicht nicht bis {0}");
            m.put("notifications.f1_neuer_stopp", "Neuer Ladestopp: {0} in {1} km");
            m.put("notifications.f1_neu_planen", "Bitte beim nächsten Halt neu planen");
            m.put("notifications.f1_voll_titel", "{0} ist gerade voll");
            m.put("notifications.f1_voll_ausweich", "Ausweich-Säule: {0} · {1} km weiter");
        }

        @Override
        public String get(String key, Object... args) {
            String s = m.get(key);
            for (int i = 0; args != null && i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
            return s;
        }
    };

    static RangeWatch.Lage lage(RangeWatch.Stufe stufe, double prognose) {
        RangeWatch.Lage l = new RangeWatch.Lage();
        l.aktiv = true;
        l.stufe = stufe;
        l.prognoseProzent = prognose;
        l.naechsterName = "Ionity Ansfelden";
        l.naechsterKm = 30;
        return l;
    }

    @Test
    public void showsOnChangeAndEveryTenMinutesOnly() {
        BandRegel r = new BandRegel(DE);
        long t = 0;
        BandRegel.Anzeige a = r.pruefe(t, lage(RangeWatch.Stufe.PASST, 22.4), null, null, false);
        assertNotNull("erste Bewertung zeigt", a);
        assertEquals(8_000L, a.dauerMs);
        assertEquals("passt", a.stufe);
        assertEquals("Reicht bis Ionity Ansfelden", a.titel);
        assertEquals("Ankunft mit 22 %", a.zeile2);
        assertNull(r.pruefe(t + 10_000, lage(RangeWatch.Stufe.PASST, 21), null, null, false));
        assertNull("Prozent ändert sich, Aussage nicht", r.pruefe(t + 5 * 60_000L, lage(RangeWatch.Stufe.PASST, 19), null, null, false));
        assertNotNull("Erinnerung", r.pruefe(t + 10 * 60_000L, lage(RangeWatch.Stufe.PASST, 18), null, null, false));
        BandRegel.Anzeige k = r.pruefe(t + 10 * 60_000L + 10_000, lage(RangeWatch.Stufe.KNAPP, 13), null, null, false);
        assertNotNull("Stufenwechsel sofort", k);
        assertEquals("Wird knapp bis Ionity Ansfelden", k.titel);
        assertEquals("Ankunft mit 13 % · etwas langsamer fahren hilft", k.zeile2);
    }

    @Test
    public void announcementOffByDefaultAndRateLimited() {
        BandRegel r = new BandRegel(DE);
        assertFalse(r.pruefe(0, lage(RangeWatch.Stufe.PASST, 30), null, null, false).ansagen);
        BandRegel s = new BandRegel(DE);
        assertTrue(s.pruefe(0, lage(RangeWatch.Stufe.PASST, 30), null, null, true).ansagen);
        BandRegel.Anzeige k = s.pruefe(3 * 60_000L, lage(RangeWatch.Stufe.KNAPP, 13), null, null, true);
        assertNotNull(k);
        assertFalse("höchstens alle 10 min", k.ansagen);
        BandRegel.Anzeige erinnerung = s.pruefe(14 * 60_000L, lage(RangeWatch.Stufe.KNAPP, 13), null, null, true);
        assertNotNull(erinnerung);
        assertFalse("Erinnerung ohne Stufenwechsel wird nicht angesagt", erinnerung.ansagen);
        assertTrue(s.pruefe(15 * 60_000L, lage(RangeWatch.Stufe.REICHT_NICHT, 5), null, null, true).ansagen);
    }

    @Test
    public void notEnoughShowsSuggestionOrReplanHint() throws Exception {
        BandRegel r = new BandRegel(DE);
        BandRegel.Anzeige a = r.pruefe(0, lage(RangeWatch.Stufe.REICHT_NICHT, 4), null, null, false);
        assertEquals("Reicht nicht bis Ionity Ansfelden", a.titel);
        assertEquals("Bitte beim nächsten Halt neu planen", a.zeile2);
        Neuplanung.Vorschlag v = Neuplanung.auswerten(new JSONObject(NeuplanungTest.ANTWORT), 1);
        assertNull("neuer Inhalt erst nach 1 min", r.pruefe(30_000, lage(RangeWatch.Stufe.REICHT_NICHT, 4), null, v, false));
        BandRegel.Anzeige b = r.pruefe(70_000, lage(RangeWatch.Stufe.REICHT_NICHT, 4), null, v, false);
        assertNotNull("Vorschlag kam dazu", b);
        assertEquals("Neuer Ladestopp: Smatrics Haag in 38 km", b.zeile2);
    }

    @Test
    public void fullStopWithAlternative() throws Exception {
        JSONObject res = new JSONObject("{\"stationen\":["
                + "{\"name\":\"Ionity Ansfelden\",\"lat\":48.2,\"lon\":14.3,\"kw\":350,\"status_bekannt\":true,\"frei\":0,\"ladepunkte\":6},"
                + "{\"name\":\"Tesla Ansfelden\",\"lat\":48.2,\"lon\":14.32,\"kw\":250,\"status_bekannt\":true,\"frei\":3,\"ladepunkte\":8}]}");
        StoppBelegung b = StoppBelegung.auswerten(res, 48.2, 14.3, 175);
        assertTrue(b.voll);
        BandRegel r = new BandRegel(DE);
        BandRegel.Anzeige a = r.pruefe(0, lage(RangeWatch.Stufe.REICHT_NICHT, 20), b, null, false);
        assertEquals("Ionity Ansfelden ist gerade voll", a.titel);
        assertTrue(a.zeile2, a.zeile2.startsWith("Ausweich-Säule: Tesla Ansfelden · 1.5 km weiter"));
    }

    @Test
    public void inactiveShowsNothingAndResetRepeats() {
        BandRegel r = new BandRegel(DE);
        RangeWatch.Lage aus = new RangeWatch.Lage();
        assertNull(r.pruefe(0, aus, null, null, false));
        assertNull(r.pruefe(0, null, null, null, false));
        assertNotNull(r.pruefe(0, lage(RangeWatch.Stufe.PASST, 30), null, null, false));
        r.zuruecksetzen();
        assertNotNull("nach Halt wieder zeigen", r.pruefe(5_000, lage(RangeWatch.Stufe.PASST, 30), null, null, false));
    }

    @Test
    public void allLanguagesHaveTheBandTexts() throws Exception {
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject n = new JSONObject(NeuplanungTest.lies("app/src/main/assets/server-i18n/" + lang + ".json"))
                    .getJSONObject("notifications");
            for (String k : new String[] {"f1_ziel", "f1_passt_titel", "f1_ankunft", "f1_knapp_titel", "f1_knapp_zeile",
                    "f1_reicht_nicht_titel", "f1_neuer_stopp", "f1_neu_planen", "f1_voll_titel", "f1_voll_ausweich"}) {
                assertTrue(lang + " " + k, n.has(k));
                assertFalse(lang + " " + k + " ohne Apostroph (MessageFormat)", n.getString(k).contains("'"));
            }
        }
    }

    @Test
    public void overlayIsTopRightNotTouchableTwoLines() throws Exception {
        String svc = NeuplanungTest.lies("app/src/main/java/at/greenchili/surfmate/overlay/MessageOverlayService.java");
        assertTrue(svc.contains("\"band\".equalsIgnoreCase(kind)"));
        // 0.1.9.1: Position einstellbar (Standard oben Mitte), weiterhin toastLayoutParams = FLAG_NOT_TOUCHABLE
        assertTrue(svc.contains("toastLayoutParams(Gravity.TOP | (mitte ? Gravity.CENTER_HORIZONTAL : Gravity.END))"));
        assertTrue(svc.contains("if (dialogShowing && overlayView != null) return false;"));
        String cmd = at.greenchili.surfmate.byd.MessageOverlayController.bandCommand("Reicht bis A's", "x", "knapp", 99_999);
        assertTrue(cmd, cmd.contains("--es kind band"));
        assertTrue(cmd, cmd.contains("'Reicht bis A'\\''s'"));
        assertTrue("Dauer begrenzt", cmd.contains("--es durationMs '15000'"));
        assertEquals("band und ansage im Schema", 2, count(NeuplanungTest.lies(
                "app/src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java"), "new Feld\\(\"planner\\.(band|ansage)\""));
    }

    private static int count(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
