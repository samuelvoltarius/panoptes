package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.telegram.KopplungsKnopf;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Kopplung per Anfrage + 1 Tipp (KOPPLUNG_ANFRAGE.md): Ablauf, Grenzen, Telegram, Regel B. */
public class KopplungsAnfragenTest {

    static final long T0 = 1_000_000L;

    /** Ausgeber wie im Daemon, aber mit echter Kopplungsliste im Speicher. */
    static final class Aufbau {
        final JSONArray[] platte = {new JSONArray()};
        final Kopplungen kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return platte[0]; }
            public boolean speichere(JSONArray g) { platte[0] = g; return true; }
        });
        final List<String> neu = new ArrayList<>();
        final List<String> entschieden = new ArrayList<>();
        final KopplungsAnfragen a = new KopplungsAnfragen((geraet, art, now) -> {
            String kid = kopplungen.anlegen(geraet, art, now);
            return kid == null ? null : new String[] {kid, "jwt-" + kid};
        });

        Aufbau() {
            a.setBeobachter(new KopplungsAnfragen.Beobachter() {
                public void neu(String ref, String geraet, String pruefzahl) { neu.add(ref + "|" + geraet + "|" + pruefzahl); }
                public void entschieden(String ref, boolean erlaubt) { entschieden.add(ref + "|" + erlaubt); }
            });
        }

        String ref() {
            JSONArray l = a.offeneListe(T0);
            return l.optJSONObject(l.length() - 1).optString("ref");
        }
    }

    @Test
    public void happyPathTokenOnlyOnceThenInPairingList() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("PC-Wohnzimmer", "4711", "100.1.2.3", T0);
        assertNull(n.grund);
        assertTrue(n.id.matches("^[0-9a-f]{32}$"));
        assertEquals(T0 + KopplungsAnfragen.GUELTIG_MS, n.gueltigBis);
        String ref = x.ref();
        assertTrue(ref.matches("^[0-9a-f]{16}$"));
        assertFalse("Abhol-id ist nicht der Griff", n.id.startsWith(ref));
        assertEquals(1, x.neu.size());
        assertEquals(ref + "|PC-Wohnzimmer|4711", x.neu.get(0));

        assertEquals(KopplungsAnfragen.Status.WARTET, x.a.abholen(n.id, T0 + 1000).status);
        assertEquals(0, x.kopplungen.alle(T0).length());
        assertEquals(KopplungsAnfragen.Entscheid.OK, x.a.entscheiden(ref, true, T0 + 5000));
        assertEquals(ref + "|true", x.entschieden.get(0));

        KopplungsAnfragen.Abholung ab = x.a.abholen(n.id, T0 + 6000);
        assertEquals(KopplungsAnfragen.Status.ERLAUBT, ab.status);
        assertNotNull(ab.jwt);
        assertTrue(ab.kid.startsWith("k-"));
        assertEquals("Eintrag in der Kopplungsliste", 1, x.kopplungen.alle(T0).length());
        assertEquals("PC-Wohnzimmer", x.kopplungen.alle(T0).optJSONObject(0).optString("name"));
        assertTrue("widerrufbar wie heute", x.kopplungen.pruefe(ab.kid, T0 + 7000));

        KopplungsAnfragen.Abholung zwei = x.a.abholen(n.id, T0 + 8000);
        assertEquals(KopplungsAnfragen.Status.ABGEHOLT, zwei.status);
        assertNull("JWT nur einmal", zwei.jwt);
        assertEquals(1, x.kopplungen.alle(T0).length());
        assertEquals(Kopplungen.WIDERRUFEN, x.kopplungen.widerrufen(ab.kid));
    }

    @Test
    public void declineGivesNoTokenAndNoEntry() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("Fremd", "1234", "a", T0);
        assertEquals(KopplungsAnfragen.Entscheid.OK, x.a.entscheiden(x.ref(), false, T0 + 1000));
        KopplungsAnfragen.Abholung ab = x.a.abholen(n.id, T0 + 2000);
        assertEquals(KopplungsAnfragen.Status.ABGELEHNT, ab.status);
        assertNull(ab.jwt);
        assertEquals(0, x.kopplungen.alle(T0).length());
    }

    @Test
    public void expiresAfterTwoMinutesAndLateClicksAreRejected() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("PC", "1234", "a", T0);
        String ref = x.ref();
        long spaet = T0 + KopplungsAnfragen.GUELTIG_MS;
        assertEquals(KopplungsAnfragen.Entscheid.ABGELAUFEN, x.a.entscheiden(ref, true, spaet));
        assertEquals(KopplungsAnfragen.Status.ABGELAUFEN, x.a.abholen(n.id, spaet + 1).status);
        assertEquals(0, x.a.offeneListe(spaet).length());
        assertEquals(0, x.kopplungen.alle(T0).length());
        assertNull("nach 10 min vergessen", x.a.abholen(n.id, spaet + KopplungsAnfragen.FENSTER_MS + 1).status);
    }

    @Test
    public void allowedButNotPickedUpExpires() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("PC", "1234", "a", T0);
        x.a.entscheiden(x.ref(), true, T0 + 1000);
        assertEquals(KopplungsAnfragen.Status.ABGELAUFEN,
                x.a.abholen(n.id, T0 + 1000 + KopplungsAnfragen.ABHOLEN_MS).status);
        assertEquals("kein Eintrag ohne Abholung", 0, x.kopplungen.alle(T0).length());
    }

    @Test
    public void secondDecisionHasNoEffect() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("PC", "1234", "a", T0);
        String ref = x.ref();
        assertEquals(KopplungsAnfragen.Entscheid.OK, x.a.entscheiden(ref, false, T0 + 1000));
        assertEquals(KopplungsAnfragen.Entscheid.SCHON_ENTSCHIEDEN, x.a.entscheiden(ref, true, T0 + 2000));
        assertEquals(KopplungsAnfragen.Entscheid.SCHON_ENTSCHIEDEN, x.a.entscheiden(ref, false, T0 + 3000));
        assertEquals(KopplungsAnfragen.Status.ABGELEHNT, x.a.abholen(n.id, T0 + 4000).status);
        assertEquals(1, x.entschieden.size());
        assertEquals(KopplungsAnfragen.Entscheid.UNBEKANNT, x.a.entscheiden("0000000000000000", true, T0));
        assertEquals("Abhol-id ist kein Griff", KopplungsAnfragen.Entscheid.UNBEKANNT, x.a.entscheiden(n.id, true, T0));
    }

    @Test
    public void limitsAtMostThreeOpenAndRatePerSender() {
        Aufbau x = new Aufbau();
        assertEquals("pruefzahl", x.a.anlegen("PC", "12a4", "a", T0).grund);
        assertEquals("pruefzahl", x.a.anlegen("PC", "12345", "a", T0).grund);
        for (int i = 0; i < 3; i++) assertNull(x.a.anlegen("PC" + i, "1234", "s" + i, T0).grund);
        assertEquals("zu_viele_offen", x.a.anlegen("PC", "1234", "s9", T0).grund);
        // abgelaufen → wieder Platz, aber je Absender höchstens 5 in 10 min
        Aufbau y = new Aufbau();
        long t = T0;
        for (int i = 0; i < 5; i++) {
            assertNull(y.a.anlegen("PC", "1234", "gleich", t).grund);
            t += 40_000L;   // höchstens 3 gleichzeitig offen, ältere laufen nach 2 min ab
        }
        assertEquals("ratenlimit", y.a.anlegen("PC", "1234", "gleich", t).grund);
        assertNull("anderer Absender geht", y.a.anlegen("PC", "1234", "anders", t).grund);
        assertNull("nach dem Fenster wieder", y.a.anlegen("PC", "1234", "gleich", T0 + KopplungsAnfragen.FENSTER_MS + 1).grund);
    }

    @Test
    public void listShowsRefNeverPickupIdAndCleansName() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("<b>PC</b>\u0007", "4711", "a", T0);
        JSONObject o = x.a.offeneListe(T0).optJSONObject(0);
        assertFalse(o.has("id"));
        assertFalse(o.toString().contains(n.id));
        assertEquals("bPC/b", o.optString("geraet"));
        assertEquals("4711", o.optString("pruefzahl"));
    }

    @Test
    public void optionalDeviceKindIsStoredOnlyIfKnown() {
        Aufbau x = new Aufbau();
        KopplungsAnfragen.Neu n = x.a.anlegen("Home Assistant", "1234", "homeassistant", "a", T0);
        assertEquals("homeassistant", x.a.offeneListe(T0).optJSONObject(0).optString("art"));
        x.a.entscheiden(x.ref(), true, T0 + 1000);
        x.a.abholen(n.id, T0 + 2000);
        assertEquals("homeassistant", x.kopplungen.alle(T0).optJSONObject(0).optString("art"));
        Aufbau y = new Aufbau();
        y.a.anlegen("X", "1234", "<script>", "a", T0);
        assertFalse("unbekannte Art fällt weg", y.a.offeneListe(T0).optJSONObject(0).has("art"));
    }

    @Test
    public void pairedDeviceCanOnlyRevokeItself() throws Exception {
        assertFalse(ExternGuard.blocks("POST", "/api/sitzung/abmelden"));
        String eh = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        int i = eh.indexOf("pathOnly.equals(\"/api/sitzung/abmelden\")");
        assertTrue(i > 0);
        String block = eh.substring(i, eh.indexOf("pathOnly.equals(\"/api/sitzung/erneuern\")", i));
        assertTrue("nur die eigene kid", block.contains("String kid = AuthMiddleware.requestKopplungsId();"));
        assertTrue(block.contains("Kopplungen.get().widerrufen(kid)"));
        assertTrue("Alfreds Login (ohne kid) widerruft nichts", block.contains("if (kid == null) {"));
    }

    // ------------------------------------------------------------------ Telegram

    @Test
    public void telegramForeignChatOrSenderRejected() {
        long besitzer = 4242;
        assertTrue(KopplungsKnopf.berechtigt(besitzer, besitzer, besitzer));
        assertFalse("fremde chat_id", KopplungsKnopf.berechtigt(besitzer, 999, besitzer));
        assertFalse("fremder Absender in Besitzer-Chat", KopplungsKnopf.berechtigt(999, besitzer, besitzer));
        assertFalse("kein Besitzer gekoppelt", KopplungsKnopf.berechtigt(-1, -1, -1));
    }

    @Test
    public void telegramCallbackDataCarriesRefOnly() {
        String d = KopplungsKnopf.daten("0123456789abcdef", true);
        assertEquals("kp:0123456789abcdef:j", d);
        assertTrue(d.length() <= 64);
        assertEquals("0123456789abcdef", KopplungsKnopf.lies(d)[0]);
        assertEquals("n", KopplungsKnopf.lies(KopplungsKnopf.daten("0123456789abcdef", false))[1]);
        assertNull(KopplungsKnopf.lies("kp:0123456789abcdef0123456789abcdef:j"));
        assertNull(KopplungsKnopf.lies("kp:xyz:j"));
        assertNull(KopplungsKnopf.lies("cmd:/status"));
        assertEquals("/api/kopplung/anfrage/0123456789abcdef/erlauben", KopplungsKnopf.pfad("0123456789abcdef", true));
    }

    @Test
    public void telegramDoubleClickAndExpiredAnswers() {
        assertEquals("kopplung.erlaubt", KopplungsKnopf.ergebnisSchluessel(200, true));
        assertEquals("kopplung.abgelehnt", KopplungsKnopf.ergebnisSchluessel(200, false));
        assertEquals("doppelter Klick", "kopplung.schon_entschieden", KopplungsKnopf.ergebnisSchluessel(409, true));
        assertEquals("abgelaufen", "kopplung.abgelaufen", KopplungsKnopf.ergebnisSchluessel(410, true));
        assertEquals("kopplung.fehler", KopplungsKnopf.ergebnisSchluessel(0, true));
        assertEquals("PC\\_\\*x", KopplungsKnopf.md("PC_*x"));
    }

    // ------------------------------------------------------------------ Regel B / Verdrahtung

    @Test
    public void couplingTokenCannotListOrDecide() {
        assertTrue(ExternGuard.blocks("GET", "/api/kopplung/anfragen"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/anfrage/0123456789abcdef/erlauben"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/anfrage/0123456789abcdef/ablehnen"));
    }

    @Test
    public void wiring() throws Exception {
        String eh = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        int liste = eh.indexOf("pathOnly.equals(\"/api/kopplung/anfragen\")");
        int entscheid = eh.indexOf("pathOnly.startsWith(\"/api/kopplung/anfrage/\")");
        assertTrue(liste > 0 && entscheid > 0);
        assertTrue("zweite Sperre im Handler (Liste)",
                eh.substring(liste, liste + 200).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        assertTrue("zweite Sperre im Handler (Erlauben)",
                eh.substring(entscheid, entscheid + 300).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        String ah = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java"));
        assertTrue(ah.contains("path.equals(\"/auth/kopplung/anfrage\") && method.equals(\"POST\")"));
        assertTrue(ah.contains("path.startsWith(\"/auth/kopplung/anfrage/\") && method.equals(\"GET\")"));
        assertFalse("Regel B: Anfrage-Weg erneuert nie das Geheimnis", ah.substring(ah.indexOf("handleAnfrage(String"))
                .contains("regenerate"));
        String mw = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AuthMiddleware.java"));
        assertTrue(mw.contains("\"/auth/kopplung/anfrage\","));
        String ka = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/auth/KopplungsAnfragen.java"));
        assertTrue("JWT nur mit scope kopplung + kid",
                ka.contains("AuthManager.generateJwt(PairingCodes.SCOPE, kid)"));
        String tg = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java"));
        assertTrue(tg.contains("KopplungsKnopf.berechtigt(userId, chatId, ownerChatId)"));
        assertTrue("Knopf vor dem allgemeinen Log (keine Griffe im Log)",
                tg.indexOf("handleKopplungsKnopf(callbackQuery") < tg.indexOf("log(\"Callback from \" + userId"));
        String melder = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/auth/KopplungsMelder.java"));
        assertTrue("Display nur im Stand", melder.contains("if (!at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.isMovementBlocked())"));
        assertFalse("kein Ton", melder.contains("speak("));
    }

    @Test
    public void textsInAllLanguages() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + l + ".json")));
            JSONObject t = d.getJSONObject("telegram").getJSONObject("kopplung");
            for (String k : new String[] {"anfrage", "knopf_erlauben", "knopf_ablehnen", "erlaubt", "abgelehnt",
                    "schon_entschieden", "abgelaufen", "fehler"}) {
                assertTrue(l + " telegram.kopplung." + k, t.has(k));
            }
            assertTrue(t.getString("anfrage").contains("{1}"));
            JSONObject n = d.getJSONObject("notifications");
            for (String k : new String[] {"kopplung_titel", "kopplung_text", "kopplung_erlauben", "kopplung_ablehnen"}) {
                assertTrue(l + " " + k, n.has(k));
            }
        }
    }
}
