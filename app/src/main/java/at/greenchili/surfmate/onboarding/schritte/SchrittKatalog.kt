package at.greenchili.surfmate.onboarding.schritte

/** Schritt-ID (§0) → Bildschirm. Unbekannte IDs (z. B. neue Kern-Schritte) → null, der Assistent zeigt dann „fertig“. */
object SchrittKatalog {
    fun erzeuge(id: String, a: Assistent): SchrittAnsicht? = when (id) {
        "sprache" -> SpracheSchritt(a)
        "haftung" -> HaftungSchritt(a)
        "autostart" -> AutostartSchritt(a, a.host)
        "ueberlagerung" -> UeberlagerungSchritt(a)
        "rechte" -> RechteSchritt(a)
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
        "extra_telegram", "extra_handy", "extra_planer", "extra_cockpit", "extra_fernzugang", "extra_notzugang" -> ExtraSchritt(id, a, a.host)
        "extra_overdrive" -> OverdriveSchritt(a)
        "extra_gewohnheiten" -> GewohnheitenSchritt(a)
        "fertig" -> FertigSchritt(a, a.host)
        else -> null
    }
}
