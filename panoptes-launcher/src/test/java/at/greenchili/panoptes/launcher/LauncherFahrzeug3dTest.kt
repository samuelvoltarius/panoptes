package at.greenchili.panoptes.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.1: Das Fahrzeug im Launcher kommt aus Panoptes (Modellkatalog + Renderer ev-card-3d.js über
 * launcher-fahrzeug.html), nie aus einer eigenen Kopie. Bis es gezeichnet ist, steht ein ruhiges Standardbild;
 * gezeichnet wird nur bei Bedarf und nur, wenn der Launcher sichtbar ist.
 */
class LauncherFahrzeug3dTest {

    @Test
    fun modellKommtAusPanoptes() {
        val client = quelle("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/PanoptesClient.kt")
        assertTrue(client.contains("http://127.0.0.1:8180/local/launcher-fahrzeug.html"))
        // Standbild ebenfalls aus Panoptes: dasselbe Renderbild wie im Panoptes-Dashboard
        assertTrue(client.contains("\"/api/models/art?id=\""))
        // keine eigenen 3D-Modelle oder three.js in der Launcher-APK
        val assets = File(wurzel(), "panoptes-launcher/src/main/assets")
        val dateien = assets.walkTopDown().filter { it.isFile }.map { it.name.lowercase() }.toList()
        assertFalse(dateien.toString(), dateien.any { it.endsWith(".glb") || it.endsWith(".gltf") || it.contains("three") })
        val seite = quelle("app/src/main/assets/web/local/launcher-fahrzeug.html")
        assertTrue(seite.contains("../shared/ev-card-3d.js"))
        assertTrue(seite.contains("diagnostics.runnable === false"))
        assertTrue(seite.contains("document.title = 'fehler'"))
    }

    @Test
    fun standbildBisGezeichnetUndNurSichtbar() {
        val main = quelle("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue(main.contains("R.drawable.ic_pn_auto_seite"))
        assertTrue(main.contains("autoGezeichnet = title == \"bereit\""))
        assertTrue("Ersatz erst weg, wenn gezeichnet", main.contains("ersatz.visibility = if (autoGezeichnet) View.GONE else View.VISIBLE"))
        assertTrue(main.contains("webAuto?.onPause()"))
        assertTrue(main.contains("webAuto?.onResume()"))
        // Renderer ohne Dauerschleife (Bildgrenze: zeichnet nur bei Änderungen)
        val renderer = quelle("app/src/main/assets/web/shared/ev-card-3d.js")
        assertTrue(renderer.contains("SurfMateEvCard3D.prototype._scheduleRender"))
        assertFalse(renderer.contains("setInterval("))
    }

    @Test
    fun frontAnsichtNurImLauncher() {
        val main = quelle("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue(main.contains("\"&ansicht=front\""))
        val seite = quelle("app/src/main/assets/web/local/launcher-fahrzeug.html")
        assertTrue(seite.contains("front: q.ansicht === 'front'"))
        val renderer = quelle("app/src/main/assets/web/shared/ev-card-3d.js")
        // nur auf ausdrücklichen Wunsch; ohne Option bleibt die bisherige Drehung (Panoptes-Dashboard)
        assertTrue(renderer.contains("this.front = !!(opts && opts.front === true && this.view === 'three-quarter');"))
        assertTrue(renderer.contains("self.carModel.rotation.y = self.front ? 0.95 + Math.PI : 0.95;"))
        val shell = quelle("app/src/main/assets/web/shared/app-shell.js")
        assertFalse("Dashboard nutzt die Front-Ansicht nicht", shell.contains("front: true"))
    }

    private fun wurzel(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Wurzel nicht gefunden")
    }

    private fun quelle(rel: String): String = File(wurzel(), rel).readText()
}
