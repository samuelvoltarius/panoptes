package at.greenchili.surfmate.einstellungen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alle Einstellungen, die das Schema ausliefert (Vertrag EINSTELLUNGEN_SCHEMA.md v1.1).
 * Nur Felder, die der Daemon wirklich auswertet; neue Felder hier ergänzen.
 */
public final class Katalog {

    private Katalog() {}

    /** Laufzeit-Kontext für Standards, Sperren und Sichtbarkeit. */
    public static final class Kontext {
        public Laenderprofil.Land land = Laenderprofil.Land.UNBEKANNT;
        public String akkuChemie = AkkuChemie.BLEI;
        public boolean cluster;
        public boolean hud;
        /** Nachweislich höchste HAL-Rate, wenn der HAL die Rate nicht setzen kann (0 = unbekannt/setzbar). */
        public int kameraHalMaxFps;
    }

    public static final class Gruppe {
        public final String id, bereich;
        public final int reihenfolge;
        Gruppe(String id, String bereich, int reihenfolge) {
            this.id = id; this.bereich = bereich; this.reihenfolge = reihenfolge;
        }
    }

    /** Aktuelle Textversion der Haftungshinweise (DISCLAIMER.md, Neubau). Bei Textänderung hochzählen. */
    public static final String HINWEISE_VERSION = "2026-10-21";

    public static final List<Gruppe> GRUPPEN = Collections.unmodifiableList(java.util.Arrays.asList(
            new Gruppe("waechter", "waechter", 30),
            new Gruppe("auto", "auto", 40),
            new Gruppe("camping", "auto", 45),
            new Gruppe(LadenKatalog.ABSCHNITT, "fahren", 50),
            new Gruppe("system", "system", 90),
            new Gruppe("update", "system", 95),
            new Gruppe("energie", "system", 92),
            new Gruppe("datenschutz", "system", 85)));

    /** Abschnitt-ID → Reihenfolge. */
    public static final Map<String, Integer> ABSCHNITTE = new HashMap<>();
    static {
        ABSCHNITTE.put("aufnahme", 10);
        ABSCHNITTE.put("abschreckung", 20);
        ABSCHNITTE.put("stopp", 30);
    }

    public static List<Feld> felder(Kontext k) {
        List<Feld> l = new ArrayList<>();
        int r = 0;

        // ---------------------------------------------------------------- Wächter: Aufnahme
        Feld trig = new Feld("surveillance.triggerMode", Feld.Typ.AUSWAHL);
        trig.gruppe = "waechter"; trig.abschnitt = "aufnahme"; trig.reihenfolge = r += 10;
        trig.standard = null;   // nicht gesetzt = „nicht gewählt“: bis zur Wahl nur Erschütterung (0.1.9.1)
        trig.bestaetigung = "guardLegal.trigger";
        trig.bestaetigungZeitKey = "surveillance.triggerConsentAt";
        trig.optionen.add(new Feld.Option(TriggerModus.SHOCK, "guardLegal.trigger.a.label"));
        Feld.Option b = new Feld.Option(TriggerModus.AI_EVERYWHERE, "guardLegal.trigger.b.label");
        if (k.land == Laenderprofil.Land.SCHWEIZ) {
            b.gesperrt = true;
            b.gesperrtGrundKey = "guardLegal.trigger.blockedCH";
        }
        trig.optionen.add(b);
        trig.optionen.add(new Feld.Option(TriggerModus.AI_PRIVATE, "guardLegal.trigger.c.label"));
        trig.optionen.add(new Feld.Option(TriggerModus.AUS, "guardLegal.trigger.aus.label"));
        l.add(trig);

        Feld zonen = new Feld("surveillance.privateZones", Feld.Typ.GEOZONE);
        zonen.gruppe = "waechter"; zonen.abschnitt = "aufnahme"; zonen.reihenfolge = r += 10;
        zonen.standard = new JSONArray();
        zonen.maxEintraege = 20;
        zonen.min = 20.0; zonen.max = 500.0; zonen.schritt = 5.0; zonen.einheit = "m";
        zonen.nurWenn = obj("key", "surveillance.triggerMode", "gleich", TriggerModus.AI_PRIVATE);
        l.add(zonen);

        Feld shock = new Feld("surveillance.shockSensitivity", Feld.Typ.AUSWAHL);
        shock.gruppe = "waechter"; shock.abschnitt = "aufnahme"; shock.reihenfolge = r += 10;
        shock.standard = "auto";   // an (mittel), sobald ein Auslöser gewählt ist; Alt-Installation aus
        for (String o : new String[] {"auto", "aus", "niedrig", "mittel", "hoch"}) {
            shock.optionen.add(new Feld.Option(o, "settings.surveillance.shockSensitivity." + o));
        }
        l.add(shock);

        // Aufbewahrung (RECHTSLAGE Nr. 7): empfohlen 72 h, „Behalten“ bis 30 Tage, danach Rückfrage.
        // Nicht gesetzt (Alt-Installation) = 0 = unbegrenzt (nur Speicherplatz); 72 setzt die Einrichtung.
        Feld halten = new Feld("surveillance.clipRetentionHours", Feld.Typ.INT);
        halten.gruppe = "waechter"; halten.abschnitt = "aufnahme"; halten.reihenfolge = r += 10;
        halten.standard = 0; halten.min = 0.0; halten.max = 720.0; halten.schritt = 1.0; halten.einheit = "h";
        halten.nullIstAus = true;
        halten.empfohlen = 72;
        l.add(halten);

        Feld keep = new Feld("surveillance.keepDays", Feld.Typ.INT);
        keep.gruppe = "waechter"; keep.abschnitt = "aufnahme"; keep.reihenfolge = r += 10;
        keep.standard = 30; keep.min = 1.0; keep.max = 90.0; keep.schritt = 1.0; keep.einheit = "d";
        l.add(keep);

        // Kamera-Rate im Stand (0.1.9, mit Neubau 2 abgestimmt): nicht gesetzt = Energie-Profil, gesetzt gewinnt.
        // Wo der HAL eine Stufe nachweislich nicht liefert, ist sie gesperrt (Grund im Text).
        for (String name : new String[] {"fpsRuhe", "fpsBewegung"}) {
            Feld f = new Feld("kamera." + name, Feld.Typ.AUSWAHL);
            f.gruppe = "waechter"; f.abschnitt = "aufnahme"; f.reihenfolge = r += 10;
            f.standard = null; f.erweitert = true;
            for (int stufe : Energie.KAMERA_STUFEN) {
                Feld.Option o = new Feld.Option(String.valueOf(stufe), "settings.kamera.fps." + stufe);
                if (k.kameraHalMaxFps > 0 && stufe > k.kameraHalMaxFps + 1) {
                    o.gesperrt = true;
                    o.gesperrtGrundKey = "settings.kamera.fps.gesperrtHal";
                }
                f.optionen.add(o);
            }
            l.add(f);
        }

        // ---------------------------------------------------------------- Wächter: Abschreckung
        Feld disp = new Feld("deterrent.display", Feld.Typ.BOOL);
        disp.gruppe = "waechter"; disp.abschnitt = "abschreckung"; disp.reihenfolge = r += 10;
        disp.standard = false;
        disp.alias = "surveillance.screenDeterrentEnabled";
        disp.zugriff = Feld.standardZugriff("surveillance.screenDeterrentEnabled");
        l.add(disp);

        Feld light = new Feld("deterrent.light", Feld.Typ.BOOL);
        light.gruppe = "waechter"; light.abschnitt = "abschreckung"; light.reihenfolge = r += 10;
        light.standard = false; light.gefahr = true;
        light.alias = "surveillance.deterrentAction";
        light.zugriff = aktionAlias("flash_lights");
        l.add(light);

        Feld horn = new Feld("deterrent.horn", Feld.Typ.BOOL);
        horn.gruppe = "waechter"; horn.abschnitt = "abschreckung"; horn.reihenfolge = r += 10;
        horn.standard = false; horn.gefahr = true; horn.erweitert = true;
        horn.alias = "surveillance.deterrentAction";
        horn.zugriff = aktionAlias("find_car");
        l.add(horn);

        Feld ruhe = new Feld("surveillance.deterrentQuietSeconds", Feld.Typ.INT);
        ruhe.gruppe = "waechter"; ruhe.abschnitt = "abschreckung"; ruhe.reihenfolge = r += 10;
        ruhe.standard = 120; ruhe.min = 30.0; ruhe.max = 1800.0; ruhe.schritt = 10.0; ruhe.einheit = "s";
        l.add(ruhe);

        // ---------------------------------------------------------------- Wächter: Stopp
        double[] v12 = AkkuChemie.v12Standard(k.akkuChemie);
        l.add(zahl("guardStop.enabled", Feld.Typ.BOOL, true, null, null, null, null, r += 10));
        l.add(nullAus(zahl("guardStop.socBelow", Feld.Typ.INT, 20, 0.0, 80.0, 1.0, "%", r += 10)));
        l.add(nullAus(zahl("guardStop.v12Below", Feld.Typ.ZAHL, v12[0], 0.0, 13.5, 0.1, "V", r += 10)));
        l.add(zahl("guardStop.v12Resume", Feld.Typ.ZAHL, v12[1], 11.5, 13.8, 0.1, "V", r += 10));
        l.add(nullAus(zahl("guardStop.maxParkHours", Feld.Typ.INT, 24, 0.0, 720.0, 1.0, "h", r += 10)));
        Feld win = new Feld("guardStop.windows", Feld.Typ.ZEITFENSTER);
        win.standard = new JSONArray(); win.maxEintraege = 7; win.reihenfolge = r += 10;
        win.listeVon = obj("days", "int[1..7]", "from", "HH:MM", "to", "HH:MM");
        stopp(win);
        l.add(win);
        l.add(zahl("guardStop.useQuietZones", Feld.Typ.BOOL, true, null, null, null, null, r += 10));
        l.add(zahl("guardStop.reenable", Feld.Typ.BOOL, true, null, null, null, null, r += 10));
        l.add(zahl("guardStop.notify", Feld.Typ.BOOL, true, null, null, null, null, r += 10));

        // ---------------------------------------------------------------- Auto
        Feld chem = new Feld("auto.akkuChemie", Feld.Typ.AUSWAHL);
        chem.gruppe = "auto"; chem.reihenfolge = 10; chem.standard = AkkuChemie.AUTO; chem.erweitert = true;
        chem.optionen.add(new Feld.Option(AkkuChemie.AUTO, "settings.auto.akkuChemie.auto"));
        chem.optionen.add(new Feld.Option(AkkuChemie.LFP, "settings.auto.akkuChemie.lfp"));
        chem.optionen.add(new Feld.Option(AkkuChemie.BLEI, "settings.auto.akkuChemie.blei"));
        chem.optionen.add(new Feld.Option(AkkuChemie.AGM, "settings.auto.akkuChemie.agm"));
        l.add(chem);

        // ---------------------------------------------------------------- System
        Feld id = new Feld("planner.autoId", Feld.Typ.TEXT);
        id.gruppe = "system"; id.reihenfolge = 10; id.nurLesend = true; id.nurLesendExtern = true;
        l.add(id);
        // planner.reserve ist in laden.ankunftSocMin aufgegangen (LADEN_VERTRAG A2, Doppelung).
        // F1 „Reicht es noch?“: Band am Mittelschirm (nur Anzeige) und Ansage (Standard aus, Koordination 21.10.)
        Feld band = new Feld("planner.band", Feld.Typ.BOOL);
        band.gruppe = "system"; band.reihenfolge = 16; band.standard = true;
        l.add(band);
        Feld ansage = new Feld("planner.ansage", Feld.Typ.BOOL);
        ansage.gruppe = "system"; ansage.reihenfolge = 17; ansage.standard = false;
        l.add(ansage);
        Feld member = new Feld("planner.memberToken", Feld.Typ.GEHEIM);
        member.gruppe = "system"; member.reihenfolge = 20; member.nurLesendExtern = true;
        l.add(member);
        // Community (Regeln teilen + Straßenzustand gemeinsam): Standard aus, ohne Einschalten kein Aufruf.
        Feld comm = new Feld("community.enabled", Feld.Typ.BOOL);
        comm.gruppe = "system"; comm.reihenfolge = 30; comm.standard = false;
        l.add(comm);
        // Blitzer-Warnung beim Fahren: wirkt nur in Österreich (sonst immer aus); Ansage Standard aus.
        Feld blitz = new Feld("speedcam.enabled", Feld.Typ.BOOL);
        blitz.gruppe = "system"; blitz.reihenfolge = 40; blitz.standard = false;
        // Nutzung während der Fahrt ist in mehreren Ländern verboten (DE § 23 Abs. 1c StVO, CH, FR …) – Hinweis
        // vor dem Einschalten; Text liefert das Web unter speedcam.rechtshinweis.*
        blitz.bestaetigung = "speedcam.rechtshinweis";
        l.add(blitz);
        // 0.1.9.1: Ton aus | hinweiston | ansage (speedcam.ansage geht darin auf; Bestand ansage=true wirkt als ansage).
        // Nur sichtbar mit eingeschaltetem Blitzer; dessen Rechtshinweis deckt den Ton mit ab.
        Feld blitzTon = new Feld("speedcam.ton", Feld.Typ.AUSWAHL);
        blitzTon.gruppe = "system"; blitzTon.reihenfolge = 41; blitzTon.standard = "aus";
        for (String o : new String[] {"aus", "hinweiston", "ansage"}) {
            blitzTon.optionen.add(new Feld.Option(o, "settings.speedcam.ton." + o));
        }
        blitzTon.nurWenn = obj("key", "speedcam.enabled", "gleich", true);
        l.add(blitzTon);
        // Datenschutz Regel Nr. 2: Telegram-Parknachricht enthält die Position. Bestand bleibt (Standard wie bisher an),
        // neue Einrichtungen setzen sie aus (OnboardingHost). Verborgen: die Telegram-Seite hat eigene Schalter.
        Feld park = new Feld("telegram.parkingMessages", Feld.Typ.BOOL);
        park.gruppe = "system"; park.reihenfolge = 70; park.standard = true; park.verborgen = true;
        park.nurLesendExtern = true;
        l.add(park);
        // 0.1.21: Telegram „zurück am Auto“ beim Einschalten nach einer Parkzeit (ohne Ort). Standard aus.
        Feld zurueck = new Feld("telegram.zurueckAmAuto", Feld.Typ.BOOL);
        zurueck.gruppe = "system"; zurueck.reihenfolge = 71; zurueck.standard = false; zurueck.nurLesendExtern = true;
        l.add(zurueck);
        // Datenschutz Regel Nr. 2: Wetter über Open-Meteo nur mit Einwilligung (Position grob, ≈ 1 km)
        Feld wetter = new Feld("wetter.online", Feld.Typ.BOOL);
        wetter.gruppe = "system"; wetter.reihenfolge = 60; wetter.standard = false; wetter.nurLesendExtern = true;
        l.add(wetter);
        // Darstellung am Display (Neubau 2, 0.1.9): „leicht“ spart Grafiklast im Auto (2D-Karte, keine
        // Dauer-Animationen …). Gilt nur fürs Display; Handy/PC zeigen lokal immer „voll“.
        Feld darst = new Feld("anzeige.darstellung", Feld.Typ.AUSWAHL);
        darst.gruppe = "system"; darst.reihenfolge = 5; darst.standard = "leicht";
        darst.nurLesendExtern = true;
        darst.optionen.add(new Feld.Option("leicht", "settings.anzeige.darstellung.leicht"));
        darst.optionen.add(new Feld.Option("voll", "settings.anzeige.darstellung.voll"));
        l.add(darst);
        // Band am Display (Blitzer, „Reicht es noch?“), 0.1.9.1 nach Alfreds Fahrt: Standard groß und oben Mitte
        Feld bandG = new Feld("anzeige.bandGroesse", Feld.Typ.AUSWAHL);
        bandG.gruppe = "system"; bandG.reihenfolge = 6; bandG.standard = "gross";
        bandG.optionen.add(new Feld.Option("normal", "settings.anzeige.bandGroesse.normal"));
        bandG.optionen.add(new Feld.Option("gross", "settings.anzeige.bandGroesse.gross"));
        l.add(bandG);
        Feld bandP = new Feld("anzeige.bandPosition", Feld.Typ.AUSWAHL);
        bandP.gruppe = "system"; bandP.reihenfolge = 7; bandP.standard = "oben_mitte";
        bandP.optionen.add(new Feld.Option("oben_rechts", "settings.anzeige.bandPosition.oben_rechts"));
        bandP.optionen.add(new Feld.Option("oben_mitte", "settings.anzeige.bandPosition.oben_mitte"));
        l.add(bandP);
        // Unterwegs-Hilfe (F1, Koordination 0.1.9): eigener Schalter, holt beim Fahren die geplante Route vom
        // Planer. Neue Installation aus; Bestand mit Planer-Schlüssel wird beim ersten Start „an“ festgeschrieben.
        Feld unterwegs = new Feld("planner.unterwegs", Feld.Typ.BOOL);
        unterwegs.gruppe = "datenschutz"; unterwegs.reihenfolge = 15; unterwegs.standard = false;
        unterwegs.nurLesendExtern = true;
        l.add(unterwegs);
        // 0.1.16 Planer §17: Neuplanung unterwegs – Route, Position (~100 m) und Akku alle 10 min an plan-abweichung
        // (zustandslos). Eigene Einwilligung, Standard aus, nur sichtbar mit Unterwegs-Hilfe.
        Feld abweichung = new Feld("planner.abweichung", Feld.Typ.BOOL);
        abweichung.gruppe = "datenschutz"; abweichung.reihenfolge = 16; abweichung.standard = false;
        abweichung.nurLesendExtern = true;
        abweichung.nurWenn = obj("key", "planner.unterwegs", "gleich", true);
        l.add(abweichung);
        // Vertrag 9 (Planer, 0.1.9): Zustand an den Routenplaner senden – Regel 2, Standard AUS, Einwilligung
        // beim Einschalten (Text planer.zustandEinwilligung.*). Ausschalten löscht am Server.
        Feld zust = new Feld("planner.zustandSenden", Feld.Typ.BOOL);
        zust.gruppe = "datenschutz"; zust.reihenfolge = 20; zust.standard = false;
        zust.nurLesendExtern = true; zust.bestaetigung = "planer.zustandEinwilligung";
        l.add(zust);
        // Vertrag 9, Nachtrag Position (0.1.9): EIGENE Einwilligung, Standard AUS; nur zusammen mit dem Zustand.
        Feld pos = new Feld("planner.positionSenden", Feld.Typ.BOOL);
        pos.gruppe = "datenschutz"; pos.reihenfolge = 21; pos.standard = false;
        pos.nurLesendExtern = true; pos.bestaetigung = "planer.positionEinwilligung";
        l.add(pos);
        // LADEN_VERTRAG A7 (Planer §14): Lade-Vorlieben für /ev/ im Browser, EIGENE Einwilligung, Standard AUS;
        // nur zusammen mit dem Zustand, beim Planer nur im RAM (bis 24 h). Ausschalten löscht sofort.
        Feld vorl = new Feld("planner.vorliebenSenden", Feld.Typ.BOOL);
        vorl.gruppe = "datenschutz"; vorl.reihenfolge = 22; vorl.standard = false;
        vorl.nurLesendExtern = true; vorl.bestaetigung = "planer.vorliebenEinwilligung";
        l.add(vorl);
        // 0.1.17 „Anonym mitzählen“ (27.10.2026): Standard AUS, Frage im Einrichten („Daten teilen“).
        // Einmal am Tag nur Zufallskennung + Version (analytics/Mitzaehlen). Kennung neu/löschen über kennungAktion.
        Feld zaehlen = new Feld("zaehlen.an", Feld.Typ.BOOL);
        zaehlen.gruppe = "datenschutz"; zaehlen.reihenfolge = 30; zaehlen.standard = false;
        zaehlen.nurLesendExtern = true;
        l.add(zaehlen);
        Feld kennung = new Feld("zaehlen.kennung", Feld.Typ.TEXT);
        kennung.gruppe = "datenschutz"; kennung.reihenfolge = 31; kennung.nurLesend = true; kennung.nurLesendExtern = true;
        l.add(kennung);
        Feld kennungAktion = new Feld("zaehlen.kennungAktion", Feld.Typ.AUSWAHL);
        kennungAktion.gruppe = "datenschutz"; kennungAktion.reihenfolge = 32; kennungAktion.standard = "behalten";
        kennungAktion.nurLesendExtern = true;
        kennungAktion.optionen.add(new Feld.Option("behalten", "settings.zaehlen.kennungAktion.behalten"));
        kennungAktion.optionen.add(new Feld.Option("neu", "settings.zaehlen.kennungAktion.neu"));
        kennungAktion.optionen.add(new Feld.Option("loeschen", "settings.zaehlen.kennungAktion.loeschen"));
        kennungAktion.zugriff = kennungAktion();
        l.add(kennungAktion);
        Feld zaehlenUrl = new Feld("zaehlen.url", Feld.Typ.TEXT);
        zaehlenUrl.gruppe = "datenschutz"; zaehlenUrl.reihenfolge = 33; zaehlenUrl.erweitert = true;
        zaehlenUrl.nurLesendExtern = true; zaehlenUrl.standard = at.greenchili.surfmate.analytics.Mitzaehlen.STANDARD_URL;
        l.add(zaehlenUrl);
        // 0.1.19 „Ladekurve anonym teilen“ (Regel 2): Standard AUS, Frage im Einrichten („Daten teilen“). Höchstens 1×/Tag
        // je Modell SoC-Bänder + Median-kW + Anzahl, ohne Ort/Zeit/VIN/Konto (ladekurve/LadekurveTeilen). Aus = löschen.
        Feld kurve = new Feld("ladekurve.teilen", Feld.Typ.BOOL);
        kurve.gruppe = "datenschutz"; kurve.reihenfolge = 34; kurve.standard = false;
        kurve.nurLesendExtern = true; kurve.bestaetigung = "planer.ladekurveEinwilligung";
        l.add(kurve);
        Feld kurveKennung = new Feld("ladekurve.kennung", Feld.Typ.TEXT);
        kurveKennung.gruppe = "datenschutz"; kurveKennung.reihenfolge = 35; kurveKennung.nurLesend = true;
        kurveKennung.nurLesendExtern = true;
        l.add(kurveKennung);
        Feld kurveUrl = new Feld("ladekurve.url", Feld.Typ.TEXT);
        kurveUrl.gruppe = "datenschutz"; kurveUrl.reihenfolge = 36; kurveUrl.erweitert = true;
        kurveUrl.nurLesendExtern = true; kurveUrl.standard = at.greenchili.surfmate.ladekurve.LadekurveTeilen.STANDARD_URL;
        l.add(kurveUrl);
        // Datenschutz Regel Nr. 2: Kartenquelle wählt der Nutzer (Web kartenquelle.js + native Karte).
        // Nicht gesetzt = keine (auch Bestand), kein stiller Rückfall auf einen anderen Anbieter.
        Feld karte = new Feld("karte.quelle", Feld.Typ.AUSWAHL);
        karte.gruppe = "datenschutz"; karte.reihenfolge = 10; karte.standard = Kartenquelle.KEINE;
        karte.nurLesendExtern = true;
        karte.titelKey = "kartenquelle.karte_titel";
        karte.optionen.add(new Feld.Option(Kartenquelle.COMMUNITY, "kartenquelle.q_community"));
        karte.optionen.add(new Feld.Option(Kartenquelle.OSM, "kartenquelle.q_osm"));
        karte.optionen.add(new Feld.Option(Kartenquelle.KEINE, "kartenquelle.q_keine"));
        l.add(karte);
        // Energie (ENERGIE_INTERVALLE.md): Profil + Einzelwerte; nicht gesetzt = normal (bisheriges Verhalten)
        Feld prof = new Feld("energie.profil", Feld.Typ.AUSWAHL);
        prof.gruppe = "energie"; prof.reihenfolge = 10; prof.standard = Energie.NORMAL; prof.empfohlen = Energie.SPARSAM;
        prof.optionen.add(new Feld.Option(Energie.NORMAL, "settings.energie.profil.normal"));
        prof.optionen.add(new Feld.Option(Energie.SPARSAM, "settings.energie.profil.sparsam"));
        prof.optionen.add(new Feld.Option(Energie.MINIMAL, "settings.energie.profil.minimal"));
        l.add(prof);
        Feld gps = new Feld("energie.gpsStandSekunden", Feld.Typ.INT);
        gps.gruppe = "energie"; gps.reihenfolge = 20; gps.standard = null; gps.min = 1.0; gps.max = 60.0;
        gps.schritt = 1.0; gps.einheit = "s"; gps.erweitert = true;
        l.add(gps);
        Feld weck = new Feld("energie.weckMinuten", Feld.Typ.INT);
        weck.gruppe = "energie"; weck.reihenfolge = 30; weck.standard = null; weck.min = 5.0; weck.max = 10.0;
        weck.schritt = 1.0; weck.einheit = "min"; weck.erweitert = true;
        l.add(weck);
        // In-App-Updater (NEUBAU2 §1): Kanal stabil/test, Modus melden/automatisch (Standard melden)
        Feld kanal = new Feld("update.kanal", Feld.Typ.AUSWAHL);
        kanal.gruppe = "update"; kanal.reihenfolge = 10; kanal.standard = "stabil";
        kanal.optionen.add(new Feld.Option("stabil", "settings.update.kanal.stabil"));
        kanal.optionen.add(new Feld.Option("test", "settings.update.kanal.test"));
        l.add(kanal);
        Feld modus = new Feld("update.modus", Feld.Typ.AUSWAHL);
        modus.gruppe = "update"; modus.reihenfolge = 20; modus.standard = "melden";
        modus.optionen.add(new Feld.Option("melden", "settings.update.modus.melden"));
        modus.optionen.add(new Feld.Option("automatisch", "settings.update.modus.automatisch"));
        l.add(modus);
        campingFelder(l, k);
        // Prüfen alle 6 h bei GitHub: nicht gesetzt = Bestand = an; die Ersteinrichtung fragt ohne Vorauswahl
        Feld pruefen = new Feld("update.pruefen", Feld.Typ.BOOL);
        pruefen.gruppe = "update"; pruefen.reihenfolge = 5; pruefen.standard = true;
        l.add(pruefen);
        // Projektinhaber 21.10.: viele Autos haben nur die SIM → Mobilfunk Standard an; aus = nur im WLAN laden
        Feld mobil = new Feld("update.mobilfunk", Feld.Typ.BOOL);
        mobil.gruppe = "update"; mobil.reihenfolge = 30; mobil.standard = true;
        l.add(mobil);
        // Update-Quelle (0.1.15): Standard öffentliches Repo ohne Token; „eigen“ = anderes Repo, optional Lese-Token.
        // Zugangs-Einstellung (Regel B): von gekoppelten Geräten nur lesbar. Signatur + sha256 gelten für jede Quelle.
        Feld quelle = new Feld("update.quelle", Feld.Typ.AUSWAHL);
        quelle.gruppe = "update"; quelle.reihenfolge = 40; quelle.standard =
                at.greenchili.surfmate.appupdate.UpdateQuelle.OEFFENTLICH;
        quelle.erweitert = true; quelle.nurLesendExtern = true;
        quelle.optionen.add(new Feld.Option(at.greenchili.surfmate.appupdate.UpdateQuelle.OEFFENTLICH,
                "settings.update.quelle.oeffentlich"));
        quelle.optionen.add(new Feld.Option(at.greenchili.surfmate.appupdate.UpdateQuelle.EIGEN,
                "settings.update.quelle.eigen"));
        l.add(quelle);
        Feld repo = new Feld("update.repo", Feld.Typ.TEXT);
        repo.gruppe = "update"; repo.reihenfolge = 41; repo.standard = null;
        repo.erweitert = true; repo.nurLesendExtern = true; repo.nullErlaubt = true;
        repo.nurWenn = obj("key", "update.quelle", "gleich", at.greenchili.surfmate.appupdate.UpdateQuelle.EIGEN);
        repo.pruefer = v -> {
            if (!(v instanceof String) || !at.greenchili.surfmate.appupdate.UpdateQuelle.gueltigesRepo((String) v)) {
                throw new IllegalArgumentException("typ");
            }
            return ((String) v).trim();
        };
        l.add(repo);
        Feld token = new Feld("update.token", Feld.Typ.GEHEIM);
        token.gruppe = "update"; token.reihenfolge = 42;
        token.erweitert = true; token.nurLesendExtern = true;
        token.nurWenn = obj("key", "update.quelle", "gleich", at.greenchili.surfmate.appupdate.UpdateQuelle.EIGEN);
        l.add(token);
        // Fahrtenbuch auf /ev/ ansehen (Planer §8): nur Chiffretext verlässt das Auto; Standard aus
        Feld fb = new Feld("fahrtenbuch.planer", Feld.Typ.BOOL);
        fb.gruppe = "system"; fb.reihenfolge = 25; fb.standard = false; fb.nurLesendExtern = true;
        l.add(fb);
        // Home Assistant (HA_VERTRAG.md): Position nur doppelt freiwillig, mDNS Standard aus (Koordination 21.10.)
        Feld haPos = new Feld("ha.positionTeilen", Feld.Typ.BOOL);
        haPos.gruppe = "system"; haPos.reihenfolge = 50; haPos.standard = false; haPos.nurLesendExtern = true;
        l.add(haPos);
        Feld haMdns = new Feld("ha.mdns", Feld.Typ.BOOL);
        haMdns.gruppe = "system"; haMdns.reihenfolge = 51; haMdns.standard = false; haMdns.nurLesendExtern = true;
        haMdns.neustartNoetig = true; haMdns.erweitert = true;
        l.add(haMdns);
        // Haftungshinweise (DISCLAIMER.md): Web fragt, solange wert ≠ aktuell; Bestätigung mit Zeitstempel.
        Feld hinweise = new Feld("hinweise.version", Feld.Typ.TEXT);
        hinweise.gruppe = "system"; hinweise.reihenfolge = 90; hinweise.standard = null;
        hinweise.aktuell = HINWEISE_VERSION;
        hinweise.bestaetigung = "disclaimer.setup";
        hinweise.bestaetigungZeitKey = "hinweise.bestaetigtAm";
        hinweise.verborgen = true;
        hinweise.nurLesendExtern = true;
        l.add(hinweise);
        // 0.1.20 P7: Panoptes bei jedem ACC an nach vorn holen (BYD-Autostart greift nur beim Hochfahren). Standard aus;
        // nicht vor CarPlay/Android Auto, nur die Activity (A+). Gefragt im Einrichtungsschritt „Autostart“.
        Feld beiZuendung = new Feld("autostart.beiZuendung", Feld.Typ.BOOL);
        beiZuendung.gruppe = "system"; beiZuendung.reihenfolge = 94; beiZuendung.standard = false;
        l.add(beiZuendung);
        // Autostart-Weg (Koordination 22.10., AUTOSTART_BEFUND): sichtbar = BYD-Dialog (heute); settings =
        // ssc_whitelist ergänzen (SscWhitelist, vorbereitet). Verborgen, nur lokal; aktiviert erst nach Befund (a) + GO.
        Feld asWeg = new Feld("autostart.weg", Feld.Typ.AUSWAHL);
        asWeg.gruppe = "system"; asWeg.reihenfolge = 95; asWeg.standard = "sichtbar";
        asWeg.optionen.add(new Feld.Option("sichtbar", "settings.autostart.weg.sichtbar"));
        asWeg.optionen.add(new Feld.Option("settings", "settings.autostart.weg.settings"));
        asWeg.verborgen = true;
        asWeg.nurLesendExtern = true;
        l.add(asWeg);
        // Eigene Angabe (Koordination 22.10.): „Ich sehe Panoptes-Hinweise über CarPlay/Android Auto“ –
        // überschreibt die Tabelle faehigkeiten_belegt.json für das eigene Auto. Standard unbekannt = aus.
        for (String[] p : new String[][] {{"faehigkeiten.ueberCarPlay", "96"}, {"faehigkeiten.ueberAndroidAuto", "97"}}) {
            Feld u = new Feld(p[0], Feld.Typ.AUSWAHL);
            u.gruppe = "system"; u.reihenfolge = Integer.parseInt(p[1]); u.standard = "unbekannt"; u.erweitert = true;
            for (String o : new String[] {"unbekannt", "ja", "nein"}) {
                u.optionen.add(new Feld.Option(o, "settings." + p[0] + "." + o));
            }
            l.add(u);
        }
        l.addAll(LadenKatalog.felder());
        return l;
    }

    /**
     * Camping-Modus (0.1.16, CAMPING_PLAN.md 2.1/3/5). Nicht gesetzt = Standard; ausgewertet in
     * {@code camping.Camping}. In Stufe 2a heizt nichts (Trockenlauf) – die Heizwerte steuern nur Plan und Budget.
     */
    private static void campingFelder(List<Feld> l, Kontext k) {
        int r = 0;
        Feld w = new Feld("camping.waerme", Feld.Typ.AUSWAHL);
        w.gruppe = "camping"; w.reihenfolge = r += 10; w.standard = "warm";
        for (String o : new String[]{"aus", "sparen", "mild", "warm", "sehr_warm"}) {
            w.optionen.add(new Feld.Option(o, "settings.camping.waerme." + o));
        }
        l.add(w);
        // 0.1.16 Stufe 2b: wie geheizt wird. auto_an = nur lokal bei eingeschaltetem Auto (am Surf belegt);
        // auto_an_und_cloud = zusätzlich Fernklima-Sitzungen über die BYD-Cloud bei ausgeschaltetem Auto.
        // Standard Probelauf (Koordination 23.10.2026): echtes Heizen erst nach dem Test mit Projektinhaber bzw. auf
        // ausdrückliche Wahl – im Freeze 0.1.16 schaltet ohne diese Wahl nichts.
        Feld weg = new Feld("camping.heizweg", Feld.Typ.AUSWAHL);
        weg.standard = "probelauf";
        for (String o : new String[]{"probelauf", "auto_an", "auto_an_und_cloud"}) {
            weg.optionen.add(new Feld.Option(o, "settings.camping.heizweg." + o));
        }
        l.add(camping(weg, r += 5));
        l.add(camping(zahlOhneStopp("camping.vorheizenMinuten", Feld.Typ.INT, 30, 0.0, 60.0, 5.0, "min"), r += 10));
        l.add(camping(zahlOhneStopp("camping.morgensHeizen", Feld.Typ.BOOL, false, null, null, null, null), r += 10));
        Feld weck = new Feld("camping.weckzeit", Feld.Typ.ZEIT);
        weck.standard = null;
        l.add(camping(weck, r += 10));
        l.add(camping(zahlOhneStopp("camping.socGrenze", Feld.Typ.INT, 20, 5.0, 80.0, 1.0, "%"), r += 10));
        double[] v12 = AkkuChemie.v12Standard(k.akkuChemie);
        l.add(camping(nullAus(zahlOhneStopp("camping.v12Warnung", Feld.Typ.ZAHL, v12[0], 0.0, 13.5, 0.1, "V")), r += 10));
        l.add(camping(zahlOhneStopp("camping.hoechstdauerStunden", Feld.Typ.INT, 12, 1.0, 24.0, 1.0, "h"), r += 10));
        // 0.1.17: Ende = Weckzeit + Nachlauf (ohne ausdrückliches Plan-Ende), spätestens Höchstdauer
        l.add(camping(zahlOhneStopp("camping.nachlaufMinuten", Feld.Typ.INT, 60, 0.0, 240.0, 5.0, "min"), r += 5));
        l.add(camping(zahlOhneStopp("camping.waechterBewohnt", Feld.Typ.BOOL, true, null, null, null, null), r += 10));
        Feld aussen = new Feld("camping.aussenBewegung", Feld.Typ.AUSWAHL);
        aussen.standard = "nichts";
        aussen.optionen.add(new Feld.Option("nichts", "settings.camping.aussenBewegung.nichts"));
        aussen.optionen.add(new Feld.Option("notiz", "settings.camping.aussenBewegung.notiz"));
        l.add(camping(aussen, r += 10));
        l.add(camping(zahlOhneStopp("camping.nachtNachSekunden", Feld.Typ.INT, 30, 10.0, 600.0, 10.0, "s"), r += 10));
    }

    private static Feld camping(Feld f, int reihenfolge) {
        f.gruppe = "camping";
        f.abschnitt = null;
        f.reihenfolge = reihenfolge;
        return f;
    }

    private static Feld zahlOhneStopp(String key, Feld.Typ typ, Object std, Double min, Double max,
                                      Double schritt, String einheit) {
        Feld f = new Feld(key, typ);
        f.standard = std; f.min = min; f.max = max; f.schritt = schritt; f.einheit = einheit;
        return f;
    }

    private static Feld zahl(String key, Feld.Typ typ, Object std, Double min, Double max,
                             Double schritt, String einheit, int reihenfolge) {
        Feld f = new Feld(key, typ);
        f.standard = std; f.min = min; f.max = max; f.schritt = schritt; f.einheit = einheit;
        f.reihenfolge = reihenfolge;
        stopp(f);
        return f;
    }

    private static Feld nullAus(Feld f) {
        f.nullIstAus = true;
        return f;
    }

    private static void stopp(Feld f) {
        f.gruppe = "waechter";
        f.abschnitt = "stopp";
    }

    /** deterrent.light / .horn ⇔ surveillance.deterrentAction (eine Wahrheit). */
    private static Feld.Zugriff aktionAlias(final String aktion) {
        return new Feld.Zugriff() {
            public Object lese(JSONObject root) {
                JSONObject s = root == null ? null : root.optJSONObject("surveillance");
                if (s == null || !s.has("deterrentAction")) return null;
                return aktion.equals(s.optString("deterrentAction"));
            }
            public Map<String, Map<String, Object>> schreibe(Object wert, JSONObject root) {
                JSONObject s = root == null ? null : root.optJSONObject("surveillance");
                String jetzt = s == null ? "silent" : s.optString("deterrentAction", "silent");
                String neu;
                if (Boolean.TRUE.equals(wert)) neu = aktion;
                else neu = aktion.equals(jetzt) ? "silent" : jetzt;
                Map<String, Object> v = new HashMap<>();
                v.put("deterrentAction", neu);
                Map<String, Map<String, Object>> out = new HashMap<>();
                out.put("surveillance", v);
                return out;
            }
        };
    }

    /**
     * zaehlen.kennungAktion: wirkt beim Speichern und liest sich immer als „behalten“. „neu“ = neue Zufallskennung,
     * „loeschen“ = Kennung weg und Mitzählen aus; die alte Kennung kommt jeweils in die Löschliste (Regel 2).
     */
    static Feld.Zugriff kennungAktion() {
        return new Feld.Zugriff() {
            public Object lese(JSONObject root) {
                return "behalten";
            }
            public Map<String, Map<String, Object>> schreibe(Object wert, JSONObject root) {
                JSONObject z = root == null ? null : root.optJSONObject(at.greenchili.surfmate.analytics.Mitzaehlen.ABSCHNITT);
                String alt = z == null ? "" : z.optString("kennung", "");
                String liste = z == null ? "" : z.optString("loeschen", "");
                Map<String, Object> v = new HashMap<>();
                if ("neu".equals(wert) || "loeschen".equals(wert)) {
                    v.put("loeschen", at.greenchili.surfmate.analytics.Mitzaehlen.loeschenDazu(liste, alt));
                    v.put("letzterTag", "");
                    if ("neu".equals(wert)) {
                        // aus: keine Kennung auf Vorrat (der Takt erzeugt sie beim Einschalten)
                        v.put("kennung", z != null && z.optBoolean("an", false)
                                ? at.greenchili.surfmate.analytics.Mitzaehlen.neueKennung() : "");
                    } else {
                        v.put("kennung", "");
                        v.put("an", false);
                    }
                }
                Map<String, Map<String, Object>> out = new HashMap<>();
                out.put(at.greenchili.surfmate.analytics.Mitzaehlen.ABSCHNITT, v);
                return out;
            }
        };
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Feld finde(List<Feld> felder, String key) {
        for (Feld f : felder) if (f.key.equals(key)) return f;
        return null;
    }
}
