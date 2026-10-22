package at.greenchili.surfmate.lizenz;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Clean-Room 0.1.9: Der DiLink-5-IPC-Client ist eine eigene MIT-Neufassung (pkam_client, pkam_protokoll) nach
 * CLEANROOM_FASTCAM_SPEC.md. Die GPL-Dateien sind aus Build und Baum entfernt; nichts verweist mehr auf sie.
 * Die Protokoll-Tests gegen einen Server-Nachbau liegen in app/src/test/native (Linux: run.sh).
 */
public class PkamCleanRoomTest {

    @Test
    public void gplDateienSindWeg() {
        assertFalse(Files.exists(Paths.get("src/main/cpp/camera/fast_cam_bridge.cpp")));
        assertFalse(Files.exists(Paths.get("src/main/cpp/include/fast_cam_bridge.h")));
        assertFalse(Files.exists(Paths.get("src/main/cpp/include/fast_cam_ipc.h")));
    }

    @Test
    public void neufassungIstMitUndVerdrahtet() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/cpp/include/pkam_protokoll.h"));
        String c = Quelltext.lies(Paths.get("src/main/cpp/camera/pkam_client.cpp"));
        String ch = Quelltext.lies(Paths.get("src/main/cpp/camera/pkam_client.h"));
        for (String s : new String[]{h, c, ch}) {
            assertTrue(s.contains("SPDX-License-Identifier: MIT"));
            assertTrue(s.contains("Clean-Room"));
        }
        String cmake = Quelltext.lies(Paths.get("src/main/cpp/CMakeLists.txt"));
        assertTrue(cmake.contains("camera/pkam_client.cpp"));
        assertFalse(cmake.contains("fast_cam_bridge.cpp"));
        String q = Quelltext.lies(Paths.get("src/main/cpp/camera/qcarcam_bridge.cpp"));
        assertTrue(q.contains("#include \"pkam_client.h\""));
        assertFalse(q.contains("fast_cam_bridge.h"));
        assertFalse(q.contains("fast_cam_ipc.h"));
        String g = Quelltext.lies(Paths.get("src/main/cpp/camera/fast_cam_release_guard.c"));
        assertTrue(g.contains("#include \"pkam_protokoll.h\""));
        assertFalse(g.contains("fast_cam_ipc.h"));
        assertTrue(Files.exists(Paths.get("src/test/native/pkam_client_test.cpp")));
        assertTrue(Files.exists(Paths.get("src/test/native/run.sh")));
    }

    /** Drahtformat laut Spec (Größen stehen zusätzlich als static_assert im Header). */
    @Test
    public void drahtformat() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/cpp/include/pkam_protokoll.h"));
        assertTrue(h.contains("#define PKAM_KENNUNG 0x4643414Du"));
        assertTrue(h.contains("#define PKAM_FAEHIG_RUECKGABE 0x80000000u"));
        assertTrue(h.contains("static_assert(sizeof(pkam_begruessung_t) == 240"));
        assertTrue(h.contains("static_assert(sizeof(pkam_bild_t) == 36"));
        assertTrue(h.contains("static_assert(sizeof(pkam_rueckgabe_t) == 20"));
    }
}
