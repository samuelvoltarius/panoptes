package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.auth.PcZugang;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.38.9 „schlafend“ (Entscheid Projektinhaber): Mit Schalter aus (Standard) lehnen ALLE neuen PC-Wege ab, es gibt keinen neuen
 * Port, Dienst oder Netzverkehr. Mit Schalter an, aber ohne Server-Teil und Handy-Teil, entsteht höchstens ein Code, der
 * ins Leere läuft – und gemeldet wird.
 */
public class PcSchlafendTest {

    static boolean an;

    static final class Wirkung implements BesitzerApiHandler.Wirkung {
        int codes;
        final java.util.List<String> meldungen = new java.util.ArrayList<>();
        @Override public String code(long now, String art, java.util.List<String> rechte) { codes++; return "ABCD2345"; }
        @Override public String code(long now, String art, java.util.List<String> rechte, long dauerMs) { codes++; return "ABCD2345"; }
        @Override public JSONObject brueckeEinmal(long bis) { return new JSONObject(); }
        @Override public void melde(String text) { meldungen.add(text); }
        // pcErlaubt() bewusst NICHT ersetzt: echte Quelle PcZugang (hier mit Test-Schalter)
    }

    private Kopplungen k;
    private Wirkung w;
    private String besitzer;

    @Before
    public void auf() {
        an = false;
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return an; }
            public int tagesMb() { return PcZugang.TAGES_MB; }
        };
        PcZugang.leeren();
        Kopplungen.besitzerMelder = null;
        k = new Kopplungen(new BesitzerHandyTest.Mem());
        besitzer = k.anlegen("Projektinhaber", "handy", 1L, true);
        BesitzerApiHandler.kopplungen = k;
        w = new Wirkung();
        BesitzerApiHandler.wirkung = w;
        BesitzerApiHandler.ratenlimitLeeren();
    }

    @After
    public void ab() {
        BesitzerApiHandler.kopplungen = null;
        BesitzerApiHandler.wirkung = new BesitzerApiHandler.Wirkung() { };
        BesitzerApiHandler.ratenlimitLeeren();
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
    }

    private JSONObject pcCode() throws Exception {
        return BesitzerApiHandler.antwort("POST", BesitzerApiHandler.CODE, BesitzerSignaturHilfe.signiert(k, besitzer,
                BesitzerApiHandler.CODE, new JSONObject().put("art", "pc").put("bestaetigt", true)
                        .put("rechte", new org.json.JSONArray().put("web")), 10_000L).toString(), besitzer, 10_000L);
    }

    @Test
    public void schalterAusAlleNeuenPcWegeLehnenAb() throws Exception {
        // 1) Kopplungs-Code für einen PC
        JSONObject r = pcCode();
        assertEquals(409, r.optInt("_status"));
        assertEquals("pc_aus", r.optString("grund"));
        assertEquals("kein Code entstanden", 0, w.codes);
        // 2) Manifest der Web-Dateien
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(WebManifest.handle("GET", WebManifest.PFAD, out));
        String antwort = out.toString("UTF-8");
        assertTrue(antwort, antwort.startsWith("HTTP/1.1 403"));
        assertTrue(antwort.contains("pc_aus"));
        // 3) Kopplung Art pc entsteht nicht, auch nicht über einen alten gebundenen Code
        assertEquals(null, k.anlegenGebunden("PC", Kopplungen.ART_PC, java.util.Collections.singletonList("web"), 2L, 0L));
        // 4) PC-Pfade unter /api/handy/v1 gibt es nur für eine gültige PC-Kopplung (die es nicht gibt)
        assertTrue(k.pcIds().isEmpty());
    }

    @Test
    public void schalterStandardAusNurAmDisplay() {
        at.greenchili.surfmate.einstellungen.Feld f = null;
        for (at.greenchili.surfmate.einstellungen.Feld x : at.greenchili.surfmate.einstellungen.Katalog.felder(
                new at.greenchili.surfmate.einstellungen.Katalog.Kontext())) {
            if (x.key.equals(PcZugang.SCHALTER)) f = x;
        }
        assertEquals(Boolean.FALSE, f.standard);
        assertTrue("0.1.38.9: in der Oberfläche noch nicht sichtbar", f.verborgen);
        assertTrue(f.nieExtern);
        for (at.greenchili.surfmate.einstellungen.Einstellungen.Stufe s : new at.greenchili.surfmate.einstellungen.Einstellungen.Stufe[]{
                at.greenchili.surfmate.einstellungen.Einstellungen.Stufe.BESITZER,
                at.greenchili.surfmate.einstellungen.Einstellungen.Stufe.KOPPLUNG,
                at.greenchili.surfmate.einstellungen.Einstellungen.Stufe.KEIN_RECHT}) {
            assertTrue(s + " darf nicht einschalten", at.greenchili.surfmate.einstellungen.Einstellungen.sperre(f, s) != null);
        }
    }

    @Test
    public void schalterAnOhneServerUndHandyLaeuftInsLeereUndWirdGemeldet() throws Exception {
        an = true;
        PcZugang.vergessen();
        JSONObject r = pcCode();
        assertTrue(r.toString(), r.optBoolean("ok"));
        assertEquals("Code wird dem Besitzer gemeldet", 1, w.meldungen.size());
        // Ohne PC-Hülle löst niemand den Code ein; er verfällt nach 10 min (PairingCodes.TTL_MS). Keine PC-Kopplung.
        assertTrue(k.pcIds().isEmpty());
    }

    @Test
    public void keinNeuerPortKeinDienstKeinNetzInDenNeuenKlassen() throws Exception {
        for (String datei : new String[]{"auth/PcZugang.java", "server/WebManifest.java"}) {
            String q = new String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/surfmate/" + datei)),
                    StandardCharsets.UTF_8);
            for (String verboten : new String[]{"ServerSocket", "new Socket(", "HttpURLConnection", "URL(", "new Thread(",
                    "Executors.", "Timer(", "DatagramSocket", "WebSocket"}) {
                assertFalse(datei + " enthält " + verboten, q.contains(verboten));
            }
        }
    }
}
