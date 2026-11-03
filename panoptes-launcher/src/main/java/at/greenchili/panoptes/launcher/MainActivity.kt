package at.greenchili.panoptes.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val client = PanoptesClient()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var vehicle: TextView
    private lateinit var range: TextView
    private lateinit var soc: TextView
    private lateinit var state: TextView
    private lateinit var traffic: TextView
    private lateinit var history: TextView
    private lateinit var apps: LinearLayout
    private lateinit var root: LinearLayout
    private var stopped = false
    private var cachedHistory = LauncherHistory(null, null)
    private var lastHistoryFetch = 0L
    // A slow or half-available localhost endpoint must not queue another complete
    // refresh every five seconds. Otherwise the single IO worker can build an
    // unbounded backlog and paint minutes-old vehicle data after recovery.
    private val refreshInFlight = AtomicBoolean(false)

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (!stopped) main.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 18, 29)
        window.navigationBarColor = Color.rgb(7, 18, 29)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        main.post(poll)
    }

    override fun onStop() {
        stopped = true
        main.removeCallbacks(poll)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
            setBackgroundColor(Color.rgb(7, 18, 29))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(getString(R.string.title), 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val clock = text("", 21f, true)
        header.addView(clock)
        root.addView(header)
        main.post(object : Runnable {
            override fun run() {
                clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (!stopped) main.postDelayed(this, 30_000)
            }
        })

        status = text(getString(R.string.connecting), 15f, false).apply { setTextColor(Color.rgb(151, 174, 193)) }
        root.addView(status, margin(top = 5, bottom = 14))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = tile() }
        vehicle = text(getString(R.string.vehicle_unknown), 26f, true)
        range = text(getString(R.string.range_format, getString(R.string.unknown)), 40f, true)
        soc = text(getString(R.string.soc_format, getString(R.string.unknown)), 20f, false)
        state = text(getString(R.string.state_format, getString(R.string.unknown), getString(R.string.unknown)), 17f, false)
        left.setPadding(dp(20), dp(18), dp(20), dp(18))
        left.addView(vehicle); left.addView(range, margin(top = 18)); left.addView(soc, margin(top = 8)); left.addView(state, margin(top = 18))
        traffic = text(getString(R.string.traffic_empty), 15f, false)
        history = text(getString(R.string.history_empty), 15f, false)
        left.addView(traffic, margin(top = 14))
        left.addView(history, margin(top = 8))
        left.addView(button(getString(R.string.open_panoptes)) { openPanoptes() }, margin(top = 20))
        content.addView(left, LinearLayout.LayoutParams(0, -2, 4f).apply { marginEnd = dp(14) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = tile(); setPadding(dp(18), dp(16), dp(18), dp(18)) }
        right.addView(text(getString(R.string.apps), 22f, true))
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(apps, margin(top = 10))
        content.addView(right, LinearLayout.LayoutParams(0, -2, 6f))
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                val summary = client.summary()
                val appList = client.apps().filter { it.packageName != BuildConfig.APPLICATION_ID }
                val trafficValue = runCatching { client.traffic() }.getOrDefault(LauncherTraffic(0, 0))
                val appearance = runCatching { client.appearance() }.getOrDefault(LauncherAppearance("auto", "crystal"))
                val now = System.currentTimeMillis()
                if (now - lastHistoryFetch >= 60_000) {
                    runCatching { client.history() }.getOrNull()?.let {
                        cachedHistory = it
                        lastHistoryFetch = now
                    }
                }
                main.post { paint(summary, appList, trafficValue, cachedHistory, appearance) }
            } catch (_: Exception) {
                main.post { status.text = getString(R.string.offline); showRetry() }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun paint(
        summary: LauncherSummary,
        appList: List<LaunchableApp>,
        trafficValue: LauncherTraffic,
        historyValue: LauncherHistory,
        appearance: LauncherAppearance
    ) {
        status.text = ""
        applyAppearance(appearance)
        vehicle.text = summary.vehicleName ?: getString(R.string.vehicle_unknown)
        val nf = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
        range.text = getString(R.string.range_format, summary.rangeKm?.let { nf.format(it) } ?: getString(R.string.unknown))
        soc.text = getString(R.string.soc_format, summary.socPct?.let { nf.format(it) } ?: getString(R.string.unknown))
        state.text = getString(R.string.state_format, summary.acc ?: getString(R.string.unknown), summary.gear ?: getString(R.string.unknown))
        traffic.text = if (trafficValue.nearby == 0) getString(R.string.traffic_empty)
        else getString(R.string.traffic_format, trafficValue.nearby, trafficValue.approaching)
        val lastTrip = historyValue.lastTripKm?.let { nf.format(it) } ?: getString(R.string.unknown)
        val lastCharge = historyValue.lastChargeKwh?.let { nf.format(it) } ?: getString(R.string.unknown)
        history.text = if (historyValue.lastTripKm == null && historyValue.lastChargeKwh == null) getString(R.string.history_empty)
        else getString(R.string.history_format, lastTrip, lastCharge)
        apps.removeAllViews()
        if (appList.isEmpty()) apps.addView(text(getString(R.string.apps_empty), 15f, false))
        appList.take(12).forEach { app ->
            apps.addView(button(app.label) { launch(app.packageName) }, margin(bottom = 8))
        }
    }

    private fun applyAppearance(value: LauncherAppearance) {
        val color = when (value.theme) {
            "polar" -> Color.rgb(15, 31, 43)
            "glacier" -> Color.rgb(8, 31, 48)
            "ember" -> Color.rgb(37, 20, 18)
            "signal" -> Color.rgb(15, 28, 24)
            else -> Color.rgb(7, 18, 29)
        }
        root.setBackgroundColor(color)
        window.statusBarColor = color
        window.navigationBarColor = color
    }

    private fun showRetry() {
        apps.removeAllViews()
        apps.addView(button(getString(R.string.retry)) { refresh() })
    }

    private fun launch(packageName: String) {
        io.execute {
            val ok = try { client.launch(packageName) } catch (_: Exception) { false }
            if (!ok) main.post { status.text = getString(R.string.launch_failed) }
        }
    }

    private fun openPanoptes() {
        packageManager.getLaunchIntentForPackage("at.greenchili.surfmate")?.let(::startActivity)
            ?: run { status.text = getString(R.string.offline) }
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(54); setOnClickListener { action() }
    }

    private fun tile() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat()
        setColor(Color.rgb(23, 38, 54)); setStroke(dp(1), Color.rgb(58, 82, 103))
    }

    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
