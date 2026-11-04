package at.greenchili.surfmate.handy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.ble.BleNaehe;
import at.greenchili.surfmate.byd.routing.FahrzeugAktionen;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 0.1.38.8 Aufsperren beim Annähern: alle Bedingungen, einmal je Annäherung, Wiederzusperren mit Zurücklesen,
 * Selbst-Abschaltung, Scan-Ratenlimit, BLE-Bake. Reiner JVM-Test – gefälschtes Auto, Bluetooth und Router,
 * kein Befehl erreicht SDK oder Cloud (REGEL A+ für Tests).
 */
public class AnnaeherungTest {

    // Testort ohne Bezug zu einem echten Parkplatz (Nullinsel), Abstände nur relativ
    static final double LAT = 0.5, LON = 0.5;

    long jetzt = 1_800_000_000_000L;
    JSONObject config, aktionen;
    List<String> gemeldetAktionen;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    KeyPair naehe;
    String kid;
    byte[] bake = new byte[32];
    int nonceNr;

    // gefälschtes Auto
    boolean autoAus = true, waechter = false, zusperrenWirkt = true, bleAn = true, bakeSichtbar = true;
    int schloss = 1, tuerOffenAb = -1, warteSchritte;
    double[] autoPos = {LAT, LON, 8};
    int rssi = -60;
    final List<String> scans = new ArrayList<>(), gemeldet = new ArrayList<>(), befehle = new ArrayList<>();

    final Annaeherung.Umgebung u = new Annaeherung.Umgebung() {
        public long jetzt() { return jetzt; }
        public double[] autoPosition() { return autoPos; }
        public boolean autoAus() { return autoAus; }
        public int schloss() { return schloss; }
        public boolean waechterEreignis() { return waechter; }
        public JSONObject bleSuchen(List<String> baken) {
            scans.add(String.join(",", baken));
            try {
                if (!bleAn) return new JSONObject().put("ok", false).put("zustand", "bt_aus");
                boolean passt = bakeSichtbar && baken.contains(BleNaehe.bake(bake, jetzt));
                return new JSONObject().put("ok", true).put("gesehen", passt && rssi >= BleNaehe.RSSI_MIN).put("rssi", rssi);
            } catch (Exception e) {
                return null;
            }
        }
        public int tuerOffen() { return tuerOffenAb >= 0 && warteSchritte >= tuerOffenAb ? 1 : 0; }
        public VehicleCommandRouter.CommandResult aufsperren(HandySignatur.Freigabe f, String wer) {
            befehle.add("auf:" + f.kopplungId);
            schloss = 0;
            return VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 1);
        }
        public VehicleCommandRouter.CommandResult zusperren() {
            befehle.add("zu");
            if (zusperrenWirkt) schloss = 1;
            return VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 1);
        }
        public void melden(String t, String p, Object... a) { gemeldet.add(t); }
        public void spaeter(Runnable r) { r.run(); }
        public void warte(long ms) {
            warteSchritte++;
            jetzt += ms;
        }
    };

    @Before
    public void auf() throws Exception {
        Annaeherung.zuruecksetzen();
        // 0.1.38.8: naeheBake liegt nur verschlüsselt; in der JVM fehlt android.util.Base64 für CredentialCipher
        Kopplungen.bakeCipherFuerTest(s -> "ENC:" + new StringBuilder(s).reverse(),
                s -> s.startsWith("ENC:") ? new StringBuilder(s.substring(4)).reverse().toString() : "");
        aktionen = new JSONObject().put("aufsperren", true).put("annaeherung", true);
        config = new JSONObject().put("fahrzeugAktionen", aktionen);
        gemeldetAktionen = FahrzeugAktionen.fuerTest(config, () -> jetzt);
        kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        kid = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        kopplungen.rechteSetzen(kid, Kopplungen.RECHTE);
        kopplungen.schluesselMelden(kid, pub(paar()), "android", "Pixel 8");
        naehe = paar();
        for (int i = 0; i < bake.length; i++) bake[i] = (byte) (i * 7 + 3);
        assertTrue(kopplungen.naeheSetzen(kid, pub(naehe), jetzt + 30L * 86_400_000L,
                Base64.getEncoder().encodeToString(bake)));
    }

    @After
    public void ab() {
        FahrzeugAktionen.fuerTest(null);
        Annaeherung.zuruecksetzen();
        Kopplungen.bakeCipherFuerTest(null, null);
    }

    static KeyPair paar() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String pub(KeyPair p) {
        return Base64.getEncoder().encodeToString(p.getPublic().getEncoded());
    }

    /** Meldung so, wie die Handy-App sie baut (ANNAEHERUNG_VERTRAG §3). */
    JSONObject meldung(double lat, double lon, double acc, KeyPair mit, double signLat) throws Exception {
        String nonce = "nonce-an-" + String.format("%06d", ++nonceNr);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(mit.getPrivate());
        s.update(HandySignatur.nachricht(Annaeherung.signierteAktion(signLat, lon, acc), kid, jetzt, nonce)
                .getBytes(StandardCharsets.UTF_8));
        JSONObject sig = new JSONObject().put("zeit", jetzt).put("nonce", nonce)
                .put("sig", Base64.getEncoder().encodeToString(s.sign()));
        return new JSONObject().put("aktion", "annaeherung").put("lat", lat).put("lon", lon).put("genauigkeitM", acc)
                .put("signatur", sig);
    }

    JSONObject melden() throws Exception {
        return Annaeherung.melden(meldung(LAT + 0.0001, LON, 6, naehe, LAT + 0.0001), kid, kopplungen, u);
    }

    String status() throws Exception {
        return Annaeherung.stand(kid).optString("status");
    }

    // ------------------------------------------------------------------ der gute Fall

    @Test
    public void allesStimmtAufUndNach60sWiederZu() throws Exception {
        JSONObject r = melden();
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("pruefe", r.getString("status"));
        assertEquals(1, scans.size());
        assertEquals("[auf:" + kid + ", zu]", befehle.toString());
        assertEquals(Annaeherung.WIEDER_ZU, status());
        assertEquals("[aktionen.annaeherung_auf, aktionen.annaeherung_wieder_zu]", gemeldet.toString());
        assertTrue("erst nach 60 s zu", warteSchritte * Annaeherung.TUER_TAKT_MS >= Annaeherung.ZU_NACH_MS);
        assertTrue(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
    }

    @Test
    public void tuerGeoeffnetBleibtOffen() throws Exception {
        tuerOffenAb = 5;
        melden();
        assertEquals("[auf:" + kid + "]", befehle.toString());
        assertEquals(Annaeherung.TUER_OFFEN, status());
    }

    @Test
    public void zusperrenScheitertMeldetOffenUndSchaltetAb() throws Exception {
        zusperrenWirkt = false;
        melden();
        assertEquals("[auf:" + kid + ", zu]", befehle.toString());
        assertEquals(Annaeherung.OFFEN, status());
        assertTrue(gemeldet.contains("aktionen.offen"));
        assertFalse("Automatik aus", FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        assertTrue("Handy-Aufsperren bleibt", FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));
        assertTrue(gemeldetAktionen.contains("aktionen.automatik_aus"));
    }

    // ------------------------------------------------------------------ jede Bedingung sperrt

    void abgelehnt(String grund) throws Exception {
        JSONObject r = melden();
        // Gründe nach dem Scan stehen im Stand (die Sofort-Antwort ist dann „pruefe“)
        String g = r.has("grund") ? r.optString("grund") : Annaeherung.stand(kid).optString("grund");
        assertEquals(r.toString(), grund, g);
        assertTrue("nichts aufgesperrt bei " + grund, befehle.isEmpty());
        jetzt += Annaeherung.SCAN_ABSTAND_MS + 1;
        Annaeherung.zuruecksetzen();
    }

    @Test
    public void jedeBedingungSperrt() throws Exception {
        autoAus = false;
        abgelehnt("auto_an");
        autoAus = true;
        waechter = true;
        abgelehnt("waechter_ereignis");
        waechter = false;
        schloss = 0;
        abgelehnt("nicht_verriegelt");
        schloss = -1;
        abgelehnt("schloss_unbekannt");
        schloss = 1;
        autoPos = null;
        abgelehnt("auto_ort_unbekannt");
        autoPos = new double[] {LAT, LON, 80};
        abgelehnt("auto_ort_ungenau");
        autoPos = new double[] {LAT + 0.0005, LON, 5};   // ~55 m
        abgelehnt("zu_weit");
        autoPos = new double[] {LAT, LON, 5};
        bakeSichtbar = false;
        abgelehnt("nicht_in_der_naehe");
        bakeSichtbar = true;
        rssi = -90;
        abgelehnt("nicht_in_der_naehe");
        rssi = -60;
        bleAn = false;
        abgelehnt("ble_bt_aus");
        assertTrue("Ort, Schloss, Wächter vor dem Scan geprüft",
                scans.size() == 3);
    }

    @Test
    public void ungenauesHandy() throws Exception {
        JSONObject r = Annaeherung.melden(meldung(LAT, LON, 35, naehe, LAT), kid, kopplungen, u);
        assertEquals("ungenau", r.getString("grund"));
        assertTrue(scans.isEmpty());
    }

    @Test
    public void schalterAusOderOhneRecht() throws Exception {
        aktionen.put("annaeherung", false);
        assertEquals("aktion_aus", melden().getString("grund"));
        aktionen.put("annaeherung", true).put("aufsperren", false);
        assertEquals("aktion_aus", melden().getString("grund"));
        aktionen.put("aufsperren", true);
        kopplungen.rechteSetzen(kid, new ArrayList<>());
        assertEquals("nicht_erlaubt", melden().getString("grund"));
        assertTrue(scans.isEmpty());
        assertTrue(befehle.isEmpty());
    }

    // ------------------------------------------------------------------ einmal je Annäherung

    @Test
    public void einmalJeAnnaeherung() throws Exception {
        melden();
        assertEquals(2, befehle.size());
        jetzt += 60_000L;
        schloss = 1;
        assertEquals("schon_ausgeloest", melden().getString("grund"));
        assertEquals(2, befehle.size());
        // 10 min Ruhe dieser Kopplung → wieder scharf
        jetzt += Annaeherung.NEU_SCHARF_MS + 1;
        befehle.clear();
        melden();
        assertEquals(2, befehle.size());
        // Zündung an → wieder scharf
        jetzt += 60_000L;
        Annaeherung.zuendungAn();
        befehle.clear();
        melden();
        assertEquals(2, befehle.size());
    }

    @Test
    public void scanRatenlimit() throws Exception {
        autoPos = new double[] {LAT + 0.001, LON, 5};
        melden();
        jetzt += 5_000L;
        assertEquals("ratenlimit", melden().getString("grund"));
        jetzt += Annaeherung.SCAN_ABSTAND_MS;
        assertEquals("zu_weit", melden().getString("grund"));
        for (int i = 0; i < Annaeherung.SCANS_JE_STUNDE; i++) {
            jetzt += Annaeherung.SCAN_ABSTAND_MS;
            melden();
        }
        jetzt += Annaeherung.SCAN_ABSTAND_MS;
        assertEquals("höchstens 20 je Stunde", "ratenlimit", melden().getString("grund"));
    }

    // ------------------------------------------------------------------ Signatur und Selbst-Abschaltung

    @Test
    public void standortIstMitsigniert() throws Exception {
        JSONObject r = Annaeherung.melden(meldung(LAT, LON, 6, naehe, LAT + 0.01), kid, kopplungen, u);
        assertEquals("signatur", r.getString("grund"));
        assertTrue(befehle.isEmpty());
    }

    @Test
    public void dreiFalscheSignaturenSchaltenAb() throws Exception {
        KeyPair fremd = paar();
        for (int i = 0; i < Annaeherung.FEHLVERSUCHE; i++) {
            assertEquals("signatur", Annaeherung.melden(meldung(LAT, LON, 6, fremd, LAT), kid, kopplungen, u)
                    .getString("grund"));
            jetzt += Annaeherung.SCAN_ABSTAND_MS + 1;
        }
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        assertTrue(gemeldetAktionen.contains("aktionen.automatik_aus"));
        assertTrue(befehle.isEmpty());
    }

    @Test
    public void gueltigeSignaturSetztFehlversucheZurueck() throws Exception {
        KeyPair fremd = paar();
        for (int i = 0; i < 2; i++) {
            Annaeherung.melden(meldung(LAT, LON, 6, fremd, LAT), kid, kopplungen, u);
            jetzt += Annaeherung.SCAN_ABSTAND_MS + 1;
        }
        autoPos = new double[] {LAT + 0.001, LON, 5};
        melden();   // gültig, aber zu weit
        jetzt += Annaeherung.SCAN_ABSTAND_MS + 1;
        Annaeherung.melden(meldung(LAT, LON, 6, fremd, LAT), kid, kopplungen, u);
        assertTrue(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
    }

    @Test
    public void abgelaufenerSchluesselSchaltetAb() throws Exception {
        jetzt += 31L * 86_400_000L;
        assertEquals("kein_naehe_schluessel", melden().getString("grund"));
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        assertEquals(0, kopplungen.naeheBis(kid));
    }

    @Test
    public void kopplungEntferntSchaltetAb() {
        kopplungen.widerrufen(kid);
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
    }

    @Test
    public void geraeteSchluesselWegNimmtNaeheMit() throws Exception {
        kopplungen.schluesselLoeschen(kid);
        assertNull(kopplungen.naeheSchluessel(kid, jetzt));
        assertEquals("kein_naehe_schluessel", melden().getString("grund"));
    }

    @Test
    public void oberflaecheSiehtNieGeheimnisse() throws Exception {
        String alle = kopplungen.alle(jetzt).toString();
        assertTrue(alle.contains("\"naehe\":true"));
        assertFalse(alle.contains(Base64.getEncoder().encodeToString(bake)));
        assertFalse(alle.contains(pub(naehe)));
    }

    // ------------------------------------------------------------------ BLE-Bake

    @Test
    public void bake() throws Exception {
        String a = BleNaehe.bake(bake, jetzt);
        assertTrue(a.matches("[0-9a-f]{16}"));
        assertEquals("gleiches 30-s-Fenster", a, BleNaehe.bake(bake, jetzt - jetzt % 30_000L));
        assertNotEquals(a, BleNaehe.bake(bake, jetzt + 30_000L));
        assertEquals(3, BleNaehe.erwartet(bake, jetzt).size());
        assertTrue(BleNaehe.erwartet(bake, jetzt).contains(BleNaehe.bake(bake, jetzt - 30_000L)));
        assertNull(BleNaehe.bake(new byte[4], jetzt));
        // nur Hex in die Shell
        List<String> boese = new ArrayList<>();
        boese.add("00;reboot;0000000");
        assertNull(BleNaehe.amBefehl(boese));
        assertTrue(BleNaehe.amBefehl(BleNaehe.erwartet(bake, jetzt)).startsWith("am broadcast -a " + BleNaehe.AKTION));
    }

    @Test
    public void bleAntwortGefiltert() {
        JSONObject r = BleNaehe.ausAmAusgabe("Broadcasting: Intent\nBroadcast completed: result=0, data=\""
                + "{\"ok\":true,\"gesehen\":true,\"rssi\":-80,\"adresse\":\"AA:BB\"}\"");
        assertFalse("zu schwach", r.optBoolean("gesehen"));
        assertFalse(r.has("adresse"));
        r = BleNaehe.ausAmAusgabe("Broadcast completed: result=0, data=\"{\"ok\":true,\"gesehen\":true,\"rssi\":-50}\"");
        assertTrue(r.optBoolean("gesehen"));
        assertNull(BleNaehe.ausAmAusgabe("Broadcast completed: result=0"));
    }

    @Test
    public void abstand() {
        double m = Annaeherung.abstandM(LAT, LON, LAT + 0.0002, LON);
        assertTrue(String.valueOf(m), m > 20 && m < 25);
    }
}
