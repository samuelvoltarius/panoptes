package at.greenchili.surfmate.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang;
import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.43.16 (E2E 0.1.43.13): Dialog-Overlay ohne Fehltipp. Ein Tipp kurz nach dem Erscheinen zählt nicht; schließt der
 * Dialog von selbst, fängt das unsichtbare Fenster noch kurz die Tipps ab, statt sie an den Launcher durchzulassen; der Kern
 * erfährt, ob OK getippt wurde oder ob der Dialog nur von selbst zuging.
 */
public class DialogFehltippSchutzTest {

    @Test
    public void tippInDenErstenMillisekundenZaehltNicht() {
        long t = 50_000L;
        assertTrue(MessageOverlayService.tippZuFrueh(t, t));
        assertTrue(MessageOverlayService.tippZuFrueh(t, t + 699));
        assertFalse(MessageOverlayService.tippZuFrueh(t, t + MessageOverlayService.TIPP_SPERRE_MS));
        assertFalse(MessageOverlayService.tippZuFrueh(t, t + 5_000));
        // 0.1.43.17: vor dem ersten Bild ist alles gesperrt
        assertTrue("vor dem ersten Bild", MessageOverlayService.tippZuFrueh(t, t - 1));
        assertTrue("noch kein Bild", MessageOverlayService.tippZuFrueh(-1, t));
        assertEquals(700, MessageOverlayService.TIPP_SPERRE_MS);
        assertEquals(1000, MessageOverlayService.NACHLAUF_MS);
    }

    /** 0.1.43.17 (E2E 0.1.43.16, 3a): das erste Bild kommt 0,8 s nach addView; ein Tipp 0,35 s danach zählt nicht. */
    @Test
    public void sperreZaehltAbDemErstenBildNichtAbAddView() {
        MessageOverlayService.TippSperre s = new MessageOverlayService.TippSperre();
        long addView = 10_000L;
        assertTrue("noch nichts gezeichnet", s.zuFrueh(addView + 50));
        assertTrue(s.zuFrueh(addView + 760));
        long bild = addView + 800;                      // Attrappe: erstes Bild verzögert
        s.erstesBild(bild);
        s.erstesBild(bild + 500);                       // spätere Bilder ändern nichts
        assertTrue("0,35 s nach dem Sichtbarwerden: ignoriert", s.zuFrueh(bild + 350));
        assertTrue("0,06 s danach (Rechte-Dialog in der E2E)", s.zuFrueh(bild + 60));
        assertTrue(s.zuFrueh(bild + MessageOverlayService.TIPP_SPERRE_MS - 1));
        assertFalse("nach 0,7 s zählt der Tipp", s.zuFrueh(bild + MessageOverlayService.TIPP_SPERRE_MS));
    }

    /**
     * 0.1.43.17c (E2E 0.1.43.17, 3a): der App-Faden war beschäftigt; der Finger setzte 0,37 s nach dem Sichtbarwerden auf,
     * der Click kam erst 0,33 s später (über 0,7 s nach dem Bild) an. Maßgeblich ist das DOWN, nicht die Verarbeitung.
     */
    @Test
    public void spaetVerarbeiteterTippZaehltNachDerDownZeit() {
        MessageOverlayService.TippSperre s = new MessageOverlayService.TippSperre();
        long bild = 20_000L;
        s.erstesBild(bild);
        s.down(bild + 370);
        assertTrue("DOWN 0,37 s nach dem Bild, Click erst bei 1,1 s: gesperrt", s.klickZuFrueh(bild + 370 + 330 + 400));
        s.down(bild + 410);
        assertTrue(s.klickZuFrueh(bild + 2_000));
        // ein späterer, echter Tipp zählt
        s.down(bild + 900);
        assertFalse(s.klickZuFrueh(bild + 1_250));
        // das DOWN gilt nur für einen Click; ohne DOWN zählt die Verarbeitungszeit
        assertFalse(s.klickZuFrueh(bild + 1_300));
    }

    /** 0.1.43.17c: DOWN noch vor dem ersten Bild bleibt gesperrt, auch wenn das Bild vor der Verarbeitung gemeldet wird. */
    @Test
    public void downVorDemErstenBildBleibtGesperrt() {
        MessageOverlayService.TippSperre s = new MessageOverlayService.TippSperre();
        long addView = 30_000L;
        s.down(addView + 100);                          // Finger schon unten, noch nichts am Display
        s.erstesBild(addView + 600);                    // Bildtakt erst danach verarbeitet
        assertTrue(s.klickZuFrueh(addView + 600 + 2_000));
        // ohne bekanntes DOWN, lange nach dem Bild: nie für immer gesperrt
        assertFalse(s.klickZuFrueh(addView + 600 + MessageOverlayService.TIPP_SPERRE_MS));
    }

    /** 0.1.43.17c: Bildzeit aus dem Bildtakt (Nanosekunden, gleiche Uhr wie uptimeMillis), sonst die Uhr beim Rückruf. */
    @Test
    public void bildZeitAusDemBildtakt() {
        assertEquals(5_000L, MessageOverlayService.bildZeitMs(5_000_000_000L, 5_016L));
        assertEquals("andere Uhr: Rückruf-Zeit", 9_000L, MessageOverlayService.bildZeitMs(1_000_000_000L, 9_000L));
        assertEquals("Bildtakt in der Zukunft: Rückruf-Zeit", 5_000L, MessageOverlayService.bildZeitMs(6_000_000_000L, 5_000L));
        assertEquals(3000, MessageOverlayService.BILD_SPAETESTENS_MS);
    }

    @Test
    public void nurOkZaehltAlsGelesen() {
        assertEquals(DialogAusgang.OK, DialogAusgang.ausSchluss("SCHLUSS_OK"));
        assertEquals(DialogAusgang.ABGELAUFEN, DialogAusgang.ausSchluss("SCHLUSS_ABGELAUFEN"));
        assertEquals(DialogAusgang.WEG_GETIPPT, DialogAusgang.ausSchluss("SCHLUSS_WEG"));
        assertEquals(DialogAusgang.VERDRAENGT, DialogAusgang.ausSchluss("SCHLUSS_VERDRAENGT"));
        assertEquals("kein Bescheid", DialogAusgang.UNBEKANNT, DialogAusgang.ausSchluss(""));
        assertEquals(DialogAusgang.UNBEKANNT, DialogAusgang.ausSchluss(null));
        assertFalse(DialogAusgang.NICHT_GEZEIGT.gezeigt());
        assertTrue(DialogAusgang.ABGELAUFEN.gezeigt());
    }

    @Test
    public void dialogPfadVerdrahtet() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/overlay/MessageOverlayService.java"));
        int von = s.indexOf("private boolean showDialog(Intent intent)");
        int bis = s.indexOf("// ── Overlay plumbing");
        assertTrue(von > 0 && bis > von);
        String dialog = s.substring(von, bis);
        // OK und daneben: erst die Sperre, dann Bescheid an den Kern
        assertEquals("OK und Abdunklung prüfen die Sperre", 2, zaehle(dialog, "if (dialogTippZuFrueh()) return;"));
        assertTrue(dialog.contains("meldeSchluss(\"SCHLUSS_OK\")"));
        // 0.1.43.17: je Dialog eine neue Sperre, gestartet beim ersten Bild
        assertTrue(dialog.contains("TippSperre sperre = new TippSperre();"));
        assertTrue(dialog.contains("FrameLayout scrim = dialogScrim(ctx, sperre);"));
        assertTrue(dialog.contains("beimErstenBild(scrim, sperre);"));
        // 0.1.43.17c: Ereigniszeit des DOWN, Click prüft sie; Bildzeit aus dem Bildtakt; Sicherheitsnetz
        assertTrue(s.contains("sperre.down(ev.getEventTime());"));
        assertTrue(s.contains("ACTION_DOWN"));
        assertTrue(s.contains("dialogSperre.klickZuFrueh(android.os.SystemClock.uptimeMillis())"));
        assertTrue(s.contains("sperre.erstesBild(bildZeitMs(f, android.os.SystemClock.uptimeMillis()))"));
        assertTrue(s.contains("BILD_SPAETESTENS_MS);"));
        assertTrue(s.contains("addOnDrawListener(") && s.contains("removeOnDrawListener(") && s.contains("postFrameCallback("));
        assertFalse("nicht mehr ab addView", s.contains("dialogSeitMs"));
        assertTrue(dialog.contains("meldeSchluss(\"SCHLUSS_WEG\")"));
        // Selbstschließen: Bescheid „abgelaufen“, Fenster leer, Tipps schlucken, erst nach dem Nachlauf entfernen
        assertTrue(dialog.contains("autoDismiss = () -> selbstSchliessen(scrim);"));
        assertTrue(dialog.contains("meldeSchluss(\"SCHLUSS_ABGELAUFEN\")"));
        assertTrue(dialog.contains("scrim.setOnClickListener(v -> { });"));
        assertTrue(dialog.contains("handler.postDelayed(autoDismiss, NACHLAUF_MS);"));
        assertFalse("nicht mehr sofort weg", dialog.contains("if (timeoutSec > 0) scheduleDismiss(timeoutSec * 1000L);"));
        // verdrängt oder beendet: der Kern erfährt es
        assertTrue(s.contains("meldeSchluss(\"SCHLUSS_VERDRAENGT\")"));
        String c = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/MessageOverlayController.java"));
        assertTrue(c.contains("--ez schlussAck true"));
    }

    private static int zaehle(String s, String teil) {
        int n = 0;
        for (int i = s.indexOf(teil); i >= 0; i = s.indexOf(teil, i + 1)) n++;
        return n;
    }
}
