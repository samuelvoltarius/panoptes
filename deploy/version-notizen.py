#!/usr/bin/env python3
"""0.1.38.8: Notizen für version.json aus app/src/main/assets/web/shared/neuigkeiten.json (eine Quelle für Web,
Mittelschirm und Updater).

    python3 deploy/version-notizen.py <version.json> [neuigkeiten.json] [--pruefen-nur]

Schreibt in version.json:
  notizen           die Zeilen der eigenen Version (je Sprache höchstens 8 à 200 Zeichen)
  notizenSeitGross  nur bei einer GROSSEN Version (3. Stelle oder höher neu, z. B. 0.1.39): die gekürzte Sammelliste
                    aller Änderungen seit der letzten großen Version, höchstens 8 Punkte je Sprache. Der Updater zeigt
                    sie in der Meldung vor einem großen Update (Telegram, Handy, Display). Kleine Versionen (nur die
                    4. Stelle neu) kommen still und brauchen keine Liste.

Die Sammelliste füllt der Integrator: Ein Eintrag der großen Version in neuigkeiten.json kann sie ausdrücklich unter
"seitGross": {"de": [...], "en": [...], ...} tragen (empfohlen, von Hand auf das Wichtigste gekürzt). Fehlt sie, nimmt
das Skript die Zeilen der eigenen Version und danach die der kleinen Versionen seit der letzten großen (neueste
zuerst, Dubletten weg) und kürzt auf 8; es sagt dann, was weggefallen ist.

Mit --pruefen-nur wird nichts geschrieben, nur gezeigt. Exit 1, wenn die Version in neuigkeiten.json fehlt.
"""
import json
import sys

SPRACHEN = ("de", "en", "fr", "it", "nl")
MAX_ZEILEN = 8
MAX_ZEICHEN = 200


def stellen(name):
    t = [int(x) for x in name.split("-")[0].split(".")]
    return t + [0] * (4 - len(t))


def gross(name):
    return stellen(name)[3] == 0


def kuerzen(z):
    z = z.strip()
    if len(z) <= MAX_ZEICHEN:
        return z
    schnitt = z[:MAX_ZEICHEN - 1].rsplit(" ", 1)[0].rstrip(",;:")
    return schnitt + "…"


def sammel(eintraege, ziel, sprache):
    """Zeilen der Zielversion und der kleinen Versionen seit der letzten großen davor, neueste zuerst, ohne Dubletten."""
    z = stellen(ziel)
    letzte_grosse = max((stellen(e["version"]) for e in eintraege
                         if gross(e["version"]) and stellen(e["version"]) < z), default=[0, 0, 0, 0])
    dabei = sorted((e for e in eintraege if letzte_grosse < stellen(e["version"]) <= z),
                   key=lambda e: stellen(e["version"]), reverse=True)
    out, gesehen = [], set()
    for e in dabei:
        for zeile in e.get(sprache) or []:
            k = zeile.strip()
            if k and k not in gesehen:
                gesehen.add(k)
                out.append(k)
    return out


def main(argv):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    nur = "--pruefen-nur" in argv
    argv = [a for a in argv if a != "--pruefen-nur"]
    if not argv:
        print(__doc__)
        return 2
    vj = argv[0]
    quelle = argv[1] if len(argv) > 1 else "app/src/main/assets/web/shared/neuigkeiten.json"
    v = json.load(open(vj, encoding="utf-8"))
    eintraege = json.load(open(quelle, encoding="utf-8"))["versionen"]
    name = v["versionName"]
    eigen = next((e for e in eintraege if e.get("version") == name), None)
    if eigen is None:
        print("ABBRUCH: %s fehlt in %s – erst den Eintrag anlegen" % (name, quelle))
        return 1
    v["notizen"] = {sp: [kuerzen(z) for z in (eigen.get(sp) or [])][:MAX_ZEILEN] for sp in SPRACHEN}
    v.pop("notizenSeitGross", None)
    if gross(name):
        ausdruecklich = eigen.get("seitGross") or {}
        seit = {}
        for sp in SPRACHEN:
            liste = ausdruecklich.get(sp) or sammel(eintraege, name, sp)
            if len(liste) > MAX_ZEILEN:
                print("Hinweis %s: %d Punkte, nur die ersten %d bleiben; weggefallen:" % (sp, len(liste), MAX_ZEILEN))
                for z in liste[MAX_ZEILEN:]:
                    print("   - " + z[:100])
            seit[sp] = [kuerzen(z) for z in liste[:MAX_ZEILEN]]
        v["notizenSeitGross"] = seit
        print("%s ist groß: notizenSeitGross %s" % (name, "aus seitGross" if ausdruecklich else "gesammelt"))
    else:
        print("%s ist klein: kommt still, keine Sammelliste" % name)
    for sp in ("de", "en"):
        print("[%s] notizen:" % sp)
        for z in v["notizen"][sp]:
            print("  • " + z)
        for z in v.get("notizenSeitGross", {}).get(sp, []):
            print("  ∑ " + z)
    if not nur:
        open(vj, "w", encoding="utf-8", newline="\n").write(json.dumps(v, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
