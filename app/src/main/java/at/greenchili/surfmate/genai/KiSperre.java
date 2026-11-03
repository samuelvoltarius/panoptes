package at.greenchili.surfmate.genai;

import at.greenchili.surfmate.automation.ActionGroups;
import at.greenchili.surfmate.automation.Automations;
import at.greenchili.surfmate.server.BefehlsLog;
import at.greenchili.surfmate.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * REGEL A+ (03.11.2026, erweitert auf Alfreds „ja“): harte ERLAUBNISLISTE für den KI-Assistenten (Text und Sprache).
 *
 * <p>Der Assistent darf über eine Regel nur Klima (inkl. Lenkrad- und Scheibenheizung), Sitzheizung/-lüftung,
 * Sonnenrollo, reine Anzeigen (Hinweis am Display, Benachrichtigung, Auswertung) und reinen Ablauf (Warten, Variablen,
 * Wenn/Schleife) auslösen. Alles andere ist gesperrt – Hupe, Blinker, Warnblinker, Licht, „Auto finden“, Alarm/Wächter,
 * Sperren, Fenster, Sonnendach, Lüften, Kofferraum/Heckklappe, Spiegel, Kindersicherung, Audio innen (Lautsprecher,
 * Ansagen, Ton, Video), MQTT-Publish, Shell, Fahr-/Assistenzsysteme, Laden, Apps … und jede künftige neue Aktion,
 * bis sie hier ausdrücklich erlaubt wird. Auch indirekt: Aktionsgruppe, Wenn/Schleife (beide Zweige), „Regel
 * einschalten/umschalten“ werden rekursiv geprüft. Geprüft wird an EINER Stelle, vor jedem KI-Vorschlag und noch
 * einmal beim Ausführen (Stand der Regel im Moment des Tipps), sowie bei KI-Entwürfen.
 *
 * <p>Nur der KI-Weg: Regeln, Tasten, Home Assistant und „Jetzt testen“ des Nutzers rufen diese Klasse nicht auf.
 * Unbekanntes (fehlende Aktionsgruppe, zu tiefe Verschachtelung, unbekannter Typ) gilt als gesperrt.
 */
public final class KiSperre {

    /** Kennwort fürs Befehls-Log. */
    public static final String GRUND = "ki_sperre";

    /** Klima, Lenkrad-/Scheibenheizung, Klima-Zeitplan (Katalog-ids aus {@code Actions}, klein geschrieben). */
    static final Set<String> KLIMA = set("acauto", "acsync", "acautooff", "fanonly", "steeringheat", "frontdefrost",
            "reardefrost", "recirculation", "setac", "setactemp", "stepactemp", "setacfan", "remoteclimateschedule",
            "remoteclimatescheduleupdate", "remoteclimatescheduledelete");
    /** Sitzheizung/-lüftung ({@code seat} = seat_climate, nicht Sitzposition) und Sonnenrollo. */
    static final Set<String> KOMFORT = set("seat", "sunshade");
    /** Reine Anzeige bzw. Lesen: Hinweis/Dialog am Display, Benachrichtigung an den Nutzer, KI-Auswertung. */
    static final Set<String> ANZEIGE = set("showtoast", "showdialog", "notification", "genaiinsight");
    /** Reiner Ablauf ohne Wirkung nach außen; Kinder werden rekursiv geprüft. */
    static final Set<String> ABLAUF = set("pause", "waituntil", "waituntilstate", "setvariable", "incrementvariable",
            "computevariable", "capturevariable", "loop", "if", "actiongroup", "automationcontrol");

    /** Alles, was der Assistent (über eine Regel) auslösen darf. */
    static final Set<String> ERLAUBT;

    static {
        Set<String> e = new HashSet<>();
        e.addAll(KLIMA);
        e.addAll(KOMFORT);
        e.addAll(ANZEIGE);
        e.addAll(ABLAUF);
        ERLAUBT = Collections.unmodifiableSet(e);
    }

    /** Darf der Assistent diesen Aktions-Typ auslösen (ohne Blick auf Kinder)? */
    public static boolean erlaubterTyp(String typ) {
        return typ != null && ERLAUBT.contains(typ.trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> set(String... ids) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ids)));
    }

    /** Höchste Verschachtelung (Gruppen, Wenn, Schleife, Regel schaltet Regel). Darüber: gesperrt. */
    static final int MAX_TIEFE = 12;

    private KiSperre() {
    }

    /** Lesender Zugriff auf Regeln und Aktionsgruppen (im Daemon der echte Speicher, in Tests ersetzbar). */
    interface Quelle {
        JSONObject automationen();

        JSONObject gruppen();
    }

    static final Quelle SPEICHER = new Quelle() {
        @Override
        public JSONObject automationen() {
            return Automations.toJson();
        }

        @Override
        public JSONObject gruppen() {
            return ActionGroups.toJson();
        }
    };

    /**
     * Grund, warum der Assistent die gespeicherte Regel {@code id} nicht starten darf, sonst {@code null}.
     * Eine unbekannte Regel liefert {@code null} (das prüft der Aufrufer selbst).
     */
    public static String automationGesperrt(String id) {
        return automationGesperrt(id, SPEICHER);
    }

    static String automationGesperrt(String id, Quelle quelle) {
        if (id == null || id.trim().isEmpty()) return null;
        JSONObject alle = sicher(quelle.automationen());
        JSONObject regel = alle.optJSONObject(id.trim());
        if (regel == null) return null;
        return regel(regel, alle, sicher(quelle.gruppen()), new HashSet<>(), new HashSet<>(), 0);
    }

    /** Grund, warum eine (von der KI entworfene) Regel gesperrte Aktionen enthält, sonst {@code null}. */
    public static String regelGesperrt(JSONObject regel) {
        return regelGesperrt(regel, SPEICHER);
    }

    static String regelGesperrt(JSONObject regel, Quelle quelle) {
        if (regel == null) return null;
        return regel(regel, sicher(quelle.automationen()), sicher(quelle.gruppen()),
                new HashSet<>(), new HashSet<>(), 0);
    }

    private static String regel(JSONObject regel, JSONObject alle, JSONObject gruppen,
                                Set<String> regelnGesehen, Set<String> gruppenGesehen, int tiefe) {
        String g = liste(regel.optJSONArray("actions"), alle, gruppen, regelnGesehen, gruppenGesehen, tiefe);
        if (g != null) return g;
        return liste(regel.optJSONArray("elseActions"), alle, gruppen, regelnGesehen, gruppenGesehen, tiefe);
    }

    private static String liste(JSONArray aktionen, JSONObject alle, JSONObject gruppen,
                                Set<String> regelnGesehen, Set<String> gruppenGesehen, int tiefe) {
        if (aktionen == null) return null;
        if (tiefe > MAX_TIEFE) return "zu_tief";
        for (int i = 0; i < aktionen.length(); i++) {
            JSONObject a = aktionen.optJSONObject(i);
            if (a == null) continue;
            String g = aktion(a, alle, gruppen, regelnGesehen, gruppenGesehen, tiefe);
            if (g != null) return g;
        }
        return null;
    }

    private static String aktion(JSONObject a, JSONObject alle, JSONObject gruppen,
                                 Set<String> regelnGesehen, Set<String> gruppenGesehen, int tiefe) {
        String typ = a.optString("type", "").trim().toLowerCase(Locale.ROOT);
        JSONObject v = a.optJSONObject("variables");
        if (v == null) v = new JSONObject();
        if (!ERLAUBT.contains(typ)) return typ.isEmpty() ? "unbekannt" : typ;
        // Wenn/Schleife: beide Zweige
        String g = liste(a.optJSONArray("childActions"), alle, gruppen, regelnGesehen, gruppenGesehen, tiefe + 1);
        if (g != null) return g;
        g = liste(a.optJSONArray("elseActions"), alle, gruppen, regelnGesehen, gruppenGesehen, tiefe + 1);
        if (g != null) return g;
        if ("actiongroup".equals(typ)) {
            String gid = v.optString("groupId", "").trim();
            if (gid.isEmpty()) return null; // läuft als No-op
            if (!gruppenGesehen.add(gid)) return null; // Zyklus, läuft nicht ein zweites Mal
            JSONObject gruppe = gruppen.optJSONObject(gid);
            if (gruppe == null) return "unbekannte_gruppe";
            return liste(gruppe.optJSONArray("actions"), alle, gruppen, regelnGesehen, gruppenGesehen, tiefe + 1);
        }
        if ("automationcontrol".equals(typ)) {
            // Ausschalten ist harmlos; Einschalten/Umschalten macht die Ziel-Regel lauffähig
            if ("disable".equals(v.optString("action", "").trim())) return null;
            String ziel = v.optString("target", "").trim();
            if (ziel.isEmpty() || !regelnGesehen.add(ziel)) return null;
            JSONObject zielRegel = alle.optJSONObject(ziel);
            if (zielRegel == null) return null; // unbekannte Regel: No-op zur Laufzeit
            return regel(zielRegel, alle, gruppen, regelnGesehen, gruppenGesehen, tiefe + 1);
        }
        return null;
    }

    /** Verständliche Ablehnung für Assistent und Nutzer. */
    public static String ablehnungStarten() {
        return Messages.text("genai.ki_sperre_start",
                "The assistant may only run rules with climate, seat heating, sunshade or on-screen notices. "
                        + "This rule does more (for example lights, horn, windows, tailgate, locks, sound or MQTT), "
                        + "also through an action group. Run it yourself from Rules or with a button.");
    }

    public static String ablehnungEntwurf() {
        return Messages.text("genai.ki_sperre_entwurf",
                "The assistant only creates rules with climate, seat heating, sunshade or on-screen notices. "
                        + "Create other rules yourself in the rule editor.");
    }

    /** Ablehnung ins Befehls-Log (nur Kennwörter, keine Namen oder Werte). */
    public static void protokolliere(String was) {
        try {
            BefehlsLog.grund(GRUND);
            BefehlsLog.ereignis("ki." + (was == null ? "aktion" : was), "gesperrt", null, null);
        } catch (Throwable ignored) {
            // Mitschreiben darf die Ablehnung nie verhindern
        }
    }

    private static JSONObject sicher(JSONObject o) {
        return o == null ? new JSONObject() : o;
    }
}
