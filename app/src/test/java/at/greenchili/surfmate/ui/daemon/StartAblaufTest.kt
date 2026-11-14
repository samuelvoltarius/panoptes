package at.greenchili.surfmate.ui.daemon

import at.greenchili.surfmate.updater.HardResetTor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 0.1.38.10 gestaffelter Start: Reihenfolge aus den Einstellungen, Zugang nur ausgelöst, nie hinter etwas anderem,
 * Bereitschaft statt fester Pausen mit Zeitlimit, und das Tor: kein Start (also nichts, was ein Hard-Reset töten
 * könnte), solange ein Hard-Reset läuft.
 */
class StartAblaufTest {

    /** Attrappen-Uhr mit Planer: führt die Aufgaben in Zeitreihenfolge aus. */
    private class Welt {
        var jetzt = 0L
        private val aufgaben = ArrayList<Pair<Long, () -> Unit>>()
        val aktionen = ArrayList<Pair<StartStufe, Long>>()
        val geprueft = HashMap<StartStufe, Int>()
        val meldungen = ArrayList<Triple<StartStufe, StufeZustand, Long>>()

        fun plane(ms: Long, r: () -> Unit) { aufgaben.add(Pair(jetzt + ms, r)) }

        fun laufen(bisMs: Long) {
            while (true) {
                val naechste = aufgaben.filter { it.first <= bisMs }.minByOrNull { it.first } ?: break
                aufgaben.remove(naechste)
                jetzt = naechste.first
                naechste.second()
            }
            jetzt = bisMs
        }
    }

    @Before @After fun tor() { HardResetTor.zuruecksetzen() }

    private fun ablauf(
        w: Welt, stufen: List<StartStufe>, bootSchutzMs: Long = 1_000L,
        bereitAb: Map<StartStufe, Long> = emptyMap(), toroffen: () -> Boolean = { true },
        abbruch: () -> Boolean = { false }
    ) = StartAblauf(
        stufen = stufen, bootSchutzMs = bootSchutzMs,
        plane = { ms, r -> w.plane(ms, r) },
        jetzt = { w.jetzt },
        toroffen = toroffen,
        aktion = { s -> w.aktionen.add(Pair(s, w.jetzt)) },
        pruefen = { s, rueck -> w.geprueft[s] = (w.geprueft[s] ?: 0) + 1; rueck(w.jetzt >= (bereitAb[s] ?: 0L)) },
        abbruch = abbruch,
        melde = { s, z -> w.meldungen.add(Triple(s, z, w.jetzt)) }
    )

    private fun zeit(w: Welt, s: StartStufe) = w.aktionen.first { it.first == s }.second

    @Test
    fun reihenfolgeAusDenEinstellungen() {
        assertEquals(listOf(StartStufe.ZUGANG, StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST),
            DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = true)))
        assertEquals("ohne Tailscale beginnt die Oberfläche",
            listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST),
            DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = false)))
    }

    @Test
    fun zugangNurAusgeloestNieAbgewartet() {
        val w = Welt()
        // WEB wird erst spät bereit, der Zugang (Tailscale, 10-40 s) darf die Oberfläche nicht aufhalten
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(true)), bereitAb = mapOf(StartStufe.ZUGANG to 1_000_000L)).starten()
        w.laufen(5_000)
        // der Zugang wird nur nebenher beobachtet (Anzeige), der Ablauf hat nicht darauf gewartet (siehe Zeiten unten)
        assertTrue((w.geprueft[StartStufe.ZUGANG] ?: 0) >= 1)
        val z = zeit(w, StartStufe.ZUGANG)
        val web = zeit(w, StartStufe.OBERFLAECHE)
        assertEquals(DaemonStartPlan.MIN_MS, z)
        assertTrue("Web startet gleich nach dem Auslösen des Zugangs, nicht erst nach dessen Bereitschaft", web - z <= 1_000L)
        assertTrue(w.aktionen.map { it.first }.indexOf(StartStufe.ZUGANG) < w.aktionen.map { it.first }.indexOf(StartStufe.OBERFLAECHE))
    }

    @Test
    fun ohneTailscaleKeinZugang() {
        val w = Welt()
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(false))).starten()
        w.laufen(5_000)
        assertFalse(w.aktionen.any { it.first == StartStufe.ZUGANG })
        assertEquals(StartStufe.OBERFLAECHE, w.aktionen.first().first)
    }

    @Test
    fun bootSchutzHaeltNurWebUndFolgendeAufDenZugangNicht() {
        val w = Welt()
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(true)), bootSchutzMs = 15_000L).starten()
        w.laufen(30_000)
        assertEquals("Zugang unabhängig vom Boot-Schutz", DaemonStartPlan.MIN_MS, zeit(w, StartStufe.ZUGANG))
        assertTrue("Web nicht vor dem Boot-Schutz", zeit(w, StartStufe.OBERFLAECHE) >= 15_000L)
        assertTrue(zeit(w, StartStufe.WAECHTER) >= zeit(w, StartStufe.OBERFLAECHE))
    }

    @Test
    fun naechsteStufeWartetAufBereitschaftNichtAufFestePause() {
        val w = Welt()
        ablauf(w, listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST), bereitAb = mapOf(StartStufe.OBERFLAECHE to 7_000L)).starten()
        w.laufen(30_000)
        val web = zeit(w, StartStufe.OBERFLAECHE)
        val waechter = zeit(w, StartStufe.WAECHTER)
        assertTrue("Wächter erst nach der Bereitschaft von Web", waechter >= 7_000L)
        assertTrue("aber gleich danach (Takt), keine feste Pause", waechter <= 7_000L + DaemonStartPlan.PRUEF_TAKT_MS + 1)
        assertTrue(web < 7_000L)
    }

    @Test
    fun zeitlimitBeendetDasWarten() {
        val w = Welt()
        ablauf(w, listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST), bereitAb = mapOf(StartStufe.OBERFLAECHE to Long.MAX_VALUE, StartStufe.WAECHTER to Long.MAX_VALUE)).starten()
        w.laufen(200_000)
        val web = zeit(w, StartStufe.OBERFLAECHE)
        val waechter = zeit(w, StartStufe.WAECHTER)
        val rest = zeit(w, StartStufe.REST)
        assertTrue("Web-Limit 60 s", waechter - web in 60_000L..61_000L)
        assertTrue("Wächter-Limit 20 s", rest - waechter in 20_000L..21_000L)
    }

    @Test
    fun hardResetTorHaeltJedenStartAn() {
        val w = Welt()
        HardResetTor.erwarten(0L)
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(true)),
            toroffen = { HardResetTor.frei(w.jetzt) }).starten()
        w.laufen(10_000)
        assertEquals("solange der Hard-Reset läuft, startet KEIN Daemon außer dem Zugang (er würde ihn töten)",
            listOf(StartStufe.ZUGANG), w.aktionen.map { it.first })
        w.plane(0) { HardResetTor.beenden() }   // der Hard-Reset ist durch
        w.laufen(12_000)
        val o = w.aktionen.first { it.first == StartStufe.OBERFLAECHE }
        assertTrue("Oberfläche erst nach dem Ende des Hard-Resets", o.second >= 10_000L)
        assertTrue(o.second <= 10_000L + DaemonStartPlan.PRUEF_TAKT_MS)
    }

    @Test
    fun hardResetUndTorHaltenDenZugangNieAn() {
        val w = Welt()
        HardResetTor.erwarten(0L)
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(true)),
            toroffen = { HardResetTor.frei(w.jetzt) }).starten()
        w.laufen(HardResetTor.ZEITLIMIT_MS / 2)
        assertEquals("Zugang sofort, trotz geschlossenem Tor", DaemonStartPlan.MIN_MS, zeit(w, StartStufe.ZUGANG))
        assertFalse(w.aktionen.any { it.first == StartStufe.OBERFLAECHE })
    }

    @Test
    fun hardResetBeendetKeinenTailscaledVonWeg1() {
        val f = File("src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java")
            .let { if (it.isFile) it else File("app/src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java") }
        val s = f.readText()
        assertFalse("Der Zugang darf nie vom Update abhängen", s.contains("TailscaledProzesse.beendenSkript"))
        assertTrue(s.contains("TailscaledProzesse.laeuftSkript"))
        assertTrue(s.contains("TAILSCALED_ALT_LAEUFT"))
    }

    @Test
    fun hardResetTorOeffnetNachDemZeitlimit() {
        val w = Welt()
        HardResetTor.erwarten(0L)
        ablauf(w, listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST), toroffen = { HardResetTor.frei(w.jetzt) }).starten()
        w.laufen(HardResetTor.ZEITLIMIT_MS - 1)
        assertTrue(w.aktionen.isEmpty())
        w.laufen(HardResetTor.ZEITLIMIT_MS + 1_000)
        assertEquals("ein hängender Hard-Reset hält den Start nie ewig auf", StartStufe.OBERFLAECHE, w.aktionen.first().first)
    }

    @Test
    fun abbruchStoppt() {
        val w = Welt()
        var weg = false
        ablauf(w, listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST), bereitAb = mapOf(StartStufe.OBERFLAECHE to 10_000L), abbruch = { weg }).starten()
        w.laufen(3_000)
        weg = true
        w.laufen(60_000)
        assertEquals(listOf(StartStufe.OBERFLAECHE), w.aktionen.map { it.first })
    }

    private fun zustaende(w: Welt, s: StartStufe) = w.meldungen.filter { it.first == s }.map { it.second }

    @Test
    fun zustandJeStufeStartetBereitStartetNicht() {
        val w = Welt()
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = true)),
            bereitAb = mapOf(StartStufe.ZUGANG to 5_000L, StartStufe.OBERFLAECHE to 3_000L,
                StartStufe.WAECHTER to Long.MAX_VALUE)).starten()
        w.laufen(200_000)
        assertEquals(listOf(StufeZustand.STARTET, StufeZustand.BEREIT), zustaende(w, StartStufe.ZUGANG))
        assertEquals(listOf(StufeZustand.STARTET, StufeZustand.BEREIT), zustaende(w, StartStufe.OBERFLAECHE))
        assertEquals("Limit 20 s: startet nicht", listOf(StufeZustand.STARTET, StufeZustand.STARTET_NICHT),
            zustaende(w, StartStufe.WAECHTER))
        assertEquals(listOf(StufeZustand.STARTET, StufeZustand.BEREIT), zustaende(w, StartStufe.REST))
    }

    /**
     * 0.1.43.7 (Paket F 3): Nach den Updates 0.1.43.5/0.1.43.6 stand je einmal `STUFE_STARTET_NICHT stufe=waechter`
     * im Protokoll, der Wächter lief aber 1–2 s später. Ein Spätstart nach dem Limit ist kein Fehler: der Ablauf geht
     * nach 20 s weiter, „startet nicht“ kommt erst nach der Nachfrist – ein echter Nichtstart wird weiter gemeldet.
     */
    @Test
    fun spaetstartDesWaechtersNachDemLimitIstKeinFehler() {
        val w = Welt()
        val log = ArrayList<String>()
        val vorher = StartStatus.protokoll
        StartStatus.zuruecksetzen()
        StartStatus.protokoll = { e, v -> log.add("$e $v") }
        try {
            val stufen = listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST)
            StartStatus.beginne(stufen, 0L)
            StartAblauf(
                stufen = stufen, bootSchutzMs = 1_000L,
                plane = { ms, r -> w.plane(ms, r) }, jetzt = { w.jetzt }, toroffen = { true },
                aktion = { s -> w.aktionen.add(Pair(s, w.jetzt)) },
                pruefen = { s, rueck -> rueck(s != StartStufe.WAECHTER || w.jetzt >= 25_000L) },
                melde = { s, z -> w.meldungen.add(Triple(s, z, w.jetzt)); StartStatus.melde(s, z, w.jetzt) }
            ).starten()
            w.laufen(200_000)
            val waechter = zeit(w, StartStufe.WAECHTER)
            assertTrue("Szenario: bereit erst nach dem 20-s-Limit", waechter + DaemonStartPlan.zeitlimitMs(StartStufe.WAECHTER) < 25_000L)
            assertEquals("Spätstart: startet → bereit, kein „startet nicht“", listOf(StufeZustand.STARTET, StufeZustand.BEREIT),
                zustaende(w, StartStufe.WAECHTER))
            assertTrue("nichts im Start-Protokoll: $log", log.isEmpty())
            assertTrue("der Ablauf wartet trotzdem nur 20 s (Rest nicht aufgehalten)",
                zeit(w, StartStufe.REST) - waechter in 20_000L..21_000L)
            assertEquals(StufeZustand.BEREIT, StartStatus.stand(200_000L).first { it.first == StartStufe.WAECHTER }.second)
        } finally {
            StartStatus.protokoll = vorher
            StartStatus.zuruecksetzen()
        }
    }

    @Test
    fun echterNichtstartDesWaechtersWirdNachDerNachfristGemeldet() {
        val w = Welt()
        val log = ArrayList<String>()
        val vorher = StartStatus.protokoll
        StartStatus.zuruecksetzen()
        StartStatus.protokoll = { e, v -> log.add("$e $v") }
        try {
            val stufen = listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST)
            StartStatus.beginne(stufen, 0L)
            StartAblauf(
                stufen = stufen, bootSchutzMs = 1_000L,
                plane = { ms, r -> w.plane(ms, r) }, jetzt = { w.jetzt }, toroffen = { true },
                aktion = { s -> w.aktionen.add(Pair(s, w.jetzt)) },
                pruefen = { s, rueck -> rueck(s != StartStufe.WAECHTER) },
                melde = { s, z -> w.meldungen.add(Triple(s, z, w.jetzt)); StartStatus.melde(s, z, w.jetzt) }
            ).starten()
            w.laufen(200_000)
            val waechter = zeit(w, StartStufe.WAECHTER)
            val nicht = w.meldungen.single { it.first == StartStufe.WAECHTER && it.second == StufeZustand.STARTET_NICHT }
            val frist = DaemonStartPlan.zeitlimitMs(StartStufe.WAECHTER) + DaemonStartPlan.nachfristMs(StartStufe.WAECHTER)
            assertEquals(80_000L, frist)
            assertTrue("nach Limit + Nachfrist (80 s)", nicht.third - waechter in frist..frist + 2_500L)
            assertEquals(listOf("STUFE_STARTET_NICHT stufe=waechter grund=zeitlimit_ablauf"), log)
            assertTrue("unter dem Anzeige-Rückfall", frist + 45_000L + 60_000L < StartStatus.ZEITLIMIT_MS)
            assertTrue("Rest trotzdem nach 20 s", zeit(w, StartStufe.REST) - waechter in 20_000L..21_000L)
        } finally {
            StartStatus.protokoll = vorher
            StartStatus.zuruecksetzen()
        }
    }

    @Test
    fun zugangStartetNichtNachDerBeobachtung() {
        val w = Welt()
        ablauf(w, DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = true)),
            bereitAb = mapOf(StartStufe.ZUGANG to Long.MAX_VALUE)).starten()
        w.laufen(200_000)
        val nicht = w.meldungen.first { it.first == StartStufe.ZUGANG && it.second == StufeZustand.STARTET_NICHT }
        assertTrue("nach 60 s Beobachtung", nicht.third - DaemonStartPlan.MIN_MS in 60_000L..62_500L)
        // und die Oberfläche kam trotzdem sofort
        assertTrue(zeit(w, StartStufe.OBERFLAECHE) <= 2_000L)
    }

    @Test
    fun restNurMitTelegramOderWireGuard() {
        assertEquals(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER),
            DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = false, restAn = false)))
        assertEquals(listOf(StartStufe.ZUGANG, StartStufe.OBERFLAECHE, StartStufe.WAECHTER),
            DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = true, restAn = false)))
    }

    @Test
    fun torIstAmProzessstartUndBeimHardResetVerdrahtet() {
        fun q(rel: String): String {
            var f = File(rel); if (!f.exists()) f = File("app/$rel")
            return f.readText().replace("\r\n", "\n")
        }
        val app = q("src/main/java/at/greenchili/surfmate/SurfMateApplication.kt")
        val tor = app.indexOf("HardResetTor.erwarten(")
        val start = app.indexOf("DaemonKeepaliveService.start(this)")
        assertTrue("Tor wird VOR allen Startwegen gesetzt", tor in 1 until start)
        val ul = q("src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java")
        assertTrue(ul.indexOf("HardResetTor.erwarten(") < ul.indexOf("launcher.executeShellScript(script"))
        assertTrue("das Tor öffnet im Abschluss des Hard-Resets", ul.indexOf("HardResetTor.beenden()") > ul.indexOf("private static void finishHardReset"))
        val main = q("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt")
        assertTrue("auch beim Zeitablauf", main.contains("at.greenchili.surfmate.updater.HardResetTor.beenden()"))
        val m = q("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt")
        assertTrue(m.contains("toroffen = { at.greenchili.surfmate.updater.HardResetTor.frei("))
        assertTrue(m.contains("DaemonStartPlan.stufen("))
        assertTrue("Zustand je Stufe geht an die EINE Stelle StartStatus",
            m.contains("StartStatus.beginne(anzeige,") && m.contains("melde = { s, z -> StartStatus.melde(s, z) }"))
    }
}
