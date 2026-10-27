#!/usr/bin/env python3
"""0.1.20: web/shared/design-tokens.json aus web/shared/design-tokens.css erzeugen (eine Quelle, Namen wie im CSS).

Cockpit und Handy-App bauen ihr Aussehen daraus (Koordination 27.10.2026: „die Apps sehen aus wie Panoptes“).
Aufruf: python tools/design_tokens_json.py   (Test DesignTokensJsonTest prüft den Gleichstand)
"""
import io, json, os, re, sys

HIER = os.path.dirname(os.path.abspath(__file__))
CSS = os.path.join(HIER, '..', 'app', 'src', 'main', 'assets', 'web', 'shared', 'design-tokens.css')
JSON = os.path.join(HIER, '..', 'app', 'src', 'main', 'assets', 'web', 'shared', 'design-tokens.json')


def bloecke(css):
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.S)
    out = {}
    for sel, body in re.findall(r'([^{}]+)\{([^{}]*)\}', css):
        sel = ' '.join(sel.split())
        werte = dict((k.strip(), ' '.join(v.split())) for k, v in re.findall(r'--([a-z0-9-]+)\s*:\s*([^;]+);', body))
        if werte:
            out.setdefault(sel, {}).update(werte)
    return out


def erzeugen():
    b = bloecke(io.open(CSS, encoding='utf-8').read())
    dunkel, hell = b.get(':root', {}), b.get(':root[data-theme="light"]', {})
    gruppen = {
        'farbe': [k for k in dunkel if not k.startswith(('radius-', 'family-', 'tracking-', 'duration-', 'easing-',
                                                           'shadow-', 'page-', 'card-', 'tile-'))],
        'schrift': [k for k in dunkel if k.startswith(('family-', 'tracking-'))],
        'rundung': [k for k in dunkel if k.startswith('radius-')],
        'kachel': [k for k in dunkel if k.startswith(('card-', 'tile-', 'page-'))],
        'schatten': [k for k in dunkel if k.startswith('shadow-')],
        'bewegung': [k for k in dunkel if k.startswith(('duration-', 'easing-'))],
    }
    return {
        'version': 1,
        'quelle': 'web/shared/design-tokens.css',
        'hinweis': 'Erzeugt mit tools/design_tokens_json.py, nicht von Hand ändern. Namen wie die CSS-Variablen '
                   '(ohne --). Standard am Auto-Display: dunkel; hell überschreibt nur die genannten Werte.',
        'standard': 'dunkel',
        'schriftdateien': {'Sora': ['fonts/sora-latin-wght.woff2', 'fonts/sora-latin-ext-wght.woff2'],
                           'Manrope': ['fonts/manrope-latin-wght.woff2', 'fonts/manrope-latin-ext-wght.woff2'],
                           'lizenz': 'SIL OFL 1.1 (web/shared/fonts/)'},
        'gruppen': gruppen,
        'dunkel': dunkel,
        'hell': hell,
    }


if __name__ == '__main__':
    d = erzeugen()
    io.open(JSON, 'w', encoding='utf-8', newline='\n').write(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
    print('geschrieben:', os.path.normpath(JSON), len(d['dunkel']), 'dunkel,', len(d['hell']), 'hell')
