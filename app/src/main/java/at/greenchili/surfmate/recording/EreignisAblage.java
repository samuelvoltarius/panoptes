package at.greenchili.surfmate.recording;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.server.RecordingsIndex;
import at.greenchili.surfmate.storage.Aufbewahrung;
import at.greenchili.surfmate.storage.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pflege des Ereignis-Ordners: Aufbewahrungsfrist (wie alle Clips, außer „behalten“) und eigene
 * Obergrenze {@link EreignisClip#GRENZE_BYTES}, älteste zuerst (Logik: {@link EreignisClip#zuLoeschen}).
 * Läuft nach jedem neuen Ereignis-Clip und im 10-min-Takt der {@link Aufbewahrung}.
 */
public final class EreignisAblage {

    private static final DaemonLogger logger = DaemonLogger.getInstance("EreignisClip");

    private EreignisAblage() {}

    public static synchronized int pflege() {
        int geloescht = 0;
        try {
            StorageManager sm = StorageManager.getInstance();
            List<EreignisClip.Datei> dateien = new ArrayList<>();
            org.json.JSONObject config = UnifiedConfigManager.loadConfig();
            // 0.1.43.19: keine Rückfrage mehr. Offene „Behalten?“-Fragen aus älteren Versionen fallen weg; die Clips
            // bleiben 30 Tage (wie jeder Ereignis-Clip), behalten nur, wer in der Clip-Liste „Behalten“ wählt.
            for (String s : EreignisClip.offeneStaemme(config)) {
                if (EreignisClip.frageErledigt(s)) logger.info("Offene Frage übernommen, Clip bleibt 30 Tage: " + s);
            }
            Set<String> behalten = new HashSet<>();
            long gesamt = 0;
            for (File dir : sm.getAllEreignisDirs()) {
                if (dir == null || !dir.isDirectory()) continue;
                try { gesamt = Math.max(gesamt, dir.getTotalSpace()); } catch (Throwable ignored) { }
                File[] files = sm.listMp4Files(dir);
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile() || !EreignisClip.istEreignis(f.getName())) continue;
                    dateien.add(new EreignisClip.Datei(f.getName(), f.length(), f.lastModified(), f));
                    if (Aufbewahrung.istBehalten(f.getName())) behalten.add(EreignisClip.stamm(f.getName()));
                }
            }
            // 0.1.43.19: eigene Frist 30 Tage (nicht die 72 h der übrigen Clips); von Hand behaltene nie nach Alter
            long maxAlter = EreignisClip.LOESCHEN_MS;
            long grenze = EreignisClip.grenze(gesamt);
            List<EreignisClip.Datei> weg = EreignisClip.zuLoeschen(
                    dateien, System.currentTimeMillis(), maxAlter, behalten, grenze);
            for (EreignisClip.Datei d : weg) {
                File f = (File) d.bezug;
                String stamm = EreignisClip.stamm(f.getName());
                boolean warBehalten = behalten.contains(stamm);
                if (f.delete()) {
                    geloescht++;
                    AusloeserInfo.loesche(f);
                    try { RecordingsIndex.getInstance().removeByPath(f.getAbsolutePath()); } catch (Throwable ignored) {}
                    EreignisClip.frageErledigt(stamm);
                    if (warBehalten) Aufbewahrung.freigeben(stamm);
                    boolean abgelaufen = !warBehalten && d.zeit > 0
                            && System.currentTimeMillis() - d.zeit > EreignisClip.LOESCHEN_MS;
                    if (abgelaufen) {
                        logger.info("Ereignis-Clip nach " + EreignisClip.LOESCHEN_TAGE + " Tagen gelöscht: " + f.getName());
                    } else {
                        // Obergrenze erreicht → ältester gesicherter Ereignis-Clip ersetzt, immer geloggt
                        logger.info("Ereignis-Ordner voll (Grenze " + (grenze / (1024 * 1024))
                                + " MB): ältesten gesicherten Ereignis-Clip ersetzt" + (warBehalten ? " (behalten)" : "")
                                + ": " + f.getName());
                    }
                }
            }
            if (geloescht > 0) logger.info("Ereignis-Ordner gepflegt: " + geloescht + " Clip(s) gelöscht");
        } catch (Throwable t) {
            logger.warn("Ereignis-Ordner pflegen fehlgeschlagen: " + t.getMessage());
        }
        return geloescht;
    }
}
