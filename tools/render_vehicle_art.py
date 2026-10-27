"""0.1.20: Fahrzeugbilder für die native Startkarte aus den CC-BY-Modellen des Katalogs rendern (Blender, ohne Fenster).

Ersetzt die früheren vehicle_*.webp (Pressefotos mit Logo, ohne Lizenz – Koordination 27.10.2026: entfernen).
Aufruf (je Modell):
  blender -b --factory-startup -P tools/render_vehicle_art.py -- <modell.glb> <ausgabe.png> [lack-hex]
Kamera 3/4 von vorn links, transparenter Hintergrund, weiches Studiolicht. Namensnennung steht im Manifest
(attribution) und in THIRD_PARTY_NOTICES.md; die Bilder sind „verändert für Panoptes“.
"""
import math
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
GLB, AUS = argv[0], argv[1]
LACK = argv[2] if len(argv) > 2 else None

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=GLB)

objs = [o for o in bpy.context.scene.objects if o.type == "MESH"]
lo = Vector((1e9, 1e9, 1e9))
hi = Vector((-1e9, -1e9, -1e9))
for o in objs:
    for c in o.bound_box:
        w = o.matrix_world @ Vector(c)
        lo = Vector(map(min, lo, w))
        hi = Vector(map(max, hi, w))
mitte = (lo + hi) / 2
groesse = max((hi - lo).x, (hi - lo).y, (hi - lo).z)

if LACK:
    r, g, b = (int(LACK[i:i + 2], 16) / 255.0 for i in (1, 3, 5))
    lin = [((c + 0.055) / 1.055) ** 2.4 if c > 0.04045 else c / 12.92 for c in (r, g, b)]
    for o in objs:
        for slot in o.material_slots:
            m = slot.material
            if m and "body" in (m.name or "").lower() and m.use_nodes:
                bsdf = m.node_tree.nodes.get("Principled BSDF")
                if bsdf:
                    bsdf.inputs["Base Color"].default_value = (lin[0], lin[1], lin[2], 1.0)

scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in [e.identifier for e in
    bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE"
scene.render.film_transparent = True
scene.render.resolution_x = 1200
scene.render.resolution_y = 800
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.view_settings.view_transform = "Standard"

welt = bpy.data.worlds.new("Welt")
welt.use_nodes = True
welt.node_tree.nodes["Background"].inputs[0].default_value = (0.75, 0.8, 0.85, 1.0)
welt.node_tree.nodes["Background"].inputs[1].default_value = 0.8
scene.world = welt

def licht(name, energie, ort, groesse_l):
    d = bpy.data.lights.new(name, "AREA")
    d.energy = energie
    d.size = groesse_l
    o = bpy.data.objects.new(name, d)
    scene.collection.objects.link(o)
    o.location = ort
    richtung = mitte - o.location
    o.rotation_euler = richtung.to_track_quat("-Z", "Y").to_euler()

# Achse: glTF importiert mit Y vorn/hinten; Fahrzeuge liegen meist entlang der längsten waagrechten Achse
licht("Key", 520 * groesse, mitte + Vector((-1.2, -1.6, 1.4)) * groesse, groesse * 1.5)
licht("Fill", 230 * groesse, mitte + Vector((1.6, -0.6, 0.8)) * groesse, groesse * 2.0)
licht("Rim", 300 * groesse, mitte + Vector((0.4, 1.8, 1.2)) * groesse, groesse * 1.5)

cam_d = bpy.data.cameras.new("Kamera")
cam_d.lens = 50
cam = bpy.data.objects.new("Kamera", cam_d)
scene.collection.objects.link(cam)
laenge_x = (hi - lo).x >= (hi - lo).y
winkel = math.radians(35)
abstand = groesse * 2.1
if laenge_x:
    pos = Vector((-math.cos(winkel) * abstand, -math.sin(winkel) * abstand, groesse * 0.32))
else:
    pos = Vector((-math.sin(winkel) * abstand, math.cos(winkel) * abstand, groesse * 0.32))
cam.location = mitte + pos
cam.rotation_euler = (mitte + Vector((0, 0, -(hi - lo).z * 0.08)) - cam.location).to_track_quat("-Z", "Y").to_euler()
scene.camera = cam

scene.render.filepath = AUS
bpy.ops.render.render(write_still=True)
print("RENDER_OK", AUS)
