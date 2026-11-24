package at.greenchili.surfmate.tacho;

/**
 * Erlaubt-Liste der Tacho-Kohorten (0.1.43.20, P3). Nur Autos, deren genauer Hardware-Schlüssel hier steht
 * ({@link TachoKohorte.Schluessel}), dürfen Projektions-Befehle (16/35, Größe 29/30/31) an den Tacho schicken.
 * 18/0 (Projektion schließen, Instrumente zurück) gehen immer.
 *
 * <p>Ein Eintrag kommt nur aus einer Community-Meldung mit Einwilligung (Tacho-Test, ohne Kennung und Ort) UND einem
 * Foto-Beleg „Warnleuchten und Tempo sichtbar“ während der Projektion. Jedes Feld muss exakt passen; leere Felder in
 * einem Eintrag gibt es nicht. {@code panel} prüft das Gate nur, wenn die Tacho-Anzeige schon gemessen ist.
 *
 * <p>Stand 24.11.2026: noch kein belegter Eintrag. DashCast nutzt eine Forschungs-Kohorte (Seal EU, Carrier
 * 21f7735c); deren Hashes kennen wir nicht und übernehmen sie nicht. Bis zum ersten Eintrag bleibt die Projektion
 * überall aus, außer ein Tester schaltet sie ausdrücklich für sein Auto frei ({@code tachoSicht.testerFreigabe}).
 *
 * <p>Format je Eintrag: {@code {"name","modell","firmware","build","api","libBydCluster","fissiond","panel"}}.
 */
final class TachoKohortenListe {

    private TachoKohortenListe() {}

    static final String JSON = "{\"version\":1,\"eintraege\":[]}";
}
