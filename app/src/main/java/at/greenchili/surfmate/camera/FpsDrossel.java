package at.greenchili.surfmate.camera;

/**
 * Software-Drossel für die Kamera (Koordination 0.1.9, #7). Rein, testbar, nur vom GL-Thread benutzt.
 *
 * <p>Viele BYD-HALs lehnen {@code setCameraFps} ab oder liefern trotzdem mit voller Rate. Bisher liefen dann
 * HAL-Bindung (Gralloc/EGLImage), Ring-Kopie und KI mit der vollen HAL-Rate, auch wenn 2–5 fps angefordert waren.
 * Die Drossel nimmt jeden Frame entgegen (der HAL-Puffer muss zurück), verarbeitet aber nur so viele, wie
 * angefordert sind.
 *
 * <p>Aktiv nur, wenn der HAL die Rate nicht setzen kann ({@code halSetzbar == false}) oder die gemessene
 * Lieferrate deutlich (über {@link #TOLERANZ}) über dem Ziel liegt. Liefert der HAL ohnehin weniger (DiLink 4:
 * fest ~4,5 fps), verwirft sie nie etwas. DiLink 5 drosselt nativ ({@code setOutputFps}) und nutzt das hier nicht.
 *
 * <p>Takt mit festem Raster ({@code naechster += intervall}), damit z. B. 25 → 10 fps im Mittel genau 10 ergibt und
 * nicht durch Rundung auf 8,3 fällt.
 */
public final class FpsDrossel {

    /** Gemessene Rate muss mindestens so viel über dem Ziel liegen, damit gedrosselt wird. */
    static final double TOLERANZ = 1.25;
    /** Messfenster für die HAL-Lieferrate. */
    static final long FENSTER_NS = 2_000_000_000L;

    private long fensterStartNs = -1;
    private int fensterAnkuenfte;
    private double gemessenFps = -1;
    private long naechsterNs = -1;
    private long angenommen, verworfen;
    private boolean aktiv;

    /** true = Frame verarbeiten, false = nur zurückgeben (vor Bindung und Verbrauchern verwerfen). */
    public boolean annehmen(long nowNs, int zielFps, Boolean halSetzbar) {
        messen(nowNs);
        aktiv = zielFps > 0 && (Boolean.FALSE.equals(halSetzbar)
                || (gemessenFps > 0 && gemessenFps > zielFps * TOLERANZ));
        if (!aktiv) {
            naechsterNs = -1;
            angenommen++;
            return true;
        }
        long intervall = 1_000_000_000L / zielFps;
        if (naechsterNs < 0 || nowNs >= naechsterNs) {
            // Nach einer Pause (Lücke > 1 Intervall) neu ansetzen statt nachzuholen
            naechsterNs = (naechsterNs < 0 || nowNs - naechsterNs > intervall) ? nowNs + intervall
                    : naechsterNs + intervall;
            angenommen++;
            return true;
        }
        verworfen++;
        return false;
    }

    /** Der zuletzt verworfene Aufruf war doch kein Frame (kein Bild im Puffer): Zählung zurücknehmen. */
    public void keinFrame() {
        if (fensterAnkuenfte > 0) fensterAnkuenfte--;
        if (verworfen > 0) verworfen--;
    }

    private void messen(long nowNs) {
        if (fensterStartNs < 0) {
            fensterStartNs = nowNs;
            fensterAnkuenfte = 0;
        }
        fensterAnkuenfte++;
        long dauer = nowNs - fensterStartNs;
        if (dauer >= FENSTER_NS) {
            gemessenFps = fensterAnkuenfte * 1e9 / dauer;
            fensterStartNs = nowNs;
            fensterAnkuenfte = 0;
        }
    }

    /** Vom HAL gelieferte Rate (alle Frames, auch verworfene), −1 = noch nicht gemessen. */
    public double gemessenFps() { return gemessenFps; }

    public boolean aktiv() { return aktiv; }

    public long angenommen() { return angenommen; }

    public long verworfen() { return verworfen; }

    /** Neu beginnen (Kamera neu geöffnet). */
    public void zuruecksetzen() {
        fensterStartNs = -1;
        fensterAnkuenfte = 0;
        gemessenFps = -1;
        naechsterNs = -1;
        aktiv = false;
    }
}
