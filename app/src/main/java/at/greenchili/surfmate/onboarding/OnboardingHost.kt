package at.greenchili.surfmate.onboarding

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import at.greenchili.surfmate.R
import at.greenchili.surfmate.launcher.AdbDaemonLauncher

private typealias SetupStep = OnboardingConfigPolicy.Step

/**
 * Drives the two-track onboarding guide: owns the [OnboardingOverlayView] lifecycle, the
 * parked-only [OnboardingGate], a cached encoder-busy signal, and the ordered novice
 * spine. The wizards (camera / vehicle / dashboard) are delegated to dedicated coaches
 * that the host hands the overlay to.
 *
 * SEQUENCING: launched from MainActivity AFTER the PIN gate and AFTER SetupGuideDialog,
 * only when [OnboardingState.shouldAutoRunNovice] and the parked gate allow. The overlay
 * attaches to the Activity content root; the camera/vehicle wizards re-parent the overlay
 * onto the relevant dialog window so coachmarks sit ABOVE the MaterialAlertDialog.
 *
 * Step 0 (daemon auth) persists in app-private prefs and is the only hard gate. Everything
 * else is skippable or resumable; nothing here can fire while driving (ACC-ON dismiss).
 */
class OnboardingHost(
    private val activity: Activity,
    private val adbLauncher: AdbDaemonLauncher,
) {
    private val state = OnboardingState.get(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: OnboardingOverlayView? = null
    private var gate: OnboardingGate? = null
    private var started = false
    private var activeCameraCoach: CameraWizardCoach? = null
    private var replayMode = false   // replay re-runs novice basics even if complete
    private var expertMode = false   // launch straight into the Expert track

    // Cached "is the camera daemon (encoder) running?" — refreshed off the main thread
    // every ENCODER_POLL_MS and read synchronously by the overlay's emphasis gate so a
    // decorative pulse never competes with the H.265 encoder on the shared bus.
    @Volatile private var encoderBusyCached = false
    private val encoderPoll = object : Runnable {
        override fun run() {
            try {
                adbLauncher.isDaemonRunning { running -> encoderBusyCached = running }
            } catch (t: Throwable) {
                Log.w(TAG, "encoder poll failed: ${t.message}")
            }
            mainHandler.postDelayed(this, ENCODER_POLL_MS)
        }
    }

    // ---- public lifecycle ------------------------------------------------------------

    /**
     * Start the novice track if it should run and we're parked. Idempotent — safe to call
     * from onResume. Returns true if the overlay was shown.
     */
    fun startIfNeeded(): Boolean {
        if (started) return false
        // 0.1.10 (§0, Kern 4): Startentscheidung über GET /api/einrichtung. Nur HTTP, kein ADB (sonst käme das
        // USB-Popup ungefragt). neuZaehler gleicht lokale Merker ab („Einrichtung erneut starten“ im Web).
        Thread({
            val r = at.greenchili.surfmate.onboarding.schritte.EinrichtungApi.get("/api/einrichtung")
            if (r.ok) rechteNachmelden(r.json)
            mainHandler.post {
                if (started) return@post
                when {
                    r.ok -> {
                        state.abgleichen(r.json.optInt("neuZaehler", 0))
                        val aktuell = if (r.json.isNull("aktuell")) null else r.json.optString("aktuell", "")
                        if (OnboardingGate(activity) {}.sollStarten(aktuell)) start()
                    }
                    // Daemon aus (vor der USB-Freigabe) bzw. Kern ohne §0: wie bisher nach dem lokalen Merker
                    else -> if (state.shouldAutoRunNovice()) start()
                }
            }
        }, "EinrichtungStart").start()
        return false
    }

    /**
     * 0.1.26 (Alfreds Auto): Rechte längst erteilt, „Noch offen“ zeigt trotzdem „Berechtigungen (später)“ – nur die App
     * sieht die Android-Rechte, also meldet sie den echten Zustand nach (Hintergrund-Thread, Fehler still).
     */
    private fun rechteNachmelden(json: org.json.JSONObject) {
        try {
            if (!at.greenchili.surfmate.onboarding.schritte.EinrichtungStand.ausJson(json).rechteNachmelden()) return
            val alle = at.greenchili.surfmate.onboarding.schritte.FreigabenSchritt.ANDROID_RECHTE.all {
                activity.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (alle) at.greenchili.surfmate.onboarding.schritte.EinrichtungApi.schritt("rechte",
                org.json.JSONObject().put("geprueft", true).put("ok", true))
        } catch (t: Throwable) {
            Log.w(TAG, "Rechte nachmelden: ${t.message}")
        }
    }

    /** Force-start (replay "?" affordance). Ignores onboardingComplete, still parked-gated. */
    fun startReplay() {
        if (started) dismiss()
        replayMode = true
        start()
    }

    /** Launch the Expert track directly (from the novice "Explore Setup" CTA). */
    fun startExpertTour() {
        if (started) dismiss()
        expertMode = true
        start()
    }

    private fun start(): Boolean {
        val gate = OnboardingGate(activity) { dismiss() }
        if (!gate.canShow()) return false
        this.gate = gate
        gate.register()

        if (!expertMode) {
            // 0.1.10: der Einrichtungsassistent (onboarding/schritte) ist der einzige Vordergrund.
            started = true
            Log.i(TAG, "Einrichtung gestartet (replay=$replayMode)")
            starteAssistentOderAlt()
            return true
        }

        val ov = OnboardingOverlayView(activity, encoderBusy = { encoderBusyCached })
        ov.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        contentRoot().addView(ov)
        overlay = ov
        started = true

        mainHandler.post(encoderPoll)
        Log.i(TAG, "Onboarding started (expert=$expertMode replay=$replayMode)")
        if (expertMode) launchExpertTrack() else routeFirstStep()
        return true
    }

    private fun launchExpertTrack() {
        val ov = overlay ?: return
        ExpertTourCoach(activity, ov, state) {
            dismiss()
        }.begin()
    }

    // ---- Einrichtungsassistent 0.1.10 ----------------------------------------------------

    private var anbindung: at.greenchili.surfmate.onboarding.schritte.AssistentAnbindung? = null

    /** §0 da (oder Daemon noch aus = Vorlauf) → neuer Assistent; Kern ohne §0 (404) → bisheriger Ablauf. */
    private fun starteAssistentOderAlt() {
        Thread({
            val r = at.greenchili.surfmate.onboarding.schritte.EinrichtungApi.get("/api/einrichtung")
            mainHandler.post {
                if (!started) return@post
                if (r.status == 404) altenAblaufStarten() else neuenAssistentStarten()
            }
        }, "EinrichtungStart").start()
    }

    private fun neuenAssistentStarten() {
        val an = at.greenchili.surfmate.onboarding.schritte.AssistentAnbindung(
            activity, contentRoot(), adbLauncher, state,
            beendet = { _ -> anbindung = null; dismiss() },
            rundgangStarten = { mainHandler.post { rundgangStarten() } },
        )
        anbindung = an
        an.starten()
    }

    /** Bisheriger Ablauf (Kern ohne Einrichtungs-Vertrag §0). */
    private fun altenAblaufStarten() {
        val ov = OnboardingOverlayView(activity, encoderBusy = { encoderBusyCached })
        ov.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        contentRoot().addView(ov)
        overlay = ov
        mainHandler.post(encoderPoll)
        routeFirstStep()
    }

    /** „Kurze Führung durch Panoptes?“ nach dem Assistenten (DashboardTourCoach, unverändert). */
    private fun rundgangStarten() {
        val ov = OnboardingOverlayView(activity, encoderBusy = { encoderBusyCached })
        ov.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        contentRoot().addView(ov)
        DashboardTourCoach(activity, ov, state) { (ov.parent as? ViewGroup)?.removeView(ov) }.begin()
    }

    /** Tear everything down (ACC-ON, completion, or replay restart). */
    fun dismiss() {
        anbindung?.let { an -> anbindung = null; an.beenden() }
        // Nie ohne Daemon hängen bleiben: war der Start aufgeschoben, spätestens jetzt starten (Regel B).
        (activity as? at.greenchili.surfmate.ui.MainActivity)?.onboardingDaemonStarten()
        mainHandler.removeCallbacks(encoderPoll)
        // Cancel the camera coach FIRST so its ~10s applying animator can't keep running
        // against a detached overlay (transient Activity leak) after we remove the view.
        activeCameraCoach?.cancel(); activeCameraCoach = null
        gate?.unregister(); gate = null
        overlay?.let { ov -> (ov.parent as? ViewGroup)?.removeView(ov) }
        overlay = null
        started = false
        expertMode = false
        replayMode = false
    }

    /** Called from MainActivity.onAuthGranted — advances Step 0 if we're waiting on it. */
    fun onDaemonAuthGranted() {
        state.daemonAuthorized = true
        if (started && currentStep == SetupStep.AUTHORIZE) {
            mainHandler.post { advanceFromAuthorize() }
        }
    }

    /**
     * Forwarded from MainActivity.onConfigurationChanged (the Activity uses configChanges,
     * so it doesn't recreate on rotation). The overlay recomputes its responsive width +
     * re-resolves the live anchor's new bounds; the card width and the spotlight cutout
     * are otherwise frozen to the launch orientation.
     */
    fun onConfigChanged() {
        if (!started) return
        overlay?.onConfigChanged()
    }

    // ---- step machine ----------------------------------------------------------------

    private var currentStep = SetupStep.AUTHORIZE

    private fun routeFirstStep() {
        // If the daemon is ALREADY running (already-installed device / auth granted on a
        // prior launch / the OS remembered "Always allow"), the system ADB popup will
        // never re-appear — so Step 0 must NOT wait for an edge that already passed.
        // Detect it up front and skip straight past AUTHORIZE.
        adbLauncher.isDaemonRunning { running ->
            mainHandler.post {
                if (!started) return@post
                if (running) state.daemonAuthorized = true
                reconcilePersistedSetupState()
                currentStep = OnboardingConfigPolicy.nextStep(
                    state.daemonAuthorized,
                    state.modeChosen,
                    state.vehicleStepDone,
                    state.cameraStep == OnboardingState.CameraStep.SAVED_OK,
                    state.dashboardTourDone,
                )
                renderCurrent()
            }
        }
    }

    private fun renderCurrent() {
        val ov = overlay ?: return
        // Frage „Nach Updates suchen?“ (Koordination 0.1.9, Regel 2): nach der Betriebsart, nur bei neuer
        // Einrichtung, ohne Vorauswahl. Eigener Panoptes-Schritt ohne Enum-Wert (wie der Fernzugang).
        if (!replayMode && !state.updateFrageDone && state.daemonAuthorized
            && currentStep != SetupStep.AUTHORIZE && currentStep != SetupStep.MODE) {
            renderUpdateFrage(ov)
            return
        }
        when (currentStep) {
            SetupStep.AUTHORIZE -> renderAuthorize(ov)
            SetupStep.MODE -> renderModeChoice(ov)
            // Fernzugang vor der Fahrzeugwahl (eigener Panoptes-Schritt, ohne eigenen Enum-Wert,
            // damit die Reihenfolge-Logik in OnboardingConfigPolicy unverändert bleibt).
            SetupStep.VEHICLE -> if (state.remoteAccessDone) launchVehicleChapter() else renderRemoteAccess(ov)
            SetupStep.CAMERA -> launchCameraChapter()
            SetupStep.DASHBOARD -> launchDashboardChapter()
            SetupStep.DONE -> renderDone(ov)
        }
    }

    /**
     * App-private onboarding flags are not part of config backups. Reconcile
     * them with durable setup state so reinstall+restore does not ask the
     * owner to reconfigure a validated camera or re-select a known vehicle.
     */
    private fun reconcilePersistedSetupState() {
        try {
            val vehicle = at.greenchili.surfmate.config.UnifiedConfigManager.getVehicle()
            if (OnboardingConfigPolicy.hasConfiguredVehicle(
                    vehicle.optString("modelId", ""),
                    vehicle.optString("modelSource", ""),
                )) {
                state.vehicleStepDone = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "vehicle setup reconciliation failed: ${t.message}")
        }

        try {
            val camera = at.greenchili.surfmate.camera.CameraConfigResolver.getCameraSection()
            if (OnboardingConfigPolicy.hasConfiguredCamera(
                    camera.optInt("probedCameraId", -1),
                    camera.optBoolean("manualOverride", false),
                    camera.optBoolean("probedAndValidated", false),
                )) {
                state.cameraStep = OnboardingState.CameraStep.SAVED_OK
            }
        } catch (t: Throwable) {
            Log.w(TAG, "camera setup reconciliation failed: ${t.message}")
        }
    }

    // ---- Step 0: authorize daemon ----------------------------------------------------

    private fun renderAuthorize(ov: OnboardingOverlayView) {
        ov.showCentered()
        if (state.daemonAuthorized) { advanceFromAuthorize(); return }

        ov.bindStep(
            title = activity.getString(R.string.onboarding_authorize_title),
            body = activity.getString(R.string.onboarding_authorize_body),
            primaryText = activity.getString(R.string.onboarding_authorize_button),
            onPrimary = {
                // The system ADB popup is OS-owned (we can't draw over it). Daemon
                // startup already triggers it; here we just narrate + wait for the
                // onAuthGranted callback (routed via MainActivity → onDaemonAuthGranted).
                ov.bindStep(
                    title = activity.getString(R.string.onboarding_authorize_waiting_title),
                    body = activity.getString(R.string.onboarding_authorize_waiting_body),
                    primaryText = null, onPrimary = null,
                    // ALWAYS-AVAILABLE ESCAPE: the user must never be locked behind the
                    // waiting state (e.g. already-installed device where no popup appears).
                    secondaryText = activity.getString(R.string.onboarding_dismiss),
                    onSecondary = { dismiss() },
                )
                // Failsafe: if the grant never arrives (popup dismissed / already authed),
                // re-offer with a Try-again + Dismiss instead of hanging.
                mainHandler.postDelayed({
                    if (started && currentStep == SetupStep.AUTHORIZE && !state.daemonAuthorized) {
                        renderAuthorizeRetry(ov)
                    }
                }, AUTH_WAIT_MS)
            },
            // Escape on the very first auth card too.
            secondaryText = activity.getString(R.string.onboarding_dismiss),
            onSecondary = { dismiss() },
        )
    }

    private fun renderAuthorizeRetry(ov: OnboardingOverlayView) {
        ov.bindStep(
            title = activity.getString(R.string.onboarding_authorize_retry_title),
            body = activity.getString(R.string.onboarding_authorize_retry_body),
            primaryText = activity.getString(R.string.onboarding_authorize_retry_button),
            onPrimary = { renderAuthorize(ov) },
            secondaryText = activity.getString(R.string.onboarding_dismiss),
            onSecondary = { dismiss() },
        )
    }

    private fun advanceFromAuthorize() {
        reconcilePersistedSetupState()
        currentStep = SetupStep.MODE
        renderCurrent()
    }

    // ---- Step 1: choose operating mode -----------------------------------------------

    /**
     * First-run operating-mode choice. Presented AFTER daemon authorization so the
     * daemon's HTTP server is up and [persistOperatingMode] can POST the value into
     * UnifiedConfig (the app UID can't write the daemon's config file directly).
     *
     * Two mutually-exclusive options mapped to the card's primary/secondary buttons:
     *   Primary   = "Vehicle ON + OFF" (recommended, default) → operatingMode="onAndOff"
     *   Secondary = "Vehicle ON only"                          → operatingMode="onOnly"
     * Never a hard gate: either pick (or a dismiss) advances to CAMERA, and the config
     * default is already "onAndOff" so no write is strictly required for the default.
     */
    private fun renderModeChoice(ov: OnboardingOverlayView) {
        ov.showCentered()
        ov.bindStep(
            title = activity.getString(R.string.onboarding_mode_title),
            body = activity.getString(R.string.onboarding_mode_body),
            primaryText = activity.getString(R.string.onboarding_mode_on_off_button),
            onPrimary = { chooseModeAndAdvance("onAndOff") },
            secondaryText = activity.getString(R.string.onboarding_mode_on_only_button),
            onSecondary = { chooseModeAndAdvance("onOnly") },
        )
    }

    private fun chooseModeAndAdvance(mode: String) {
        // "onAndOff" is the config DEFAULT — no write is required for it to take effect,
        // so mark the step done immediately (a failed/absent write still yields onAndOff)
        // and clear any stale pending non-default mode.
        // "onOnly" is only real once durably persisted: record it as pendingOperatingMode
        // (survives process death + onboarding completion) and defer marking modeChosen
        // until the POST confirms, so a daemon-not-ready race re-asks / flushes later
        // instead of silently dropping the choice. Never a hard gate — always advance now.
        if (mode == "onAndOff") {
            state.pendingOperatingMode = null
            state.modeChosen = true
        } else {
            state.pendingOperatingMode = mode
        }
        persistOperatingMode(mode)
        if (!replayMode) setRetentionDefaultIfUnset()
        reconcilePersistedSetupState()
        currentStep = OnboardingConfigPolicy.nextStep(
            true,
            true,
            state.vehicleStepDone,
            state.cameraStep == OnboardingState.CameraStep.SAVED_OK,
            state.dashboardTourDone,
        )
        renderCurrent()
    }

    /**
     * Persist the chosen operating mode into UnifiedConfig by POSTing to the daemon's
     * local HTTP server (127.0.0.1:8180 — CameraDaemon.HTTP_PORT). Off the main thread.
     * On a fresh install this can fire before the daemon has bound :8180 (it's launched
     * ~500ms after auth and takes a few more seconds), so we RETRY with backoff. Only on
     * a confirmed 2xx for "onOnly" do we set modeChosen=true — otherwise the flag stays
     * false and the MODE step re-shows next launch (routeFirstStep), so a non-default
     * choice is never silently lost. Forces a direct connection (Proxy.NO_PROXY) so a
     * live sing-box global proxy doesn't swallow the loopback call — same guard
     * MainActivity uses for its daemon POSTs.
     */
    private fun persistOperatingMode(mode: String) {
        Thread {
            val ok = postOperatingModeWithRetry(mode)
            if (ok) {
                // Durably written — record the step done + clear the pending marker
                // (covers the onOnly case chooseModeAndAdvance deliberately left unmarked).
                state.modeChosen = true
                state.pendingOperatingMode = null
            } else {
                // Not confirmed within the window. modeChosen stays false and (for a
                // non-default pick) pendingOperatingMode stays set, so it is flushed later
                // by flushPendingOperatingMode() when the daemon is confirmed up — this
                // survives onboarding completion, unlike the MODE step re-ask.
                Log.w(TAG, "persistOperatingMode($mode) never confirmed — left pending for daemon-ready flush")
            }
        }.start()
    }

    /**
     * Neue Installation: Aufbewahrung der Wächter-Aufnahmen auf die Empfehlung 72 h setzen
     * (RECHTSLAGE Nr. 7). Nur wenn der Wert noch NIE gesetzt wurde (Schema „gesetzt:false“);
     * eine bewusste Wahl (auch 0 = unbegrenzt) wird nie überschrieben. Daemon ohne Schema-API
     * (404) oder nicht erreichbar: nichts tun. Kein Befehl ans Auto, nur eine Einstellung.
     */
    /** Standards nur für neue Einrichtungen (Schlüssel → Wert); nie gesetzte Felder bekommen sie. */
    private fun einrichtungsStandards(): List<Pair<String, Any>> = listOf(
        RETENTION_KEY to RETENTION_DEFAULT_H,
        ENERGIE_KEY to ENERGIE_DEFAULT,
        // Regel Nr. 2: Telegram-Kategorien mit Position bei neuer Einrichtung einzeln aus
        "telegram.parkingMessages" to false,
    )

    private fun setRetentionDefaultIfUnset() {
        Thread {
            for (attempt in 0 until PERSIST_MAX_ATTEMPTS) {
                try {
                    val get = daemonJson("GET", "/api/einstellungen", null)
                    if (get == null) {
                        // nicht erreichbar → später nochmal
                    } else if (get.optInt("_status") == 404) {
                        return@Thread
                    } else {
                        // Nur neue Einrichtung, nur nie gesetzte Felder: Aufbewahrung 72 h und
                        // Energie-Profil „sparsam“ (Koordination 21.10.; Alt-Installationen durchlaufen das
                        // Onboarding nicht und bleiben „normal“).
                        val werte = get.optJSONObject("werte") ?: return@Thread
                        val neu = org.json.JSONObject()
                        for ((key, wert) in einrichtungsStandards()) {
                            val w = werte.optJSONObject(key) ?: continue
                            if (!w.optBoolean("gesetzt", false)) neu.put(key, wert)
                        }
                        if (neu.length() == 0) return@Thread
                        val body = org.json.JSONObject()
                            .put("etag", get.optString("etag", ""))
                            .put("werte", neu)
                        val put = daemonJson("PUT", "/api/einstellungen", body.toString())
                        val gespeichert = put?.optJSONObject("gespeichert")
                        if (gespeichert != null && neu.keys().asSequence().all { gespeichert.has(it) }) {
                            Log.i(TAG, "Einrichtungs-Standards gesetzt: $neu")
                            return@Thread
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "setRetentionDefaultIfUnset attempt ${attempt + 1}: ${t.message}")
                }
                try { Thread.sleep((attempt + 1) * 2000L) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); return@Thread
                }
            }
            Log.w(TAG, "setRetentionDefaultIfUnset: nicht bestätigt")
        }.start()
    }

    /** Lokaler Daemon-Aufruf (wie postOperatingModeWithRetry), Antwort-JSON mit „_status“; null = nicht erreichbar. */
    private fun daemonJson(method: String, path: String, body: String?): org.json.JSONObject? {
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = java.net.URL("http://127.0.0.1:8180$path")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { org.json.JSONObject(text) } catch (_: Throwable) { org.json.JSONObject() }
            return json.put("_status", code)
        } catch (t: Throwable) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- Update-Frage (update.pruefen) -------------------------------------------------

    /**
     * Ist update.pruefen schon gesetzt (z. B. Sicherung zurückgespielt), nicht fragen. Sonst zwei
     * gleichwertige Antworten, keine ist vorgewählt; erst die bestätigt gespeicherte Antwort hakt
     * den Schritt ab, sonst kommt die Frage beim nächsten Start wieder (nicht gesetzt = an).
     */
    private fun renderUpdateFrage(ov: OnboardingOverlayView) {
        Thread {
            val get = daemonJson("GET", "/api/einstellungen", null)
            val gesetzt = get?.optJSONObject("werte")?.optJSONObject(UPDATE_PRUEFEN_KEY)
                ?.optBoolean("gesetzt", false) == true
            mainHandler.post {
                if (!started) return@post
                if (gesetzt) {
                    state.updateFrageDone = true
                    renderCurrent()
                    return@post
                }
                ov.showCentered()
                ov.bindStep(
                    title = activity.getString(R.string.onboarding_update_title),
                    body = activity.getString(R.string.onboarding_update_body),
                    primaryText = activity.getString(R.string.onboarding_update_ja),
                    onPrimary = { beantworteUpdateFrage(ov, true) },
                    secondaryText = activity.getString(R.string.onboarding_update_nein),
                    onSecondary = { beantworteUpdateFrage(ov, false) },
                )
            }
        }.start()
    }

    private fun beantworteUpdateFrage(ov: OnboardingOverlayView, an: Boolean) {
        ov.bindStep(
            title = activity.getString(R.string.onboarding_update_title),
            body = activity.getString(R.string.onboarding_update_speichert),
            primaryText = null, onPrimary = null,
        )
        Thread {
            val ok = setzeEinstellung(UPDATE_PRUEFEN_KEY, an)
            mainHandler.post {
                if (!started) return@post
                if (ok) {
                    state.updateFrageDone = true
                    renderCurrent()
                } else {
                    // Nicht gespeichert: ehrlich sagen, nochmal fragen oder später (dann beim nächsten Start)
                    ov.bindStep(
                        title = activity.getString(R.string.onboarding_update_title),
                        body = activity.getString(R.string.onboarding_update_fehler),
                        primaryText = activity.getString(R.string.onboarding_authorize_retry_button),
                        onPrimary = { renderUpdateFrage(ov) },
                        secondaryText = activity.getString(R.string.onboarding_dismiss),
                        onSecondary = { dismiss() },
                    )
                }
            }
        }.start()
    }

    /** Eine Einstellung bestätigt schreiben (PUT mit etag, wenige Versuche). Nicht auf dem Hauptthread. */
    private fun setzeEinstellung(key: String, wert: Any): Boolean {
        for (attempt in 0 until PERSIST_MAX_ATTEMPTS) {
            try {
                val get = daemonJson("GET", "/api/einstellungen", null)
                if (get != null && get.optInt("_status") in 200..299) {
                    val body = org.json.JSONObject()
                        .put("etag", get.optString("etag", ""))
                        .put("werte", org.json.JSONObject().put(key, wert))
                    val put = daemonJson("PUT", "/api/einstellungen", body.toString())
                    if (put?.optJSONObject("gespeichert")?.has(key) == true) return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "setzeEinstellung($key) attempt ${attempt + 1}: ${t.message}")
            }
            try { Thread.sleep((attempt + 1) * 1000L) } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return false
    }

    // ---- chapter launchers (delegated to coaches) ------------------------------------

    private fun launchCameraChapter() {
        val ov = overlay ?: return
        val coach = CameraWizardCoach(activity, ov, state, adbLauncher) { _ ->
            // Vehicle selection has already run (or was restored), so camera
            // completion now advances directly to the dashboard tour.
            activeCameraCoach = null
            currentStep = SetupStep.DASHBOARD
            renderCurrent()
        }
        activeCameraCoach = coach
        coach.begin()
    }

    // ---- Fernzugang (Tailscale) --------------------------------------------------------

    /**
     * „Fernzugang einrichten“: ein Satz Erklärung, Einrichten oder Später. Ist das Auto schon
     * im Tailnet angemeldet, wird der Schritt still übersprungen.
     */
    private fun renderRemoteAccess(ov: OnboardingOverlayView) {
        at.greenchili.surfmate.launcher.TailscaleLauncher(
            activity,
            at.greenchili.surfmate.launcher.AdbShellExecutor(activity),
            at.greenchili.surfmate.logging.LogManager.getInstance(),
        ).readStatus { backend, _, _ ->
            mainHandler.post {
                if (!started) return@post
                if (backend.equals("Running", ignoreCase = true)) {
                    state.remoteAccessDone = true
                    renderCurrent()
                    return@post
                }
                ov.showCentered()
                ov.bindStep(
                    title = activity.getString(R.string.remote_setup_title),
                    body = activity.getString(R.string.remote_setup_why),
                    primaryText = activity.getString(R.string.remote_setup_start),
                    onPrimary = {
                        at.greenchili.surfmate.ui.setup.RemoteAccessDialog(activity) { _ ->
                            state.remoteAccessDone = true
                            mainHandler.post { if (started) renderCurrent() }
                        }.show()
                    },
                    secondaryText = activity.getString(R.string.remote_setup_skip),
                    onSecondary = {
                        state.remoteAccessDone = true
                        renderCurrent()
                    },
                )
            }
        }
    }

    private fun launchVehicleChapter() {
        val ov = overlay ?: return
        VehicleWizardCoach(activity, ov, state) {
            reconcilePersistedSetupState()
            currentStep = if (state.cameraStep == OnboardingState.CameraStep.SAVED_OK) {
                SetupStep.DASHBOARD
            } else {
                SetupStep.CAMERA
            }
            renderCurrent()
        }.begin()
    }

    private fun launchDashboardChapter() {
        val ov = overlay ?: return
        DashboardTourCoach(activity, ov, state) {
            currentStep = SetupStep.DONE
            renderCurrent()
        }.begin()
    }

    // ---- completion ------------------------------------------------------------------

    private fun renderDone(ov: OnboardingOverlayView) {
        ov.showCentered()
        ov.pulseAttention()
        ov.bindStep(
            title = activity.getString(R.string.onboarding_done_title),
            body = activity.getString(R.string.onboarding_done_body),
            primaryText = activity.getString(R.string.onboarding_done_primary),
            onPrimary = {
                state.onboardingComplete = true
                state.expertTourEntry = EXPERT_ENTRY_CAMERA_ADVANCED
                dismiss()
            },
            secondaryText = activity.getString(R.string.onboarding_done_secondary),
            onSecondary = {
                // Hand straight into the Expert track (starts at its first chapter).
                state.onboardingComplete = true
                startExpertTour()
            },
        )
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun contentRoot(): ViewGroup =
        activity.findViewById(android.R.id.content)

    companion object {
        private const val TAG = "OnboardingHost"
        private const val ENCODER_POLL_MS = 3000L
        private const val AUTH_WAIT_MS = 20000L
        private const val PERSIST_MAX_ATTEMPTS = 5
        private const val UPDATE_PRUEFEN_KEY = "update.pruefen"
        private const val RETENTION_KEY = "surveillance.clipRetentionHours"
        private const val RETENTION_DEFAULT_H = 72

        private const val ENERGIE_KEY = "energie.profil"
        private const val ENERGIE_DEFAULT = "sparsam"
        private const val EXPERT_ENTRY_CAMERA_ADVANCED = "cameraAdvanced"

        /**
         * Blocking POST of operatingMode to the daemon with retry+backoff, trusting only
         * a confirmed {success:true} body — HTTP 200 alone is NOT enough, since the
         * daemon's sendJsonError returns 200 {success:false} on a persist failure (mirror
         * MainActivity.postSurveillanceConfig). MUST be called off the main thread.
         * Returns true only on a durable write.
         */
        private fun postOperatingModeWithRetry(mode: String): Boolean {
            // ~2s, 4s, 6s, 8s, 10s backoff ≈ 30s total — comfortably covers daemon bring-up.
            for (attempt in 0 until PERSIST_MAX_ATTEMPTS) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL("http://127.0.0.1:8180/api/surveillance/config")
                        .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write("{\"operatingMode\":\"$mode\"}".toByteArray()) }
                    val code = conn.responseCode
                    Log.i(TAG, "postOperatingMode($mode) attempt ${attempt + 1} → HTTP $code")
                    if (code in 200..299) {
                        // A false 200 keeps retrying (transient lock contention); a
                        // non-JSON body assumes success (legacy endpoint).
                        val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                        val success = try {
                            org.json.JSONObject(bodyText).optBoolean("success", true)
                        } catch (_: Throwable) { true }
                        if (success) return true
                        Log.w(TAG, "postOperatingMode($mode) attempt ${attempt + 1}: HTTP 200 but success=false — retrying")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "postOperatingMode($mode) attempt ${attempt + 1} failed: ${t.message}")
                } finally {
                    conn?.disconnect()
                }
                try { Thread.sleep((attempt + 1) * 2000L) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
            }
            return false
        }

        /**
         * Daemon-ready flush of a pending non-default operating-mode choice. Call from
         * MainActivity once the daemon is confirmed up (onDaemonAuthGranted / daemon-ready
         * path). If the first-run POST retries all expired before the daemon bound :8180
         * and the user then completed onboarding (which disables the MODE step re-ask via
         * onboardingComplete), the choice is still recorded in
         * OnboardingState.pendingOperatingMode and gets written here — decoupled from the
         * onboarding step lifecycle. No-op when nothing is pending. Runs its own worker
         * thread; safe to call repeatedly (idempotent).
         */
        @JvmStatic
        fun flushPendingOperatingMode(context: android.content.Context) {
            val state = OnboardingState.get(context)
            val pending = state.pendingOperatingMode ?: return
            Thread {
                // Reconcile FIRST: if the user has since explicitly set the operating mode
                // (via Settings — the daemon marks operatingModeSetByUser=true on any
                // operatingMode POST), a stale onboarding pending value must NOT clobber
                // that later choice. Drop the pending marker instead of re-asserting it.
                // Only when the daemon reports no explicit user choice yet (untouched
                // default) do we write the pending onboarding pick.
                when (readOperatingModeSetByUser()) {
                    true -> {
                        state.pendingOperatingMode = null
                        state.modeChosen = true
                        Log.i(TAG, "flushPendingOperatingMode: mode already set by user — dropping stale pending=$pending")
                        return@Thread
                    }
                    null -> {
                        // Daemon unreachable — can't reconcile safely. Leave pending for a
                        // later flush rather than risk clobbering; do NOT POST blindly.
                        Log.w(TAG, "flushPendingOperatingMode: daemon unreachable for reconcile — leaving pending=$pending")
                        return@Thread
                    }
                    false -> { /* untouched default — safe to write the pending pick below */ }
                }
                if (postOperatingModeWithRetry(pending)) {
                    state.pendingOperatingMode = null
                    state.modeChosen = true
                    Log.i(TAG, "flushPendingOperatingMode: persisted pending mode=$pending")
                } else {
                    Log.w(TAG, "flushPendingOperatingMode: daemon still unreachable — leaving pending=$pending")
                }
            }.start()
        }

        /**
         * Clear the daemon-side operatingModeSetByUser marker (POST
         * {operatingModeSetByUser:false}). Called from onboarding reset/replay so a wiped
         * session doesn't inherit a PRIOR session's "user chose a mode" flag — without
         * this, flushPendingOperatingMode would GET a stale true and wrongly drop a
         * legitimate NEW replay pick. Runs its own worker thread with retry+backoff; the
         * body-success check means a false 200 keeps retrying. Best-effort: if the daemon
         * is unreachable the marker stays set, but the immediate persistOperatingMode POST
         * from the replay pick (which does NOT consult the marker) still normally lands the
         * new choice — this clear only closes the narrow "immediate POST also failed" window.
         */
        @JvmStatic
        fun clearOperatingModeUserFlag() {
            Thread {
                for (attempt in 0 until PERSIST_MAX_ATTEMPTS) {
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        conn = java.net.URL("http://127.0.0.1:8180/api/surveillance/config")
                            .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write("{\"operatingModeSetByUser\":false}".toByteArray()) }
                        if (conn.responseCode in 200..299) {
                            val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                            val success = try {
                                org.json.JSONObject(bodyText).optBoolean("success", true)
                            } catch (_: Throwable) { true }
                            if (success) {
                                Log.i(TAG, "clearOperatingModeUserFlag: cleared daemon marker")
                                return@Thread
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "clearOperatingModeUserFlag attempt ${attempt + 1} failed: ${t.message}")
                    } finally {
                        conn?.disconnect()
                    }
                    try { Thread.sleep((attempt + 1) * 2000L) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                }
                Log.w(TAG, "clearOperatingModeUserFlag: never confirmed (daemon unreachable)")
            }.start()
        }

        /**
         * GET the daemon's surveillance config and return operatingModeSetByUser.
         * true  = user has explicitly chosen a mode (onboarding or Settings) — don't clobber.
         * false = untouched default — safe to write a pending onboarding pick.
         * null  = daemon unreachable / unparseable — caller must not write blindly.
         * MUST be called off the main thread.
         */
        private fun readOperatingModeSetByUser(): Boolean? {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL("http://127.0.0.1:8180/api/surveillance/config")
                    .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode !in 200..299) return null
                val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(bodyText)
                // GET wraps the payload under "config" (sendConfig); tolerate either shape.
                val cfg = json.optJSONObject("config") ?: json
                return cfg.optBoolean("operatingModeSetByUser", false)
            } catch (t: Throwable) {
                Log.w(TAG, "readOperatingModeSetByUser failed: ${t.message}")
                return null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
