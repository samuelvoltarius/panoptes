package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 0.1.43.17 (E2E 0.1.43.16, 5b und Verdrängung): Bei offenem Frage-Dialog prüft der Kern etwa jede Sekunde, ob das Auto
 * noch sicher steht, und schließt ihn sonst sofort. Höchstens ein Frage-Dialog zugleich.
 */
public class DialogStandPruefungTest {

    private static ServerSocket server() throws Exception {
        ServerSocket s = new ServerSocket();
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        return s;
    }

    @Test
    public void losgefahrenSchliesstDenDialogInnerhalbEinerSekunde() throws Exception {
        try (ServerSocket s = server()) {
            AtomicBoolean steht = new AtomicBoolean(true);
            AtomicInteger geschlossen = new AtomicInteger();
            long start = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                steht.set(false);                              // jetzt fährt das Auto
            }).start();
            String r = MessageOverlayController.schlussLesen(s, "tok", 200_000, steht::get, geschlossen::incrementAndGet);
            long dauer = System.currentTimeMillis() - start;
            assertEquals(MessageOverlayController.ABBRUCH_FAHRT, r);
            assertEquals("Dialog zu", 1, geschlossen.get());
            assertTrue("spätestens ca. 1 s nach dem Losfahren, war " + dauer + " ms", dauer < 1200 + 2 * MessageOverlayController.STAND_PRUEF_MS);
        }
    }

    @Test
    public void standFehlerSchliesstSofort() throws Exception {
        try (ServerSocket s = server()) {
            AtomicInteger geschlossen = new AtomicInteger();
            String r = MessageOverlayController.schlussLesen(s, "tok", 200_000,
                    () -> { throw new IllegalStateException("kein Wert"); }, geschlossen::incrementAndGet);
            assertEquals(MessageOverlayController.ABBRUCH_FAHRT, r);
            assertEquals(1, geschlossen.get());
        }
    }

    @Test
    public void imStandKommtDerSchlussBescheidDurch() throws Exception {
        try (ServerSocket s = server()) {
            int port = s.getLocalPort();
            new Thread(() -> {
                try {
                    Thread.sleep(1500);                        // nach mindestens einer Stand-Prüfung
                    try (Socket c = new Socket("127.0.0.1", port)) {
                        DataOutputStream o = new DataOutputStream(c.getOutputStream());
                        o.writeUTF("tok");
                        o.writeUTF("SCHLUSS_OK");
                        o.writeUTF("");
                        o.flush();
                    }
                } catch (Exception ignored) {
                }
            }).start();
            AtomicInteger geschlossen = new AtomicInteger();
            String r = MessageOverlayController.schlussLesen(s, "tok", 10_000, () -> true, geschlossen::incrementAndGet);
            assertEquals("SCHLUSS_OK", r);
            assertEquals(0, geschlossen.get());
        }
    }

    @Test
    public void ohneStandPruefungWieBisherBisZumEnde() throws Exception {
        try (ServerSocket s = server()) {
            assertEquals("", MessageOverlayController.schlussLesen(s, "tok", 300, null, () -> { }));
        }
    }

    @Test
    public void zweiterFrageDialogIstBelegtUndZeigtNichts() throws Exception {
        CountDownLatch gehalten = new CountDownLatch(1);
        CountDownLatch los = new CountDownLatch(1);
        Thread anderer = new Thread(() -> {
            MessageOverlayController.FRAGE.lock();             // ein anderer Frage-Dialog ist offen
            try {
                gehalten.countDown();
                los.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } finally {
                MessageOverlayController.FRAGE.unlock();
            }
        });
        anderer.start();
        assertTrue(gehalten.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(MessageOverlayController.DialogAusgang.BELEGT,
                    MessageOverlayController.showDialogMitAusgang("T", "Text", "OK", "info", 180, () -> true));
        } finally {
            los.countDown();
            anderer.join(5000);
        }
        assertTrue(MessageOverlayController.DialogAusgang.BELEGT.ohneVersuch());
        assertTrue(MessageOverlayController.DialogAusgang.ABBRUCH_FAHRT.ohneVersuch());
        assertTrue(MessageOverlayController.DialogAusgang.VERDRAENGT.ohneVersuch());
        assertTrue(!MessageOverlayController.DialogAusgang.ABBRUCH_FAHRT.gezeigt());
        assertTrue(MessageOverlayController.DialogAusgang.ABGELAUFEN.gezeigt());
    }
}
