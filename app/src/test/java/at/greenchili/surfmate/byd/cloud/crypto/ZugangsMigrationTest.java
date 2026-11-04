package at.greenchili.surfmate.byd.cloud.crypto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** 0.1.38.6 Sicherheitsfix: gespeicherte Zugangsdaten nur noch über CredentialCipher, Bestand wird migriert. */
public class ZugangsMigrationTest {

    /** Attrappe: "ENC:gut…" ist lesbar, "ENC:kaputt" nicht. */
    static final UnaryOperator<String> ENT = s -> s.startsWith("ENC:gut") ? "klar" : "";
    static final Predicate<String> NIE_ALT = s -> false;

    @Test
    public void klartextWirdMigriert() {
        assertTrue(ZugangsMigration.noetig(Arrays.asList("geheim", ""), ENT, NIE_ALT, true));
        assertTrue(ZugangsMigration.noetig(Arrays.asList("ENC:gut1", "geheim"), ENT, NIE_ALT, true));
    }

    @Test
    public void schonVerschluesseltOderLeerBleibtUnangetastet() {
        assertFalse(ZugangsMigration.noetig(Arrays.asList("ENC:gut1", "ENC:gut2"), ENT, NIE_ALT, true));
        assertFalse(ZugangsMigration.noetig(Arrays.asList("", null), ENT, NIE_ALT, true));
    }

    @Test
    public void alterFirmwareSchluesselWirdUmgeschluesselt() {
        assertTrue(ZugangsMigration.noetig(Arrays.asList("ENC:gut-alt"), ENT, s -> s.endsWith("-alt"), true));
    }

    @Test
    public void ohneGeraeteIdKeinNeuschreiben() {
        // encrypt() fiele auf Klartext zurück: nichts gewonnen
        assertFalse(ZugangsMigration.noetig(Arrays.asList("geheim"), ENT, NIE_ALT, false));
    }

    @Test
    public void unlesbarerWertWirdNieUeberschrieben() {
        // Speichern würde ENC:kaputt mit "" ersetzen; bei Rückkehr der Geräte-Id wäre er sonst verloren
        assertFalse(ZugangsMigration.noetig(Arrays.asList("geheim", "ENC:kaputt"), ENT, NIE_ALT, true));
    }

    @Test
    public void klartextErkennung() {
        assertTrue(ZugangsMigration.klartext("abc"));
        assertFalse(ZugangsMigration.klartext("ENC:abc"));
        assertFalse(ZugangsMigration.klartext(""));
        assertFalse(ZugangsMigration.klartext(null));
    }

    @Test
    public void abrpBehaeltKlartextUndSchreibtSchatten() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/abrp/AbrpConfig.java"));
        assertTrue("Klartext bleibt (Rückfall 0.1.38.5)", q.contains("props.setProperty(PROP_USER_TOKEN, userToken);"));
        assertTrue(q.contains("props.setProperty(PROP_API_KEY, apiKey);"));
        assertTrue(q.contains("schattenSetzen(props, PROP_USER_TOKEN, userToken);"));
        assertTrue(q.contains("schattenSetzen(props, PROP_API_KEY, apiKey);"));
        assertTrue(q.contains("ZugangsMigration.schatten(rohToken, props.getProperty(ZugangsMigration.schattenSchluessel(PROP_USER_TOKEN))) != null"));
        assertFalse("nie ENC: in den Klartext-Schlüssel", q.contains("props.setProperty(PROP_USER_TOKEN, CredentialCipher.encrypt"));
    }

    @Test
    public void mqttMigriertBestandBeimLaden() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionStore.java"));
        assertTrue(s.contains("rohwerte.add(obj.optString(\"username\", \"\"));"));
        assertTrue(s.contains("rohwerte.add(obj.optString(\"password\", \"\"));"));
        assertTrue(s.contains("if (ZugangsMigration.noetig(rohwerte)) {"));
        String c = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionConfig.java"));
        assertTrue(c.contains("json.put(\"username\", CredentialCipher.encrypt(username));"));
        assertTrue(c.contains("json.put(\"password\", CredentialCipher.encrypt(password));"));
    }

    @Test
    public void planerLiestVerschluesseltenUndAltenMitgliedsSchluessel() throws Exception {
        String klar = "0123456789abcdef0123456789abcdef";
        org.json.JSONObject enc = new org.json.JSONObject().put("memberToken", "ENC:x");
        String[] k = at.greenchili.surfmate.planer.PlanerSchluessel.kopf(enc, g -> "ENC:x".equals(g) ? klar : "");
        org.junit.Assert.assertEquals(klar, k[1]);
        String[] alt = at.greenchili.surfmate.planer.PlanerSchluessel.kopf(
                new org.json.JSONObject().put("memberToken", klar), g -> "");
        org.junit.Assert.assertEquals(klar, alt[1]);
        org.junit.Assert.assertNull(at.greenchili.surfmate.planer.PlanerSchluessel.kopf(enc, g -> ""));
    }

    @Test
    public void lesestellenEntschluesseln() throws Exception {
        String cz = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/community/CommunityZugang.kt"));
        assertTrue(cz.contains("CredentialCipher.decrypt(roh)"));
        String up = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java"));
        assertTrue(up.contains("\"token\".equals(key) ? at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher.decrypt(v) : v"));
        String byd = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudConfig.java"));
        assertTrue(byd.contains("entschluessle.apply(bydCloud.optString(\"loginKey\", \"\"))"));
        assertTrue(byd.contains("return ausAbschnitt(bydCloud, rawPassword, CredentialCipher::decrypt);"));
        assertTrue("Klartext bleibt (Rückfall 0.1.38.5)", byd.contains("bydCloud.put(SCHLUESSEL_FELDER[i], klar[i]);"));
        assertTrue(byd.contains("bydCloud.put(ZugangsMigration.schattenSchluessel(SCHLUESSEL_FELDER[i]),"));
        String cd = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue(cd.contains("ZugangsMigration.bestandVerschluesseln()"));
    }

    @Test
    public void verschluesseltNurMitBestandenerRueckpruefung() {
        UnaryOperator<String> ver = v -> "ENC:" + v;
        UnaryOperator<String> ent = v -> v.substring(4);
        org.junit.Assert.assertEquals("ENC:geheim", CredentialUpgrade.geprueftVerschluesselt("geheim", ver, ent));
        // encrypt() fiel auf Klartext zurück → nichts schreiben
        org.junit.Assert.assertNull(CredentialUpgrade.geprueftVerschluesselt("geheim", v -> v, ent));
        // Rückprüfung liefert etwas anderes (z. B. Geräte-Id wechselte dazwischen) → Klartext bleibt
        org.junit.Assert.assertNull(CredentialUpgrade.geprueftVerschluesselt("geheim", ver, v -> ""));
        // schon verschlüsselt oder leer → nichts zu tun
        org.junit.Assert.assertNull(CredentialUpgrade.geprueftVerschluesselt("ENC:x", ver, ent));
        org.junit.Assert.assertNull(CredentialUpgrade.geprueftVerschluesselt("", ver, ent));
    }

    // ---------------------------------------------------------------- Schatten (0.1.38.6, Rückfall-sicher)

    static final UnaryOperator<String> VER = v -> "ENC:" + new StringBuilder(v).reverse();
    static final UnaryOperator<String> DEC = v -> v.startsWith("ENC:") ? new StringBuilder(v.substring(4)).reverse().toString() : v;

    @Test
    public void schattenNeuWennFehlt() {
        org.junit.Assert.assertEquals("ENC:cba", ZugangsMigration.schatten("abc", "", VER, DEC));
        org.junit.Assert.assertEquals("ENC:cba", ZugangsMigration.schatten("abc", null, VER, DEC));
    }

    @Test
    public void schattenUnveraendertWennPassend() {
        org.junit.Assert.assertNull(ZugangsMigration.schatten("abc", "ENC:cba", VER, DEC));
    }

    @Test
    public void schattenFolgtAenderungInDerAltenVersion() {
        // Rückfall auf 0.1.38.5, dort Token geändert, wieder 0.1.38.6: Schatten wird nachgezogen
        org.junit.Assert.assertEquals("ENC:uen", ZugangsMigration.schatten("neu", "ENC:cba", VER, DEC));
    }

    @Test
    public void schattenWirdGeleertWennKlartextGeloescht() {
        // in der alten Version gelöschter Zugang darf nicht aus dem Schatten wieder aufleben
        org.junit.Assert.assertEquals("", ZugangsMigration.schatten("", "ENC:cba", VER, DEC));
        org.junit.Assert.assertNull(ZugangsMigration.schatten("", "", VER, DEC));
    }

    @Test
    public void ohneFunktionierendeVerschluesselungKeinSchatten() {
        org.junit.Assert.assertNull(ZugangsMigration.schatten("abc", "", v -> v, DEC));
        org.junit.Assert.assertNull(ZugangsMigration.schatten("abc", "", VER, v -> ""));
        org.junit.Assert.assertNull("ENC: im Klartext-Feld bleibt", ZugangsMigration.schatten("ENC:x", "", VER, DEC));
    }

    @Test
    public void schattenSchluesselSindGeheim() {
        for (String k : new String[]{"enc_memberToken", "enc_token", "enc_loginKey", "enc_signPassword", "enc_commandPwd"}) {
            assertTrue(k, at.greenchili.surfmate.config.Geheimnisse.istGeheim(k));
        }
    }
}
