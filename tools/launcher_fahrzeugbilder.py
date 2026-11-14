"""0.1.43.4: Launcher-Fahrzeugbilder aus der EINEN Modellquelle von Panoptes erzeugen – ohne Handarbeit.

Quelle ist dieselbe Liste, aus der Panoptes sein 3D-Fahrzeug lädt:
  app/src/main/assets/web/shared/models/manifest.json  (Modelle, Datei, SHA-256, Lizenz, Autor, Lack-Hinweis)
  app/src/main/assets/licenses/MODELLE_LIZENZEN.md      (die eine Lizenzliste)
Die GLB-Dateien kommen von manifest.baseUrl + file (wie in der App) oder aus einem lokalen Ordner (--glb), immer mit
SHA-256-Prüfung gegen das Manifest. Passt die Prüfsumme nicht, bricht das Skript ab (nie ein fremdes Modell rendern).

Gerendert wird jedes Modell mit "top": true, das nicht "verfuegbar": false ist (13.11.: „nur die 5 guten
Modelle“). Ein neues oder besseres Modell braucht nur seinen Eintrag im Manifest (Datei, SHA-256, Lizenz, "top": true);
dann diesen Lauf starten. Weitere Schreibweisen eines Modells (z. B. „Dolphin Surf“ → seagull) stehen dort als
"aliases".

Fotos (13.11.): Ein Modell ohne "top", aber mit "photo": {"file", "sha256", "author", "license", "sourceUrl",
optional "url", "verdecken", "weg"} bekommt statt der Zeichnung ein frei lizenziertes Foto (<id>_foto.webp, 1200×675)
mit Namensnennung „Foto: <Urheber> · <Lizenz> · bearbeitet“. "verdecken" = Liste [x0, y0, x1, y1] (Anteile des
Originals) für Kennzeichen, Plaketten, Schilder und Personen: dort wird das Foto grob verpixelt und weichgezeichnet
(unlesbar). 0.1.43.5 (14.11.): Das Auto wird freigestellt (BiRefNet lite, MIT, siehe FREISTELLER_URL) und wie
die Renderings auf transparentem Grund mit weichem Bodenschatten gesetzt (gleiche Größe und Lage, WebP mit Alpha).
"weg" = Flächen [x0, y0, x1, y1], die nicht zum Auto gehören (Schilder, Aufbauten), wenn der Freisteller sie mitnimmt.
Mehr wird nicht verändert (Freistellen, Zuschnitt, Unkenntlichmachen); bei CC BY-SA steht die Bearbeitung wieder unter
CC BY-SA. Erlaubt: CC0, gemeinfrei, CC BY, CC BY-SA; NC/ND und Unbekanntes
brechen ab. Das Foto kommt aus --foto ORDNER oder von photo.url bzw. baseUrl + "photos/" + file, SHA-256 Pflicht.

Ablauf: GLB holen und prüfen → Blender (tools/render_launcher_fahrzeug.py) je Modell, zwischengespeichert nach
SHA-256 → tools/launcher_bilder_bauen.py → panoptes-launcher/src/main/assets/fahrzeug/ (alte Modelle werden entfernt)
→ katalog.json (Modell → Lizenz, Namensnennung, SHA-256, Aliase), das der Launcher liest.

Aufruf (aus dem Repo-Wurzelordner):
  python tools/launcher_fahrzeugbilder.py [--blender PFAD] [--glb ORDNER]... [--foto ORDNER]... [--cache ORDNER] [--neu]
                                         [--nur-katalog]
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request

WURZEL = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(WURZEL, "app", "src", "main", "assets", "web", "shared", "models", "manifest.json")
ZIEL = os.path.join(WURZEL, "panoptes-launcher", "src", "main", "assets", "fahrzeug")
FOTOS_APP = os.path.join(WURZEL, "app", "src", "main", "assets", "web", "shared", "models", "fotos")
# 0.1.43.5: dieselben Rendering-Ebenen für Panoptes (/api/models/art rechnet die Lackfarbe ein wie der Launcher)
BILDER_APP = os.path.join(WURZEL, "app", "src", "main", "assets", "web", "shared", "models", "bilder")
EBENEN_APP = ("_grund.webp", "_lack.webp")
RENDER = os.path.join(WURZEL, "tools", "render_launcher_fahrzeug.py")
BAUEN = os.path.join(WURZEL, "tools", "launcher_bilder_bauen.py")
ENDUNGEN = ("_grund.webp", "_lack.webp", "_punkte.json")
FOTO = "_foto.webp"


def norm(s):
    """Wie FahrzeugModelle.norm im Launcher: nur Buchstaben/Ziffern, klein."""
    return re.sub(r"[^0-9a-z]", "", (s or "").lower())


def auswahl(manifest):
    return [m for m in manifest["models"] if m.get("top") is True and m.get("verfuegbar", True) is not False]


def namensnennung(m):
    lizenz = m.get("license", "")
    if lizenz.startswith("CC-BY"):
        return "3D: %s · CC BY %s · verändert" % (m.get("author", "?"), lizenz.split("-")[-1])
    return None  # CC0: keine Namensnennung nötig


def foto_auswahl(manifest):
    top = {m["id"] for m in auswahl(manifest)}
    return [m for m in manifest["models"] if m["id"] not in top and isinstance(m.get("photo"), dict)]


def foto_lizenz(lizenz):
    """Lizenz-Kürzel für die Namensnennung; NC/ND/Unbekanntes → None (nicht erlaubt)."""
    l = (lizenz or "").upper().replace(" ", "-")
    if l in ("CC0", "CC0-1.0"):
        return "CC0"
    if l in ("PD", "PUBLIC-DOMAIN", "GEMEINFREI"):
        return "gemeinfrei"
    r = re.fullmatch(r"CC-(BY|BY-SA)-(\d\.\d)", l)
    return "CC %s %s" % (r.group(1), r.group(2)) if r else None


def foto_nennung(f):
    l = foto_lizenz(f.get("license"))
    return "Foto: %s · %s%s" % (f.get("author") or "unbekannt", l, " · bearbeitet" if l.startswith("CC BY") else "")


def verdecken(b, boxen):
    """Stellen unkenntlich machen: grob verpixeln (je ~12 Blöcke) und stark weichzeichnen."""
    from PIL import ImageFilter
    w, h = b.size
    for x0, y0, x1, y1 in boxen:
        r = (int(x0 * w), int(y0 * h), max(int(x1 * w), int(x0 * w) + 2), max(int(y1 * h), int(y0 * h) + 2))
        t = b.crop(r)
        tw, th = t.size
        s = max(1, max(tw, th) // 12)
        t = t.resize((max(1, tw // s), max(1, th // s))).resize((tw, th))
        t = t.filter(ImageFilter.GaussianBlur(max(tw, th) / 10.0))
        b.paste(t, r)
    return b


# Freistellen (0.1.43.5, 14.11.: „Fotos freistellen“): BiRefNet lite (MIT, ZhengPeng7/BiRefNet), ONNX-Fassung
# onnx-community/BiRefNet_lite-ONNX, onnx/model.onnx. Das Modell liegt NICHT im Repo; --freisteller PFAD oder es wird
# einmal in den Cache geladen, immer mit SHA-256-Prüfung. Die fertigen Masken werden je Foto-SHA gemerkt.
FREISTELLER_URL = "https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx"
FREISTELLER_SHA = "5600024376f572a557870a5eb0afb1e5961636bef4e1e22132025467d0f03333"
# Lage wie die Renderings (1200 × 675): Auto höchstens so breit/hoch, Unterkante und Mitte wie dort gemessen
AUTO_BREITE, AUTO_HOEHE, AUTO_UNTEN, AUTO_MITTE = 830, 500, 600, 570


def freisteller_holen(wunsch, cache):
    p = wunsch or os.path.join(cache, "freisteller", "birefnet_lite.onnx")
    if os.path.isfile(p) and sha(p) == FREISTELLER_SHA:
        return p
    if wunsch:
        sys.exit("FEHLER: Freisteller %s passt nicht zur SHA-256 %s" % (wunsch, FREISTELLER_SHA))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    print("lade", FREISTELLER_URL)
    urllib.request.urlretrieve(FREISTELLER_URL, p + ".teil")
    if sha(p + ".teil") != FREISTELLER_SHA:
        os.remove(p + ".teil")
        sys.exit("FEHLER: SHA-256 des Freistellers passt nicht – abgebrochen")
    os.replace(p + ".teil", p)
    return p


def maske(b, modell, merk, weg=()):
    """Alpha-Maske (uint8, Größe des Fotos) des Autos: BiRefNet (Rohmaske je Foto gemerkt), ohne die "weg"-Flächen
    (Schilder, Aufbauten; Anteile des Originals), dann nur die größte zusammenhängende Fläche. Wo das Auto am Bildrand
    abgeschnitten ist, läuft es weich aus statt mit harter Kante."""
    import numpy as np
    from PIL import Image
    if merk and os.path.isfile(merk):
        a = np.array(Image.open(merk).convert("L"))
    else:
        import onnxruntime
        s = onnxruntime.InferenceSession(modell, providers=["CPUExecutionProvider"])
        x = np.asarray(b.resize((1024, 1024), Image.BILINEAR), dtype=np.float32) / 255.0
        x = (x - np.array([0.485, 0.456, 0.406], np.float32)) / np.array([0.229, 0.224, 0.225], np.float32)
        y = s.run(None, {s.get_inputs()[0].name: x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0, 0]
        if y.min() < 0 or y.max() > 1:
            y = 1.0 / (1.0 + np.exp(-y))
        a = np.array(Image.fromarray((y * 255).clip(0, 255).astype(np.uint8)).resize(b.size, Image.BILINEAR))
        if merk:
            os.makedirs(os.path.dirname(merk), exist_ok=True)
            Image.fromarray(a).save(merk)
    h, w = a.shape
    for x0, y0, x1, y1 in weg:
        a[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)] = 0
    from scipy import ndimage
    teile, n = ndimage.label(a > 128)
    if n == 0:
        sys.exit("FEHLER: kein Auto im Foto gefunden")
    if n > 1:
        groesse = ndimage.sum(np.ones_like(a), teile, range(1, n + 1))
        haupt = teile == (int(np.argmax(groesse)) + 1)
        a = np.where(ndimage.binary_dilation(haupt, iterations=6), a, 0).astype(np.uint8)
    # bis auf 5 % an den Rand heran = abgeschnitten (oder von einer verdeckten Stelle begrenzt): dort ausblenden
    r = max(2, int(0.06 * max(w, h)))
    ys, xs = np.where(a > 128)
    rampe = np.linspace(0.0, 1.0, r, dtype=np.float32)
    f = np.ones(a.shape, dtype=np.float32)
    l, re, o = int(xs.min()), int(xs.max()) + 1, int(ys.min())
    if l < 0.05 * w:
        f[:, l:l + r] *= rampe[None, :w - l][:, :min(r, w - l)]
        f[:, :l] = 0
    if re > 0.95 * w:
        f[:, max(0, re - r):re] *= rampe[::-1][None, -min(r, re):]
        f[:, re:] = 0
    if o < 0.05 * h:
        f[o:o + r, :] *= rampe[:min(r, h - o), None]
        f[:o, :] = 0
    return (a * f).astype(np.uint8)


def freigestellt(b, a):
    """Auto (RGBA) auf 1200 × 675 wie die Renderings: gleiche Größe und Lage, weicher Bodenschatten darunter."""
    import numpy as np
    from PIL import Image, ImageDraw, ImageFilter
    ys, xs = np.where(a > 128)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    s = min(AUTO_BREITE / float(x1 - x0), AUTO_HOEHE / float(y1 - y0))
    rgba = b.convert("RGBA")
    rgba.putalpha(Image.fromarray(a))
    auto = rgba.crop((x0, y0, x1, y1))
    w, h = max(1, int(round((x1 - x0) * s))), max(1, int(round((y1 - y0) * s)))
    auto = auto.resize((w, h), Image.LANCZOS)
    px, py = AUTO_MITTE - w // 2, AUTO_UNTEN - h
    # Schatten: Umriss zum Boden gestaucht (Aufstandsfläche) + breite weiche Ellipse, leicht nach rechts versetzt
    form = Image.new("L", (1200, 675), 0)
    platt = auto.getchannel("A").resize((w, max(1, int(h * 0.20))), Image.BILINEAR)
    form.paste(platt, (px + int(w * 0.015), AUTO_UNTEN - platt.size[1] + int(h * 0.012)))
    nah = form.filter(ImageFilter.GaussianBlur(9)).point(lambda v: int(v * 0.80))
    weit = Image.new("L", (1200, 675), 0)
    ImageDraw.Draw(weit).ellipse((px, AUTO_UNTEN - int(w * 0.10), px + int(w * 1.08), AUTO_UNTEN + int(w * 0.03)),
                                 fill=155)
    weit = weit.filter(ImageFilter.GaussianBlur(int(w * 0.03)))
    schatten = Image.fromarray(np.maximum(np.asarray(nah), np.asarray(weit)).astype(np.uint8))
    erg = Image.new("RGBA", (1200, 675), (0, 0, 0, 0))
    erg.putalpha(schatten)
    erg.alpha_composite(auto, (px, py))
    return erg


def katalog(manifest):
    modelle, fotos, alias = {}, {}, {}
    for m in auswahl(manifest):
        modelle[m["id"]] = {"sha256": m["sha256"], "lizenz": m.get("license"), "namensnennung": namensnennung(m)}
        for a in m.get("aliases", []):
            alias[norm(a)] = m["id"]
    for m in foto_auswahl(manifest):
        f = m["photo"]
        fotos[m["id"]] = {"sha256": f["sha256"], "lizenz": f.get("license"), "urheber": f.get("author"),
                          "quelle": f.get("sourceUrl"), "namensnennung": foto_nennung(f)}
        for a in m.get("aliases", []):
            alias[norm(a)] = m["id"]
    return {"quelle": "app/src/main/assets/web/shared/models/manifest.json", "manifestVersion": manifest.get("version"),
            "regel": "top == true und verfuegbar != false; sonst photo", "modelle": modelle, "fotos": fotos,
            "alias": alias, "bitteUrl": manifest.get("modellBitteUrl")}


def foto_bauen(m, base_url, ordner, cache, freisteller=None):
    f = m["photo"]
    if foto_lizenz(f.get("license")) is None:
        sys.exit("FEHLER: Foto-Lizenz von %s nicht erlaubt: %s" % (m["id"], f.get("license")))
    if not f.get("author") or not f.get("sourceUrl"):
        sys.exit("FEHLER: Foto von %s ohne Urheber oder Quelle" % m["id"])
    quelle = None
    for d in ordner:
        for p in glob.glob(os.path.join(d, "**", f["file"]), recursive=True):
            if sha(p) == f["sha256"]:
                quelle = p
    if quelle is None:
        quelle = os.path.join(cache, "fotos", f["file"])
        if not (os.path.isfile(quelle) and sha(quelle) == f["sha256"]):
            os.makedirs(os.path.dirname(quelle), exist_ok=True)
            url = f.get("url") or base_url + "photos/" + f["file"]
            print("lade", url)
            urllib.request.urlretrieve(url, quelle + ".teil")
            if sha(quelle + ".teil") != f["sha256"]:
                os.remove(quelle + ".teil")
                sys.exit("FEHLER: SHA-256 des Fotos %s passt nicht zum Manifest – abgebrochen" % url)
            os.replace(quelle + ".teil", quelle)
    from PIL import Image
    roh = Image.open(quelle).convert("RGB")
    # Maske auf dem Original (ein verpixeltes Feld hielte der Freisteller sonst für einen Teil des Autos), danach
    # unkenntlich machen: was im Hintergrund lag (Personen, Schilder), fällt mit dem Hintergrund ohnehin weg.
    merk = os.path.join(cache, "masken", f["sha256"][:16] + "-" + FREISTELLER_SHA[:8] + "-o.png")
    modell = None if os.path.isfile(merk) else freisteller_holen(freisteller, cache)
    a = maske(roh, modell, merk, f.get("weg") or [])
    erg = freigestellt(verdecken(roh, f.get("verdecken") or []), a)
    erg.save(os.path.join(ZIEL, m["id"] + FOTO), "WEBP", quality=88, alpha_quality=90, method=6)
    # dieselbe Datei für Panoptes (fahrzeug-foto.js): eine Quelle, ein Foto
    os.makedirs(FOTOS_APP, exist_ok=True)
    shutil.copy2(os.path.join(ZIEL, m["id"] + FOTO), os.path.join(FOTOS_APP, m["id"] + ".webp"))


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def glb_holen(m, base_url, ordner, cache):
    for d in ordner:
        for p in glob.glob(os.path.join(d, "**", m["file"]), recursive=True):
            if sha(p) == m["sha256"]:
                return p
    p = os.path.join(cache, "glb", m["file"])
    if os.path.isfile(p) and sha(p) == m["sha256"]:
        return p
    os.makedirs(os.path.dirname(p), exist_ok=True)
    url = base_url + m["file"]
    print("lade", url)
    urllib.request.urlretrieve(url, p + ".teil")
    if sha(p + ".teil") != m["sha256"]:
        os.remove(p + ".teil")
        sys.exit("FEHLER: SHA-256 von %s passt nicht zum Manifest – abgebrochen" % url)
    os.replace(p + ".teil", p)
    return p


def blender_finden(wunsch):
    if wunsch:
        return wunsch
    for k in [shutil.which("blender")] + glob.glob("C:/Program Files/Blender Foundation/Blender */blender.exe"):
        if k and os.path.isfile(k):
            return k
    sys.exit("FEHLER: Blender nicht gefunden (--blender PFAD)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--blender")
    ap.add_argument("--glb", action="append", default=[], help="lokaler Ordner mit GLB-Dateien (SHA-256 wird geprüft)")
    ap.add_argument("--foto", action="append", default=[], help="lokaler Ordner mit Fotos (SHA-256 wird geprüft)")
    ap.add_argument("--freisteller", help="BiRefNet-lite-ONNX (SHA-256 wird geprüft); sonst einmal in den Cache laden")
    ap.add_argument("--cache", default=os.path.join(os.path.expanduser("~"), ".cache", "panoptes-launcher-bilder"))
    ap.add_argument("--neu", action="store_true", help="auch zwischengespeicherte Renderings neu rechnen")
    ap.add_argument("--nur-katalog", action="store_true", help="nur katalog.json schreiben und Ordner abgleichen")
    a = ap.parse_args()

    with open(MANIFEST, encoding="utf-8") as f:
        manifest = json.load(f)
    wahl = auswahl(manifest)
    if not wahl:
        sys.exit("FEHLER: kein Modell mit \"top\": true im Manifest")
    print("Modelle:", ", ".join(m["id"] for m in wahl))

    if not a.nur_katalog:
        blender = blender_finden(a.blender)
        stufe = os.path.join(a.cache, "stufe")
        shutil.rmtree(stufe, ignore_errors=True)
        os.makedirs(stufe)
        for m in wahl:
            roh = os.path.join(a.cache, "roh", "%s-%s" % (m["id"], m["sha256"][:16]))
            if a.neu or not os.path.isfile(os.path.join(roh, m["id"] + "_punkte.json")):
                glb = glb_holen(m, manifest["baseUrl"], a.glb, a.cache)
                env = dict(os.environ, PN_PROBEN=os.environ.get("PN_PROBEN", "128"),
                           PN_BELICHTUNG=os.environ.get("PN_BELICHTUNG", "-0.25"))
                r = subprocess.run([blender, "-b", "--factory-startup", "-P", RENDER, "--", glb, roh, m["id"],
                                    m.get("paintMeshHint") or "bodypaint"], env=env)
                if r.returncode != 0 or not os.path.isfile(os.path.join(roh, m["id"] + "_punkte.json")):
                    sys.exit("FEHLER: Rendering von %s fehlgeschlagen" % m["id"])
            for p in glob.glob(os.path.join(roh, m["id"] + "_*")):
                shutil.copy2(p, stufe)
        neu = os.path.join(a.cache, "assets")
        shutil.rmtree(neu, ignore_errors=True)
        r = subprocess.run([sys.executable, "-I", BAUEN, stufe, neu])
        if r.returncode != 0:
            sys.exit("FEHLER: launcher_bilder_bauen.py")
        os.makedirs(BILDER_APP, exist_ok=True)
        for m in wahl:
            for e in ENDUNGEN:
                shutil.copy2(os.path.join(neu, m["id"] + e), os.path.join(ZIEL, m["id"] + e))
            for e in EBENEN_APP:
                shutil.copy2(os.path.join(neu, m["id"] + e), os.path.join(BILDER_APP, m["id"] + e))

    fotos = foto_auswahl(manifest)
    if not a.nur_katalog:
        for m in fotos:
            foto_bauen(m, manifest["baseUrl"], a.foto, a.cache, a.freisteller)
    print("Fotos:", ", ".join(m["id"] for m in fotos) or "keine")

    # Ordner abgleichen: nur ausgewählte Modelle und Fotos bleiben
    ids = {m["id"] for m in wahl}
    foto_ids = {m["id"] for m in fotos}
    for p in os.listdir(ZIEL):
        for e in ENDUNGEN:
            if p.endswith(e) and p[:-len(e)] not in ids:
                os.remove(os.path.join(ZIEL, p))
                print("entfernt", p)
        if p.endswith(FOTO) and p[:-len(FOTO)] not in foto_ids:
            os.remove(os.path.join(ZIEL, p))
            print("entfernt", p)
    if os.path.isdir(FOTOS_APP):
        for p in os.listdir(FOTOS_APP):
            if p.endswith(".webp") and p[:-5] not in foto_ids:
                os.remove(os.path.join(FOTOS_APP, p))
                print("entfernt", "fotos/" + p)
    if os.path.isdir(BILDER_APP):
        for p in os.listdir(BILDER_APP):
            if not any(p.endswith(e) and p[:-len(e)] in ids for e in EBENEN_APP):
                os.remove(os.path.join(BILDER_APP, p))
                print("entfernt", "bilder/" + p)
    for m in wahl:
        for e in EBENEN_APP:
            q, z = os.path.join(ZIEL, m["id"] + e), os.path.join(BILDER_APP, m["id"] + e)
            if not os.path.isfile(z) or sha(z) != sha(q):
                os.makedirs(BILDER_APP, exist_ok=True)
                shutil.copy2(q, z)
    for i in foto_ids:
        if not os.path.isfile(os.path.join(FOTOS_APP, i + ".webp")):
            sys.exit("FEHLER: Panoptes-Foto %s.webp fehlt – ohne --nur-katalog laufen lassen" % i)
        if not os.path.isfile(os.path.join(ZIEL, i + FOTO)):
            sys.exit("FEHLER: %s%s fehlt – ohne --nur-katalog laufen lassen" % (i, FOTO))
    for m in wahl:
        for e in ENDUNGEN:
            if not os.path.isfile(os.path.join(ZIEL, m["id"] + e)):
                sys.exit("FEHLER: %s%s fehlt – ohne --nur-katalog laufen lassen" % (m["id"], e))
    with open(os.path.join(ZIEL, "katalog.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(katalog(manifest), f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    print("katalog.json:", len(ids), "Modelle,", len(foto_ids), "Fotos")


if __name__ == "__main__":
    main()
