"""0.1.43.4: Roh-Renderings → Launcher-Assets (Pillow + numpy, normales Python, nicht in Blender).

Fahrzeuge: 1200×675 (16:9 wie FahrzeugStart.BILD_SEITEN), WebP, Ankerpunkte auf den Ausschnitt umgerechnet.
Szenen (optional, drittes Argument): PNG 1600×1000 → WebP.
Aufruf: python tools/launcher_bilder_bauen.py <roh-fahrzeuge> <assets/fahrzeug> [<roh-szenen> <assets/szenen>]
Ablauf gesamt: tools/render_launcher_fahrzeug.py je Modell, tools/render_launcher_szenen.py je Szene/Zeit, dann dies.
"""
import glob
import json
import os
import sys

import numpy as np
from PIL import Image

roh, ziel = sys.argv[1], sys.argv[2]
os.makedirs(ziel, exist_ok=True)
H_ZIEL = 675
summe = 0
for pj in sorted(glob.glob(os.path.join(roh, "*_punkte.json"))):
    mid = os.path.basename(pj)[:-len("_punkte.json")]
    P = json.load(open(pj, encoding="utf-8"))
    A = Image.open(os.path.join(roh, mid + "_grund.png")).convert("RGBA")
    D = np.load(os.path.join(roh, mid + "_lack.npy"))
    w, h = A.size
    r = P["rahmen"]
    # Ausschnitt: Auto (inkl. Schatten) senkrecht mittig, nie über den Rand
    mitte = (r["y0"] + r["y1"]) / 2 * h
    oben = int(round(min(max(mitte - H_ZIEL / 2, 0), h - H_ZIEL)))
    A2 = A.crop((0, oben, w, oben + H_ZIEL))
    # Schattenfänger: reine Schattenpixel (schwarz, halbdurchsichtig) ohne den feinen Schleier über dem ganzen Bild
    # und zum Rand hin weich ausblenden – sonst zeichnet sich auf hellen Szenen ein Rechteck ab.
    q = np.asarray(A2).astype(np.float32) / 255
    schatten = q[..., :3].max(axis=2) < 0.02
    ys, xs = np.mgrid[0:H_ZIEL, 0:w]
    rand = np.minimum.reduce([xs / (w * 0.10), (w - 1 - xs) / (w * 0.10), ys / (H_ZIEL * 0.10),
                              (H_ZIEL - 1 - ys) / (H_ZIEL * 0.06)])
    rand = np.clip(rand, 0, 1)
    rand = rand * rand * (3 - 2 * rand)
    al = q[..., 3]
    al = np.where(schatten, np.clip((al - 0.06) / 0.94, 0, 1) * rand, al)
    q[..., 3] = al
    A2 = Image.fromarray(np.round(q * 255).astype(np.uint8), "RGBA")
    D2 = Image.fromarray(D[oben:oben + H_ZIEL, :], "L")
    pa = os.path.join(ziel, mid + "_grund.webp")
    pd = os.path.join(ziel, mid + "_lack.webp")
    A2.save(pa, "WEBP", quality=86, method=6, alpha_quality=90)
    D2.save(pd, "WEBP", quality=88, method=6)
    neu = {"modell": mid, "breite": w, "hoehe": H_ZIEL, "kamera": P["kamera"], "punkte": {}}
    for n, q in P["punkte"].items():
        neu["punkte"][n] = {"x": q["x"], "y": round((q["y"] * h - oben) / H_ZIEL, 4), "sichtbar": q["sichtbar"]}
    neu["rahmen"] = {"x0": r["x0"], "x1": r["x1"], "y0": round((r["y0"] * h - oben) / H_ZIEL, 4),
                     "y1": round((r["y1"] * h - oben) / H_ZIEL, 4)}
    json.dump(neu, open(os.path.join(ziel, mid + "_punkte.json"), "w", encoding="utf-8"), ensure_ascii=False,
              indent=1)
    gr = os.path.getsize(pa) + os.path.getsize(pd)
    summe += gr
    print(f"{mid:12s} grund {os.path.getsize(pa)//1024:4d} KB  lack {os.path.getsize(pd)//1024:4d} KB  oben {oben}"
          f"  rahmen y {neu['rahmen']['y0']:.3f}..{neu['rahmen']['y1']:.3f}")
print("SUMME Fahrzeuge", summe // 1024, "KB")

if len(sys.argv) > 4:
    rs, zs = sys.argv[3], sys.argv[4]
    os.makedirs(zs, exist_ok=True)
    s2 = 0
    for p in sorted(glob.glob(os.path.join(rs, "*.png"))):
        name = os.path.basename(p)[:-4] + ".webp"
        Image.open(p).convert("RGB").save(os.path.join(zs, name), "WEBP", quality=92, method=6)
        g = os.path.getsize(os.path.join(zs, name))
        s2 += g
        print(f"{name:24s} {g // 1024:4d} KB")
    print("SUMME Szenen", s2 // 1024, "KB")
