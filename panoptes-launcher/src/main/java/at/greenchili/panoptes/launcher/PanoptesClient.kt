package at.greenchili.panoptes.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PanoptesClient(private val baseUrl: String = "http://127.0.0.1:8180") {
    fun summary(): LauncherSummary = LauncherContract.parseSummary(get("/api/launcher/v1/summary"))
    fun traffic(): LauncherTraffic = LauncherContract.parseTraffic(get("/api/launcher/v1/traffic"))
    fun history(): LauncherHistory = LauncherContract.parseHistory(get("/api/launcher/v1/history"))
    fun appearance(): LauncherAppearance = LauncherContract.parseAppearance(get("/api/launcher/v1/appearance"))
    fun apps(): List<LaunchableApp> = LauncherContract.parseApps(get("/api/launcher/v1/apps"))
    fun launch(packageName: String): Boolean = LauncherContract.launchSucceeded(
        request("POST", "/api/launcher/v1/apps/launch", LauncherContract.launchBody(packageName))
    )

    /**
     * App neben dem Launcher (geteilter Bildschirm, Launcher links) über den Kern-Weg `launchSplitPair`.
     * false = geht hier nicht (Auto, App oder Kern), dann öffnet der Launcher sie normal.
     */
    fun nebenOeffnen(packageName: String): NebenErgebnis = try {
        LauncherContract.parseNeben(requestMitFehler("POST", "/api/launcher/v1/apps/launch",
            LauncherContract.launchBody(packageName, "neben"), 15_000))
    } catch (_: Exception) {
        NebenErgebnis.FEHLER
    }

    /** 0.1.39 (L1): geteilten Bildschirm verlassen, Launcher im Vollbild (Kern-Weg `splitBeenden`). false = ging nicht. */
    fun splitBeenden(): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/apps/launch", LauncherContract.launchBody("", "split_ende"), 8_000))
            .optBoolean("success", false)
    } catch (_: Exception) {
        false
    }

    /** 0.1.38.5: welche Apps können neben dem Launcher laufen (Kern prüft resizeMode, nur lesen)? */
    fun teilbar(pakete: List<String>): Map<String, String> = try {
        LauncherContract.parseTeilbar(get("/api/launcher/v1/apps/teilbar?pakete=" +
            java.net.URLEncoder.encode(pakete.take(64).joinToString(","), "UTF-8")))
    } catch (_: Exception) {
        emptyMap()
    }

    /** Klima über den eng begrenzten Kern-Weg (nur die sechs Aktionen); Ablehnungen kommen mit Grund zurück. */
    fun klima(aktion: String): KlimaAntwort =
        LauncherContract.parseKlima(requestMitFehler("POST", "/api/launcher/v1/klima", LauncherContract.klimaBody(aktion), KLIMA_LESEN_MS))

    /** 0.1.44 (D1): ein Fenster auf/zu über den engen Kern-Weg (nur im Stand, nur vl/vr/hl/hr/dach). */
    fun fenster(fenster: String, ziel: String): FensterAntwort = try {
        LauncherContract.parseFenster(requestMitFehler("POST", "/api/launcher/v1/fenster", LauncherContract.fensterBody(fenster, ziel), FENSTER_LESEN_MS))
    } catch (_: Exception) {
        FensterAntwort(false, "fehler")
    }

    /** „Tacho wieder normal“: der Kern stoppt jede Projektion am Fahrerdisplay. true = alles gestoppt. */
    fun tachoNormal(): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/tacho/normal", "{}", 10_000)).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /** 0.1.41 (LC): bietet dieses Auto „Navi aufs Kombi“ an, ist die App freigegeben? Nur lesen; null = unbekannt. */
    fun kombiStand(paket: String): Kombi.Stand? = try {
        Kombi.parse(requestMitFehler("GET", "/api/launcher/v1/tacho/kombi?paket=" + java.net.URLEncoder.encode(paket, "UTF-8"), null, 8_000))
    } catch (_: Exception) { null }

    /** 0.1.41 (LC): Navi-App aufs Kombi über den Kern-Weg. null = läuft, sonst Grund des Kerns. */
    fun kombiStarten(paket: String): String? = try {
        Kombi.startGrund(requestMitFehler("POST", "/api/launcher/v1/tacho/kombi",
            org.json.JSONObject().put("paket", paket).toString(), 20_000))
    } catch (_: Exception) { "fehler" }

    /** 0.1.41 (LC): Seitenblick beim Blinken – Stand der Kern-Funktion; null = nicht erreichbar. */
    fun seitenblickStand(): Seitenblick.Stand? = try {
        Seitenblick.parse(requestMitFehler("GET", "/api/launcher/v1/seitenblick", null, 5_000))
    } catch (_: Exception) { null }

    /** 0.1.41 (LC): Seitenblick ein/aus; true nur, wenn der Kern ok meldet. */
    fun seitenblickSchalten(an: Boolean): Boolean = try {
        Seitenblick.geschaltet(requestMitFehler("POST", "/api/launcher/v1/seitenblick",
            org.json.JSONObject().put("an", an).toString(), 15_000))
    } catch (_: Exception) { false }

    fun update(): UpdateStand = LauncherContract.parseUpdate(get("/api/launcher/v1/update"))
    /** 0.1.43.3 (Rang 7): Katalog der Sprachbefehle (nur lesen); null, wenn Panoptes nicht antwortet. */
    fun sprache(): SprachKatalog? = Sprache.parse(get("/api/launcher/v1/sprache"))

    /** 0.1.38.7: Wunsch „Launcher als Startbildschirm“ aus dem Kern; null = nicht erreichbar. */
    fun homeWunsch(): HomeWunsch? = try {
        HomeAbgleich.parse(get("/api/launcher/v1/home"))
    } catch (_: Exception) {
        null
    }

    /** „Behalten“: der Kern setzt den Launcher jetzt als Standard. true = geschafft. */
    fun homeBehalten(): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/home/behalten", "{}", 20_000)).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /** „Zurück zum BYD-Startbildschirm“: der Kern setzt den Standard zurück. true = geschafft. */
    fun homeAus(): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/home/aus", "{}", 20_000)).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /** 0.1.44 (P1): Schalter „Startbildschirm“ an (Totmann bleibt, bis „Behalten“). true = geschrieben. */
    fun homeAn(): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/home/an", "{}")).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /** 0.1.44 (P1): „Zurück“ bzw. „Letzte App“ als Daemon-Taste. true = der Kern hat sie gedrückt. */
    fun taste(taste: String): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/taste",
            org.json.JSONObject().put("taste", taste).toString())).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /** 0.1.38: Layout aus dem Kern; null = Kern nicht erreichbar oder Antwort unbrauchbar (dann gilt die lokale Kopie). */
    fun layoutHolen(): KernLayout? = try {
        LayoutAbgleich.parseKern(get("/api/launcher/v1/layout"))
    } catch (_: Exception) {
        null
    }

    /** 0.1.38: Layout in den Kern schreiben, mit der zuletzt gekannten Basis; 409 = Kern ist neuer. */
    fun layoutSpeichern(l: StartLayout, basis: Long): SpeicherAntwort = try {
        LayoutAbgleich.parseSpeichern(200, request("PUT", "/api/launcher/v1/layout", LayoutAbgleich.speichernBody(l, basis), 5000))
    } catch (e: HttpFehler) {
        LayoutAbgleich.parseSpeichern(e.status, e.koerper)
    } catch (_: Exception) {
        SpeicherAntwort.Fehler(0)
    }

    /** Installieren auf Tipp; {"ok", "grund"?, "vorgemerkt"?, "text"?} als Rohtext. */
    fun installieren(paket: String, p: UpdatePaket, mobil: Boolean): String =
        requestMitFehler("POST", "/api/launcher/v1/update/installieren", LauncherContract.installierenBody(paket, p, mobil), 10_000)

    /** Eigenes Hintergrundbild aus der Dateiablage; null, wenn keines da ist. Höchstens 15 MB. */
    fun hintergrundBild(): ByteArray? = bild("/api/launcher/v1/hintergrund/bild")

    /**
     * Ruhiges Standbild des Fahrzeugs aus Panoptes (dasselbe Renderbild wie im Panoptes-Dashboard,
     * `/api/models/art`, aus den Katalog-Modellen gerendert); null, wenn nicht erreichbar.
     */
    fun fahrzeugBild(modelId: String): ByteArray? = bild("/api/models/art?id=" + java.net.URLEncoder.encode(modelId, "UTF-8"))

    private fun bild(pfad: String): ByteArray? {
        val connection = (URL(baseUrl + pfad).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1800
            readTimeout = 5000
            useCaches = false
        }
        return try {
            if (connection.responseCode != 200) return null
            val laenge = connection.contentLengthLong
            if (laenge > MAX_BILD) return null
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val puffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(puffer)
                    if (n < 0) break
                    out.write(puffer, 0, n)
                    if (out.size() > MAX_BILD) return null
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Hintergrund-Video als Stream-Adresse (nur Loopback, Kern prüft Schalter und Datei). */
    /** 0.1.44 (P4): Haus-Status (Home Assistant, nur lesen) für die gewählten Geräte; ohne [ids] die ganze Liste. null = nicht lesbar. */
    fun haus(ids: List<String>?): String? = try {
        requestMitFehler("GET", "/api/launcher/v1/haus" + (ids?.let { "?ids=" + java.net.URLEncoder.encode(it.joinToString(","), "UTF-8") } ?: ""), null, 5_000)
    } catch (_: Exception) { null }

    /** 0.1.44 (P4): gespeicherte Sitzpositionen und die gerade passende (nur lesen). null = nicht lesbar. */
    fun sitz(): String? = try {
        requestMitFehler("GET", "/api/launcher/v1/sitz", null, 8_000)
    } catch (_: Exception) { null }

    /** M1: laufende Musik (Titel/Interpret) vom Kern, nur lesen; null = nicht erreichbar. */
    fun musik(fokus: String?): String? = try {
        requestMitFehler("GET", "/api/launcher/v1/musik" + (fokus?.let { "?fokus=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), null, 4_000)
    } catch (_: Exception) { null }

    /** Kamera im Launcher: Liste der Aufnahmen vom Kern, nur lesen (Zeitfenster und Sortierung aus festen Listen). null = nicht erreichbar. */
    fun aufnahmen(zeit: String, sort: String): String? = try {
        requestMitFehler("GET", "/api/launcher/v1/aufnahmen?zeit=" + (if (zeit in Kamera.ZEITEN) zeit else "alle") + "&sort=" + (if (sort in Kamera.SORTIERUNGEN) sort else "neu"), null, 8_000)
    } catch (_: Exception) { null }

    val hintergrundVideoUrl: String get() = "$baseUrl/api/launcher/v1/hintergrund/video"

    /** 0.1.44 (L1): Live-Umgebung; jede Abfrage hält die Kern-Erkennung wach, nur solange die Kachel sichtbar ist. */
    fun umgebung(): Umgebung.Stand? = Umgebung.parse(get("/api/launcher/v1/umgebung"))
    fun umgebungSchalten(an: Boolean): Boolean = try {
        org.json.JSONObject(requestMitFehler("POST", "/api/launcher/v1/umgebung", Umgebung.schaltenBody(an), 5_000)).optBoolean("ok", false)
    } catch (e: Exception) { false }

    private fun get(path: String): String = request("GET", path, null)

    /** Wie [request], liefert aber auch den Körper einer 4xx-Antwort (Grund der Ablehnung). */
    private fun requestMitFehler(method: String, path: String, body: String?, lesenMs: Int = 2500): String = try {
        request(method, path, body, lesenMs)
    } catch (e: HttpFehler) {
        e.koerper
    }

    private fun request(method: String, path: String, body: String?, lesenMs: Int = 2500): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 1800
            readTimeout = lesenMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (status !in 200..299) throw HttpFehler(status, text)
            text
        } finally {
            connection.disconnect()
        }
    }

    class HttpFehler(val status: Int, val koerper: String) : IllegalStateException("HTTP $status")

    companion object {
        /** Fahrzeug-Seite der Panoptes-Weboberfläche (3D-Modell aus dem Katalog), nur Loopback. */
        const val FAHRZEUG_SEITE = "http://127.0.0.1:8180/local/launcher-fahrzeug.html"
        private const val MAX_BILD = 15L * 1024 * 1024
        /** Klima an wartet im Kern auf das Echo der Anlage (einige Sekunden). */
        private const val KLIMA_LESEN_MS = 15_000
        /** Fenster warten im Kern auf das Echo (FENSTER_ECHO_MS). */
        private const val FENSTER_LESEN_MS = 20_000
    }
}
