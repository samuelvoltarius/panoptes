package at.greenchili.surfmate.appupdate;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.1 Update-Meldungen (04.11.: „bekommen wir eine Nachricht, wenn Launcher, Panoptes … geupdatet
 * werden?“). Rein: Zustand rein, Meldungen raus. Gesendet wird über {@link AppUpdateDienst.Umgebung} (Telegram
 * Kategorie UPDATE und Handy-Push Kategorie {@code system.updates}, gleiche Einwilligung wie „neue Version“).
 *
 * <ul>
 *   <li><b>Panoptes installiert und läuft</b>: erst wenn die Selbstprüfung der Startskripte grün ist und der Updater
 *       nicht mehr beobachtet (Selbstbestätigung). Meldet die Selbstprüfung eine Störung, geht stattdessen deren
 *       Klartext-Meldung hinaus ({@code WatchdogSelbstpruefung}) und hier nichts.</li>
 *   <li><b>Launcher verfügbar</b>: nur wenn der Launcher installiert ist und die Freigabe neuer ist.</li>
 *   <li><b>Launcher installiert</b>: wenn der installierte Launcher neuer ist als beim letzten Mal.</li>
 * </ul>
 * Je Version höchstens einmal. Beim allerersten Lauf (Neuinstallation) wird nur gemerkt, nichts gemeldet, außer die
 * laufende Version war vorher schon als „neu verfügbar“ gemeldet (dann war es ein Update).
 */
public final class UpdateMeldung {

    static final String DATEI = ".update-meldung.json";

    public static final String PANOPTES_INSTALLIERT = "panoptes_installiert";
    public static final String LAUNCHER_VERFUEGBAR = "launcher_verfuegbar";
    public static final String LAUNCHER_INSTALLIERT = "launcher_installiert";

    /** Selbstprüfung: grün, gemeldete Störung, noch kein Ergebnis. */
    public static final String SELBST_GRUEN = "gruen";
    public static final String SELBST_STOERUNG = "stoerung";
    public static final String SELBST_OFFEN = "offen";

    /** Eine Meldung: Art und Version (Anzeigename). */
    public static final class Meldung {
        public final String art;
        public final int versionCode;
        public final String version;

        Meldung(String art, int versionCode, String version) {
            this.art = art;
            this.versionCode = versionCode;
            this.version = version;
        }

        @Override public String toString() { return art + " " + version + " (" + versionCode + ")"; }
    }

    int panoptesVc;
    int launcherVc;
    int launcherVerfuegbarVc;

    /**
     * Ein Durchgang. Ändert den Zustand (danach {@link #speichern}).
     *
     * @param gemeldetVerfuegbarVc höchster versionCode, der schon als „neu verfügbar“ gemeldet wurde
     * @param beobachtet           der Updater prüft die neue Version noch (Selbstbestätigung offen)
     * @param launcherInstalliertVc 0 = Launcher nicht installiert
     * @param launcherFrei          bekannte Launcher-Freigabe oder null
     */
    List<Meldung> pruefe(int installiertVc, String installiertName, int gemeldetVerfuegbarVc, String selbst,
                         boolean beobachtet, int launcherInstalliertVc, Freigabe.Launcher launcherFrei) {
        List<Meldung> out = new ArrayList<>();
        if (installiertVc > 0) {
            if (panoptesVc <= 0) {
                // Erster Lauf: war genau diese Version vorher als „neu“ gemeldet, ist es ein Update.
                panoptesVc = gemeldetVerfuegbarVc == installiertVc ? installiertVc - 1 : installiertVc;
            }
            if (installiertVc < panoptesVc) {
                panoptesVc = installiertVc;   // Rückfall: still mitgehen
            } else if (installiertVc > panoptesVc && !beobachtet) {
                if (SELBST_GRUEN.equals(selbst)) {
                    out.add(new Meldung(PANOPTES_INSTALLIERT, installiertVc, installiertName));
                    panoptesVc = installiertVc;
                } else if (SELBST_STOERUNG.equals(selbst)) {
                    panoptesVc = installiertVc;   // die Störungsmeldung ist schon draußen
                }
            }
        }
        if (launcherInstalliertVc > 0) {
            if (launcherVc <= 0) {
                launcherVc = launcherInstalliertVc;
            } else if (launcherInstalliertVc > launcherVc) {
                String name = launcherFrei != null && launcherFrei.versionCode == launcherInstalliertVc
                        ? launcherFrei.versionName : Integer.toString(launcherInstalliertVc);
                out.add(new Meldung(LAUNCHER_INSTALLIERT, launcherInstalliertVc, name));
                launcherVc = launcherInstalliertVc;
            } else if (launcherInstalliertVc < launcherVc) {
                launcherVc = launcherInstalliertVc;
            }
            if (launcherFrei != null && launcherFrei.versionCode > launcherInstalliertVc
                    && launcherFrei.versionCode > launcherVerfuegbarVc) {
                out.add(new Meldung(LAUNCHER_VERFUEGBAR, launcherFrei.versionCode, launcherFrei.versionName));
                launcherVerfuegbarVc = launcherFrei.versionCode;
            }
        }
        return out;
    }

    static UpdateMeldung lade(File basis) {
        UpdateMeldung m = new UpdateMeldung();
        try {
            File f = new File(basis, DATEI);
            if (!f.isFile()) return m;
            JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            m.panoptesVc = j.optInt("panoptesVc", 0);
            m.launcherVc = j.optInt("launcherVc", 0);
            m.launcherVerfuegbarVc = j.optInt("launcherVerfuegbarVc", 0);
        } catch (Exception ignored) {
        }
        return m;
    }

    void speichern(File basis) {
        try {
            JSONObject j = new JSONObject().put("panoptesVc", panoptesVc).put("launcherVc", launcherVc)
                    .put("launcherVerfuegbarVc", launcherVerfuegbarVc);
            File tmp = new File(basis, DATEI + ".neu");
            Files.write(tmp.toPath(), j.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(basis, DATEI))) {
                Files.write(new File(basis, DATEI).toPath(), j.toString().getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** Telegram-Schlüssel ({@code telegram.update.*}) bzw. Push-Titel ({@code notifications.*}) je Art. */
    public static String telegramSchluessel(String art) {
        switch (art) {
            case PANOPTES_INSTALLIERT: return "update.installiert_laeuft";
            case LAUNCHER_VERFUEGBAR: return "update.launcher_verfuegbar";
            default: return "update.launcher_installiert";
        }
    }

    public static String pushSchluessel(String art) {
        switch (art) {
            case PANOPTES_INSTALLIERT: return "notifications.update_installiert";
            case LAUNCHER_VERFUEGBAR: return "notifications.launcher_verfuegbar";
            default: return "notifications.launcher_installiert";
        }
    }
}
