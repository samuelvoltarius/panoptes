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
            Set<String> behalten = new HashSet<>();
            for (File dir : sm.getAllEreignisDirs()) {
                if (dir == null || !dir.isDirectory()) continue;
                File[] files = sm.listMp4Files(dir);
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile() || !EreignisClip.istEreignis(f.getName())) continue;
                    dateien.add(new EreignisClip.Datei(f.getName(), f.length(), f.lastModified(), f));
                    if (Aufbewahrung.istBehalten(f.getName())) behalten.add(EreignisClip.stamm(f.getName()));
                }
            }
            long maxAlter = Aufbewahrung.maxAlterMs(UnifiedConfigManager.loadConfig());
            List<EreignisClip.Datei> weg = EreignisClip.zuLoeschen(
                    dateien, System.currentTimeMillis(), maxAlter, behalten,
                    EreignisClip.GRENZE_BYTES);
            for (EreignisClip.Datei d : weg) {
                File f = (File) d.bezug;
                if (f.delete()) {
                    geloescht++;
                    try { RecordingsIndex.getInstance().removeByPath(f.getAbsolutePath()); } catch (Throwable ignored) {}
                    EreignisClip.frageErledigt(EreignisClip.stamm(f.getName()));
                }
            }
            if (geloescht > 0) logger.info("Ereignis-Ordner gepflegt: " + geloescht + " Clip(s) gelöscht");
        } catch (Throwable t) {
            logger.warn("Ereignis-Ordner pflegen fehlgeschlagen: " + t.getMessage());
        }
        return geloescht;
    }
}
