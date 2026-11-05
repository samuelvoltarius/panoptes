package at.greenchili.surfmate.navmap.nav

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G3: natives Navi an eigenen Diensten. Routing ueber Kern /api/navi/fuehrung (Vertrag fuehrung/1), Suche ueber Kern
 * /api/navi/orte und /api/navi/link - kein Routing-Schluessel, keine Fremddienste fuer Suche und Route aus dem Auto.
 */
class FuehrungKernTest {

    private fun quelle(rel: String): String {
        val p = "src/main/$rel"
        return (listOf(File(p), File("app/$p")).first { it.isFile }).readText()
    }

    // ---- Anfrage an den Kern (fuehrung/1 §3)

    @Test
    fun anfrageNurRouteOhneVia() {
        val b = JSONObject(ValhallaRouteClient.buildFuehrungBody(
            listOf(GeoPoint(48.306, 14.286), GeoPoint(48.208, 16.372)), null, "de-DE"))
        assertEquals(setOf("start", "ziel", "sprache"), b.keySet())
        assertEquals(48.306, b.getJSONArray("start").getDouble(0), 0.0)
        assertEquals(14.286, b.getJSONArray("start").getDouble(1), 0.0)
        assertEquals(16.372, b.getJSONArray("ziel").getDouble(1), 0.0)
        assertEquals("de", b.getString("sprache"))
    }

    @Test
    fun anfrageMitViaUndKurs() {
        val pts = listOf(GeoPoint(47.07, 15.44), GeoPoint(47.5, 15.0), GeoPoint(48.0, 14.5), GeoPoint(48.2, 16.37))
        val b = JSONObject(ValhallaRouteClient.buildFuehrungBody(pts, -90.0, "fr"))
        val via: JSONArray = b.getJSONArray("via")
        assertEquals(2, via.length())
        assertEquals(47.5, via.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(14.5, via.getJSONArray(1).getDouble(1), 0.0)
        assertEquals("kurs auf 0-359 gebracht", 270, b.getInt("kurs"))
        assertEquals("fr", b.getString("sprache"))
        assertEquals(0, JSONObject(ValhallaRouteClient.buildFuehrungBody(pts.take(2), 360.0, "de")).getInt("kurs"))
        assertFalse(JSONObject(ValhallaRouteClient.buildFuehrungBody(pts.take(2), Double.NaN, "de")).has("kurs"))
    }

    @Test
    fun spracheAufFuenf() {
        assertEquals("nl", ValhallaRouteClient.sprache("nl-NL"))
        assertEquals("it", ValhallaRouteClient.sprache("it"))
        assertEquals("en", ValhallaRouteClient.sprache("zh-CN"))
        assertEquals("en", ValhallaRouteClient.sprache(null))
    }

    @Test
    fun hoechstensFuenfZwischenstopps() {
        assertEquals(5, ValhallaRouteClient.MAX_VIA)
        val zuViele = (0..7).map { GeoPoint(47.0 + it * 0.1, 15.0) }      // 6 Zwischenstopps
        assertTrue(ValhallaRouteClient.routeVia(zuViele).isEmpty())
        assertEquals(ValhallaRouteClient.RouteError.HTTP, ValhallaRouteClient.lastError)
        assertEquals(400, ValhallaRouteClient.lastHttpCode)
    }

    // ---- Antwort (fuehrung/1 §4): Valhalla-trip unveraendert -> vorhandener Parser

    private fun leg(shape: String, typen: List<Int>, km: Double) = JSONObject()
        .put("shape", shape)
        .put("summary", JSONObject().put("length", km).put("time", 600))
        .put("maneuvers", JSONArray().apply {
            typen.forEachIndexed { i, t ->
                put(JSONObject().put("type", t).put("instruction", "M$i").put("begin_shape_index", i)
                    .put("length", 0.5).put("time", 30)
                    .put("verbal_pre_transition_instruction", "Gleich M$i").put("roundabout_exit_count", if (t == 26) 2 else 0))
            }
        })

    @Test
    fun fuehrungsAntwortWirdGeparst() {
        val shape = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val antwort = JSONObject()
            .put("ok", true).put("vertrag", "fuehrung/1").put("instanz", "eu").put("rueckfall", JSONObject.NULL)
            .put("halte", JSONArray().put(JSONObject().put("art", "start").put("bein_ab", 0))
                .put(JSONObject().put("art", "via").put("nr", 1).put("bein_bis", 0).put("bein_ab", 1))
                .put(JSONObject().put("art", "ziel").put("bein_bis", 1)))
            .put("plan", JSONObject.NULL)
            .put("trip", JSONObject()
                .put("legs", JSONArray().put(leg(shape, listOf(1, 26, 4), 10.0)).put(leg(shape, listOf(1, 15, 4), 5.0)))
                .put("summary", JSONObject().put("length", 15.0).put("time", 1200)))
        val routes = ValhallaRouteClient.parseRoutes(antwort.toString())
        assertEquals("fuehrung/1 liefert genau eine Route", 1, routes.size)
        val r = routes[0]
        assertEquals(15000.0, r.totalDistanceMeters, 1e-6)
        assertEquals(1200.0, r.totalDurationSeconds, 1e-6)
        assertEquals("nur die letzte Ankunft bleibt", 1, r.maneuvers.count { it.type in 4..6 })
        assertEquals(2, r.maneuvers.first { it.type == 26 }.roundaboutExitCount)
        assertEquals("Gleich M1", r.maneuvers.first { it.type == 26 }.verbalPre)
        assertTrue(r.points.size >= 3)
    }

    @Test
    fun fehlercodesDesKerns() {
        assertEquals(ValhallaRouteClient.RouteError.AUTH, ValhallaRouteClient.fehlerArt(401))
        assertEquals(ValhallaRouteClient.RouteError.LIMIT, ValhallaRouteClient.fehlerArt(429))
        assertEquals(ValhallaRouteClient.RouteError.EMPTY, ValhallaRouteClient.fehlerArt(422))
        assertEquals(ValhallaRouteClient.RouteError.NETWORK, ValhallaRouteClient.fehlerArt(503))
        assertEquals(ValhallaRouteClient.RouteError.NETWORK, ValhallaRouteClient.fehlerArt(502))
        assertEquals(ValhallaRouteClient.RouteError.HTTP, ValhallaRouteClient.fehlerArt(400))
        assertEquals(17, ValhallaRouteClient.retryAfterS(" 17 ", null))
        assertEquals(30, ValhallaRouteClient.retryAfterS(null, "{\"ok\":false,\"warte_ms\":30500}"))
        assertEquals("Kern 429", 12, ValhallaRouteClient.retryAfterS(null, "{\"ok\":false,\"code\":\"zu_viele\",\"warte_s\":12}"))
        assertEquals("Kern-Pause", 45, ValhallaRouteClient.retryAfterS(null, "{\"code\":\"routing_weg\",\"grund\":\"netz\",\"wiederInS\":45}"))
        assertEquals(0, ValhallaRouteClient.retryAfterS(null, "<html>"))
        assertEquals(3600, ValhallaRouteClient.retryAfterS("99999", null))
    }

    // ---- Suche ueber den Kern

    @Test
    fun orteAntwortWieNaviHtml() {
        val j = "{\"orte\":[{\"name\":\"Linz, Oberösterreich, Österreich\",\"kurz\":\"Linz\",\"lat\":48.3,\"lon\":14.29}," +
            "{\"name\":\"Wien\",\"kurz\":\"Wien\",\"lat\":48.2,\"lon\":16.37},{\"name\":\"\",\"lat\":1,\"lon\":2}," +
            "{\"name\":\"Graz\",\"lat\":47.07,\"lon\":15.44}]}"
        val r = ForwardGeocoder.parseOrte(j, 6)
        assertEquals(listOf("Linz, Oberösterreich, Österreich", "Wien", "Graz"), r.map { it.label })
        assertEquals(14.29, r[0].lng, 0.0)
        assertEquals(2, ForwardGeocoder.parseOrte(j, 2).size)
        assertTrue(ForwardGeocoder.parseOrte("kaputt", 6).isEmpty())
    }

    @Test
    fun linkAntwort() {
        val r = ForwardGeocoder.parseLink("{\"ok\":true,\"lat\":48.2,\"lng\":16.37,\"name\":\"Stephansplatz\",\"quelle\":\"link\"}")
        assertEquals("Stephansplatz", r!!.label)
        assertEquals(16.37, r.lng, 0.0)
        assertEquals("48.20000, 16.37000", ForwardGeocoder.parseLink("{\"ok\":true,\"lat\":48.2,\"lng\":16.37}")!!.label)
        assertNull(ForwardGeocoder.parseLink("{\"ok\":false,\"grund\":\"kurzlink\"}"))
    }

    @Test
    fun sucheGehtNurAnDenKern() {
        val gesendet = ArrayList<Pair<String, String>>()
        val alt = ForwardGeocoder.senden
        ForwardGeocoder.senden = { pfad, body ->
            gesendet.add(pfad to body)
            if (pfad == "/api/navi/orte") 200 to "{\"orte\":[{\"name\":\"Graz\",\"lat\":47.07,\"lon\":15.44}]}"
            else 200 to "{\"ok\":true,\"lat\":48.2,\"lng\":16.37,\"name\":\"Ziel\"}"
        }
        try {
            assertEquals("Graz", ForwardGeocoder.search("Graz", 3, 47.1, 15.4).single().label)
            assertEquals("/api/navi/orte", gesendet.last().first)
            val body = JSONObject(gesendet.last().second)
            assertEquals(setOf("q", "limit"), body.keySet())           // keine Position geht hinaus
            assertEquals("Ziel", ForwardGeocoder.search("https://maps.app.goo.gl/abc", 3).single().label)
            assertEquals("/api/navi/link", gesendet.last().first)
            assertTrue(JSONObject(gesendet.last().second).getBoolean("aufloesen"))
            gesendet.clear()
            assertEquals("offline, nichts gesendet", 1, ForwardGeocoder.search("48.2, 16.37", 3).size)
            assertTrue(ForwardGeocoder.autocomplete("Gr", 6).isEmpty())   // < 3 Zeichen: nicht fragen
            assertTrue(gesendet.isEmpty())
            assertNull(ForwardGeocoder.reverse(48.2, 16.37))
        } finally {
            ForwardGeocoder.senden = alt
        }
    }

    // ---- Menueweg zum Planer-Verbinden: echt aus app-shell.js SECTIONS + system.html (Koordination 05.11.2026)

    @Test
    fun menuewegZumPlanerStimmtInFuenfSprachen() {
        val shell = quelle("assets/web/shared/app-shell.js")
        assertTrue(Regex("id: 'system'[^\\n]*i18n: 'shell.sec_einstellungen'").containsMatchIn(shell))
        assertTrue(shell.contains("href: 'system.html',          label: 'Alle Einstellungen', i18n: 'shell.page_einstellungen_alle'"))
        val sys = quelle("assets/web/local/system.html")
        assertTrue(sys.contains("id=\"planerKarte\"") && sys.contains("data-i18n=\"system.planer_title\""))
        val res = mapOf("de" to "values-de", "en" to "values", "fr" to "values-fr", "it" to "values-it", "nl" to "values-nl")
        for ((l, ordner) in res) {
            val t = JSONObject(quelle("assets/web/i18n/$l.json"))
            val weg = t.getJSONObject("shell").getString("sec_einstellungen") + " → " +
                t.getJSONObject("shell").getString("page_einstellungen_alle") + " → " +
                t.getJSONObject("system").getString("planer_title")
            val knopf = t.getJSONObject("system").getString("planer_connect")
            val xml = quelle("res/$ordner/strings.xml").replace("\\'", "'")
            val s = Regex("<string name=\"roadsense_map_route_planer_verbinden\">([^<]*)</string>").find(xml)!!.groupValues[1]
            assertTrue("$l Android: $s", s.contains(weg) && s.contains(knopf))
            val hilfe = t.getJSONObject("road_sense").getString("routing_help")
            assertTrue("$l Hilfe: $hilfe", hilfe.contains(weg) && hilfe.contains(knopf))
            assertFalse("$l alter Weg", s.contains("System →") || s.contains("Système →") || s.contains("Sistema →") || s.contains("Systeem →"))
        }
    }

    // ---- Kandidat B (E2E 05.11.): kein stiller Ausfall, kein Autofokus, Fahrsperre im Suchfeld

    @Test
    fun kandidatBHinweiseUndFahrsperre() {
        val a = quelle("java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt")
        // (a) Karte fehlt / kein Standort: Hinweis, keine Anfrage
        assertTrue(a.contains("mainHandler.postDelayed(karteFehltPruefung, KARTE_WARTE_MS)"))
        assertTrue(a.contains("R.string.roadsense_map_fuehrung_keine_karte"))
        val ohneFix = a.substringAfter("if (lastFix == null) {").substringBefore("resume()")
        assertTrue(ohneFix.contains("roadsense_map_fuehrung_kein_standort") && ohneFix.contains("return@post"))
        // (f) kein Autofokus: Fenster ohne Tastatur, Wurzel nimmt den ersten Fokus
        val manifest = quelle("AndroidManifest.xml")
        val act = manifest.substringAfter("android:name=\"at.greenchili.surfmate.navmap.RoadSenseMapActivity\"").substringBefore("/>")
        assertTrue(act.contains("android:windowSoftInputMode=\"stateHidden\""))
        val layout = quelle("res/layout/activity_roadsense_map.xml")
        assertTrue(layout.substringAfter("android:id=\"@+id/mapRoot\"").substringBefore(">").contains("android:focusableInTouchMode=\"true\""))
        // Fahrsperre: jede ausdrueckliche Tastatur haengt an fahrsperre()
        assertEquals(2, Regex("if \\(gesperrt\\) showSnackbar").findAll(a).count())
        assertEquals("nur die zwei gesperrten showSoftInput", 2, Regex("imm\\.showSoftInput\\(input").findAll(a).count())
        assertTrue(a.contains("input.showSoftInputOnFocus = !fahrsperre()"))
        assertTrue(a.contains("const val FAHRT_SPERRE_MPS = 1.5"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = quelle("res/$d/strings.xml")
            for (k in listOf("roadsense_map_fuehrung_keine_karte", "roadsense_map_fuehrung_kein_standort", "roadsense_map_suche_fahrt"))
                assertTrue("$d $k", x.contains("<string name=\"$k\">"))
        }
    }

    // ---- Keine Fremddienste, kein Schluessel, kein Positionslog

    @Test
    fun keineFremdadressenUndKeinSchluessel() {
        val route = quelle("java/at/greenchili/surfmate/navmap/nav/ValhallaRouteClient.kt")
        val suche = quelle("java/at/greenchili/surfmate/navmap/nav/ForwardGeocoder.kt")
        for ((name, s) in listOf("ValhallaRouteClient" to route, "ForwardGeocoder" to suche)) {
            assertFalse("$name: keine https-Adresse", s.contains("\"https://"))
            assertFalse("$name: kein api_key", s.contains("api_key"))
            assertFalse("$name: kein NavMapConfig", s.contains("NavMapConfig"))
            assertTrue("$name: nur der eigene Kern", s.contains("http://127.0.0.1:8180"))
            // Kein Ort im Log: Log-Zeilen enthalten weder lat/lng noch den Body
            Regex("Log\\.[wdie]\\(TAG, \"([^\"]*)\"").findAll(s).forEach {
                val z = it.groupValues[1]
                assertFalse("$name loggt Ort/Body: $z", Regex("lat|lng|lon|body|query|\\\$q\\b").containsMatchIn(z))
            }
        }
        assertFalse(File("src/main/java/at/greenchili/surfmate/navmap/NavMapConfig.java").isFile ||
            File("app/src/main/java/at/greenchili/surfmate/navmap/NavMapConfig.java").isFile)
        val html = quelle("assets/web/local/road-sense.html")
        val js = quelle("assets/web/shared/road-sense.js")
        assertFalse(html.contains("rsRoutingKey") || js.contains("/api/navmap/routing/"))
        assertFalse(Regex("(?i)stadia").containsMatchIn(html + js))
        assertFalse(quelle("java/at/greenchili/surfmate/server/NavMapApiHandler.java").contains("equals(\"/api/navmap/routing/"))
    }
}
