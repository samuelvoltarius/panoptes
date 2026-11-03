package at.greenchili.surfmate.onboarding.schritte

/** Schritt-ID (§0) → Bildschirm. Unbekannte IDs dürfen nie als „fertig“ behandelt werden. */
object SchrittKatalog {
    /** UI-Vertrag in derselben Reihenfolge wie der Serververtrag. Ein Paritätstest hält beide synchron. */
    @JvmField
    val IDS = listOf(
        "sprache", "haftung", "autostart", "ueberlagerung", "rechte", "usb_freigabe",
        "auto", "kameras", "waechter", "betrieb", "karte", "daten", "blitzer", "updates",
        "strompreis", "extra_telegram", "extra_handy", "extra_planer", "extra_cockpit",
        "extra_overdrive", "extra_gewohnheiten", "extra_fernzugang", "extra_notzugang", "fertig",
    )

    @JvmStatic
    fun kennt(id: String): Boolean = id in IDS

    fun erzeuge(id: String, a: Assistent): SchrittAnsicht? = when (id) {
        "sprache" -> SpracheSchritt(a)
        "haftung" -> HaftungSchritt(a)
        "autostart", "ueberlagerung", "rechte" -> FreigabenSchritt(id, a)
        "usb_freigabe" -> UsbFreigabeSchritt(a, a.host)
        "auto" -> AutoSchritt(a, a.host)
        "kameras" -> KamerasSchritt(a, a.host)
        "waechter" -> WaechterSchritt(a)
        "betrieb" -> BetriebSchritt(a)
        "karte" -> KarteSchritt(a)
        "daten" -> DatenSchritt(a)
        "blitzer" -> BlitzerSchritt(a)
        "updates" -> UpdatesSchritt(a)
        "strompreis" -> StrompreisSchritt(a)
        "extra_handy" -> HandySchritt(a)
        "extra_telegram", "extra_planer", "extra_cockpit", "extra_fernzugang", "extra_notzugang" -> ExtraSchritt(id, a, a.host)
        "extra_overdrive" -> OverdriveSchritt(a)
        "extra_gewohnheiten" -> GewohnheitenSchritt(a)
        "fertig" -> FertigSchritt(a, a.host)
        else -> null
    }
}
