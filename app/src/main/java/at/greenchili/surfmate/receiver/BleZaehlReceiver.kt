package at.greenchili.surfmate.receiver

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import at.greenchili.surfmate.ble.BleZaehlung

/**
 * 0.1.16: BLE-Zählen in der App (der Daemon mit UID 2000 darf nicht scannen). Angestoßen nur vom Daemon per
 * `am broadcast -n …/.receiver.BleZaehlReceiver` (Manifest: exportiert, aber nur mit android.permission.DUMP – also
 * Shell/System, keine fremde App). Antwort über die Broadcast-Ergebnisdaten: nur Anzahl und grobe Typen.
 *
 * Schaltet NIE Bluetooth oder den Standortdienst ein (Koordination 23.10.): ist etwas aus, kommt nur der Zustand
 * zurück. Passiver Scan mit geringer Leistung, [BleZaehlung.SCAN_MS] lang; Adressen nur im Speicher zum Entdoppeln.
 */
class BleZaehlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BleZaehlung.AKTION) return
        val ergebnis = goAsync()
        val fertig: (String) -> Unit = { json ->
            try {
                ergebnis.resultData = json
                ergebnis.finish()
            } catch (t: Throwable) {
                Log.w(TAG, "Ergebnis: ${t.message}")
            }
        }
        val zustand = vorbedingung(context)
        if (zustand != null) {
            fertig(BleZaehlung.nicht(zustand).toString())
            return
        }
        try {
            starteScan(context, fertig)
        } catch (t: Throwable) {
            Log.w(TAG, "Scan: ${t.javaClass.simpleName}")
            fertig(BleZaehlung.nicht(BleZaehlung.SCAN_FEHLER).toString())
        }
    }

    /** null = darf scannen; sonst der Zustand (bt_aus, standort_aus, recht_fehlt …). Nur lesen, nichts einschalten. */
    private fun vorbedingung(c: Context): String? {
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return BleZaehlung.BT_FEHLT
        if (!adapter.isEnabled) return BleZaehlung.BT_AUS
        val fein = c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val grob = c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fein && !grob) return BleZaehlung.RECHT_FEHLT
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val standortAn = try { lm?.isLocationEnabled == true } catch (_: Throwable) { false }
        if (!standortAn) return BleZaehlung.STANDORT_AUS   // Android liefert ohne Standortdienst keine Treffer
        return null
    }

    private fun starteScan(c: Context, fertig: (String) -> Unit) {
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter.bluetoothLeScanner ?: run {
            fertig(BleZaehlung.nicht(BleZaehlung.BT_AUS).toString())
            return
        }
        val geraete = LinkedHashMap<String, String>()   // Adresse → Typ, nur im Speicher
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                val dienste = rec?.serviceData?.keys?.mapNotNull { kurz(it.uuid) } ?: emptyList()
                val hersteller = ArrayList<Int>()
                rec?.manufacturerSpecificData?.let { s -> for (i in 0 until s.size()) hersteller.add(s.keyAt(i)) }
                synchronized(geraete) { geraete[result.device.address] = BleZaehlung.typ(dienste, hersteller) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan fehlgeschlagen: $errorCode")
            }
        }
        val einstellungen = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanner.startScan(null, einstellungen, cb)
        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(cb) } catch (_: Throwable) {}
            val kopie = synchronized(geraete) { HashMap(geraete) }
            fertig(BleZaehlung.ergebnis(kopie, BleZaehlung.SCAN_MS).toString())
        }, BleZaehlung.SCAN_MS)
    }

    /** 16-Bit-UUID aus einer UUID auf der Bluetooth-Basis, sonst null. */
    private fun kurz(u: java.util.UUID): Int? {
        if (u.leastSignificantBits != -0x7fffff7fa064cb05L) return null   // 0x800000805F9B34FB
        val hi = u.mostSignificantBits
        if ((hi and 0xFFFFFFFFL) != 0x1000L || (hi ushr 48) != 0L) return null
        return ((hi ushr 32) and 0xFFFFL).toInt()
    }

    private companion object {
        const val TAG = "BleZaehlReceiver"
    }
}
