package at.greenchili.surfmate.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.1.43.22 (Upstream Overdrive #475 Relay-Teil + #686): Mobilfunk-Relay nur für Hotspot-Clients
 * und Loopback, atomares Sitzungslimit, keine Proxy-Zugangsdaten zum Ziel, keine lokalen Ziele.
 */
class CellularRelaySicherTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun nurHotspotSubnetzUndLoopbackDuerfenRein() {
        val ap = "192.168.43."
        assertTrue(CellularRelay.isAllowedClient(ip("192.168.43.17"), ap))
        assertTrue(CellularRelay.isAllowedClient(ip("127.0.0.1"), ap))
        assertTrue(CellularRelay.isAllowedClient(ip("::1"), ap))
        assertTrue("IPv4-gemappt", CellularRelay.isAllowedClient(ip("::ffff:192.168.43.5"), ap))
        // fremde Netze: Heim-WLAN, Tailscale, Mobilfunk, anderes /24
        assertFalse(CellularRelay.isAllowedClient(ip("192.168.1.20"), ap))
        assertFalse("Präfix darf nicht über Ziffern hinaus passen", CellularRelay.isAllowedClient(ip("192.168.4.3"), "192.168.4"))
        assertFalse(CellularRelay.isAllowedClient(ip("192.168.143.3"), ap))
        assertFalse(CellularRelay.isAllowedClient(ip("100.64.0.9"), ap))
        assertFalse(CellularRelay.isAllowedClient(ip("10.0.0.7"), ap))
        assertFalse(CellularRelay.isAllowedClient(ip("2001:db8::7"), ap))
        assertFalse(CellularRelay.isAllowedClient(null, ap))
    }

    @Test
    fun ohneBekanntesSubnetzNurLoopback() {
        assertTrue(CellularRelay.isAllowedClient(ip("127.0.0.1"), null))
        assertFalse(CellularRelay.isAllowedClient(ip("192.168.43.17"), null))
        assertFalse("kaputtes Präfix → zu", CellularRelay.isAllowedClient(ip("192.168.43.17"), ""))
        assertFalse(CellularRelay.isAllowedClient(ip("10.1.2.3"), "10.1.2."))
        // Subnetz ist live: ein anderes 192.168.x vom Framework gilt
        assertTrue(CellularRelay.isAllowedClient(ip("192.168.50.9"), "192.168.50."))
    }

    @Test
    fun lokaleUndPrivateZieleGesperrt() {
        for (z in listOf("127.0.0.1", "::1", "0.0.0.0", "10.0.0.1", "172.16.5.5", "192.168.43.1",
                "169.254.1.1", "fe80::1", "fd00::1", "224.0.0.1", "100.64.0.1", "100.127.255.1",
                "::ffff:127.0.0.1", "::ffff:192.168.1.1")) {
            assertFalse(z, CellularRelay.isAllowedDestination(ip(z)))
        }
        for (z in listOf("198.51.100.7", "203.0.113.7", "100.128.0.1", "2001:db8::5", "64:ff9b::cb00:7107")) {
            assertTrue(z, CellularRelay.isAllowedDestination(ip(z)))
        }
    }

    @Test
    fun proxyKopfzeilenErreichenDasZielNie() {
        val head = "GET http://example.org/a HTTP/1.1\r\n" +
            "Host: example.org\r\n" +
            "proxy-authorization: Basic dXNlcjpnZWhlaW0=\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Authorization: Bearer ziel-eigen\r\n" +
            "\r\n"
        val clean = CellularRelay.originRequestWithoutProxyHeaders(head)
        assertNotNull(clean)
        clean!!
        assertFalse(clean.contains("dXNlcjpnZWhlaW0="))
        assertFalse(clean.contains("Proxy-", ignoreCase = true))
        assertTrue("Ziel-Authorization bleibt", clean.contains("Authorization: Bearer ziel-eigen\r\n"))
        assertTrue(clean.startsWith("GET http://example.org/a HTTP/1.1\r\nHost: example.org\r\n"))
        assertTrue(clean.endsWith("\r\n\r\n"))
    }

    @Test
    fun anfrageOhneProxyKopfBleibtByteGleich() {
        val head = "GET http://example.org/ HTTP/1.1\r\nHost: example.org\r\nAccept: */*\r\n\r\n"
        assertEquals(head, CellularRelay.originRequestWithoutProxyHeaders(head))
    }

    @Test
    fun gefalteteOderKaputteKopfzeilenWerdenAbgewiesen() {
        assertNull("gefaltet", CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://e.org/ HTTP/1.1\r\nHost: e.org\r\nX-A: 1\r\n Proxy-Authorization: Basic eA==\r\n\r\n"))
        assertNull("Leerzeichen vor Doppelpunkt", CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://e.org/ HTTP/1.1\r\nProxy-Authorization : Basic eA==\r\n\r\n"))
        assertNull("ohne Doppelpunkt", CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://e.org/ HTTP/1.1\r\nKaputt\r\n\r\n"))
        assertNull("nur LF", CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://e.org/ HTTP/1.1\nHost: e.org\n\n"))
    }

    @Test
    fun sitzungslimitHaeltUnterGleichzeitigemAnsturm() {
        val gate = RelayAdmissionGate(8)
        val pool = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)
        val ok = AtomicInteger(0)
        val fertig = CountDownLatch(32)
        repeat(32) {
            pool.execute {
                start.await()
                if (gate.tryAcquire()) ok.incrementAndGet()
                fertig.countDown()
            }
        }
        start.countDown()
        assertTrue(fertig.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(8, ok.get())
        assertEquals(8, gate.activeCount())
        gate.release()
        assertTrue("frei gewordener Platz ist wieder nutzbar", gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }

    @Test(expected = IllegalStateException::class)
    fun doppeltesFreigebenFaellt() {
        val gate = RelayAdmissionGate(1)
        assertTrue(gate.tryAcquire())
        gate.release()
        gate.release()
    }

    /** Verdrahtung im Quelltext: Prüfung vor Übergabe, Freigabe im finally, Stopp schließt Tunnel. */
    @Test
    fun verdrahtung() {
        val r = File("src/main/java/at/greenchili/surfmate/network/CellularRelay.kt").readText().replace("\r\n", "\n")
        val accept = r.substringAfter("private fun acceptLoop(").substringBefore("private fun freigeben(")
        val pruef = accept.indexOf("isAllowedClient(client.inetAddress, currentApPrefix())")
        val platz = accept.indexOf("gate.tryAcquire()")
        val exec = accept.indexOf("pool.execute { serve(client, key) }")
        assertTrue(pruef in 0 until platz)
        assertTrue(platz in 0 until exec)
        assertFalse("alter, nicht atomarer Zähler weg", r.contains("tunnels.get() >= MAX_TUNNELS"))
        assertTrue(r.contains("freigeben(client, key)\n        }\n    }\n\n    /** Open a cellular-bound socket"))
        val stop = r.substringAfter("private fun stopLocked() {").substringBefore("// ====")
        assertTrue(stop.contains("liveSockets.clear()"))
        assertTrue(stop.contains("apServer?.close()"))
        assertTrue(r.contains("up.getOutputStream().write(originRequest.toByteArray(Charsets.ISO_8859_1))"))
        assertFalse(r.contains("up.getOutputStream().write(text.toByteArray"))
        assertTrue(r.contains("!isAllowedDestination(addr)"))
        // Upstream v65: nie die Wildcard binden, nur Loopback und die Hotspot-Adresse
        assertFalse(r.contains("InetSocketAddress(\"0.0.0.0\""))
        assertTrue(r.contains("ss.bind(InetSocketAddress(LOOPBACK, PORT))"))
        assertTrue(r.contains("ss.bind(InetSocketAddress(address, PORT))"))
        assertEquals("127.0.0.1", CellularRelay.LOOPBACK)
        val h = File("src/main/java/at/greenchili/surfmate/network/HotspotManager.kt").readText()
        assertTrue(h.contains("CellularRelay.start(ctx) { relayClientPrefix() }"))
        assertTrue(h.contains("CellularRelay.bindHotspot(relayBindAddress())"))
        assertTrue("Sampler bindet nach", h.contains("if (relayAwaitingAddress) syncClientRelay()"))
        assertTrue(h.contains("out.put(\"relay\", CellularRelay.status())"))
    }

    /**
     * Koordination 26.11. (Sichtung 2): `CONNECT 127.0.0.1:8180` darf nicht durch — AuthMiddleware vertraut
     * Loopback ohne Login. Ziel „localhost“ ist im dial() zusätzlich per Name gesperrt.
     */
    @Test
    fun connectAufLoopbackWirdAbgewiesen() {
        for (z in listOf("127.0.0.1", "127.1.2.3", "::1", "0.0.0.0", "::", "::ffff:127.0.0.1")) {
            assertFalse(z, CellularRelay.isAllowedDestination(ip(z)))
        }
        // Hotspot-Netz als Ziel ebenfalls zu (auch die Head Unit auf ihrer AP-Adresse)
        assertFalse(CellularRelay.isAllowedDestination(ip("192.168.43.1")))
        assertFalse(CellularRelay.isAllowedDestination(ip("192.168.43.20")))
        val r = File("src/main/java/at/greenchili/surfmate/network/CellularRelay.kt").readText()
        assertTrue(r.contains("if (host.equals(\"localhost\", ignoreCase = true) || !isAllowedDestination(addr)) {"))
    }

    @Test
    fun clientAusserhalbDesHotspotsWirdAbgewiesen() {
        val ap = "192.168.43."
        // Auto als WLAN-Client im Heimnetz, Tailnet, Mobilfunk-Adresse
        for (c in listOf("192.168.1.30", "100.64.0.9", "10.20.30.40", "2001:db8::9", "fe80::1")) {
            assertFalse(c, CellularRelay.isAllowedClient(ip(c), ap))
        }
        assertTrue(CellularRelay.isAllowedClient(ip("192.168.43.99"), ap))
    }

    @Test
    fun nurKonkreteHotspotAdresseWirdGebunden() {
        assertTrue(CellularRelay.isBindableHotspotAddress("192.168.43.1"))
        assertTrue(CellularRelay.isBindableHotspotAddress("192.168.50.1"))
        for (a in listOf(null, "", "0.0.0.0", "127.0.0.1", "10.0.0.1", "192.168.43.0", "192.168.43.255",
                "192.168.300.1", "fe80::1", "192.168.43")) {
            assertFalse(a.toString(), CellularRelay.isBindableHotspotAddress(a))
        }
        assertTrue("getrimmt", CellularRelay.isBindableHotspotAddress(" 192.168.43.1 "))
    }
}
