package at.greenchili.surfmate.receiver

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import at.greenchili.surfmate.ble.BleNaehe
import at.greenchili.surfmate.ble.BleZaehlung
import org.json.JSONObject

/**
 * 0.1.38.8 Aufsperren beim Annähern: kurzer BLE-Scan nach der Nähe-Bake des gekoppelten Handys (ANNAEHERUNG_VERTRAG §2).
 * Angestoßen nur vom Daemon per `am broadcast -n …/.receiver.BleNaeheReceiver` (Manifest: nur mit
 * android.permission.DUMP). Die erwarteten Baken kommen als Extra `baken` (bis 3 × 16 Hex), gescannt wird höchstens
 * [BleNaehe.SCAN_MS] mit Filter auf [BleNaehe.DIENST_UUID]. Antwort nur `{ok, gesehen, rssi}` – keine Adressen, keine Namen.
 * Schaltet NIE Bluetooth oder den Standortdienst ein; ist etwas aus, kommt nur der Zustand zurück.
 */
class BleNaeheReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BleNaehe.AKTION) return
        val ergebnis = goAsync()
        val fertig: (JSONObject) -> Unit = { json ->
            try {
                ergebnis.resultData = json.toString()
                ergebnis.finish()
            } catch (t: Throwable) {
                Log.w(TAG, "Ergebnis: ${t.message}")
            }
        }
        val baken = (intent.getStringExtra("baken") ?: "").split(",")
            .filter { it.matches(Regex("[0-9a-f]{16}")) }.take(3).toSet()
        if (baken.isEmpty()) {
            fertig(BleZaehlung.nicht("baken_fehlen"))
            return
        }
        val zustand = vorbedingung(context)
        if (zustand != null) {
            fertig(BleZaehlung.nicht(zustand))
            return
        }
        try {
            starteScan(context, baken, intent.getLongExtra("scanMs", BleNaehe.SCAN_MS).coerceIn(1_000L, 20_000L), fertig)
        } catch (t: Throwable) {
            Log.w(TAG, "Scan: ${t.javaClass.simpleName}")
            fertig(BleZaehlung.nicht(BleZaehlung.SCAN_FEHLER))
        }
    }

    /** null = darf scannen; sonst der Zustand. Nur lesen, nichts einschalten. */
    private fun vorbedingung(c: Context): String? {
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return BleZaehlung.BT_FEHLT
        if (!adapter.isEnabled) return BleZaehlung.BT_AUS
        val fein = c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fein) return BleZaehlung.RECHT_FEHLT
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val standortAn = try { lm?.isLocationEnabled == true } catch (_: Throwable) { false }
        if (!standortAn) return BleZaehlung.STANDORT_AUS
        return null
    }

    private fun starteScan(c: Context, baken: Set<String>, scanMs: Long, fertig: (JSONObject) -> Unit) {
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter.bluetoothLeScanner ?: run {
            fertig(BleZaehlung.nicht(BleZaehlung.BT_AUS))
            return
        }
        val uuid = ParcelUuid.fromString(BleNaehe.DIENST_UUID)
        val handler = Handler(Looper.getMainLooper())
        var besteRssi = -127
        var gesehen = false
        var beendet = false
        lateinit var cb: ScanCallback
        val ende: () -> Unit = {
            if (!beendet) {
                beendet = true
                try { scanner.stopScan(cb) } catch (_: Throwable) {}
                fertig(JSONObject().put("ok", true).put("gesehen", gesehen).put("rssi", besteRssi))
            }
        }
        cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val hex = BleNaehe.hex(result.scanRecord?.getServiceData(uuid)) ?: return
                if (hex !in baken) return
                gesehen = true
                if (result.rssi > besteRssi) besteRssi = result.rssi
                // stark genug: sofort fertig, spart Strom
                if (besteRssi >= BleNaehe.RSSI_MIN) handler.post(ende)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan fehlgeschlagen: $errorCode")
                handler.post {
                    if (!beendet) {
                        beendet = true
                        fertig(BleZaehlung.nicht(BleZaehlung.SCAN_FEHLER))
                    }
                }
            }
        }
        val filter = ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build()   // leere Maske: nur „Dienstdaten da“
        val einstellungen = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), einstellungen, cb)
        handler.postDelayed(ende, scanMs)
    }

    private companion object {
        const val TAG = "BleNaeheReceiver"
    }
}
