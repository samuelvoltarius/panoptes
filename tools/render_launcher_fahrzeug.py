"""0.1.43.4 (Rang 1): Fahrzeugbild für den Panoptes Launcher aus den Modellen des Katalogs rendern.

Im Launcher nur die fünf CC-BY-Vorlagen (seagull, seal, seal-u, dolphin, atto3; 13.11.), Quelle in
MODELLE_LIZENZEN.md. Keine KI-Bilder.

Normal über tools/launcher_fahrzeugbilder.py (eine Modellquelle: manifest.json, "top": true). Aufruf je Modell:
  blender -b --factory-startup -P tools/render_launcher_fahrzeug.py -- <modell.glb> <ausgabe-ordner> <id> <lack-hinweis>

Ergebnis im Ausgabe-Ordner:
  <id>_grund.png  RGBA: Auto mit schwarzem Glanzlack (Reflexe, Glas, Räder, Licht) und weichem Bodenschatten
  <id>_lack.png   Graustufe: Anteil des Lacks bei weißem Lack (B − A). Der Launcher rechnet
                  Bild = Grund + Lack × Lackfarbe, so passt jede Farbe ohne eigenes Rendering.
  <id>_punkte.json  Ankerpunkte (Türen, Fenster, Klappe, Dach, Räder …) in Bildkoordinaten 0..1, mit „sichtbar“
Kamera: Dreiviertel von vorn links (Fahrerseite bei Linkslenker), leicht erhöht. Alle Modelle gleich gerahmt.
"""
import json
import math
import os
import sys

import bpy
import numpy as np
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
GLB, AUS, MID, HINWEIS = argv[0], argv[1], argv[2], (argv[3] if len(argv) > 3 else "body")
BREITE = int(os.environ.get("PN_BREITE", "1200"))
HOEHE = int(os.environ.get("PN_HOEHE", "720"))
PROBEN = int(os.environ.get("PN_PROBEN", "128"))
os.makedirs(AUS, exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=GLB)
scene = bpy.context.scene

autos = [o for o in scene.objects if o.type == "MESH"]

# Weiche Schattierung nach Winkel: die reduzierten Netze wirken sonst facettiert (vor allem im Lack)
GLATT = math.radians(float(os.environ.get("PN_GLATT_GRAD", "35")))
for o in autos:
    # mitgelieferte Normalen (Draco/Decimate) überdecken sonst jede Glättung
    try:
        if o.data.has_custom_normals:
            bpy.context.view_layer.objects.active = o
            o.select_set(True)
            bpy.ops.mesh.customdata_custom_splitnormals_clear()
            o.select_set(False)
    except Exception as ex:
        print("PN_WARN normalen", o.name, ex)
for o in autos:
    o.select_set(True)
if autos:
    bpy.context.view_layer.objects.active = autos[0]
    try:
        bpy.ops.object.shade_smooth_by_angle(angle=GLATT, keep_sharp_edges=True)
    except Exception as ex:
        print("PN_WARN glatt", ex)
        for o in autos:
            for poly in o.data.polygons:
                poly.use_smooth = True
for o in autos:
    o.select_set(False)


def huelle(objs):
    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    for o in objs:
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c)
            lo = Vector(map(min, lo, w))
            hi = Vector(map(max, hi, w))
    return lo, hi


# Alles unter einen Leer-Knoten, auf den Boden stellen und mittig ausrichten
wurzel = bpy.data.objects.new("PN_Auto", None)
scene.collection.objects.link(wurzel)
for o in scene.objects:
    if o.parent is None and o is not wurzel:
        o.parent = wurzel
bpy.context.view_layer.update()
lo, hi = huelle(autos)
wurzel.location = Vector((-(lo.x + hi.x) / 2, -(lo.y + hi.y) / 2, -lo.z))
bpy.context.view_layer.update()
lo, hi = huelle(autos)
groesse = hi - lo
# Längsachse: die längere waagrechte Seite. glTF-Front −Z wird in Blender zu +Y.
laengs_y = groesse.y >= groesse.x
if not laengs_y:
    wurzel.rotation_euler.z = math.radians(90)
    bpy.context.view_layer.update()
    lo, hi = huelle(autos)
    groesse = hi - lo
L, B, H = groesse.y, groesse.x, groesse.z
VORN = 1.0  # +Y ist vorn (glTF −Z)

# ---------------------------------------------------------------- Lack finden
lack = []
# Materialname „bodypaint“ in allen Katalogmodellen; der Hinweis aus dem Manifest meint Netz-Namen und dient nur als Rückfall
SUCHE = ["bodypaint", HINWEIS.lower(), "paint"]
for wort in SUCHE:
    if lack:
        break
    for o in autos:
        for s in o.material_slots:
            m = s.material
            if m and wort in (m.name or "").lower() and m not in lack:
                lack.append(m)
if not lack:
    print("PN_WARN kein Lack-Material fuer Hinweis", HINWEIS)


def lack_farbe(rgb):
    for m in lack:
        if not m.use_nodes:
            m.diffuse_color = (rgb[0], rgb[1], rgb[2], 1.0)
            continue
        for n in m.node_tree.nodes:
            if n.type == "BSDF_PRINCIPLED":
                inp = n.inputs["Base Color"]
                for l in list(inp.links):
                    m.node_tree.links.remove(l)
                inp.default_value = (rgb[0], rgb[1], rgb[2], 1.0)
                n.inputs["Metallic"].default_value = 0.0
                n.inputs["Roughness"].default_value = 0.35
                if "Coat Weight" in n.inputs:
                    n.inputs["Coat Weight"].default_value = 1.0
                    n.inputs["Coat Weight"].default_value = 0.6
                    n.inputs["Coat Roughness"].default_value = 0.12


# ---------------------------------------------------------------- Boden mit Schattenfänger
bpy.ops.mesh.primitive_plane_add(size=max(L, B) * 8, location=(0, 0, 0))
boden = bpy.context.active_object
boden.is_shadow_catcher = True

# ---------------------------------------------------------------- Studio-Welt (für Reflexe im Lack)
welt = bpy.data.worlds.new("PN_Studio")
welt.use_nodes = True
nt = welt.node_tree
for n in list(nt.nodes):
    nt.nodes.remove(n)
tc = nt.nodes.new("ShaderNodeTexCoord")
sep = nt.nodes.new("ShaderNodeSeparateXYZ")
ramp = nt.nodes.new("ShaderNodeValToRGB")
bg = nt.nodes.new("ShaderNodeBackground")
out = nt.nodes.new("ShaderNodeOutputWorld")
nt.links.new(tc.outputs["Generated"], sep.inputs[0])
nt.links.new(sep.outputs["Z"], ramp.inputs["Fac"])
cr = ramp.color_ramp
cr.elements[0].position = 0.40
cr.elements[0].color = (0.03, 0.033, 0.04, 1)
cr.elements[1].position = 0.58
cr.elements[1].color = (0.55, 0.57, 0.6, 1)
e = cr.elements.new(0.9)
e.color = (0.2, 0.21, 0.23, 1)
nt.links.new(ramp.outputs["Color"], bg.inputs["Color"])
bg.inputs["Strength"].default_value = 0.9
nt.links.new(bg.outputs["Background"], out.inputs["Surface"])
scene.world = welt


def licht(name, energie, ort, ziel, groesse_l, farbe=(1, 1, 1)):
    d = bpy.data.lights.new(name, type="AREA")
    d.energy = energie
    d.size = groesse_l
    d.color = farbe
    o = bpy.data.objects.new(name, d)
    o.location = ort
    scene.collection.objects.link(o)
    richtung = Vector(ziel) - Vector(ort)
    o.rotation_euler = richtung.to_track_quat("-Z", "Y").to_euler()
    # Licht nur zum Beleuchten: keine harten Spiegelbilder der Leuchtflächen im Klarlack (sonst ausgebrannte Haube)
    try:
        o.visible_glossy = False
    except Exception:
        pass
    return o


k = max(L, B)
licht("Haupt", 900 * k, (-2.2 * k, 1.6 * k, 2.4 * k), (0, 0, H * 0.5), 3.0 * k)
licht("Kante", 700 * k, (2.0 * k, -1.8 * k, 1.6 * k), (0, 0, H * 0.6), 2.0 * k, (0.85, 0.92, 1.0))
licht("Oben", 500 * k, (0, 0, 3.0 * k), (0, 0, 0), 2.5 * k)

# ---------------------------------------------------------------- Kamera: dreiviertel vorn links
cam_d = bpy.data.cameras.new("PN_Kamera")
cam_d.lens = 50
cam_d.sensor_width = 36
cam = bpy.data.objects.new("PN_Kamera", cam_d)
scene.collection.objects.link(cam)
scene.camera = cam
WINKEL = math.radians(38)      # von vorn Richtung links (−X)
HOCH = math.radians(11)
ziel = Vector((0, L * 0.03, H * 0.42))
# Abstand so, dass alle Modelle gleich groß wirken: Länge passt in ~80 % der Bildbreite
fov = 2 * math.atan(cam_d.sensor_width / 2 / cam_d.lens)
abstand = (math.hypot(L, B) / 2) / math.tan(fov / 2) * 1.12
richt = Vector((-math.sin(WINKEL) * math.cos(HOCH), math.cos(WINKEL) * math.cos(HOCH), math.sin(HOCH)))
cam.location = ziel + richt * abstand
cam.rotation_euler = (ziel - cam.location).to_track_quat("-Z", "Y").to_euler()

# ---------------------------------------------------------------- Rendern
scene.render.engine = "CYCLES"
scene.cycles.samples = PROBEN
scene.cycles.use_denoising = True
try:
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for typ in ("OPTIX", "CUDA"):
        try:
            prefs.compute_device_type = typ
            prefs.get_devices()
            for dv in prefs.devices:
                dv.use = True
            scene.cycles.device = "GPU"
            break
        except Exception:
            continue
except Exception:
    pass
scene.render.film_transparent = True
scene.render.resolution_x = BREITE
scene.render.resolution_y = HOEHE
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.color_depth = "8"
# „Standard“ (reines sRGB): nur so lässt sich Grund + Lack × Farbe im Launcher linear exakt zusammensetzen
scene.view_settings.view_transform = "Standard"
scene.view_settings.look = "None"
scene.view_settings.exposure = float(os.environ.get("PN_BELICHTUNG", "0.0"))


def render(pfad):
    scene.render.filepath = pfad
    bpy.ops.render.render(write_still=True)


grund = os.path.join(AUS, MID + "_grund.png")
weiss = os.path.join(AUS, MID + "_weiss.png")
lack_farbe((0.0, 0.0, 0.0))
render(grund)
lack_farbe((0.8, 0.8, 0.8))
render(weiss)


def lies(p):
    im = bpy.data.images.load(p)
    a = np.array(im.pixels[:], dtype=np.float32).reshape(im.size[1], im.size[0], 4)[::-1]
    bpy.data.images.remove(im)
    return a


A = lies(grund)
W = lies(weiss)
# Lack-Anteil linear: (lin(W) − lin(A)) / 0.8, gespeichert mit sRGB-Kurve (genauer im Dunkeln)
def lin(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def srgb(x):
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * np.power(np.maximum(x, 0), 1 / 2.4) - 0.055)


d = np.clip((lin(W[..., :3]) - lin(A[..., :3])).mean(axis=2) / 0.8, 0, 1)
np.save(os.path.join(AUS, MID + "_lack.npy"), np.round(srgb(d) * 255).astype(np.uint8))
os.remove(weiss)


# ---------------------------------------------------------------- Ankerpunkte (Hüllkörper-Näherung, keine Teilenamen nötig)
def p(xa, ya, za):
    """xa: −1 links … +1 rechts, ya: −1 hinten … +1 vorn, za: 0 Boden … 1 Dach."""
    return Vector((xa * B / 2, ya * L / 2, za * H))


PUNKTE = {
    "tuer_vl": p(-1, 0.22, 0.45), "tuer_hl": p(-1, -0.22, 0.45),
    "tuer_vr": p(1, 0.22, 0.45), "tuer_hr": p(1, -0.22, 0.45),
    "fenster_vl": p(-0.92, 0.2, 0.72), "fenster_hl": p(-0.92, -0.25, 0.72),
    "fenster_vr": p(0.92, 0.2, 0.72), "fenster_hr": p(0.92, -0.25, 0.72),
    "dach": p(0, -0.05, 1.0), "haube": p(0, 0.78, 0.62), "kofferraum": p(0, -0.97, 0.62),
    "rad_vl": p(-1, 0.62, 0.18), "rad_hl": p(-1, -0.62, 0.18), "rad_vr": p(1, 0.62, 0.18), "rad_hr": p(1, -0.62, 0.18),
    "mitte": p(0, 0, 0.5),
}
SICHTBAR_SEITE = {"vl", "hl"}  # Kamera links vorn
punkte = {}
for name, v in PUNKTE.items():
    q = world_to_camera_view(scene, cam, v)
    seite = name.split("_")[-1]
    sichtbar = (seite in SICHTBAR_SEITE) or name in ("dach", "haube", "mitte")
    punkte[name] = {"x": round(q.x, 4), "y": round(1 - q.y, 4), "sichtbar": sichtbar}
# Umriss des Autos im Bild (für Plaketten-Platz und Rahmen)
alpha = A[..., 3]
ys, xs = np.where(alpha > 0.5)
rahmen = {"x0": round(xs.min() / BREITE, 4), "x1": round(xs.max() / BREITE, 4),
          "y0": round(ys.min() / HOEHE, 4), "y1": round(ys.max() / HOEHE, 4)} if len(xs) else None
json.dump({"modell": MID, "breite": BREITE, "hoehe": HOEHE, "kamera": "dreiviertel_vorn_links",
           "masse_m": {"laenge": round(L, 3), "breite": round(B, 3), "hoehe": round(H, 3)},
           "rahmen": rahmen, "punkte": punkte, "lack_materialien": [m.name for m in lack]},
          open(os.path.join(AUS, MID + "_punkte.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("PN_FERTIG", MID, L, B, H, [m.name for m in lack])
