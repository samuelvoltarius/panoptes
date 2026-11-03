package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class HomeRueckwegTest {
    private val eigen = "at.greenchili.panoptes.launcher"
    private val byd = HomeKandidat("com.byd.launcher", "com.byd.launcher.Home")
    private val selbst = HomeKandidat(eigen, "$eigen.MainActivity")
    private val auswahl = HomeKandidat("android", "com.android.internal.app.ResolverActivity")

    @Test fun standardWirdBevorzugt() {
        assertEquals(byd, HomeRueckweg.ziel(eigen, byd, listOf(selbst, HomeKandidat("x.y", "x.y.A"))))
    }

    @Test fun nieDieEigeneApp() {
        assertEquals(byd, HomeRueckweg.ziel(eigen, selbst, listOf(selbst, byd)))
        assertNull(HomeRueckweg.ziel(eigen, selbst, listOf(selbst)))
    }

    @Test fun nieDieAndroidAuswahl() {
        // Mehrere Homes ohne Standard: Android meldet die Auswahl. Die darf nie
        // angesprungen werden, weil der Nutzer dort versehentlich "Immer" wählen könnte.
        assertEquals(byd, HomeRueckweg.ziel(eigen, auswahl, listOf(auswahl, selbst, byd)))
        assertNull(HomeRueckweg.ziel(eigen, auswahl, listOf(auswahl)))
    }

    @Test fun ohneKandidatenKeinZiel() {
        assertNull(HomeRueckweg.ziel(eigen, null, emptyList()))
    }

    /** Sperre: Solange der Rückweg am Auto nicht belegt ist, darf die APK kein HOME deklarieren. */
    @Test fun manifestDeklariertKeinHome() {
        val manifest = String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), Charsets.UTF_8)
        val ohneKommentare = manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val filter = Regex("<intent-filter>.*?</intent-filter>", RegexOption.DOT_MATCHES_ALL)
            .findAll(ohneKommentare).map { it.value }.toList()
        assertTrue("LAUNCHER-Eintrag fehlt", filter.any { it.contains("android.intent.category.LAUNCHER") })
        assertFalse("HOME-Filter ist gesperrt (LAUNCHER_SPEC §1)", filter.any { it.contains("android.intent.category.HOME") })
    }
}
