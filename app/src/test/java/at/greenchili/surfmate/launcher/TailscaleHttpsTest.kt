package at.greenchili.surfmate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Paths

/**
 * /api/tailscale/https (0.1.9, Koordination 21.10., Regel B): Standard aus, nur eigene 443-Regel,
 * nie `serve reset`, tailscaled wird nie angefasst, nicht in der Kopplungs-Erlaubnisliste.
 */
class TailscaleHttpsTest {

    private val dns = "seagull.tail1234.ts.net"
    private val statusAn = """{"BackendState":"Running","Self":{"DNSName":"$dns."},"CertDomains":["$dns"]}"""
    private val statusOhneHttps = """{"BackendState":"Running","Self":{"DNSName":"$dns."}}"""
    private val statusAus = """{"BackendState":"Stopped"}"""
    private val dashboard = """"8180":{"TCPForward":"127.0.0.1:8180","ProxyProtocol":1}"""
    private val serveFrei = """{"TCP":{$dashboard}}"""
    private val serveEigen = """{"TCP":{$dashboard,"443":{"HTTPS":true}},""" +
        """"Web":{"$dns:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8180"}}}}}"""
    private val serveFremd = """{"TCP":{$dashboard,"443":{"TCPForward":"127.0.0.1:9999"}}}"""

    /** Nachgebaute Shell: merkt sich Befehle, liefert Zustand, Serve-Regel und Merkdatei. */
    private class FakeShell(var status: String, var serve: String, var merk: String = "") :
        TailscaleHttps.Shell {
        val befehle = mutableListOf<String>()
        var nachEinschalten: String? = null
        override fun run(cmd: String, timeoutSec: Int): String {
            befehle += cmd
            return when {
                cmd.endsWith("status --json") && cmd.contains("serve") -> serve
                cmd.endsWith("status --json") -> status
                cmd.startsWith("cat ") -> merk
                cmd.contains("echo true >") -> { merk = "true"; "" }
                cmd.contains("echo false >") -> { merk = "false"; "" }
                cmd.contains("serve --bg --https=443") -> { nachEinschalten?.let { serve = it }; "" }
                cmd.contains("serve --https=443 off") -> { serve = serveFreiStatic; "" }
                else -> ""
            }
        }
        companion object { val serveFreiStatic = """{"TCP":{"8180":{"TCPForward":"127.0.0.1:8180","ProxyProtocol":1}}}""" }
    }

    @Test
    fun standardAus() {
        val z = TailscaleHttps.zustand(statusAn, serveFrei, null)
        assertTrue(z.getBoolean("tailscale"))
        assertTrue(z.getBoolean("httpsMoeglich"))
        assertFalse(z.getBoolean("an"))
        assertTrue(z.isNull("adresse"))
        assertTrue(z.isNull("grund"))
    }

    @Test
    fun gruende() {
        assertEquals("kein_tailscale", TailscaleHttps.zustand(statusAus, "null", "true").getString("grund"))
        assertEquals("https_im_tailnet_aus", TailscaleHttps.zustand(statusOhneHttps, serveFrei, null).getString("grund"))
        assertEquals("belegt", TailscaleHttps.zustand(statusAn, serveFremd, null).getString("grund"))
        assertEquals("zertifikat_fehlt", TailscaleHttps.zustand(statusAn, serveFrei, "true").getString("grund"))
    }

    @Test
    fun einschaltenSetztNurDieEigeneRegelUndLiestZurueck() {
        val sh = FakeShell(statusAn, serveFrei)
        sh.nachEinschalten = serveEigen
        val z = TailscaleHttps.setzen(true, sh)
        assertTrue(z.toString(), z.getBoolean("an"))
        assertEquals("https://$dns", z.getString("adresse"))
        assertEquals("true", sh.merk)
        assertTrue(sh.befehle.any { it.contains("serve --bg --https=443 http://127.0.0.1:8180") })
        keineGefaehrlichenBefehle(sh)
    }

    @Test
    fun einschaltenOhneHttpsImTailnetAendertNichts() {
        val sh = FakeShell(statusOhneHttps, serveFrei)
        val z = TailscaleHttps.setzen(true, sh)
        assertFalse(z.getBoolean("an"))
        assertEquals("https_im_tailnet_aus", z.getString("grund"))
        assertTrue(sh.befehle.none { it.contains("echo true") || it.contains("--https=443") })
    }

    @Test
    fun fremdeRegelAuf443WirdNieAngefasst() {
        val sh = FakeShell(statusAn, serveFremd, "true")
        TailscaleHttps.setzen(true, sh)
        TailscaleHttps.setzen(false, sh)
        assertTrue(sh.befehle.none { it.contains("--https=443") || it.contains("--tls-terminated-tcp") })
        keineGefaehrlichenBefehle(sh)
    }

    @Test
    fun ausschaltenZiehtNurDieEigeneRegelZurueck() {
        val sh = FakeShell(statusAn, serveEigen, "true")
        val z = TailscaleHttps.setzen(false, sh)
        assertFalse(z.getBoolean("an"))
        assertEquals("false", sh.merk)
        assertTrue(sh.befehle.any { it.contains("serve --https=443 off") })
        keineGefaehrlichenBefehle(sh)
    }

    @Test
    fun nichtInDerKopplungsErlaubnisliste() {
        val guard = String(Files.readAllBytes(Paths.get(
            "src/main/java/at/greenchili/surfmate/server/ExternGuard.java")), Charsets.UTF_8)
        assertFalse(guard.contains("/api/tailscale"))
    }

    private fun keineGefaehrlichenBefehle(sh: FakeShell) {
        for (b in sh.befehle) {
            assertFalse(b, b.contains("serve reset"))
            assertFalse(b, b.contains("kill"))
            assertFalse(b, b.contains("tailscaled"))
            assertFalse(b, b.contains(" down"))
            assertFalse(b, b.contains(" up"))
            assertFalse(b, b.contains("logout"))
        }
    }

    @Suppress("unused")
    private fun json(s: String) = JSONObject(s)
}
