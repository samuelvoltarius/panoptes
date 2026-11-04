"""Musterbilder und Musterclips für das Demo-Profil (assets/demo/medien/).

Alles ist frei gezeichnet (PIL-Formen: Parkplatz-Schema von oben, Straßen-Schema von vorne), kein Foto, kein
KI-Bild, keine Kennzeichen, keine Gesichter, nichts vom echten Auto. Jedes Bild trägt den Hinweis „Musterbild“.

Aufruf (aus dem Repo-Wurzelordner): python tools/demo_muster_medien.py [ffmpeg]
Schreibt je Clip <id>.mp4 (H.264, 640x360, 12 fps, faststart) und <id>.jpg (Vorschau) sowie standbild.jpg.
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

ZIEL = os.path.join("app", "src", "main", "assets", "demo", "medien")
W, H, FPS = 640, 360, 12

# IDs wie im Index (32 hex); muss zu DemoProfil.CLIPS passen
PERSON = "de30a0000000000000000000000000a1"
FAHRZEUG = "de30a0000000000000000000000000a2"
BREMSUNG = "de30a0000000000000000000000000b1"

BODEN = (46, 52, 58)
LINIE = (196, 200, 190)
EIGEN = (95, 214, 230)      # Panoptes-Aqua: das eigene Auto
FREMD = (150, 156, 164)
LIME = (200, 230, 90)
TEXT = (235, 238, 240)


def schrift(groesse):
    for name in ("C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf",
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(name):
            return ImageFont.truetype(name, groesse)
    return ImageFont.load_default()


F_KLEIN, F_MITTEL = schrift(15), schrift(20)


def hinweis(d, kamera):
    d.rounded_rectangle((10, 10, 10 + d.textlength(kamera, font=F_MITTEL) + 20, 42), 8, fill=(0, 0, 0))
    d.text((20, 13), kamera, font=F_MITTEL, fill=TEXT)
    t = "Musterbild – gezeichnet, keine echte Aufnahme"
    b = d.textlength(t, font=F_KLEIN)
    d.rounded_rectangle((W - b - 30, H - 34, W - 10, H - 10), 6, fill=(0, 0, 0))
    d.text((W - b - 20, H - 32), t, font=F_KLEIN, fill=(250, 214, 90))


def auto_oben(d, x, y, b, h, farbe, licht=None):
    """Auto von oben: Karosserie, Scheiben, vier Räder. (x, y) = Mitte."""
    for rx in (-b / 2 - 3, b / 2 - 5):
        for ry in (-h * 0.32, h * 0.22):
            d.rounded_rectangle((x + rx, y + ry, x + rx + 8, y + ry + h * 0.14), 3, fill=(20, 22, 24))
    d.rounded_rectangle((x - b / 2, y - h / 2, x + b / 2, y + h / 2), int(b * 0.3), fill=farbe)
    d.rounded_rectangle((x - b * 0.36, y - h * 0.30, x + b * 0.36, y - h * 0.12), 6, fill=(30, 40, 48))
    d.rounded_rectangle((x - b * 0.34, y + h * 0.18, x + b * 0.34, y + h * 0.32), 6, fill=(30, 40, 48))
    if licht:
        d.rectangle((x - b / 2 + 4, y + h / 2 - 5, x - b / 2 + 16, y + h / 2 - 1), fill=licht)
        d.rectangle((x + b / 2 - 16, y + h / 2 - 5, x + b / 2 - 4, y + h / 2 - 1), fill=licht)


def parkplatz(d):
    d.rectangle((0, 0, W, H), fill=BODEN)
    for i in range(7):
        x = 40 + i * 95
        d.line((x, 70, x, 200), fill=LINIE, width=3)
    d.line((40, 200, 610, 200), fill=LINIE, width=2)
    for x in range(0, W, 60):   # Fahrgasse
        d.line((x, 280, x + 30, 280), fill=(120, 124, 120), width=3)


def person(d, x, y, box=True):
    d.ellipse((x - 9, y - 9, x + 9, y + 9), fill=(222, 222, 214))          # Kopf von oben (nur ein Kreis)
    d.ellipse((x - 16, y - 4, x + 16, y + 14), outline=(222, 222, 214), width=4)   # Schultern
    if box:
        d.rectangle((x - 26, y - 22, x + 26, y + 26), outline=LIME, width=2)
        d.text((x - 26, y - 42), "Person", font=F_KLEIN, fill=LIME)


def bild_person(i, n):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    parkplatz(d)
    auto_oben(d, 325, 135, 68, 112, EIGEN)
    auto_oben(d, 135, 135, 66, 110, FREMD)
    # Weg: von links unten heran, kurz stehen, wieder weg
    t = i / (n - 1)
    if t < 0.45:
        k = t / 0.45
        x, y = 40 + k * 250, 330 - k * 110
    elif t < 0.7:
        x, y = 290, 220
    else:
        k = (t - 0.7) / 0.3
        x, y = 290 + k * 360, 220 + k * 70
    person(d, x, y)
    hinweis(d, "Kamera vorne")
    return img


def bild_fahrzeug(i, n):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    parkplatz(d)
    auto_oben(d, 325, 135, 68, 112, EIGEN)
    t = i / (n - 1)
    if t < 0.55:   # fährt auf der Gasse heran
        k = t / 0.55
        x, y = -60 + k * 480, 300
        d.rounded_rectangle((x - 56, y - 30, x + 56, y + 30), 18, fill=FREMD)
        d.rounded_rectangle((x + 20, y - 24, x + 40, y + 24), 6, fill=(30, 40, 48))
        box = (x - 64, y - 38, x + 64, y + 38)
    else:          # biegt in die Lücke rechts daneben ein
        k = min(1.0, (t - 0.55) / 0.35)
        x, y = 420 + k * 95, 300 - k * 165
        auto_oben(d, x, y, 66, 110, FREMD, licht=(220, 60, 50) if k < 1 else None)
        box = (x - 42, y - 62, x + 42, y + 62)
    d.rectangle(box, outline=LIME, width=2)
    d.text((box[0], box[1] - 20), "Fahrzeug", font=F_KLEIN, fill=LIME)
    hinweis(d, "Kamera rechts")
    return img


def bild_bremsung(i, n):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W, 150), fill=(70, 86, 104))                  # Himmel
    d.rectangle((0, 150, W, H), fill=(64, 82, 62))                    # Wiese
    d.polygon([(40, H), (W - 40, H), (350, 150), (290, 150)], fill=(58, 60, 62))   # Straße
    t = i / (n - 1)
    tempo = 48 if t < 0.35 else max(0, int(48 * (1 - (t - 0.35) / 0.4)))
    weg = (i * (tempo / 48.0 + 0.02) * 40) % 80   # Leitlinien laufen mit dem Tempo
    for s in range(6):
        z = ((s * 80 + weg) % 480) / 480.0
        y0, y1 = 150 + z * z * 210, 150 + min(1, z + 0.08) ** 2 * 210
        x0, x1 = 320 + (z * z) * 2, 320 + min(1, z + 0.08) ** 2 * 2
        d.line((x0, y0, x1, y1), fill=LINIE, width=max(1, int(2 + z * 6)))
    # Auto voraus: kommt beim Bremsen näher, Bremslichter rot
    nah = 0.25 + (0.0 if t < 0.35 else min(0.55, (t - 0.35) * 1.4))
    b, h = 80 + nah * 260, 50 + nah * 160
    cx, cy = 322, 170 + nah * 120
    d.rounded_rectangle((cx - b / 2, cy - h / 2, cx + b / 2, cy + h / 2), int(b * 0.12), fill=FREMD)
    d.rounded_rectangle((cx - b * 0.38, cy - h * 0.42, cx + b * 0.38, cy - h * 0.05), 6, fill=(34, 42, 50))
    rot = (235, 40, 30) if t >= 0.3 else (110, 40, 36)
    d.rectangle((cx - b / 2 + 6, cy + h * 0.1, cx - b / 2 + b * 0.18, cy + h * 0.26), fill=rot)
    d.rectangle((cx + b / 2 - b * 0.18, cy + h * 0.1, cx + b / 2 - 6, cy + h * 0.26), fill=rot)
    d.rectangle((cx - b * 0.16, cy + h * 0.12, cx + b * 0.16, cy + h * 0.3), fill=(70, 74, 80))   # leeres Schild
    if t >= 0.35:
        d.rounded_rectangle((W / 2 - 92, 56, W / 2 + 92, 88), 8, fill=(200, 40, 30))
        d.text((W / 2 - 78, 60), "Vollbremsung", font=F_MITTEL, fill=TEXT)
    d.rounded_rectangle((W - 130, 10, W - 10, 42), 8, fill=(0, 0, 0))
    d.text((W - 118, 13), f"{tempo} km/h", font=F_MITTEL, fill=TEXT)
    hinweis(d, "Dashcam vorne")
    return img


def clip(ffmpeg, name, zeichner, sekunden):
    n = int(sekunden * FPS)
    with tempfile.TemporaryDirectory() as tmp:
        for i in range(n):
            zeichner(i, n).save(os.path.join(tmp, "f%04d.png" % i))
        subprocess.run([ffmpeg, "-nostdin", "-y", "-loglevel", "error", "-framerate", str(FPS),
                        "-i", os.path.join(tmp, "f%04d.png"), "-c:v", "libx264", "-profile:v", "baseline",
                        "-pix_fmt", "yuv420p", "-crf", "30", "-movflags", "+faststart", "-an",
                        os.path.join(ZIEL, name + ".mp4")], check=True)
        zeichner(int(n * 0.5), n).save(os.path.join(ZIEL, name + ".jpg"), quality=82)


def main():
    ffmpeg = sys.argv[1] if len(sys.argv) > 1 else "ffmpeg"
    os.makedirs(ZIEL, exist_ok=True)
    clip(ffmpeg, PERSON, bild_person, 8)
    clip(ffmpeg, FAHRZEUG, bild_fahrzeug, 8)
    clip(ffmpeg, BREMSUNG, bild_bremsung, 6)
    s = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(s)
    parkplatz(d)
    auto_oben(d, 325, 135, 68, 112, EIGEN)
    auto_oben(d, 135, 135, 66, 110, FREMD)
    hinweis(d, "Standbild vorne")
    s.save(os.path.join(ZIEL, "standbild.jpg"), quality=82)


if __name__ == "__main__":
    main()
