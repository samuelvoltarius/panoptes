package at.greenchili.surfmate.byd.cloud.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Iterator;
import java.util.function.UnaryOperator;

/**
 * Pflicht 0.1.38.8 (Paket A, Zugangsdaten Teil 2): Nach einem Rückfall auf 0.1.38.6 gehen keine Logins, Kopplungen,
 * Update-Token, Hotspot- oder Brücken-Zugänge verloren. 0.1.38.8 legt für {@code auth.deviceSecret},
 * {@code hotspot.password} und {@code bruecke.json} (a, g) nur Schattenkopien {@code enc_*} an; der Klartext bleibt.
 *
 * <p>Die {@code Leser01386}-Methoden sind die Lesestellen aus rel-0.1.38.6 (f003cb05):
 * {@code AuthManager.loadFromConfig} (optString deviceSecret, sonst „nicht eingerichtet“ → neuer Schlüssel),
 * {@code HotspotManager.applyApConfiguration} (optString password), {@code Bruecke.autoSchluessel}/{@code geheimnis}
 * (optString a/g), {@code AppUpdateDienst} (update.token), {@code Kopplungen} (Abschnitt unverändert).
 */
public class RueckfallAuf01386Test {

    static final UnaryOperator<String> VER = v -> "ENC:" + new StringBuilder(v).reverse();
    static final UnaryOperator<String> DEC = v -> v.startsWith("ENC:") ? new StringBuilder(v.substring(4)).reverse().toString() : "";

    static final class Leser01386 {
        static String geraeteToken(JSONObject root) {
            JSONObject a = root.optJSONObject("auth");
            if (a == null || a.optString("deviceSecret", "").isEmpty()) return null;   // → 0.1.38.6 würde neu erzeugen
            return a.optString("deviceId", "") + "-" + a.optString("deviceSecret", "");
        }

        static String hotspotPasswort(JSONObject root) {
            return root.optJSONObject("hotspot").optString("password", "");
        }

        static String updateToken(JSONObject root) {
            JSONObject u = root.optJSONObject("update");
            return u == null ? null : u.optString("token", null);
        }

        static String bruecke(JSONObject datei, String feld) {
            return datei.optBoolean("ein", false) ? datei.optString(feld) : null;
        }
    }

    private static JSONObject config01386() throws Exception {
        return new JSONObject()
                .put("auth", new JSONObject().put("deviceId", "byd-test01").put("deviceSecret", "geheim88").put("lastAccess", 5))
                .put("hotspot", new JSONObject().put("ssid", "Auto").put("password", "wlan-pass-1").put("enabled", false))
                .put("update", new JSONObject().put("token", "ghp-test-update").put("enc_token", "ENC:etadpu-tset-phg"))
                .put("kopplungen", new JSONObject().put("k1", new JSONObject().put("name", "Handy").put("rechte", "web")));
    }

    private static JSONObject bruecke01386() throws Exception {
        return new JSONObject().put("ein", true).put("a", "privat-a").put("p", "postfach").put("g", "geheim-g")
                .put("handys", new JSONObject().put("k1", new JSONObject().put("h", "pub").put("z", "hash")));
    }

    /** Was {@link ZugangsMigration#bestandVerschluesseln} je Feld schreibt (rein nachgestellt mit Attrappen-Cipher). */
    private static int migriere(JSONObject root) throws Exception {
        int n = 0;
        for (String[] f : ZugangsMigration.CONFIG_FELDER) {
            JSONObject sec = root.optJSONObject(f[0]);
            if (sec == null) continue;
            String sk = ZugangsMigration.schattenSchluessel(f[1]);
            String neu = ZugangsMigration.schatten(sec.optString(f[1], ""), sec.optString(sk, ""), VER, DEC);
            if (neu != null) { sec.put(sk, neu); n++; }
        }
        return n;
    }

    private static boolean migriereBruecke(JSONObject datei) {
        return ZugangsMigration.schattenImObjekt(datei, at.greenchili.surfmate.bruecke.Bruecke.GEHEIME_FELDER, VER, DEC);
    }

    private static void nurSchattenDazu(JSONObject vorher, JSONObject nachher, String wo) throws Exception {
        for (Iterator<String> it = vorher.keys(); it.hasNext(); ) {
            String k = it.next();
            Object a = vorher.get(k), b = nachher.get(k);
            if (a instanceof JSONObject) nurSchattenDazu((JSONObject) a, (JSONObject) b, wo + "." + k);
            else assertEquals(wo + "." + k, String.valueOf(a), String.valueOf(b));
        }
        for (Iterator<String> it = nachher.keys(); it.hasNext(); ) {
            String k = it.next();
            assertTrue(wo + "." + k, vorher.has(k) || k.startsWith(ZugangsMigration.SCHATTEN));
        }
    }

    @Test
    public void neueFelderSindImAbgleich() {
        boolean auth = false, hotspot = false;
        for (String[] f : ZugangsMigration.CONFIG_FELDER) {
            if (f[0].equals("auth") && f[1].equals("deviceSecret")) auth = true;
            if (f[0].equals("hotspot") && f[1].equals("password")) hotspot = true;
        }
        assertTrue(auth);
        assertTrue(hotspot);
    }

    @Test
    public void nachDerMigrationLiest01386AllesUnveraendert() throws Exception {
        JSONObject vorher = config01386(), root = config01386();
        assertEquals(2, migriere(root));   // deviceSecret + hotspot.password; update.token hatte schon den Schatten
        JSONObject bVorher = bruecke01386(), b = bruecke01386();
        assertTrue(migriereBruecke(b));

        assertEquals("byd-test01-geheim88", Leser01386.geraeteToken(root));
        assertEquals("wlan-pass-1", Leser01386.hotspotPasswort(root));
        assertEquals("ghp-test-update", Leser01386.updateToken(root));
        assertEquals("privat-a", Leser01386.bruecke(b, "a"));
        assertEquals("geheim-g", Leser01386.bruecke(b, "g"));
        nurSchattenDazu(vorher, root, "config");
        nurSchattenDazu(bVorher, b, "bruecke");

        assertEquals("geheim88", DEC.apply(root.getJSONObject("auth").getString("enc_deviceSecret")));
        assertEquals("wlan-pass-1", DEC.apply(root.getJSONObject("hotspot").getString("enc_password")));
        assertEquals("privat-a", DEC.apply(b.getString("enc_a")));
        assertEquals("geheim-g", DEC.apply(b.getString("enc_g")));
    }

    @Test
    public void neustartIstIdempotent() throws Exception {
        JSONObject root = config01386();
        JSONObject b = bruecke01386();
        migriere(root);
        migriereBruecke(b);
        String r1 = root.toString(), b1 = b.toString();
        assertEquals(0, migriere(root));
        assertFalse(migriereBruecke(b));
        assertEquals(r1, root.toString());
        assertEquals(b1, b.toString());
    }

    @Test
    public void hinZurueckUndWiederHinVerliertNichts() throws Exception {
        JSONObject root = config01386();
        JSONObject b = bruecke01386();
        migriere(root);
        migriereBruecke(b);
        // Rückfall auf 0.1.38.6: Token neu erzeugt, Hotspot-Passwort gelöscht, Brücke aus und wieder ein
        root.getJSONObject("auth").put("deviceSecret", "neu77777");
        root.getJSONObject("hotspot").put("password", "");
        JSONObject aus = new JSONObject();   // Bruecke.ausschalten() in 0.1.38.6: alles weg
        JSONObject wiederEin = new JSONObject(aus.toString()).put("ein", true).put("a", "privat-2").put("p", "p2")
                .put("g", "geheim-2").put("handys", new JSONObject());
        // wieder 0.1.38.8: Schatten folgen dem Klartext, nichts Altes lebt wieder auf
        migriere(root);
        migriereBruecke(aus);
        migriereBruecke(wiederEin);
        assertEquals("neu77777", DEC.apply(root.getJSONObject("auth").getString("enc_deviceSecret")));
        assertEquals("", root.getJSONObject("hotspot").getString("enc_password"));
        assertFalse(aus.has("enc_a"));
        assertEquals("privat-2", DEC.apply(wiederEin.getString("enc_a")));
        assertEquals("byd-test01-neu77777", Leser01386.geraeteToken(root));
        assertEquals("ghp-test-update", Leser01386.updateToken(root));
        assertEquals("Handy", root.getJSONObject("kopplungen").getJSONObject("k1").getString("name"));
    }

    @Test
    public void ohneGeraeteIdBleibtAllesWie01386() throws Exception {
        JSONObject root = config01386();
        JSONObject b = bruecke01386();
        String vorher = root.toString(), bv = b.toString();
        for (String[] f : ZugangsMigration.CONFIG_FELDER) {
            JSONObject sec = root.optJSONObject(f[0]);
            if (sec == null) continue;
            String neu = ZugangsMigration.schatten(sec.optString(f[1], ""), "", v -> v, DEC);   // encrypt() fail-open
            if (neu != null) sec.put(ZugangsMigration.schattenSchluessel(f[1]), neu);
        }
        assertFalse(ZugangsMigration.schattenImObjekt(b, at.greenchili.surfmate.bruecke.Bruecke.GEHEIME_FELDER, v -> v, DEC));
        // update.token hatte schon einen passenden Schatten; sonst nichts geschrieben
        assertEquals(vorher, root.toString());
        assertEquals(bv, b.toString());
    }

    @Test
    public void speicherwegLeertVeraltetenSchatten() {
        assertEquals("", ZugangsMigration.schattenNeu("", VER, DEC));
        assertEquals("", ZugangsMigration.schattenNeu("neu", v -> v, DEC));   // Verschlüsseln geht gerade nicht
        assertEquals("neu", DEC.apply(ZugangsMigration.schattenNeu("neu", VER, DEC)));
    }

    /**
     * Nachtrag (Koordination): Die IPC-Aufrufer-Prüfung und der Geräteschlüssel-Schalter der Sicherung schreiben
     * nichts in Config oder Daemon-Dateien – nach einem Rückfall auf 0.1.38.6 bleibt davon nichts zurück, und die
     * Aufrufer von 0.1.38.6 (App, SentryDaemon, Telegram, adb shell) nutzen dieselben Befehle und uids.
     */
    @Test
    public void ipcPruefungUndSicherungHinterlassenNichts() throws Exception {
        String ipc = lies("src/main/java/at/greenchili/surfmate/server/IpcAufrufer.java");
        assertFalse(ipc.contains("UnifiedConfigManager"));
        assertFalse(ipc.contains("FileOutputStream") || ipc.contains("Files.write") || ipc.contains("createNewFile"));
        String zugang = lies("src/main/java/at/greenchili/surfmate/server/AuthZugangIpc.java");
        assertFalse(zugang.contains("UnifiedConfigManager.update") || zugang.contains("saveConfig"));
        // Sicherung ohne did: der Import (auch der von 0.1.38.6, gleicher Code) nimmt dann den Zweig ohne DID-Wechsel
        String backup = lies("src/main/java/at/greenchili/surfmate/config/ConfigBackupService.kt");
        assertTrue(backup.contains("DidWriteResult(usable = true, changed = false)"));
    }

    @Test
    public void hotspotSchattenGehtAuchUeberDenSpeicherweg() throws Exception {
        String ucm = lies("src/main/java/at/greenchili/surfmate/config/UnifiedConfigManager.kt");
        assertTrue(ucm.contains(
                "fun updateHotspot(values: Map<String, Any>): Boolean = updateValues(\"hotspot\", hotspotMitSchatten(values))"));
        // Klartext bleibt im selben Schlüssel (0.1.38.6 liest ihn), dazu nur enc_password
        assertTrue(ucm.contains("val out = LinkedHashMap(values)"));
    }

    /** Die Geräte-Id bleibt für App (uid 10xxx) und SentryDaemon (uid 1000) lesbar – sonst verlören sie ENC-Werte. */
    @Test
    public void geraeteIdBleibtFuerAppUndSystemLesbar() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(d.contains("idFile.setReadable(true, false);"));
    }

    private static String lies(String pfad) throws Exception {
        java.io.File f = new java.io.File(pfad);
        if (!f.isFile()) f = new java.io.File("app/" + pfad);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
}
