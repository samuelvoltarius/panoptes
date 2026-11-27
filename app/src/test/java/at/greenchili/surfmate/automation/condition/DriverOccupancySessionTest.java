/*
 * Based on OverDrive's DriverOccupancySessionTest (PR #862, MIT License, Copyright (c) Yash
 * Srivastava and OverDrive contributors), adapted to the final v65.0 session (2-read debounce,
 * DiLink 5 relay evidence).
 */
package at.greenchili.surfmate.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Fahrersitz: nach Türöffnen und anhaltend fehlendem Hinweis wieder scharf, nie „leer“. */
public class DriverOccupancySessionTest {
    private static final DriverOccupancySession.Sample IGNORE = DriverOccupancySession.Sample.IGNORE;
    private static final DriverOccupancySession.Sample INVALIDATE = DriverOccupancySession.Sample.INVALIDATE;
    private static final DriverOccupancySession.Sample OCCUPIED = DriverOccupancySession.Sample.OCCUPIED;

    @Test
    public void positiveEvidencePublishesWithoutDoorHistory() {
        DriverOccupancySession s = new DriverOccupancySession();
        assertEquals(OCCUPIED, s.observePresence(1, 0L));
        assertEquals(IGNORE, s.observePresence(0, 10L)); // unbuckled is NOT empty
    }

    @Test
    public void aFirstDoorSampleDoesNotInventAnExit() {
        DriverOccupancySession s = new DriverOccupancySession();
        s.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(IGNORE, s.observePresence(0, 0L));
        assertEquals(IGNORE, s.observePresence(0, 10L));
        s.observeDoor(DriverOccupancySession.CLOSED);
        assertEquals(OCCUPIED, s.observePresence(1, 20L));
    }

    @Test
    public void beltStillBuckledWhileDoorOpenNeverRearms() {
        DriverOccupancySession s = new DriverOccupancySession();
        s.observeDoor(DriverOccupancySession.CLOSED);
        assertEquals(OCCUPIED, s.observePresence(1, 0L));
        s.observeDoor(DriverOccupancySession.OPEN);
        assertFalse(s.canPublishPositive());
        assertEquals(IGNORE, s.observePresence(1, 10L));
        assertEquals(IGNORE, s.observePresence(1, 20L));
        s.observeDoor(DriverOccupancySession.CLOSED);
        assertTrue(s.canPublishPositive());
        assertEquals(OCCUPIED, s.observePresence(1, 30L)); // same value, dedup happens downstream
    }

    @Test
    public void oneTransientMissDoesNotInvalidateButTwoInARowDoOnce() {
        DriverOccupancySession s = new DriverOccupancySession();
        s.observeDoor(DriverOccupancySession.CLOSED);
        s.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(IGNORE, s.observePresence(0, 0L));           // 1. Fehllesung
        assertEquals(IGNORE, s.observePresence(1, 10L));          // Hinweis wieder da → Zähler zurück
        assertEquals(IGNORE, s.observePresence(0, 20L));
        assertEquals(INVALIDATE, s.observePresence(Integer.MIN_VALUE, 30L));
        assertEquals(IGNORE, s.observePresence(0, 40L));          // genau einmal je Öffnung
        assertEquals(IGNORE, s.observePresence(0, 50L));
        s.observeDoor(DriverOccupancySession.CLOSED);
        assertEquals(IGNORE, s.observePresence(Integer.MIN_VALUE, 60L));
        assertEquals(OCCUPIED, s.observePresence(1, 70L));
    }

    @Test
    public void freshRelayEvidenceHoldsPresenceUntilItIsStale() {
        DriverOccupancySession s = new DriverOccupancySession();
        s.observeDoor(DriverOccupancySession.CLOSED);
        s.observeDoor(DriverOccupancySession.OPEN);
        s.observeRelayPositive(1_000L);
        assertEquals(IGNORE, s.observePresence(0, 1_500L));
        assertEquals(IGNORE, s.observePresence(0, 1_000L + DriverOccupancySession.RELAY_EVIDENCE_MS));
        assertEquals(IGNORE, s.observePresence(0, 1_001L + DriverOccupancySession.RELAY_EVIDENCE_MS));
        assertEquals(INVALIDATE, s.observePresence(0, 1_002L + DriverOccupancySession.RELAY_EVIDENCE_MS));
    }

    @Test
    public void invalidDoorReadingsAndRepeatedOpensDoNotCauseResets() {
        DriverOccupancySession s = new DriverOccupancySession();
        s.observeDoor(DriverOccupancySession.CLOSED);
        s.observeDoor(255);
        assertEquals(OCCUPIED, s.observePresence(1, 0L));
        s.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(IGNORE, s.observePresence(0, 10L));
        assertEquals(INVALIDATE, s.observePresence(0, 20L));
        s.observeDoor(DriverOccupancySession.OPEN);   // open→open is no new edge
        assertEquals(IGNORE, s.observePresence(0, 30L));
        assertEquals(IGNORE, s.observePresence(0, 40L));
    }
}
