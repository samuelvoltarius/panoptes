package at.greenchili.surfmate.ui.setup

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import at.greenchili.surfmate.R
import at.greenchili.surfmate.launcher.AdbShellExecutor
import at.greenchili.surfmate.launcher.TailscaleLauncher
import at.greenchili.surfmate.logging.LogManager
import at.greenchili.surfmate.ui.util.QrCodeGenerator
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Einrichtungs-Schritt „Fernzugang“ (Panoptes-Assistent, Koordination 21.10.2026).
 *
 * Ziel: Ein neues Auto kommt ins Tailnet seines Besitzers, ohne Laptop im Auto.
 *  1. Standard: QR-Code mit dem Tailscale-Anmeldelink groß am Autoschirm — mit dem Handy
 *     scannen, anmelden, fertig. Der Zustand wird alle 3 s gelesen; bei „Running“ zeigt
 *     der Dialog Gerätename und Tailnet-Adresse.
 *  2. Optional: Auth-Key aus der Tailscale-Admin-Konsole (mehrere oder fremde Autos). Der
 *     Schlüssel wird nie geloggt oder gespeichert (siehe [TailscaleLauncher.loginWithAuthKey]).
 *  3. Gerätename wird vorgeschlagen („panoptes“) und ist änderbar.
 * Jeder Nutzer verbindet sein Auto mit SEINEM eigenen Tailscale-Konto; nichts davon ist fest im Code.
 * Löst nichts am Auto aus (kein Fahrzeugbefehl), überspringbar.
 */
class RemoteAccessDialog(
    private val activity: Activity,
    private val onFinished: (connected: Boolean) -> Unit,
) {
    private val launcher by lazy { TailscaleLauncher(activity, AdbShellExecutor(activity), LogManager.getInstance()) }
    private val main = Handler(Looper.getMainLooper())
    private var polling = false
    private var finished = false
    private var connected = false

    private lateinit var nameField: EditText
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var qrButton: Button
    private lateinit var keyBox: LinearLayout
    private lateinit var keyField: EditText

    fun show() {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(8))
        }
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.remote_setup_why)
            textSize = 16f
        })
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.remote_setup_name_label)
            setPadding(0, px(16), 0, px(4))
            setTypeface(typeface, Typeface.BOLD)
        })
        nameField = EditText(activity).apply {
            setText(TailscaleLauncher.DEFAULT_HOSTNAME)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        root.addView(nameField)
        qrButton = Button(activity).apply {
            text = activity.getString(R.string.remote_setup_show_qr)
            setOnClickListener { startQr() }
        }
        root.addView(qrButton)
        qrImage = ImageView(activity).apply {
            visibility = View.GONE
            adjustViewBounds = true
        }
        root.addView(qrImage, LinearLayout.LayoutParams(px(320), px(320)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(8)
        })
        statusText = TextView(activity).apply {
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, px(8), 0, px(8))
        }
        root.addView(statusText)

        // Optional: Auth-Key
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.remote_setup_key_toggle)
            setTextColor(ContextCompat.getColor(activity, R.color.brand_primary))
            setPadding(0, px(12), 0, px(4))
            setOnClickListener { keyBox.visibility = if (keyBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        keyBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        keyBox.addView(TextView(activity).apply { text = activity.getString(R.string.remote_setup_key_hint) })
        keyField = EditText(activity).apply {
            hint = "tskey-auth-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        keyBox.addView(keyField)
        keyBox.addView(Button(activity).apply {
            text = activity.getString(R.string.remote_setup_key_use)
            setOnClickListener { useKey() }
        })
        root.addView(keyBox)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.Theme_SurfMate_M3_Dialog)
            .setTitle(R.string.remote_setup_title)
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(R.string.remote_setup_done) { _, _ -> finish() }
            .setNegativeButton(R.string.remote_setup_skip) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnDismissListener { polling = false }
        dialog.show()

        // Schon verbunden? Dann nur den Zustand zeigen.
        startPolling()
    }

    private fun startQr() {
        qrButton.isEnabled = false
        statusText.text = activity.getString(R.string.remote_setup_wait)
        launcher.generateLoginUrl(nameField.text.toString()) { url ->
            main.post {
                qrButton.isEnabled = true
                if (url == null) {
                    statusText.text = activity.getString(R.string.tailscale_failed_login_url)
                    return@post
                }
                qrImage.setImageBitmap(QrCodeGenerator.generate(url, 640))
                qrImage.visibility = View.VISIBLE
                statusText.text = activity.getString(R.string.remote_setup_scan)
            }
        }
    }

    private fun useKey() {
        val key = keyField.text.toString()
        keyField.setText("")
        statusText.text = activity.getString(R.string.remote_setup_wait)
        launcher.loginWithAuthKey(key, nameField.text.toString()) { ok, reason ->
            main.post {
                statusText.text = when {
                    ok -> activity.getString(R.string.remote_setup_wait)
                    reason == "format" -> activity.getString(R.string.remote_setup_key_bad)
                    else -> activity.getString(R.string.remote_setup_key_failed)
                }
            }
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                launcher.readStatus { state, dns, ip ->
                    main.post {
                        if (!polling) return@post
                        if (state.equals("Running", ignoreCase = true)) {
                            if (!connected) {
                                // Fernzugang eingerichtet → dauerhaft an, sonst käme Tailscale nach dem
                                // nächsten Neustart nicht wieder (Neuinstallation startet mit Tailscale aus)
                                try {
                                    at.greenchili.surfmate.ui.util.PreferencesManager.setDaemonEnabled(
                                        at.greenchili.surfmate.ui.model.DaemonType.TAILSCALE_TUNNEL, true)
                                } catch (t: Throwable) {
                                    android.util.Log.w("RemoteAccessDialog", "Tailscale an merken: ${t.message}")
                                }
                            }
                            connected = true
                            qrImage.visibility = View.GONE
                            statusText.text = activity.getString(
                                R.string.remote_setup_connected,
                                dns ?: nameField.text.toString(),
                                ip ?: "–",
                            )
                        }
                    }
                }
                main.postDelayed(this, 3000)
            }
        }
        main.post(tick)
    }

    private fun finish() {
        polling = false
        if (finished) return
        finished = true
        onFinished(connected)
    }
}
