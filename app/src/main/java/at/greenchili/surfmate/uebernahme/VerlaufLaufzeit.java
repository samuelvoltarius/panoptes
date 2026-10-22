package at.greenchili.surfmate.uebernahme;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Set;

/** Laufzeit-Anbindung für {@link OverdriveVerlauf}: Panoptes-DBs, Gang, Speicher, gemerkte Grenzen (Daemon-Ordner). */
public final class VerlaufLaufzeit implements OverdriveVerlauf.Umgebung {

    static final String ORDNER = "/data/user_de/0/com.android.shell/surfmate/";

    public File quellOrdner() {
        return new File(OverdriveQuelle.STANDARD_ORDNER);
    }

    public File arbeitsOrdner() {
        return new File(at.greenchili.surfmate.util.DaemonStorage.rebase(ORDNER + "uebernahme-arbeit"));
    }

    public long freiBytes(File ordner) {
        File f = ordner;
        while (f != null && !f.exists()) f = f.getParentFile();
        return f == null ? 0 : f.getUsableSpace();
    }

    public boolean steht() {
        try {
            at.greenchili.surfmate.byd.BydVehicleData v = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
            return v != null && v.gearMode == 1 && v.speedKmh < 1;   // Gang P, keine Fahrt
        } catch (Throwable t) {
            return false;
        }
    }

    public VerlaufKopierer.Ziel ladeZiel() {
        final at.greenchili.surfmate.monitor.SocHistoryDatabase d = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
        return new VerlaufKopierer.Ziel() {
            public Set<String> spalten(String t) throws Exception { return d.importLesen(c -> VerlaufKopierer.spalten(c, t)); }
            public Set<Long> schluessel(String t, String s) throws Exception { return d.importLesen(c -> VerlaufKopierer.schluessel(c, t, s)); }
            public java.util.TreeMap<Long, List<Double>> schluesselMitWert(String t, String s, String w) throws Exception {
                return d.importLesen(c -> VerlaufKopierer.schluesselMitWert(c, t, s, w));
            }
            public int einfuegen(String t, List<String> s, List<Object[]> z) throws Exception {
                return d.importArbeit(c -> VerlaufKopierer.einfuegen(c, t, s, z));
            }
        };
    }

    public VerlaufKopierer.Ziel fahrtZiel() {
        final at.greenchili.surfmate.trips.TripDatabase d = fahrtDb();
        return new VerlaufKopierer.Ziel() {
            public Set<String> spalten(String t) throws Exception {
                return d == null ? java.util.Collections.<String>emptySet() : d.importLesen(c -> VerlaufKopierer.spalten(c, t));
            }
            public Set<Long> schluessel(String t, String s) throws Exception { return d.importLesen(c -> VerlaufKopierer.schluessel(c, t, s)); }
            public java.util.TreeMap<Long, List<Double>> schluesselMitWert(String t, String s, String w) throws Exception {
                return d.importLesen(c -> VerlaufKopierer.schluesselMitWert(c, t, s, w));
            }
            public int einfuegen(String t, List<String> s, List<Object[]> z) throws Exception {
                return d.importArbeit(c -> VerlaufKopierer.einfuegen(c, t, s, z));
            }
        };
    }

    private static at.greenchili.surfmate.trips.TripDatabase fahrtDb() {
        try {
            at.greenchili.surfmate.trips.TripAnalyticsManager m = at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
            return m == null ? null : m.getDatabase();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ortskreis-Tarif, wenn einer passt (wie beim eigenen Laden) – sonst bleibt „Preis fehlt“. */
    public int preiseNachOrtskreis(List<Long> starts) {
        at.greenchili.surfmate.monitor.SocHistoryDatabase d = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
        int n = 0;
        for (long s : starts) {
            try {
                long id = d.chargingIdByStart(s);
                JSONObject z = id < 0 ? null : d.chargingPriceSnapshot(id);
                if (z == null) continue;
                double lat = z.optDouble("lat", 0), lng = z.optDouble("lng", 0);
                if (lat == 0 && lng == 0) continue;
                int dc = z.isNull("dc") ? -1 : (z.optBoolean("dc") ? 1 : 0);
                at.greenchili.surfmate.charging.TariffProfile p =
                        at.greenchili.surfmate.charging.TariffManager.getInstance().resolveInCircle(lat, lng, dc);
                if (p == null || !p.hatPreis(dc)) continue;
                String w = p.getCurrency() == null || p.getCurrency().isEmpty() ? z.optString("currency", "") : p.getCurrency();
                if (d.setChargingSessionPrice(id, p.rateFor(dc), w, p.getId(), p.getLabel(), "tarif", null)) n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    public void ladeTageNeu(List<Long> starts) {
        try {
            at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance().rebuildChargingDaysFor(starts);
        } catch (Throwable ignored) {
        }
    }

    public void fahrtSummenNeu(List<Long> starts) {
        at.greenchili.surfmate.trips.TripDatabase d = fahrtDb();
        if (d != null) d.rollupsFuerStarts(starts);
    }
}
