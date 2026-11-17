package at.greenchili.surfmate.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.Test;

/**
 * 0.1.43.10 Teil 3: BYD-EMQ-Abo über die Array-Variante. Paho 1.2.5 läuft bei
 * subscribe(String, int, listener) in eine Selbstaufruf-Schleife (StackOverflowError vor SUBSCRIBE);
 * Hinweis aus Overdrive v57 (MIT). Der Test zeigt die Schleife an Paho selbst, ohne Netz.
 */
public class BydCloudMqttAboTest {

    private static final IMqttMessageListener LEER = (t, m) -> { };

    private static MqttClient client() throws Exception {
        // Nur angelegt, nie verbunden: kein Netzzugriff
        return new MqttClient("tcp://127.0.0.1:1", "abo-test", new MemoryPersistence());
    }

    @Test
    public void einzelVarianteLaeuftInPaho125InDieSchleife() throws Exception {
        MqttClient c = client();
        try {
            c.subscribe("t/x", 1, LEER);
            fail("erwartet: Fehler ohne Verbindung");
        } catch (StackOverflowError erwartet) {
            // genau der Fehler aus Overdrive v57 – darum nutzt Panoptes diese Variante nicht
        } catch (MqttException andersBehoben) {
            // spätere Paho-Version ohne Schleife: auch in Ordnung
        } finally {
            c.close();
        }
    }

    @Test
    public void arrayVarianteErreichtDenClientOhneSchleife() throws Exception {
        MqttClient c = client();
        try {
            c.subscribe(BydCloudMqttSubscriber.abo("t/x"), new IMqttMessageListener[] {LEER});
            fail("erwartet: MqttException (nicht verbunden)");
        } catch (MqttException erwartet) {
            // richtig: kein StackOverflowError, Paho meldet nur „nicht verbunden“
        } finally {
            c.close();
        }
    }

    @Test
    public void aboHatQos1UndThema() {
        MqttSubscription[] a = BydCloudMqttSubscriber.abo("oversea/res/abc");
        assertEquals(1, a.length);
        assertEquals("oversea/res/abc", a[0].getTopic());
        assertEquals(1, a[0].getQos());
    }

    @Test
    public void subscriberNutztNurDieArrayVariante() throws Exception {
        String src = read("app/src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudMqttSubscriber.java");
        assertFalse(src.contains("mc.subscribe(topic, 1,"));
        assertTrue(src.contains("mc.subscribe(abo(topic), new org.eclipse.paho.mqttv5.client.IMqttMessageListener[] {"));
    }

    private static String read(String relativePath) throws java.io.IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) return at.greenchili.surfmate.testutil.Quelltext.lies(direct);
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
