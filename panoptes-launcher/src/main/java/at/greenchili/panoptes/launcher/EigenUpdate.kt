package at.greenchili.panoptes.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 0.1.43.4 (Paket S, Punkt 4): Update-Hinweis OHNE Panoptes. Ist der Kern nicht da, liest der Launcher selbst nur das
 * öffentliche GitHub-Release (`version.json` der neuesten Nicht-Vorabversion, Format .github/workflows/release.yml,
 * Eintrag „launcher“). Er zeigt einen Hinweis und installiert nie selbst: Auf Tipp lädt er die APK, prüft sha256 und
 * übergibt sie dem Android-Installer; dort bestätigt der Nutzer. Android prüft dabei die Signatur (nur dieselbe
 * Signatur kann den Launcher ersetzen). Mit Kern bleibt es beim Kern-Weg (Update-Karte aus /update).
 * Höchstens einmal am Tag, kein Token, keine anderen Adressen.
 */
object EigenUpdate {
    const val REPO = "samuelvoltarius/panoptes"
    const val VERSION_JSON = "https://github.com/$REPO/releases/latest/download/version.json"
    const val TAKT_MS = 24 * 60 * 60_000L
    /** Nach einem Fehlschlag (kein Netz) nicht vor dieser Zeit nochmal. */
    const val FEHL_TAKT_MS = 6 * 60 * 60_000L
    const val HOECHSTENS_JSON = 64 * 1024
    const val HOECHSTENS_APK = 80L * 1024 * 1024

    private val APK = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk")
    private val TAG = Regex("v[0-9][A-Za-z0-9._-]{0,60}")
    private val SHA = Regex("[0-9a-f]{64}")

    data class Fund(val versionCode: Int, val versionName: String, val tag: String, val apk: String, val sha256: String) {
        val adresse get() = "https://github.com/$REPO/releases/download/$tag/$apk"
    }

    /** Neuere Launcher-Version im Release oder null (nichts Neueres, kein Launcher-Eintrag, Format falsch). */
    fun parse(json: String, eigenerCode: Int): Fund? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag", "")
        val l = o.optJSONObject("launcher")
        val code = l?.optInt("versionCode", 0) ?: 0
        val name = l?.optString("versionName", "").orEmpty()
        val apk = l?.optString("apk", "").orEmpty()
        val sha = l?.optString("sha256", "").orEmpty().lowercase()
        if (l == null || code <= eigenerCode || !TAG.matches(tag) || !APK.matches(apk) || apk.contains("..") || !SHA.matches(sha)
            || !Regex("[0-9][0-9.]{0,20}").matches(name)) null
        else Fund(code, name, tag, apk, sha)
    } catch (_: Exception) {
        null
    }

    /** Jetzt nachsehen? Nur ohne Kern (mit Kern meldet der Kern Updates), höchstens einmal am Tag. */
    fun faellig(kernDa: Boolean, letzteMs: Long, fehlgeschlagen: Boolean, jetzt: Long): Boolean {
        if (kernDa) return false
        val takt = if (fehlgeschlagen) FEHL_TAKT_MS else TAKT_MS
        return letzteMs <= 0L || jetzt - letzteMs >= takt || jetzt < letzteMs
    }

    /** version.json lesen (io-Thread). null = nicht erreichbar. */
    fun holen(eigenerCode: Int): Result<Fund?> = runCatching {
        val c = (URL(VERSION_JSON).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "PanoptesLauncher")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int
                while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); if (out.size() > HOECHSTENS_JSON) error("zu groß") }
                out.toByteArray()
            }
            parse(String(bytes, Charsets.UTF_8), eigenerCode)
        } finally { c.disconnect() }
    }

    /** APK laden und sha256 prüfen (io-Thread). null = Laden oder Prüfung gescheitert (dann ist die Datei gelöscht). */
    fun laden(ctx: Context, f: Fund): File? {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val ziel = File(dir, "panoptes-launcher.apk")
        return try {
            val c = (URL(f.adresse).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "PanoptesLauncher")
            }
            try {
                if (c.responseCode !in 200..299) return null
                val md = MessageDigest.getInstance("SHA-256")
                var summe = 0L
                c.inputStream.use { inp ->
                    ziel.outputStream().use { out ->
                        val buf = ByteArray(65536); var n: Int
                        while (inp.read(buf).also { n = it } >= 0) {
                            summe += n; if (summe > HOECHSTENS_APK) error("zu groß")
                            md.update(buf, 0, n); out.write(buf, 0, n)
                        }
                    }
                }
                if (hex(md.digest()) != f.sha256) { ziel.delete(); null } else ziel
            } finally { c.disconnect() }
        } catch (_: Exception) {
            ziel.delete(); null
        }
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Darf der Launcher dem Installer eine APK geben? Sonst zuerst die Android-Seite „Unbekannte Apps installieren“. */
    fun darfInstallieren(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun erlaubnisSeite(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) {
        false
    }

    /** Android-Installer mit der geprüften Datei öffnen; installieren bestätigt der Nutzer dort. */
    fun installer(ctx: Context, apk: File): Boolean = try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".update", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) {
        false
    }
}
