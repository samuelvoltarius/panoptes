package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Updater-Quelle als Einstellung (0.1.15): Quellwahl, Migration bestehender Installationen, Fehlerdrossel. */
public class UpdateQuelleTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String OEFF_LATEST = "https://github.com/samuelvoltarius/panoptes/releases/latest/download/version.json";
    static final String ALT = "samuelvoltarius/panoptes-releases";
    static final String API = "https://api.github.com/repos/" + ALT;

    // ------------------------------------------------------------- Quellwahl

    @Test
    public void standardIstOeffentlichOhneToken() {
        UpdateQuelle q = UpdateQuelle.aus(null, null, null);
        assertEquals(UpdateQuelle.OEFFENTLICH, q.art);
        assertEquals("samuelvoltarius/panoptes", q.repo);
        assertFalse(q.mitToken());
        assertEquals(OEFF_LATEST, q.latestVersionJson());
        // öffentlich schickt einen gesetzten Token nie mit
        q = UpdateQuelle.aus("oeffentlich", ALT, "geheim");
        assertEquals("samuelvoltarius/panoptes", q.repo);
        assertNull(q.token);
        // eigen ohne gültiges Repo → zurück auf öffentlich (nie ins Leere)
        assertEquals(UpdateQuelle.OEFFENTLICH, UpdateQuelle.aus("eigen", "kein repo", "t").art);
        assertEquals(UpdateQuelle.OEFFENTLICH, UpdateQuelle.aus("eigen", "../x", "t").art);
        assertEquals(UpdateQuelle.OEFFENTLICH, UpdateQuelle.aus("eigen", null, "t").art);
        assertEquals(UpdateQuelle.OEFFENTLICH, UpdateQuelle.aus("quatsch", ALT, "t").art);
    }

    @Test
    public void eigenesRepoMitUndOhneToken() {
        UpdateQuelle q = UpdateQuelle.aus("eigen", ALT, " tok ");
        assertEquals(UpdateQuelle.EIGEN, q.art);
        assertEquals(ALT, q.repo);
        assertEquals("tok", q.token);
        assertEquals(API + "/releases/latest", q.latestRelease());
        assertEquals(API + "/releases/tags/v0.1.9", q.releaseZuTag("v0.1.9"));
        q = UpdateQuelle.aus("eigen", "jemand/panoptes-fork", "  ");
        assertFalse(q.mitToken());
        assertEquals("https://github.com/jemand/panoptes-fork/releases/latest/download/version.json",
                q.latestVersionJson());
        assertTrue(UpdateQuelle.gueltigesRepo("a-b/c.d_e"));
        assertFalse(UpdateQuelle.gueltigesRepo("a/b/c"));
        assertFalse(UpdateQuelle.gueltigesRepo("https://github.com/a/b"));
    }

    @Test
    public void assetsNurAusDerApi() {
        String rel = "{\"assets\":[{\"name\":\"version.json\",\"url\":\"" + API + "/releases/assets/1\"},"
                + "{\"name\":\"x.apk\",\"url\":\"https://evil.example/x.apk\"}]}";
        assertEquals(API + "/releases/assets/1", UpdateQuelle.asset(rel, "version.json"));
        assertNull(UpdateQuelle.asset(rel, "x.apk"));        // fremder Host wird nie benutzt
        assertNull(UpdateQuelle.asset(rel, "fehlt.apk"));
        assertNull(UpdateQuelle.asset("kein json", "version.json"));
        assertTrue(UpdateQuelle.erlaubteAdresse("https://github.com/a/b/releases/download/v1/x.apk"));
        assertFalse(UpdateQuelle.erlaubteAdresse("http://github.com/a/b"));
        assertFalse(UpdateQuelle.erlaubteAdresse("https://evil.example/x.apk"));
        assertFalse(UpdateQuelle.erlaubteAdresse(null));
    }

    // ------------------------------------------------------------- Migration (rein)

    @Test
    public void migrationRein() {
        assertTrue(UpdateQuelle.migration(null, null, null, false).isEmpty());          // neu: öffentlich
        Map<String, Object> m = UpdateQuelle.migration(null, null, null, true);         // Bestand: alte Quelle
        assertEquals("eigen", m.get("quelle"));
        assertEquals(ALT, m.get("repo"));
        m = UpdateQuelle.migration(null, null, "tok", false);                           // Token vorhanden
        assertEquals("eigen", m.get("quelle"));
        assertEquals(ALT, m.get("repo"));
        assertFalse(m.containsKey("token"));                                           // Token nie angefasst
        m = UpdateQuelle.migration(null, "jemand/fork", null, false);                   // Repo vorhanden
        assertEquals("eigen", m.get("quelle"));
        assertFalse(m.containsKey("repo"));
        assertTrue(UpdateQuelle.migration("oeffentlich", null, "tok", true).isEmpty()); // schon entschieden
        assertTrue(UpdateQuelle.migration("eigen", null, null, true).isEmpty());
    }

    // ------------------------------------------------------------- Dienst

    final Map<String, String> texte = new HashMap<>();
    final Map<String, String> cfg = new HashMap<>();
    final List<String> abgerufen = new ArrayList<>();
    final List<String> zugaenge = new ArrayList<>();
    final List<Map<String, Object>> geschrieben = new ArrayList<>();
    boolean schreibenOk = true;
    long jetzt = 1_800_000_000_000L;
    String version = "0.1.15";
    File basis, dd;

    AppUpdateDienst dienst() throws Exception {
        if (basis == null) {
            basis = tmp.newFolder("surfmate");
            dd = tmp.newFolder("deploy");
        }
        AppUpdateDienst.Netz netz = new AppUpdateDienst.Netz() {
            @Override public String text(String url) throws Exception {
                abgerufen.add(url);
                String t = texte.get(url);
                if (t == null) throw new java.io.IOException(texte.getOrDefault("*", "HTTP 404"));
                return t;
            }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer p) throws Exception {
                throw new java.io.IOException("HTTP 404");
            }
            @Override public long groesse(String url) { return 1234; }
            @Override public void zugang(String token) { zugaenge.add(String.valueOf(token)); }
        };
        AppUpdateDienst.Umgebung env = new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return version; }
            @Override public Object[] autoAus() { return new Object[]{true, null}; }
            @Override public double kmh() { return 0; }
            @Override public Boolean accAus() { return true; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return null; }
            @Override public String skript() { return ""; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) {
                throw new AssertionError("darf nicht starten");
            }
            @Override public long jetzt() { return jetzt; }
            @Override public boolean setzeConfig(Map<String, Object> werte) {
                geschrieben.add(werte);
                if (!schreibenOk) return false;
                for (Map.Entry<String, Object> e : werte.entrySet()) cfg.put(e.getKey(), String.valueOf(e.getValue()));
                return true;
            }
        };
        return new AppUpdateDienst(netz, env);
    }

    @Test
    public void bestandBehaeltAlteQuelle() throws Exception {
        basis = tmp.newFolder("surfmate");
        dd = tmp.newFolder("deploy");
        // Stand eines Updaters vor 0.1.15 (Alfreds Auto)
        Files.write(new File(basis, "appupdate_stand.json").toPath(),
                "{\"letztePruefung\":1,\"ergebnis\":\"fehler\",\"fehler\":\"nicht_erreichbar\"}"
                        .getBytes(StandardCharsets.UTF_8));
        cfg.put("modus", "automatisch");
        AppUpdateDienst d = dienst();
        assertEquals(1, geschrieben.size());
        assertEquals("eigen", cfg.get("quelle"));
        assertEquals(ALT, cfg.get("repo"));
        assertTrue(new File(basis, ".appupdate-quelle").exists());
        assertEquals(ALT, d.status("de").getString("quelleRepo"));
        // zweiter Start: nichts mehr schreiben, auch wenn jemand die Quelle umstellt
        cfg.put("quelle", "oeffentlich");
        dienst();
        assertEquals(1, geschrieben.size());
        assertEquals("oeffentlich", cfg.get("quelle"));
    }

    @Test
    public void vorhandenerTokenBleibt() throws Exception {
        cfg.put("token", "tok-alt");
        dienst();
        assertEquals("eigen", cfg.get("quelle"));
        assertEquals(ALT, cfg.get("repo"));
        assertEquals("tok-alt", cfg.get("token"));
        assertFalse(geschrieben.get(0).containsKey("token"));
    }

    @Test
    public void neueInstallationOeffentlichUndNieNachtraeglichMigriert() throws Exception {
        AppUpdateDienst d = dienst();
        assertTrue(geschrieben.isEmpty());
        assertNull(cfg.get("quelle"));
        assertTrue(new File(basis, ".appupdate-quelle").exists());
        d.pruefen();                                         // legt appupdate_stand.json an
        assertTrue(new File(basis, "appupdate_stand.json").exists());
        dienst();                                            // Neustart: Stand-Datei ist kein „Bestand“ mehr
        assertTrue(geschrieben.isEmpty());
        assertEquals(UpdateQuelle.OEFFENTLICH, d.status("de").getString("quelle"));
        assertTrue(abgerufen.contains(OEFF_LATEST));
        assertEquals("null", zugaenge.get(0));               // ohne Token
    }

    @Test
    public void migrationScheitertWirdBeimNaechstenStartWiederholt() throws Exception {
        basis = tmp.newFolder("surfmate");
        dd = tmp.newFolder("deploy");
        Files.write(new File(basis, "appupdate_stand.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        schreibenOk = false;
        dienst();
        assertFalse(new File(basis, ".appupdate-quelle").exists());
        schreibenOk = true;
        dienst();
        assertEquals(2, geschrieben.size());
        assertEquals("eigen", cfg.get("quelle"));
    }

    static String release(String tag, String apk) {
        return "{\"tag_name\":\"" + tag + "\",\"prerelease\":false,\"assets\":["
                + "{\"name\":\"version.json\",\"url\":\"" + API + "/releases/assets/11\"},"
                + "{\"name\":\"" + apk + "\",\"url\":\"" + API + "/releases/assets/12\"}]}";
    }

    @Test
    public void privatesRepoMitTokenUeberApiAssets() throws Exception {
        cfg.put("quelle", "eigen");
        cfg.put("repo", ALT);
        cfg.put("token", "tok");
        AppUpdateDienst d = dienst();
        texte.put(API + "/releases/latest", release("v0.1.16", "panoptes-0.1.16-abc.apk"));
        texte.put(API + "/releases/assets/11", AppUpdateTest.vj(17, "0.1.16", "stabil", "v0.1.16",
                AppUpdateTest.SHA0).replace("panoptes-0.1.16-abc.apk", "panoptes-0.1.16-abc.apk"));
        d.pruefen();
        JSONObject s = d.status("de");
        assertEquals("verfuegbar", s.getString("ergebnis"));
        assertEquals(17, s.getJSONObject("verfuegbar").getInt("versionCode"));
        assertEquals("tok", zugaenge.get(zugaenge.size() - 1));
        for (String u : abgerufen) assertTrue(u, u.startsWith(API));   // nur die API, nie die Web-Adressen
        assertFalse(s.toString().contains("tok\""));                   // Token nie im Status
        // Stand überlebt den Neustart samt API-Adresse der APK
        AppUpdateDienst d2 = dienst();
        assertEquals(17, d2.status("de").getJSONObject("verfuegbar").getInt("versionCode"));
    }

    @Test
    public void fehlenderZugangHeisstKeinZugang() throws Exception {
        cfg.put("quelle", "eigen");
        cfg.put("repo", ALT);
        cfg.put("token", "abgelaufen");
        AppUpdateDienst d = dienst();
        texte.put("*", "HTTP 404");                          // GitHub antwortet bei fehlendem Recht mit 404
        d.suchen("de");
        assertEquals("kein_zugang", d.status("de").getString("fehler"));
        texte.put("*", "HTTP 401");
        assertEquals("kein_zugang", AppUpdateDienst.grundAus(UpdateQuelle.aus("oeffentlich", null, null),
                new java.io.IOException("HTTP 401")));
        assertEquals("nicht_erreichbar", AppUpdateDienst.grundAus(UpdateQuelle.aus("oeffentlich", null, null),
                new java.io.IOException("HTTP 404")));
        assertEquals("nicht_erreichbar", AppUpdateDienst.grundAus(UpdateQuelle.aus("oeffentlich", null, null),
                new java.net.UnknownHostException("github.com")));
    }

    @Test
    public void quellwechselVerwirftAltenFund() throws Exception {
        AppUpdateDienst d = dienst();
        texte.put(OEFF_LATEST, AppUpdateTest.vj(17, "0.1.16", "stabil", "v0.1.16", AppUpdateTest.SHA0));
        d.pruefen();
        assertFalse(d.status("de").isNull("verfuegbar"));
        cfg.put("quelle", "eigen");
        cfg.put("repo", "jemand/fork");
        d.pruefen();                                         // dort nichts (404)
        assertTrue(d.status("de").isNull("verfuegbar"));
    }

    // ------------------------------------------------------------- Fehlerdrossel

    @Test
    public void drosselEinBerichtJeVersionUndTag() {
        long tag = 1_800_000_000_000L;
        String a = UpdateRegel.berichtSchluessel(null, "0.1.15", tag);
        assertNotNull(a);
        assertNull(UpdateRegel.berichtSchluessel(a, "0.1.15", tag + 3_600_000L));         // gleicher Tag
        String b = UpdateRegel.berichtSchluessel(a, "0.1.15", tag + 86_400_000L);         // nächster Tag
        assertNotNull(b);
        assertNotNull(UpdateRegel.berichtSchluessel(b, "0.1.16", tag + 86_400_000L));     // neue Version
    }

    @Test
    public void fehlschlagImHintergrundStillUndGedrosselt() throws Exception {
        jetzt = 20_834L * 86_400_000L;                       // Tagesbeginn (UTC)
        AppUpdateDienst d = dienst();                        // öffentliches Repo gibt es noch nicht: 404
        d.pruefen();
        String erster = d.berichtSchluessel();
        assertNotNull(erster);
        JSONObject s = d.status("de");
        assertEquals("nie", s.getString("ergebnis"));        // keine Fehler-Anzeige aus dem Hintergrund
        assertEquals("nicht_erreichbar", s.getString("fehler"));
        for (int i = 0; i < 3; i++) {                        // 6, 12, 18 Uhr: derselbe Tag
            jetzt += UpdateRegel.PRUEF_TAKT_MS;
            d.pruefen();
            assertEquals(erster, d.berichtSchluessel());     // derselbe Tag: keine zweite Warnung
        }
        // Drossel überlebt den Neustart
        assertEquals(erster, dienst().berichtSchluessel());
        jetzt += 86_400_000L;
        d.pruefen();
        assertFalse(erster.equals(d.berichtSchluessel()));   // neuer Tag: wieder eine
        // auf Knopfdruck sieht man den Fehler
        d.suchen("de");
        assertEquals("fehler", d.status("de").getString("ergebnis"));
    }

    // ------------------------------------------------------------- Pflichten

    @Test
    public void vorabversionNieAutomatisch() throws Exception {
        Freigabe t = AppUpdateTest.frei(AppUpdateTest.vj(10, "0.1.9", "test", "v0.1.9-test.2", AppUpdateTest.SHA0));
        assertEquals(-1, UpdateRegel.automatischAb(AppUpdateTest.lage(8, t), true, 1000));
        Freigabe s = AppUpdateTest.frei(AppUpdateTest.vj(10, "0.1.9", "stabil", "v0.1.9", AppUpdateTest.SHA0));
        assertEquals(1000 + UpdateRegel.AUTOMATISCH_NACH_MS,
                UpdateRegel.automatischAb(AppUpdateTest.lage(8, s), true, 1000));
    }

    @Test
    public void sha256PflichtAuchOeffentlich() throws Exception {
        AppUpdateDienst d = dienst();
        File apk = tmp.newFile("x.apk");
        Files.write(apk.toPath(), "nicht die erwartete apk".getBytes(StandardCharsets.UTF_8));
        // Netz liefert die Datei vollständig; erwartet ist SHA0 → pruefsumme, kein Start
        AppUpdateDienst d2 = new AppUpdateDienst(new AppUpdateDienst.Netz() {
            @Override public String text(String url) throws Exception {
                if (OEFF_LATEST.equals(url)) {
                    return AppUpdateTest.vj(17, "0.1.16", "stabil", "v0.1.16", AppUpdateTest.SHA0);
                }
                throw new java.io.IOException("HTTP 404");
            }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer p)
                    throws Exception {
                assertTrue(url.startsWith("https://github.com/samuelvoltarius/panoptes/"));
                Files.copy(apk.toPath(), ziel.toPath());
            }
            @Override public long groesse(String url) { return -1; }
        }, d.env);
        d2.pruefen();
        assertFalse(d2.status("de").isNull("verfuegbar"));
        d2.ausfuehren(AppUpdateTest.frei(AppUpdateTest.vj(17, "0.1.16", "stabil", "v0.1.16", AppUpdateTest.SHA0)));
        assertEquals("pruefsumme", d2.status("de").getString("fehler"));
        // ohne Download-Adresse (nur aus version.json) wird gar nicht erst geladen
        d2.ausfuehren(Freigabe.lies(AppUpdateTest.vj(17, "0.1.16", "stabil", "v0.1.16", AppUpdateTest.SHA0)));
        assertEquals("nicht_erreichbar", d2.status("de").getString("fehler"));
    }

    // ------------------------------------------------------------- Einstellungen + Sprachen

    @Test
    public void einstellungenImSchema() {
        List<Feld> f = Katalog.felder(new Katalog.Kontext());
        Feld q = Katalog.finde(f, "update.quelle");
        assertNotNull(q);
        assertEquals("update", q.gruppe);
        assertEquals(UpdateQuelle.OEFFENTLICH, q.standard);
        assertTrue(q.nurLesendExtern);
        Feld t = Katalog.finde(f, "update.token");
        assertEquals(Feld.Typ.GEHEIM, t.typ);
        assertTrue(t.nurLesendExtern);
        Feld r = Katalog.finde(f, "update.repo");
        assertEquals("a/b", r.pruefer.pruefe(" a/b "));
        try {
            r.pruefer.pruefe("../etc");
            throw new AssertionError("angenommen");
        } catch (IllegalArgumentException erwartet) {
            assertEquals("typ", erwartet.getMessage());
        }
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        File ordner = new File("src/main/assets/server-i18n");
        if (!ordner.isDirectory()) ordner = new File("app/src/main/assets/server-i18n");
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject u = new JSONObject(new String(Files.readAllBytes(new File(ordner, l + ".json").toPath()),
                    StandardCharsets.UTF_8)).getJSONObject("settings").getJSONObject("update");
            for (String k : new String[]{"quelle", "repo", "token"}) {
                assertFalse(l + " " + k, u.getJSONObject(k).getString("title").isEmpty());
                assertFalse(l + " " + k, u.getJSONObject(k).getString("help").isEmpty());
            }
            assertFalse(u.getJSONObject("quelle").getString("oeffentlich").isEmpty());
            assertFalse(u.getJSONObject("quelle").getString("eigen").isEmpty());
        }
    }
}
