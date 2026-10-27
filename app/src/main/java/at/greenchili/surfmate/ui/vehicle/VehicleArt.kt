package at.greenchili.surfmate.ui.vehicle

import androidx.annotation.DrawableRes
import at.greenchili.surfmate.R
import java.util.Locale

/**
 * Maps a selected vehicle model id to its dashboard render.
 *
 * Ids arrive from /api/models/selected, but the id space is wider than
 * models/manifest.json: a manually selected or HAL-reported model can be
 * anything DashboardFragment.modelDisplayName knows about.
 */
object VehicleArt {

    /** @return the render for [modelId], or the generic fallback. */
    @DrawableRes
    fun drawableFor(modelId: String?): Int = when (normalize(modelId)) {
        // 0.1.20: nur noch eigene Renderbilder der CC-BY-Modelle aus dem Katalog (Namensnennung im Manifest und in
        // THIRD_PARTY_NOTICES.md, erzeugt mit tools/render_vehicle_art.py). Die früheren Pressefotos ohne Lizenz sind
        // entfernt; Modelle ohne eigenes 3D-Modell zeigen die neutrale Silhouette.
        "seal" -> R.drawable.vehicle_seal
        // Same body as the Seal U; only the drivetrain differs.
        "sealu", "sealudmi" -> R.drawable.vehicle_seal_u
        "dolphin" -> R.drawable.vehicle_dolphin
        "atto3" -> R.drawable.vehicle_atto3
        "seagull", "dolphinsurf" -> R.drawable.vehicle_seagull
        else -> R.drawable.vehicle_fallback
    }

    /** Folds separator styles, so "seal-u", "seal_u" and "Seal U" all match. */
    private fun normalize(modelId: String?): String =
        modelId?.lowercase(Locale.US)?.filter(Char::isLetterOrDigit) ?: ""
}
