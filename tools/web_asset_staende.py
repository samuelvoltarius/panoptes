"""Stand der versionierten Web-Dateien festhalten (0.1.43.6, E2E R1).

Jede geteilte JS/CSS-Datei, die eine Seite mit `?v=` lädt, steht hier mit ihrem Zähler und dem SHA-256 ihres Inhalts
(Zeilenenden vereinheitlicht). Der Test WebAssetVersionTest verlangt:
  - alle Seiten nutzen pro Datei denselben Zähler,
  - ändert sich der Inhalt einer Datei, muss ihr Zähler sich ändern (sonst lädt die Auto-WebView bis zu 1 h die alte Datei),
  - der festgehaltene Stand passt zum aktuellen (nach einer gewollten Änderung: Zähler erhöhen, dann dieses Skript laufen lassen).

Aufruf: python tools/web_asset_staende.py        (schreibt app/src/test/resources/web-asset-staende.json)
        python tools/web_asset_staende.py --pruef (nur prüfen, Exit 1 bei Abweichung)
"""
import hashlib, io, json, os, re, sys

BASIS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
WEB = os.path.join(BASIS, 'app', 'src', 'main', 'assets', 'web')
ZIEL = os.path.join(BASIS, 'app', 'src', 'test', 'resources', 'web-asset-staende.json')
VERWEIS = re.compile(r'([\w.-]+\.(?:js|css))\?v=([\w.-]+)')


def dateien():
    for ordner in ('local', 'shared'):
        for wurzel, _, namen in os.walk(os.path.join(WEB, ordner)):
            for n in namen:
                if n.endswith(('.html', '.js')):
                    yield os.path.join(wurzel, n)


def finde(name):
    treffer = []
    for wurzel, _, namen in os.walk(os.path.join(WEB, 'shared')):
        if name in namen:
            treffer.append(os.path.join(wurzel, name))
    return treffer[0] if len(treffer) == 1 else None


def sha(pfad):
    roh = io.open(pfad, 'rb').read().replace(b'\r\n', b'\n')
    return hashlib.sha256(roh).hexdigest()


def stand():
    zaehler = {}
    for p in dateien():
        for m in VERWEIS.finditer(io.open(p, encoding='utf-8', errors='replace').read()):
            zaehler.setdefault(m.group(1), set()).add(m.group(2))
    aus = {}
    for name in sorted(zaehler):
        pfad = finde(name)
        if pfad is None:
            continue
        aus[name] = {'v': sorted(zaehler[name])[0] if len(zaehler[name]) == 1 else sorted(zaehler[name]), 'sha256': sha(pfad)}
    return aus


if __name__ == '__main__':
    s = stand()
    if '--pruef' in sys.argv:
        alt = json.load(io.open(ZIEL, encoding='utf-8'))
        sys.exit(0 if alt == s else 1)
    io.open(ZIEL, 'w', encoding='utf-8', newline='\n').write(json.dumps(s, indent=1, sort_keys=True) + '\n')
    print(len(s), 'Dateien festgehalten')
