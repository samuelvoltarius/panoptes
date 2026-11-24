"""Erzeugt die SYNTHETISCHEN Beispiel-Datenbanken für BydFahrtenbuchTest (0.1.43.20).

Schema wie BYDs /sdcard/energydata/EC_database.db (Auto-Fundgrube 24.11.2026), Inhalt frei erfunden:
keine echten Fahrten, keine Orte. Aufruf: python tools/byd_beispiel_db.py
"""
import os
import sqlite3

ZIEL = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "byd")
SCHEMA = ("CREATE TABLE EnergyConsumption (_id INTEGER PRIMARY KEY AUTOINCREMENT, month TEXT, date TEXT, "
          "start_timestamp INTEGER, end_timestamp INTEGER, is_deleted INTEGER DEFAULT 0, duration INTEGER, "
          "trip REAL, electricity REAL, fuel REAL )")
# 2026-03-02 00:00 UTC (Montag), feste Schrittweite: reproduzierbar ohne Zufall
BASIS = 1772409600


def neu(name):
    p = os.path.join(ZIEL, name)
    if os.path.exists(p):
        os.remove(p)
    c = sqlite3.connect(p)
    c.execute("PRAGMA page_size=4096")
    c.execute("CREATE TABLE android_metadata (locale TEXT)")
    c.execute("INSERT INTO android_metadata VALUES ('de_DE')")
    return c


def zeile(i):
    """Fahrt i: zwei Fahrten je Tag (08:00 und 17:00 UTC), km und kWh nach festem Muster."""
    tag, zweite = divmod(i, 2)
    start = BASIS + tag * 86400 + (17 if zweite else 8) * 3600
    dauer = 600 + (i % 7) * 300
    km = round(5.0 + (i % 9) * 3.5, 1)
    kwh = round(km * (0.12 + (i % 5) * 0.01), 1)
    if i % 50 == 7:
        km, kwh = 0.0, 0.1            # Kurzfahrt ohne Strecke
    if i % 60 == 11:
        kwh = -0.4                    # Bergab mit Rückgewinnung
    import time
    t = time.gmtime(start)
    return (time.strftime("%Y%m", t), time.strftime("%Y%m%d", t), start, start + dauer,
            1 if i % 97 == 3 else 0, dauer, km, kwh, 0.0)


def main():
    os.makedirs(ZIEL, exist_ok=True)
    c = neu("ec_beispiel.db")
    c.execute(SCHEMA)
    c.executemany("INSERT INTO EnergyConsumption (month,date,start_timestamp,end_timestamp,is_deleted,duration,"
                  "trip,electricity,fuel) VALUES (?,?,?,?,?,?,?,?,?)", [zeile(i) for i in range(700)])
    c.commit(); c.close()

    c = neu("ec_fremd.db")
    c.execute("CREATE TABLE EnergyConsumption (_id INTEGER PRIMARY KEY, tag TEXT, menge REAL)")
    c.execute("INSERT INTO EnergyConsumption (tag, menge) VALUES ('20260301', 1.5)")
    c.commit(); c.close()

    c = neu("ec_ueberlauf.db")
    c.execute(SCHEMA.replace("fuel REAL )", "fuel REAL , notiz TEXT)"))
    for i in range(30):
        r = zeile(i)
        c.execute("INSERT INTO EnergyConsumption (month,date,start_timestamp,end_timestamp,is_deleted,duration,"
                  "trip,electricity,fuel,notiz) VALUES (?,?,?,?,?,?,?,?,?,?)", r + (("x" * 9000) if i % 3 == 0 else "kurz",))
    c.commit(); c.close()
    print("geschrieben nach", os.path.abspath(ZIEL))


if __name__ == "__main__":
    main()
