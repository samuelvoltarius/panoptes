package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 0.1.21 Web-Anmeldung per Telegram: einmal, befristet, Ratenlimit, Protokoll ohne Schlüssel. */
public class TelegramAnmeldungTest {

    private final List<String> refs = new ArrayList<>();
    private final List<String> protokoll = new ArrayList<>();
    private int ausgestellt;

    private TelegramAnmeldung neu() {
        return new TelegramAnmeldung(now -> {
            ausgestellt++;
            return new String[]{"kid" + ausgestellt, "jwt" + ausgestellt};
        }, new TelegramAnmeldung.Melder() {
            public void neu(String ref, String pruefzahl, String absender) { refs.add(ref); }
            public void protokoll(String zeile) { protokoll.add(zeile); }
        });
    }

    @Test
    public void erlaubenDannEinmalAbholen() {
        TelegramAnmeldung t = neu();
        TelegramAnmeldung.Neu n = t.anfrage("1.2.3.4", 1000L);
        assertNull(n.grund);
        assertEquals(43, n.abholId.length());
        assertTrue(n.pruefzahl.matches("\\d{4}"));
        String ref = refs.get(0);
        assertTrue(ref.matches("[0-9a-f]{16}"));
        assertFalse("Abhol-Schlüssel ist nicht die Telegram-Referenz", n.abholId.contains(ref));

        assertEquals(TelegramAnmeldung.Status.WARTET, t.abholen(n.abholId, 2000L).status);
        assertEquals(0, ausgestellt);
        assertEquals(TelegramAnmeldung.Entscheid.OK, t.entscheiden(ref, true, 3000L));
        assertEquals(TelegramAnmeldung.Entscheid.SCHON_ENTSCHIEDEN, t.entscheiden(ref, false, 3001L));

        TelegramAnmeldung.Abholung a = t.abholen(n.abholId, 4000L);
        assertEquals(TelegramAnmeldung.Status.ERLAUBT, a.status);
        assertEquals("jwt1", a.jwt);
        TelegramAnmeldung.Abholung b = t.abholen(n.abholId, 4001L);
        assertEquals("nur einmal", TelegramAnmeldung.Status.ABGEHOLT, b.status);
        assertNull(b.jwt);
        assertEquals(1, ausgestellt);
        assertNull("falscher Schlüssel", t.abholen("x", 4002L).status);
        assertNull("Referenz ist kein Abhol-Schlüssel", t.abholen(ref, 4003L).status);
        for (String z : protokoll) assertFalse("nie Schlüssel/JWT im Protokoll", z.contains(n.abholId) || z.contains("jwt"));
    }

    @Test
    public void ablehnenUndAblauf() {
        TelegramAnmeldung t = neu();
        TelegramAnmeldung.Neu n = t.anfrage("a", 0L);
        assertEquals(TelegramAnmeldung.Entscheid.OK, t.entscheiden(refs.get(0), false, 10L));
        assertEquals(TelegramAnmeldung.Status.ABGELEHNT, t.abholen(n.abholId, 20L).status);
        assertEquals(0, ausgestellt);

        TelegramAnmeldung.Neu m = t.anfrage("a", 100L);
        assertEquals(TelegramAnmeldung.Entscheid.ABGELAUFEN,
                t.entscheiden(refs.get(1), true, 100L + TelegramAnmeldung.GUELTIG_MS + 1));
        assertEquals(TelegramAnmeldung.Status.ABGELAUFEN, t.abholen(m.abholId, 100L + TelegramAnmeldung.GUELTIG_MS + 2).status);

        TelegramAnmeldung.Neu k = t.anfrage("b", 1000L);
        t.entscheiden(refs.get(2), true, 1500L);
        assertEquals("erlaubt, aber zu spät abgeholt", TelegramAnmeldung.Status.ABGELAUFEN,
                t.abholen(k.abholId, 1500L + TelegramAnmeldung.ABHOLEN_MS + 1).status);
        assertEquals(0, ausgestellt);
        assertEquals(TelegramAnmeldung.Entscheid.UNBEKANNT, t.entscheiden("0123456789abcdef", true, 2000L));
    }

    @Test
    public void ratenlimitUndOffeneAnfragen() {
        TelegramAnmeldung t = neu();
        assertNull(t.anfrage("a", 0L).grund);
        assertNull(t.anfrage("a", 1L).grund);
        assertEquals("zu_viele_offen", t.anfrage("b", 2L).grund);
        t.entscheiden(refs.get(0), false, 3L);
        assertNull(t.anfrage("a", 4L).grund);
        t.entscheiden(refs.get(1), false, 5L);
        t.entscheiden(refs.get(2), false, 6L);
        assertEquals("drei je Absender in 10 min", "ratenlimit", t.anfrage("a", 7L).grund);
        assertNotNull("anderer Absender", t.anfrage("b", 8L).abholId);
        t.entscheiden(refs.get(3), false, 9L);
        assertNull("Fenster vorbei", t.anfrage("a", TelegramAnmeldung.FENSTER_MS + 1).grund);

        TelegramAnmeldung tag = neu();
        int ok = 0;
        for (int i = 0; i < 20; i++) {
            long now = i * (TelegramAnmeldung.FENSTER_MS + 1);
            TelegramAnmeldung.Neu n = tag.anfrage("x" + i, now);
            if (n.grund == null) {
                ok++;
                tag.entscheiden(refs.get(refs.size() - 1), false, now + 1);
            }
        }
        assertEquals("höchstens zehn je Tag", TelegramAnmeldung.MAX_TAG, ok);
    }

    @Test
    public void kopplungArtWebDarfNurWebSitzung() {
        Kopplungen k = WebSitzungTest.kopplungen();
        String web = k.anlegen("Browser (Telegram)", Kopplungen.ART_WEB, 1L);
        String cockpit = k.anlegen("Cockpit", "cockpit", 1L);
        assertNotNull(web);
        assertTrue(k.darfWebSitzung(web));
        assertFalse(k.darfWebSitzung(cockpit));
        assertFalse("kein Aufsperr-Recht", k.hatRecht(web, Kopplungen.RECHT_AUFSPERREN));
    }
}
