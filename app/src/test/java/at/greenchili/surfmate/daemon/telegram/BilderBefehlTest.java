package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 0.1.38.11 {@code /bilder}: Ausschalten geht immer (auch per Knopf unter der einmaligen Nachricht), Einschalten im Chat
 * nur nach bestätigtem Pflichttext E. „/bild“ bleibt das Kamerabild.
 */
public class BilderBefehlTest {

    @Test
    public void befehlIstAngemeldetUndOhneFreigabe() {
        assertEquals("/bilder", CommandRouter.canonical("/bilder"));
        assertEquals("/bilder", CommandRouter.canonical("/Medien@MeinBot"));
        assertEquals("/kamera", CommandRouter.canonical("/bild"));
        assertNull(CommandRouter.requiredPermission("/bilder"));
        assertTrue(new BilderCommandHandler().canHandle("/bilder"));
    }

    @Test
    public void einschaltenImChatNurNachHinweis() {
        assertFalse(BilderCommandHandler.anImChatErlaubt(0L));
        assertTrue(BilderCommandHandler.anImChatErlaubt(1L));
    }

    @Test
    public void knopfSchaltetNurAb() {
        String[][][] k = BilderCommandHandler.knoepfe(s -> s);
        assertEquals(1, k.length);
        assertEquals(1, k[0].length);
        assertEquals("bilder.btn_aus", k[0][0][0]);
        assertEquals("cmd:/bilder aus", k[0][0][1]);
    }
}
