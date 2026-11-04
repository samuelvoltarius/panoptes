package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.7 (Befund homepage-einfach, TELEGRAM_FAKTEN): Die Bot-Texte nannten „System → Telegram-Meldungen“ und den
 * Knopf „Koppeln“. In der Weboberfläche heißt der Weg „Regeln → Meldungen → Telegram-Meldungen“ und der Knopf „Kopplung
 * starten“. Der Test leitet beides aus den Web-Texten ab, damit eine Umbenennung dort auffällt. Der Hilfetext der
 * Parkmeldungen sagt nicht mehr „bei neuer Einrichtung aus“ (Telegram selbst ist Opt-in; danach sind Parkmeldungen an).
 */
public class TelegramTexteStimmenTest {

    private static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};

    private static JSONObject json(String rel) throws Exception {
        Path p = Paths.get("src/main/assets/" + rel);
        if (!Files.exists(p)) p = Paths.get("app/src/main/assets/" + rel);
        return new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    /** Alle String-Werte unter einem Schlüssel gleichen Namens, überall im Baum. */
    private static void sammle(Object o, String key, List<String> aus) throws Exception {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.get(k);
                if (k.equals(key) && v instanceof String) aus.add((String) v);
                else sammle(v, key, aus);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) sammle(a.get(i), key, aus);
        }
    }

    /** Der Text {@code help} im Objekt mit dem Namen {@code key}, wo auch immer es im Baum liegt. */
    private static void sammleObjekt(Object o, String key, List<String> aus) throws Exception {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.get(k);
                if (k.equals(key) && v instanceof JSONObject) {
                    if (((JSONObject) v).has("help")) aus.add(((JSONObject) v).getString("help"));
                } else {
                    sammleObjekt(v, key, aus);
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) sammleObjekt(a.get(i), key, aus);
        }
    }

    private static String pfad(String l) throws Exception {
        JSONObject web = json("web/i18n/" + l + ".json");
        return web.getJSONObject("shell").getString("sec_automationen") + " → "
                + web.getJSONObject("shell").getString("tab_meldungen") + " → "
                + web.getJSONObject("nav").getString("telegram");
    }

    @Test
    public void botTexteNennenDenRichtigenMenuepfad() throws Exception {
        for (String l : SPRACHEN) {
            String pfad = pfad(l);
            JSONObject srv = json("server-i18n/" + l + ".json");
            int geprueft = 0;
            for (String key : new String[]{"no_owner", "no_pin", "not_allowed", "some_locked", "locked"}) {
                List<String> werte = new ArrayList<>();
                sammle(srv, key, werte);
                for (String w : werte) {
                    if (!w.contains("→") || !w.contains("Telegram")) continue;
                    geprueft++;
                    assertTrue(l + " " + key + ": " + w, w.contains(pfad));
                }
            }
            assertTrue(l + ": mindestens die 5 Bot-Texte mit Pfad", geprueft >= 5);
            // nirgends mehr der alte Weg über „System“
            String roh = new String(Files.readAllBytes(Files.exists(Paths.get("src/main/assets/server-i18n/" + l + ".json"))
                    ? Paths.get("src/main/assets/server-i18n/" + l + ".json")
                    : Paths.get("app/src/main/assets/server-i18n/" + l + ".json")), StandardCharsets.UTF_8);
            assertFalse(l, roh.matches("(?s).*(System|Système|Sistema|Systeem) → [^\"\\\\]*Telegram.*"));
        }
    }

    @Test
    public void kopplungsKnopfHeisstWieInDerWeboberflaeche() throws Exception {
        for (String l : SPRACHEN) {
            String knopf = json("web/i18n/" + l + ".json").getJSONObject("tgp").getString("pair_start");
            List<String> werte = new ArrayList<>();
            sammle(json("server-i18n/" + l + ".json"), "no_owner", werte);
            assertTrue(l, werte.size() == 1);
            assertTrue(l + ": " + werte.get(0), werte.get(0).contains(knopf));
        }
    }

    @Test
    public void hilfetextParkmeldungenSagtNichtMehrBeiNeuerEinrichtungAus() throws Exception {
        String[] alt = {"Bei neuer Einrichtung aus", "Off for new setups", "Désactivé pour les nouvelles installations",
                "Disattivato nelle nuove configurazioni", "Uit bij nieuwe installaties"};
        for (int i = 0; i < SPRACHEN.length; i++) {
            JSONObject srv = json("server-i18n/" + SPRACHEN[i] + ".json");
            List<String> help = new ArrayList<>();
            sammleObjekt(srv, "parkingMessages", help);
            assertTrue(SPRACHEN[i], help.size() == 1);
            assertFalse(SPRACHEN[i] + ": " + help.get(0), help.get(0).contains(alt[i]));
            assertTrue(SPRACHEN[i], help.get(0).contains("Telegram"));
        }
    }
}
