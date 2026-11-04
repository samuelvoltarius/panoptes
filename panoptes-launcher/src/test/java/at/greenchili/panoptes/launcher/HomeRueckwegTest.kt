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

    @Test fun nieFallbackHome() {
        val fallback = HomeKandidat("com.android.settings", "com.android.settings.FallbackHome")
        assertEquals(byd, HomeRueckweg.ziel(eigen, selbst, listOf(fallback, selbst, byd)))
        assertNull(HomeRueckweg.ziel(eigen, selbst, listOf(fallback, selbst)))
    }

    private fun manifestOhneKommentare(): String {
        val manifest = String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), Charsets.UTF_8)
        return manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    /**
     * Sperre (0.1.38.7): HOME ist nur im deaktivierten Alias .HomeAlias erlaubt. MainActivity und alles andere
     * deklarieren nie HOME, damit die App ohne den bewussten Schalter nie Standard-Startbildschirm werden kann.
     */
    @Test fun homeNurImDeaktiviertenAlias() {
        val m = manifestOhneKommentare()
        val aliasse = Regex("<activity-alias.*?</activity-alias>", RegexOption.DOT_MATCHES_ALL).findAll(m).map { it.value }.toList()
        val ohneAliasse = aliasse.fold(m) { acc, a -> acc.replace(a, "") }
        val filter = Regex("<intent-filter>.*?</intent-filter>", RegexOption.DOT_MATCHES_ALL)
        assertTrue("LAUNCHER-Eintrag fehlt", filter.findAll(ohneAliasse).any { it.value.contains("android.intent.category.LAUNCHER") })
        assertFalse("HOME außerhalb des Alias ist gesperrt", filter.findAll(ohneAliasse).any { it.value.contains("android.intent.category.HOME") })
        val mitHome = aliasse.filter { it.contains("android.intent.category.HOME") }
        assertEquals("genau ein Alias darf HOME tragen", 1, mitHome.size)
        assertTrue("Alias heißt .HomeAlias", mitHome[0].contains("android:name=\".HomeAlias\""))
        assertTrue("Alias ist im Manifest ausgeschaltet", mitHome[0].contains("android:enabled=\"false\""))
        assertTrue("Alias führt zur MainActivity", mitHome[0].contains("android:targetActivity=\".MainActivity\""))
    }
}
