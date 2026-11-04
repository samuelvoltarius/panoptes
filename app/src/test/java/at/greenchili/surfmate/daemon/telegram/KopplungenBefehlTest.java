package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.38.8 verlorenes Handy: Telegram /kopplungen listet, fragt nach, entfernt über den Einmal-Griff (nie die id). */
public class KopplungenBefehlTest {

    @Test
    public void listeMitKnoepfenOhneKopplungsId() throws Exception {
        TelegramUpdateKnopfTest.Ctx ctx = new TelegramUpdateKnopfTest.Ctx();
        ctx.fern = new JSONObject().put("ok", true).put("geraete", new JSONArray()
                .put(new JSONObject().put("name", "Alfreds *Handy*").put("art", "handy").put("besitzer", true)
                        .put("ref", "0123456789abcdef"))
                .put(new JSONObject().put("name", "Cockpit").put("art", "cockpit").put("ref", "fedcba9876543210")));
        new KopplungenCommandHandler().handle(1L, new String[]{"/kopplungen"}, ctx);
        assertEquals("GET /api/kopplung/telegram-liste", ctx.anfragen.get(0));
        String t = ctx.texte.get(0);
        assertTrue(t.contains("tr:kopplungen.titel"));
        assertFalse("kein Markdown aus Namen", t.contains("*Handy*"));
        assertTrue(t.contains("tr:kopplungen.besitzer"));
        String[][][] k = ctx.knoepfe.get(0);
        assertEquals(2, k.length);
        assertEquals("cmd:/kopplungen entfernen 0123456789abcdef 1", k[0][0][1]);
        assertTrue(k[1][0][1].length() <= 64);
    }

    @Test
    public void rueckfrageVorDemEntfernen() {
        TelegramUpdateKnopfTest.Ctx ctx = new TelegramUpdateKnopfTest.Ctx();
        new KopplungenCommandHandler().handle(1L, new String[]{"/kopplungen", "entfernen", "0123456789abcdef", "2"}, ctx);
        assertTrue("noch nichts entfernt", ctx.anfragen.isEmpty());
        assertTrue(ctx.texte.get(0).startsWith("tr:kopplungen.frage|2"));
        String[][][] k = ctx.knoepfe.get(0);
        assertEquals("ke:0123456789abcdef", k[0][0][1]);
        assertEquals("cmd:/kopplungen", k[0][1][1]);
        // ungültiger Griff: zurück zur Liste, kein Entfernen-Knopf
        TelegramUpdateKnopfTest.Ctx c2 = new TelegramUpdateKnopfTest.Ctx();
        new KopplungenCommandHandler().handle(1L, new String[]{"/kopplungen", "entfernen", "../x", "1"}, c2);
        assertEquals("GET /api/kopplung/telegram-liste", c2.anfragen.get(0));
        assertEquals("tr:kopplungen.fehler", c2.texte.get(0));
    }

    @Test
    public void befehlIstBekannt() {
        assertEquals("/kopplungen", CommandRouter.canonical("/Geraete@MeinBot"));
        assertEquals("/kopplungen", CommandRouter.canonical("/kopplungen"));
    }
}
