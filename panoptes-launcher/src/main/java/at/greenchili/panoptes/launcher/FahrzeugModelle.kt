package at.greenchili.panoptes.launcher

import java.util.Locale

/**
 * 0.1.44 (D1b, 12.11.: „warum nutzen wir nicht die modelle die wir schon haben aus Panoptes?“ und „es geht ja
 * nicht nur um meinen Surf sondern um alle“): welches Bild die Fahrzeug-Ansicht für ein Modell zeigt.
 *
 * - **Echtes Renderbild** aus Panoptes (`/api/models/art?id=…`, dieselben Bilder wie das Panoptes-Dashboard, Kern
 *   `VehicleArt`): nur für Modelle, für die es wirklich ein eigenes Bild gibt – sonst liefert der Kern eine neutrale
 *   Silhouette, die hier nie gezeigt wird.
 * - **Sonst** die gezeichnete Grundform (Kompakt/SUV/Limousine) in der echten Lackfarbe. Nie ein fremdes Modell:
 *   ein Seal bekommt nie das Dolphin-Bild.
 *
 * Die Renderbilder stammen aus CC-BY-4.0-Modellen von Nazh Design (Sketchfab), verändert für Panoptes; die
 * Namensnennung steht sichtbar unter dem Bild. Alles rein, im Unit-Test für jedes bekannte Modell belegt.
 */
object FahrzeugModelle {
    /** Faltet Schreibweisen: „seal-u“, „Seal U“, „seal_u“ → „sealu“. */
    fun norm(id: String?): String = id?.lowercase(Locale.ROOT)?.filter(Char::isLetterOrDigit) ?: ""

    /** Modelle mit eigenem Renderbild im Kern (wie `VehicleArt.drawableFor`, ohne den neutralen Rückfall). */
    val MIT_BILD = setOf("seal", "sealu", "sealudmi", "dolphin", "atto3", "seagull", "dolphinsurf")

    /** Alle Modelle des Panoptes-Katalogs (manifest.json) und bekannte Namen mit ihrer Grundform. */
    val FORM: Map<String, FahrzeugStart.Form> = mapOf(
        // kompakt
        "dolphin" to FahrzeugStart.Form.KOMPAKT, "seagull" to FahrzeugStart.Form.KOMPAKT, "dolphinsurf" to FahrzeugStart.Form.KOMPAKT,
        "dolphinmini" to FahrzeugStart.Form.KOMPAKT,
        // SUV / hoch (Pickup und Van ohne eigene Form: SUV ist am nächsten)
        "atto2" to FahrzeugStart.Form.SUV, "atto3" to FahrzeugStart.Form.SUV, "atto3evo" to FahrzeugStart.Form.SUV,
        "yuanplus" to FahrzeugStart.Form.SUV, "yuanup" to FahrzeugStart.Form.SUV,
        "sealu" to FahrzeugStart.Form.SUV, "sealudmi" to FahrzeugStart.Form.SUV, "songplus" to FahrzeugStart.Form.SUV,
        "songplusdmi" to FahrzeugStart.Form.SUV, "sealion7" to FahrzeugStart.Form.SUV, "tang" to FahrzeugStart.Form.SUV,
        "m6" to FahrzeugStart.Form.SUV, "shark" to FahrzeugStart.Form.SUV,
        // Limousine / flach
        "seal" to FahrzeugStart.Form.LIMOUSINE, "han" to FahrzeugStart.Form.LIMOUSINE, "seal6gt" to FahrzeugStart.Form.LIMOUSINE,
        "seal6dmitouring" to FahrzeugStart.Form.LIMOUSINE, "seal6" to FahrzeugStart.Form.LIMOUSINE,
        "destroyer" to FahrzeugStart.Form.LIMOUSINE, "destroyer05" to FahrzeugStart.Form.LIMOUSINE, "qin" to FahrzeugStart.Form.LIMOUSINE
    )

    /** Katalog-Modelle (manifest.json, Stand 0.1.44), für Tests und Bericht. */
    val KATALOG = listOf("seal", "sealion7", "shark", "seal-u", "seal-u-dmi", "dolphin", "atto3", "atto3-evo", "atto2", "han",
        "tang", "m6", "seagull", "seal6-gt", "seal6-dmi-touring", "destroyer")

    /** Zeigt die Ansicht für dieses Modell das echte Renderbild? */
    fun hatBild(id: String?): Boolean = norm(id) in MIT_BILD

    /** Grundform: bekanntes Modell aus der Tabelle, sonst nach Stichworten, sonst Kompakt (neutral, kein fremdes Modell). */
    fun form(id: String?): FahrzeugStart.Form {
        val n = norm(id)
        FORM[n]?.let { return it }
        return when {
            listOf("sealu", "sealion", "atto", "tang", "song", "yuan", "suv", "shark").any { n.contains(it) } -> FahrzeugStart.Form.SUV
            listOf("seal", "han", "qin", "destroyer").any { n.contains(it) } -> FahrzeugStart.Form.LIMOUSINE
            else -> FahrzeugStart.Form.KOMPAKT
        }
    }

    /** Namensnennung für das Renderbild (CC BY 4.0), null ohne Bild. */
    fun namensnennung(id: String?): String? = if (hatBild(id)) "3D: Nazh Design · CC BY 4.0 · verändert" else null
}
