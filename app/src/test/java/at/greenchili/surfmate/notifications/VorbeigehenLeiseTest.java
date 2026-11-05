package at.greenchili.surfmate.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.surveillance.Actor;
import at.greenchili.surfmate.surveillance.SurveillanceConfig;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * 0.1.38.10 Wächter-KI B2: Wer nur nah am Auto vorbeigeht, wird als NOTICE gemeldet statt als
 * ALERT. Nie lauter, CRITICAL und Annäherung/Verweilen unverändert.
 */
public class VorbeigehenLeiseTest {

    private static final long T0 = 1_000_000_000L;

    private static Actor person(long id, Actor.Proximity prox, Actor.Trend trend,
                                Actor.Severity sev, long sichtbarMs) {
        return new Actor(id, Actor.ClassGroup.PERSON, T0, T0 + sichtbarMs, 0, sichtbarMs,
                1, prox, prox, trend,
                false, false, true, true, true,
                sev, T0, 0, 0.9f,
                10, 10, 40, 120, 320, 240, 0,
                10, 10, 40, 120, 0);
    }

    private static Actor auto(Actor.Severity sev) {
        return new Actor(9, Actor.ClassGroup.VEHICLE, T0, T0 + 2_000, 0, 2_000,
                1, Actor.Proximity.CLOSE, Actor.Proximity.CLOSE, Actor.Trend.APPROACHING,
                false, false, true, true, true,
                sev, T0, 0, 0.9f,
                10, 10, 80, 60, 320, 240, 0,
                10, 10, 80, 60, 0);
    }

    private static long dauer(Actor a) {
        return a.lastSeenWallMs - a.firstSeenWallMs;
    }

    @Test
    public void nahVorbeiKurzWirdLeise() {
        Actor a = person(1, Actor.Proximity.CLOSE, Actor.Trend.STABLE, Actor.Severity.ALERT, 2_000);
        assertTrue(NotificationGate.istVorbeigehen(a, 2_000));
        assertEquals(Actor.Severity.NOTICE,
                NotificationGate.maxSeverityGedaempft(Collections.singletonList(a), VorbeigehenLeiseTest::dauer));
        assertEquals("ungedämpft unverändert", Actor.Severity.ALERT,
                NotificationGate.maxSeverity(Collections.singletonList(a)));
    }

    @Test
    public void verweilenBleibtLaut() {
        Actor a = person(1, Actor.Proximity.CLOSE, Actor.Trend.STABLE, Actor.Severity.ALERT, 9_000);
        assertFalse(NotificationGate.istVorbeigehen(a, 9_000));
        assertFalse("Grenze exklusiv", NotificationGate.istVorbeigehen(a, NotificationGate.VORBEIGEHEN_MAX_MS));
    }

    @Test
    public void annaeherungBleibtLaut() {
        Actor a = person(1, Actor.Proximity.CLOSE, Actor.Trend.APPROACHING, Actor.Severity.ALERT, 2_000);
        assertFalse(NotificationGate.istVorbeigehen(a, 2_000));
    }

    @Test
    public void direktAmAutoBleibtLaut() {
        Actor a = person(1, Actor.Proximity.VERY_CLOSE, Actor.Trend.STABLE, Actor.Severity.CRITICAL, 1_000);
        assertFalse(NotificationGate.istVorbeigehen(a, 1_000));
        assertEquals(Actor.Severity.CRITICAL,
                NotificationGate.maxSeverityGedaempft(Collections.singletonList(a), VorbeigehenLeiseTest::dauer));
    }

    @Test
    public void mittelMitAnnaeherungBleibtLaut() {
        Actor a = person(1, Actor.Proximity.MID, Actor.Trend.APPROACHING, Actor.Severity.ALERT, 1_000);
        assertFalse(NotificationGate.istVorbeigehen(a, 1_000));
    }

    @Test
    public void fahrzeugeUnveraendert() {
        Actor v = auto(Actor.Severity.ALERT);
        assertFalse(NotificationGate.istVorbeigehen(v, 1_000));
    }

    @Test
    public void unbekannteDauerNieDaempfen() {
        Actor a = person(1, Actor.Proximity.CLOSE, Actor.Trend.STABLE, Actor.Severity.ALERT, 2_000);
        assertFalse(NotificationGate.istVorbeigehen(a, -1));
        assertEquals(Actor.Severity.ALERT,
                NotificationGate.maxSeverityGedaempft(Collections.singletonList(a), x -> -1L));
        assertEquals("Fehler beim Lesen → laut", Actor.Severity.ALERT,
                NotificationGate.maxSeverityGedaempft(Collections.singletonList(a),
                        x -> { throw new IllegalStateException(); }));
    }

    @Test
    public void nieLauterAlsUngedaempft() {
        Actor vorbei = person(1, Actor.Proximity.CLOSE, Actor.Trend.STABLE, Actor.Severity.ALERT, 2_000);
        Actor ferne = person(2, Actor.Proximity.MID, Actor.Trend.STABLE, Actor.Severity.NOTICE, 2_000);
        Actor bleibt = person(3, Actor.Proximity.CLOSE, Actor.Trend.STABLE, Actor.Severity.ALERT, 12_000);
        for (java.util.List<Actor> l : Arrays.asList(
                Arrays.asList(vorbei), Arrays.asList(vorbei, ferne), Arrays.asList(vorbei, bleibt),
                Arrays.asList(ferne), Arrays.asList(vorbei, auto(Actor.Severity.ALERT)))) {
            Actor.Severity g = NotificationGate.maxSeverityGedaempft(l, VorbeigehenLeiseTest::dauer);
            assertTrue(g.ordinal() <= NotificationGate.maxSeverity(l).ordinal());
        }
        assertEquals("Verweilender in derselben Aufnahme hält ALERT", Actor.Severity.ALERT,
                NotificationGate.maxSeverityGedaempft(Arrays.asList(vorbei, bleibt), VorbeigehenLeiseTest::dauer));
    }

    @Test
    public void standardIstAn() {
        assertTrue(new SurveillanceConfig().isVorbeigehenLeise());
    }
}
