package at.greenchili.surfmate.testutil;

import at.greenchili.surfmate.byd.ChargeSourceClassifier;

import org.junit.rules.ExternalResource;

/**
 * 0.1.40 T1: Jeder Test beginnt und endet mit leerem {@link ChargeSourceClassifier}.
 *
 * <p>Der Klassifizierer hält seine Urteile statisch für die ganze JVM. Gradle führt alle Testklassen
 * in einer JVM nacheinander aus, die Reihenfolge ist auf Linux und Windows verschieden. Ohne
 * Zurücksetzen erbte ein Test mit festem Quellnamen (clusterChargePower, externalChargingPower), was
 * eine frühere Klasse eingestuft hatte.
 */
public final class LadequellenZuruecksetzen extends ExternalResource {

    @Override
    protected void before() {
        ChargeSourceClassifier.resetForTests();
    }

    @Override
    protected void after() {
        ChargeSourceClassifier.resetForTests();
    }
}
