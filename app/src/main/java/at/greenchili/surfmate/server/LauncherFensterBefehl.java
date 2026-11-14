package at.greenchili.surfmate.server;

import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 0.1.44 (D1 „Fahrzeug-Startseite“, 12.11.: „das wollen die user“): Fenster-Schnellknöpfe des Launchers,
 * {@code POST /api/launcher/v1/fenster {fenster, ziel}}. Bewusst eng, wie der Klimaweg ({@link LauncherKlimaBefehl}):
 *
 * <ul>
 *   <li>nur {@code fenster} ∈ {@code vl, vr, hl, hr, dach} und {@code ziel} ∈ {@code auf, zu}; keine Zahlen von außen.
 *       Seitenfenster über denselben Router-Befehl wie vehicle-control „panelWindows“
 *       ({@link VehicleCommandRouter.WindowMoveCommand}, area 1–4 = LF/RF/LR/RR, 1 = auf, 2 = zu, mit Echo); das Dach
 *       (area 5) über die Zielstellung 100 / 0 %.</li>
 *   <li>Stand-Sperre (fail-closed): nur bei Tempo ≤ 0,5 km/h (oder unbekannt) UND Gang P, oder Gang unbekannt bei
 *       Zündung aus – wie das Bedienen im Launcher ({@code Fahrsicherheit.bearbeitenErlaubt}). Sonst {@code fahrt}.</li>
 *   <li>Ratenlimit: höchstens ein Befehl je {@value #MIN_ABSTAND_MS} ms und {@value #MAX_IM_FENSTER} je
 *       {@value #FENSTER_MS} ms. Jeder Befehl landet mit Ausgang und Weg im Befehls-Log.</li>
 *   <li>Nie Hupe, Licht, Blinker, Alarm, Auf-/Zusperren, Kofferraum (Regel A+): {@link #FENSTER} ist die ganze Liste.</li>
 *   <li>wer aufrufen darf, prüft {@link LauncherAufrufer} vorher (nur die Launcher-APK mit Panoptes-Signatur).</li>
 * </ul>
 */
final class LauncherFensterBefehl {

    static final Set<String> FENSTER = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("vl", "vr", "hl", "hr", "dach")));
    static final Set<String> ZIELE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("auf", "zu")));
    /** Fenster → area der Fenster-API (wie vehicle-control: 1 = LF, 2 = RF, 3 = LR, 4 = RR, 5 = Dach). */
    static final Map<String, Integer> AREA;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("vl", 1); m.put("vr", 2); m.put("hl", 3); m.put("hr", 4); m.put("dach", 5);
        AREA = Collections.unmodifiableMap(m);
    }

    static final long MIN_ABSTAND_MS = 1_000L;
    static final long FENSTER_MS = 30_000L;
    static final int MAX_IM_FENSTER = 10;
    static final double STAND_KMH = 0.5;

    /** Auto (im Test nachgebaut). */
    interface Auto {
        /** Gang „P/R/N/D“ nur frisch mit Zündung, sonst null (wie das Launcher-Summary). */
        String gang();
        boolean zuendungAn();
        /** Tempo in km/h oder null, wenn kein Messwert. */
        Double tempo();
        LauncherKlimaBefehl.Ergebnis ausfuehren(VehicleCommand c);
    }

    static volatile Auto auto = new Echt();

    private static final Deque<Long> ZEITEN = new ArrayDeque<>();

    private LauncherFensterBefehl() {}

    /** Stand wie {@code Fahrsicherheit.bearbeitenErlaubt} im Launcher: Tempo > 0,5 sperrt immer. */
    static boolean imStand(String gang, boolean zuendungAn, Double tempo) {
        if (tempo != null && tempo > STAND_KMH) return false;
        return "P".equals(gang) || (gang == null && !zuendungAn);
    }

    /** Router-Befehl; null bei unbekanntem Fenster oder Ziel. */
    static VehicleCommand befehl(String fenster, String ziel) {
        Integer area = fenster == null ? null : AREA.get(fenster);
        if (area == null || ziel == null || !ZIELE.contains(ziel)) return null;
        boolean auf = "auf".equals(ziel);
        if (area == 5) return new VehicleCommandRouter.WindowMoveCommand(5, 0, auf ? 100 : 0);
        return new VehicleCommandRouter.WindowMoveCommand(area, auf ? 1 : 2, null);
    }

    static synchronized boolean ratenlimit(long jetzt) {
        while (!ZEITEN.isEmpty() && jetzt - ZEITEN.peekFirst() > FENSTER_MS) ZEITEN.pollFirst();
        Long letzte = ZEITEN.peekLast();
        if (letzte != null && jetzt - letzte < MIN_ABSTAND_MS) return false;
        if (ZEITEN.size() >= MAX_IM_FENSTER) return false;
        ZEITEN.addLast(jetzt);
        return true;
    }

    static synchronized void ratenlimitLeeren() {
        ZEITEN.clear();
    }

    /**
     * Antwort {@code {status, json}}: 200 mit {@code ok, fenster, ziel, ausgang, weg}, 400 bei unbekanntem Fenster/Ziel,
     * 409 {@code fahrt} außerhalb des Stands, 429 beim Ratenlimit.
     */
    static Object[] ausfuehren(String body, long jetzt) {
        String fenster = "", ziel = "";
        try {
            JSONObject b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            fenster = b.optString("fenster", "");
            ziel = b.optString("ziel", "");
        } catch (Exception ignored) { }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            VehicleCommand c = befehl(fenster, ziel);
            if (c == null) {
                BefehlsLog.grund("aktion");
                return new Object[]{400, o.put("grund", "aktion")};
            }
            o.put("fenster", fenster).put("ziel", ziel);
            String name = "launcher.fenster." + fenster + "." + ziel;
            Auto a = auto;
            if (!imStand(a.gang(), a.zuendungAn(), a.tempo())) {
                BefehlsLog.grund("fahrt");
                BefehlsLog.ereignis(name, "abgelehnt", null, null);
                return new Object[]{409, o.put("grund", "fahrt")};
            }
            if (!ratenlimit(jetzt)) {
                BefehlsLog.grund("ratenlimit");
                BefehlsLog.ereignis(name, "abgelehnt", null, null);
                return new Object[]{429, o.put("grund", "ratenlimit")};
            }
            BefehlsLog.ereignis(name, "ok", null, null);
            LauncherKlimaBefehl.Ergebnis e = a.ausfuehren(c);
            boolean ok = "success".equals(e.ausgang);
            o.put("ok", ok).put("ausgang", e.ausgang).put("weg", e.weg);
            if (!ok) o.put("grund", e.ausgang);
            return new Object[]{200, o};
        } catch (Exception e) {
            return new Object[]{500, o};
        }
    }

    private static final class Echt implements Auto {
        @Override
        public String gang() {
            try {
                if (!at.greenchili.surfmate.monitor.AccMonitor.isAccOn()) return null;
                at.greenchili.surfmate.monitor.GearMonitor gm = at.greenchili.surfmate.monitor.GearMonitor.getInstance();
                if (gm == null || !gm.isRunning()) return null;
                int g = at.greenchili.surfmate.monitor.GearMonitor.gangFrisch();
                return g == at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE ? null
                        : at.greenchili.surfmate.monitor.GearMonitor.gearToString(g);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean zuendungAn() {
            try {
                return at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
            } catch (Throwable t) {
                return true;   // unbekannt = wie an (fail-closed: ohne Gang dann gesperrt)
            }
        }

        @Override
        public Double tempo() {
            try {
                at.greenchili.surfmate.byd.BydVehicleData vd =
                        at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                if (vd == null || Double.isNaN(vd.speedKmh) || vd.speedKmh < 0) return null;
                return vd.speedKmh;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public LauncherKlimaBefehl.Ergebnis ausfuehren(VehicleCommand c) {
            VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance().execute(c);
            return new LauncherKlimaBefehl.Ergebnis(r.outcome.name().toLowerCase(java.util.Locale.ROOT),
                    r.path == null ? "none" : r.path.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
