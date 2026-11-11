package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.39 (L1, Alfreds Go): Karte + Status auf einem Bildschirm. Split als Standard, sonst sicher auf den Rückfall
 * (Vollbild + Statusleiste); Automatisch = Google Maps, sonst Werksnavi; eigene Wahl bleibt; Leiste nie bei R.
 */
class KartenStatusTest {

    @Test
    fun automatischMapsDannWerksnaviEigeneWahlBleibt() {
        val alle = setOf(KartenStatus.GOOGLE_MAPS, KartenStatus.WERKSNAVI, "com.waze")
        val ok = { p: String -> p in alle }
        assertEquals(KartenStatus.GOOGLE_MAPS, Fahrsicherheit.naviZiel(null, null, listOf("com.waze"), ok))
        assertEquals("eigene Wahl zuerst (nichts still umgestellt)", "com.waze", Fahrsicherheit.naviZiel("com.waze", null, emptyList(), ok))
        val ohneMaps = { p: String -> p == KartenStatus.WERKSNAVI || p == "com.waze" }
        assertEquals(KartenStatus.WERKSNAVI, Fahrsicherheit.naviZiel(null, "com.waze", listOf("com.waze"), ohneMaps))
        val nurWaze = { p: String -> p == "com.waze" }
        assertEquals("com.waze", Fahrsicherheit.naviZiel(null, null, listOf("com.waze"), nurWaze))
    }

    @Test
    fun leisteNieImRueckwaertsgangUndNurMitRecht() {
        assertTrue(KartenStatus.leisteZeigen(true, true, "D"))
        assertTrue("Gang unbekannt (Zündung aus/alt) blendet nicht aus", KartenStatus.leisteZeigen(true, true, null))
        assertFalse("Rückfahrkamera", KartenStatus.leisteZeigen(true, true, "R"))
        assertFalse(KartenStatus.leisteZeigen(true, false, "D"))
        assertFalse(KartenStatus.leisteZeigen(false, true, "D"))
    }

    @Test
    fun abbruchGrundUndHoechstensEinNeuerVersuch() {
        assertEquals("nicht_teilbar", KartenStatus.abbruchGrund(NebenErgebnis.NICHT_TEILBAR, "D"))
        assertEquals("fehler", KartenStatus.abbruchGrund(NebenErgebnis.FEHLER, null))
        assertEquals("system_r", KartenStatus.abbruchGrund(null, "R"))
        assertEquals("system", KartenStatus.abbruchGrund(null, "D"))
        assertFalse("noch in R", KartenStatus.nochmalVersuchen(true, false, "R", null, 10_000))
        assertFalse("zu kurz Ruhe", KartenStatus.nochmalVersuchen(true, false, "D", 8_000, 10_000))
        assertTrue(KartenStatus.nochmalVersuchen(true, false, "D", 4_000, 10_000))
        assertFalse("höchstens einmal", KartenStatus.nochmalVersuchen(true, true, "D", 4_000, 10_000))
        assertFalse("nur nach Abbruch bei R", KartenStatus.nochmalVersuchen(false, false, "D", 4_000, 10_000))
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue("jeder Abbruch wird geloggt", m.contains("\"SPLIT_ABBRUCH paket=\$p grund=\$grund gang="))
        assertTrue(m.contains("if (grund == \"system_r\") karteVollbildMitLeiste(p, nachR = true)"))
        assertTrue("Start beendet den Split gewollt (kein Rückfall)", m.contains("{ splitVerlassen() }") && m.contains("        splitGewolltBeendet = true\n        io.execute {"))
    }

    @Test
    fun statuszeile() {
        assertEquals("78 % · 312 km · D", KartenStatus.zeile(78.4, 311.6, "D"))
        assertEquals("nur frische Ein-Buchstaben-Gänge", "78 %", KartenStatus.zeile(78.0, null, "UNKNOWN(0)"))
        assertEquals("—", KartenStatus.zeile(null, null, null))
    }

    /** 0.1.39 L1-Nachbesserung (E2E backup-6, E2E_0139.md): drei Fehler + Leiste unter der Android-Statusleiste. */
    @Test
    fun nachbesserungE2E() {
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        // (a) Statusspalte tickt: kein reg{} (zeichneSeite räumt KachelAktualisierer gleich wieder weg)
        val sp = m.substring(m.indexOf("private fun statusSpalte()"), m.indexOf("private fun naviWahl()"))
        assertFalse("Spalte nicht über reg{}", sp.contains("reg {"))
        assertTrue(sp.contains("aktualisierer += {") && sp.contains("KartenStatus.zeile(s?.socPct, s?.rangeKm, s?.gear)"))
        assertTrue(m.contains("aktualisierer.removeAll { it is KachelAktualisierer }"))
        // (b) Split-Wechsel ohne Neustart; Zustand übersteht trotzdem einen Neustart; Split-Ende auch nach Neustart erkannt
        val manifest = lies("src/main/AndroidManifest.xml")
        for (c in listOf("screenSize", "smallestScreenSize", "screenLayout")) assertTrue(c, manifest.contains("|$c|") || manifest.contains("\"$c|"))
        assertTrue(m.contains("outState.putString(ZUSTAND_KARTE_SPLIT, karteImSplit)"))
        assertTrue(m.contains("karteImSplit = savedInstanceState?.getString(ZUSTAND_KARTE_SPLIT)"))
        assertTrue(m.contains("if (!isInMultiWindowMode) splitEndeGesehen()   // Split endete, während die Activity neu startete"))
        val ende = m.substring(m.indexOf("private fun splitEndeGesehen()"), m.indexOf("private fun splitAbbruchLoggen("))
        assertTrue(ende.contains("splitAbbruchLoggen(p, grund)") && ende.contains("if (grund == \"system_r\") karteVollbildMitLeiste(p, nachR = true)"))
        // (c) „Start“ verlässt den Split über den Kern, sonst wenigstens nach vorn
        assertTrue(sp.contains("getString(R.string.leiste_start)) { splitVerlassen() }"))
        val sv = m.substring(m.indexOf("private fun splitVerlassen()"), m.indexOf("private fun statusSpalte()"))
        assertTrue(sv.contains("splitGewolltBeendet = true") && sv.contains("client.splitBeenden()") && sv.contains("if (!ok) {"))
        val c = lies("src/main/java/at/greenchili/panoptes/launcher/PanoptesClient.kt")
        assertTrue(c.contains("LauncherContract.launchBody(\"\", \"split_ende\")"))
        // Kosmetik: Rückfall-Leiste unter Uhr/Akku der Android-Statusleiste
        val l = lies("src/main/java/at/greenchili/panoptes/launcher/StatusLeiste.kt")
        assertTrue(l.contains("gravity = Gravity.TOP; y = statusLeistenHoehe()"))
        assertTrue(l.contains("getIdentifier(\"status_bar_height\", \"dimen\", \"android\")"))
    }

    private fun lies(rel: String): String {
        var f = File(rel); if (!f.exists()) f = File("panoptes-launcher/$rel")
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun splitZuerstSonstSichererRueckfall() {
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue(m.contains("reiter_karte, false) { karteMitStatus() }"))
        val k = m.substring(m.indexOf("private fun karteMitStatus()"), m.indexOf("private fun leisteErlaubenFragen("))
        assertTrue("Split über den Kern", k.contains("client.nebenOeffnen(p)"))
        assertTrue("alles außer OK → loggen + Rückfall", k.contains("if (r == NebenErgebnis.OK) karteImSplit = p")
                && k.contains("else { splitAbbruchLoggen(p, KartenStatus.abbruchGrund(r, summary?.gear)); karteVollbildMitLeiste(p) }"))
        assertTrue("ohne Recht: fragen, nie still", k.contains("if (!Settings.canDrawOverlays(this)) { leisteErlaubenFragen(p); return }"))
        assertTrue("Statusspalte im geteilten Bildschirm", m.contains("if (isInMultiWindowMode) {") && m.contains("override fun onMultiWindowModeChanged("))
        assertTrue("Launcher vorn → Leiste weg", m.contains("leiste?.aus()   // 0.1.39"))
        val manifest = lies("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse("nicht debuggable", manifest.contains("android:debuggable=\"true\""))
        val leiste = lies("src/main/java/at/greenchili/panoptes/launcher/StatusLeiste.kt")
        assertTrue(leiste.contains("KartenStatus.leisteZeigen(aktiv, Settings.canDrawOverlays(ctx), s?.gear)"))
        assertTrue("keine Texteingabe", !leiste.contains("EditText"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$d/strings.xml")
            for (k in listOf("leiste_musik", "leiste_zurueck", "leiste_weiter", "leiste_kamera", "leiste_start", "leiste_waechter_an",
                    "leiste_waechter_aus", "leiste_recht_titel", "leiste_recht_text", "leiste_ohne")) {
                assertTrue("$d $k", s.contains("name=\"$k\""))
            }
        }
    }
}
