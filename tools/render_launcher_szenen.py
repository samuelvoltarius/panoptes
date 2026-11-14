"""0.1.43.4 (Rang 1): eigene Szenen-Hintergründe für die Fahrzeug-Startseite des Launchers (Blender, prozedural).

Keine Fremdbilder, keine KI-Bilder, keine Texturen von außen: alles aus Grundkörpern, Rauschen und Himmelsmodell.
Aufruf: blender -b --factory-startup -P tools/render_launcher_szenen.py -- <szene> <tag|nacht> <ausgabe.png>
Szenen: studio, stadt, berge, kueste, tiefgarage, ladepark. Der untere Bildteil bleibt ruhig (dort steht das Auto),
der Horizont liegt bei etwa 45 % der Höhe, oben bleibt Platz für Karten.
"""
import math
import os
import random
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
SZENE, ZEIT, AUS = argv[0], argv[1], argv[2]
NACHT = ZEIT == "nacht"
BREITE = int(os.environ.get("PN_BREITE", "1600"))
HOEHE = int(os.environ.get("PN_HOEHE", "1000"))
PROBEN = int(os.environ.get("PN_PROBEN", "160"))
rnd = random.Random(hash((SZENE, "panoptes")) & 0xffff)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
C = scene.collection


# ---------------------------------------------------------------- Bausteine
def mat(name, farbe, rau=0.5, metall=0.0, leucht=None, staerke=0.0, coat=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*farbe, 1)
    b.inputs["Roughness"].default_value = rau
    b.inputs["Metallic"].default_value = metall
    if coat and "Coat Weight" in b.inputs:
        b.inputs["Coat Weight"].default_value = coat
    if leucht:
        b.inputs["Emission Color"].default_value = (*leucht, 1)
        b.inputs["Emission Strength"].default_value = staerke
    return m


def rausch_boden(m, skala=6.0, rau_a=0.25, rau_b=0.6, dunkel=0.75):
    """Leichte Unregelmäßigkeit (Rauheit/Helligkeit) für Asphalt/Beton, ohne Textur von außen."""
    nt = m.node_tree
    b = nt.nodes["Principled BSDF"]
    n = nt.nodes.new("ShaderNodeTexNoise")
    n.inputs["Scale"].default_value = skala
    n.inputs["Detail"].default_value = 8.0
    r = nt.nodes.new("ShaderNodeMapRange")
    r.inputs["To Min"].default_value = rau_a
    r.inputs["To Max"].default_value = rau_b
    nt.links.new(n.outputs["Fac"], r.inputs["Value"])
    nt.links.new(r.outputs["Result"], b.inputs["Roughness"])
    mix = nt.nodes.new("ShaderNodeMix")
    mix.data_type = "RGBA"
    basis = b.inputs["Base Color"].default_value[:]
    mix.inputs["A"].default_value = basis
    mix.inputs["B"].default_value = (basis[0] * dunkel, basis[1] * dunkel, basis[2] * dunkel, 1)
    nt.links.new(n.outputs["Fac"], mix.inputs["Factor"])
    nt.links.new(mix.outputs["Result"], b.inputs["Base Color"])


def wuerfel(ort, masse, m, name="Kasten"):
    bpy.ops.mesh.primitive_cube_add(size=1, location=ort)
    o = bpy.context.active_object
    o.name = name
    o.scale = masse
    o.data.materials.append(m)
    return o


def ebene(groesse, ort, m, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_plane_add(size=groesse, location=ort, rotation=rot)
    o = bpy.context.active_object
    o.data.materials.append(m)
    return o


def flaechenlicht(energie, ort, ziel, groesse, farbe=(1, 1, 1), form="RECTANGLE", sx=None, sy=None):
    d = bpy.data.lights.new("L", type="AREA")
    d.energy = energie
    d.color = farbe
    d.shape = form
    d.size = groesse
    if sx:
        d.size, d.size_y = sx, sy
    o = bpy.data.objects.new("L", d)
    o.location = ort
    C.objects.link(o)
    o.rotation_euler = (Vector(ziel) - Vector(ort)).to_track_quat("-Z", "Y").to_euler()
    return o


def punktlicht(energie, ort, farbe=(1, 1, 1), radius=0.1):
    d = bpy.data.lights.new("P", type="POINT")
    d.energy = energie
    d.color = farbe
    d.shadow_soft_size = radius
    o = bpy.data.objects.new("P", d)
    o.location = ort
    C.objects.link(o)


def himmel(sonne_hoehe_grad, staerke=1.0, sonne_drehung_grad=200, luft=1.0):
    w = bpy.data.worlds.new("Himmel")
    w.use_nodes = True
    nt = w.node_tree
    sky = nt.nodes.new("ShaderNodeTexSky")
    for typ in ("MULTIPLE_SCATTERING", "SINGLE_SCATTERING", "NISHITA"):
        try:
            sky.sky_type = typ
            break
        except Exception:
            continue
    try:
        sky.sun_elevation = math.radians(sonne_hoehe_grad)
        sky.sun_rotation = math.radians(sonne_drehung_grad)
        sky.air_density = luft
    except Exception:
        pass
    bg = nt.nodes["Background"]
    nt.links.new(sky.outputs["Color"], bg.inputs["Color"])
    bg.inputs["Strength"].default_value = staerke
    scene.world = w


def farbwelt(oben, unten, staerke=1.0):
    w = bpy.data.worlds.new("Welt")
    w.use_nodes = True
    nt = w.node_tree
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    nt.links.new(tc.outputs["Generated"], sep.inputs[0])
    nt.links.new(sep.outputs["Z"], ramp.inputs["Fac"])
    ramp.color_ramp.elements[0].position = 0.5
    ramp.color_ramp.elements[0].color = (*unten, 1)
    ramp.color_ramp.elements[1].position = 0.75
    ramp.color_ramp.elements[1].color = (*oben, 1)
    bg = nt.nodes["Background"]
    nt.links.new(ramp.outputs["Color"], bg.inputs["Color"])
    bg.inputs["Strength"].default_value = staerke
    scene.world = w


def sonne(hoehe_grad, drehung_grad, staerke, farbe=(1, 0.95, 0.88)):
    d = bpy.data.lights.new("Sonne", type="SUN")
    d.energy = staerke
    d.color = farbe
    d.angle = math.radians(1.5)
    o = bpy.data.objects.new("Sonne", d)
    o.rotation_euler = (math.radians(90 - hoehe_grad), 0, math.radians(drehung_grad))
    C.objects.link(o)


def nebel(dichte, farbe=(0.6, 0.65, 0.7)):
    """Volumen-Würfel um die Szene für Tiefe (Luftperspektive). Am Tag schwächer, sonst wirkt alles ausgewaschen."""
    if not NACHT:
        dichte *= 0.3
    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 60, 20))
    o = bpy.context.active_object
    o.scale = (400, 200, 60)
    m = bpy.data.materials.new("Nebel")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.remove(nt.nodes["Principled BSDF"])
    v = nt.nodes.new("ShaderNodeVolumePrincipled")
    v.inputs["Density"].default_value = dichte
    v.inputs["Color"].default_value = (*farbe, 1)
    nt.links.new(v.outputs["Volume"], nt.nodes["Material Output"].inputs["Volume"])
    o.data.materials.append(m)


def bergkamm(abstand, hoehe, breite, farbe, saat, stufen=90):
    """Silhouette als ausgefranste Fläche (eigenes Rauschen), weit hinten."""
    r = random.Random(saat)
    verts, faces = [], []
    pts = []
    h = 0.5
    for i in range(stufen + 1):
        x = -breite / 2 + breite * i / stufen
        h = max(0.15, min(1.0, h + r.uniform(-0.12, 0.12)))
        pts.append((x, h * hoehe * (0.6 + 0.4 * math.sin(i / stufen * math.pi))))
    for i, (x, y) in enumerate(pts):
        verts.append((x, abstand, -2))
        verts.append((x, abstand, y))
    for i in range(stufen):
        a = i * 2
        faces.append((a, a + 2, a + 3, a + 1))
    me = bpy.data.meshes.new("Kamm")
    me.from_pydata(verts, [], faces)
    o = bpy.data.objects.new("Kamm", me)
    C.objects.link(o)
    o.data.materials.append(mat("Kamm", farbe, 0.9))


# ---------------------------------------------------------------- Kamera (Augenhöhe, Horizont ~45 %)
cam_d = bpy.data.cameras.new("K")
cam_d.lens = 30
cam = bpy.data.objects.new("K", cam_d)
C.objects.link(cam)
scene.camera = cam
cam.location = (0, -9.0, 1.25)
cam.rotation_euler = (math.radians(87.5), 0, 0)
# Bühne: hier steht später (als eigene Ebene) das Auto
BUEHNE = Vector((0, 0, 0))

# ---------------------------------------------------------------- Szenen
if SZENE == "studio":
    # Hohlkehle: Boden geht weich in die Rückwand über
    bpy.ops.mesh.primitive_plane_add(size=1)
    o = bpy.context.active_object
    me = o.data
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.object.mode_set(mode="OBJECT")
    verts, faces = [], []
    n = 40
    for i in range(n + 1):
        t = i / n
        ang = t * math.pi / 2
        y = 6 + 5 * math.sin(ang) if t > 0 else -30
        z = 5 - 5 * math.cos(ang) if t > 0 else 0
        verts += [(-40, y, z), (40, y, z)]
    verts += [(-40, 11, 30), (40, 11, 30)]
    for i in range(n + 1):
        a = i * 2
        faces.append((a, a + 1, a + 3, a + 2))
    bpy.data.objects.remove(o)
    me = bpy.data.meshes.new("Kehle")
    me.from_pydata(verts, [], faces)
    k = bpy.data.objects.new("Kehle", me)
    C.objects.link(k)
    for p in k.data.polygons:
        p.use_smooth = True
    grund = (0.55, 0.57, 0.6) if not NACHT else (0.035, 0.038, 0.045)
    k.data.materials.append(mat("Kehle", grund, 0.45 if not NACHT else 0.3))
    farbwelt((0.02, 0.02, 0.025), (0.01, 0.01, 0.012), 0.2)
    if NACHT:
        flaechenlicht(2600, (0, 1.5, 6.5), (0, 1.5, 0), 7, (0.8, 0.9, 1.0), "DISK")
        flaechenlicht(250, (0, 10.5, 6), (0, 11, 4), 18, (0.25, 0.55, 0.65), "RECTANGLE", 30, 0.6)
    else:
        flaechenlicht(5000, (0, -2, 9), (0, 2, 0), 14, (1, 1, 1), "RECTANGLE", 16, 8)
        flaechenlicht(1500, (-12, 4, 6), (0, 4, 1), 6)

elif SZENE == "stadt":
    asphalt = mat("Asphalt", (0.05, 0.05, 0.055), 0.18 if NACHT else 0.5)
    rausch_boden(asphalt, 3.0, 0.08 if NACHT else 0.35, 0.45 if NACHT else 0.75, 0.6)
    ebene(400, (0, 100, 0), asphalt)
    fass = mat("Fassade", (0.035, 0.037, 0.042) if NACHT else (0.36, 0.37, 0.39), 0.75)
    fenster_warm = mat("FensterW", (0.1, 0.08, 0.05), 0.3, leucht=(1.0, 0.72, 0.42), staerke=6.0 if NACHT else 0.0)
    fenster_kalt = mat("FensterK", (0.05, 0.07, 0.09), 0.1, leucht=(0.55, 0.85, 1.0), staerke=5.0 if NACHT else 0.0)
    glas = mat("Glas", (0.08, 0.1, 0.12), 0.05, metall=0.6)
    for seite in (-1, 1):
        y = 14
        while y < 160:
            b = rnd.uniform(6, 14)
            t = rnd.uniform(8, 14)
            h = rnd.uniform(10, 46)
            x = seite * (14 + t / 2 + rnd.uniform(0, 4))
            wuerfel((x, y + b / 2, h / 2), (t, b, h), fass, "Haus")
            # Fensterbänder auf der Straßenseite
            fx = x - seite * (t / 2 + 0.02)
            zeile = 2.0
            while zeile < h - 1:
                spalte = y + 0.8
                while spalte < y + b - 0.8:
                    if rnd.random() < (0.45 if NACHT else 0.9):
                        m = glas if not NACHT else (fenster_warm if rnd.random() < 0.7 else fenster_kalt)
                        bpy.ops.mesh.primitive_plane_add(size=1, location=(fx, spalte, zeile),
                                                         rotation=(0, math.radians(90), 0))
                        f = bpy.context.active_object
                        f.scale = (0.85, 0.8, 1)
                        f.data.materials.append(m)
                    spalte += 1.5
                zeile += 3.2
            y += b + rnd.uniform(1, 6)
    # Straßenlaternen
    mast = mat("Mast", (0.08, 0.08, 0.09), 0.4, 0.8)
    lampe = mat("Lampe", (1, 1, 1), 0.2, leucht=(1.0, 0.85, 0.65), staerke=25 if NACHT else 0)
    for i in range(7):
        for seite in (-1, 1):
            y = 8 + i * 18 + (9 if seite > 0 else 0)
            wuerfel((seite * 11.5, y, 4), (0.15, 0.15, 8), mast, "Mast")
            wuerfel((seite * 10.6, y, 7.9), (1.8, 0.35, 0.12), lampe, "Lampe")
            if NACHT:
                punktlicht(1100, (seite * 10.6, y, 7.6), (1.0, 0.82, 0.6), 0.4)
    if NACHT:
        farbwelt((0.004, 0.006, 0.012), (0.05, 0.06, 0.09), 1.0)
        nebel(0.012, (0.5, 0.55, 0.7))
    else:
        himmel(18, 0.35, 160)
        sonne(18, 160, 3.0)
        nebel(0.004, (0.8, 0.85, 0.9))

elif SZENE == "berge":
    strasse = mat("Strasse", (0.06, 0.06, 0.065), 0.55)
    rausch_boden(strasse, 4, 0.4, 0.8, 0.7)
    gras = mat("Wiese", (0.08, 0.12, 0.05) if not NACHT else (0.02, 0.025, 0.03), 0.9)
    rausch_boden(gras, 0.8, 0.8, 1.0, 0.55)
    ebene(600, (0, 250, -0.02), gras)
    s = ebene(1, (0, 140, 0.0), strasse)
    s.scale = (9, 340, 1)   # beginnt hinter der Kamera: kein sichtbares Straßenende
    linie = mat("Linie", (0.8, 0.8, 0.78), 0.5)
    for i in range(40):
        l = ebene(1, (0, 6 + i * 7, 0.005), linie)
        l.scale = (0.14, 3.0, 1)
    if NACHT:
        farbwelt((0.004, 0.007, 0.018), (0.06, 0.09, 0.16), 0.9)
        kamm_f = [(0.03, 0.04, 0.07), (0.015, 0.02, 0.035), (0.006, 0.008, 0.014)]
        sonne(8, 180, 0.15, (0.6, 0.7, 1.0))
    else:
        himmel(12, 0.4, 200)
        sonne(12, 200, 3.5, (1, 0.88, 0.72))
        kamm_f = [(0.45, 0.52, 0.62), (0.25, 0.3, 0.36), (0.1, 0.13, 0.12)]
    for i, (ab, ho) in enumerate([(420, 120), (300, 80), (200, 45)]):
        bergkamm(ab, ho, 900, kamm_f[i], 31 + i)
    nebel(0.002 if NACHT else 0.0025, (0.55, 0.62, 0.75) if not NACHT else (0.2, 0.25, 0.4))

elif SZENE == "kueste":
    wasser = mat("Wasser", (0.01, 0.03, 0.05), 0.04, coat=0.0)
    w = ebene(1, (0, 260, -0.4), wasser)
    w.scale = (1200, 500, 1)
    mod = w.modifiers.new("Wellen", "OCEAN")
    try:
        mod.resolution = 10
        mod.size = 2
        mod.wave_scale = 0.25
        mod.spatial_size = 60
    except Exception:
        w.modifiers.remove(mod)
    promenade = mat("Promenade", (0.32, 0.31, 0.29) if not NACHT else (0.04, 0.04, 0.045), 0.55)
    rausch_boden(promenade, 2, 0.4 if not NACHT else 0.15, 0.8 if not NACHT else 0.5, 0.75)
    p = ebene(1, (0, 2, 0), promenade)
    p.scale = (200, 26, 1)
    gelaender = mat("Gelaender", (0.6, 0.62, 0.65), 0.3, 0.9)
    wuerfel((0, 14.5, 1.0), (200, 0.06, 0.06), gelaender, "Handlauf")
    for i in range(-40, 41):
        wuerfel((i * 2.5, 14.5, 0.5), (0.05, 0.05, 1.0), gelaender, "Pfosten")
    if NACHT:
        farbwelt((0.003, 0.005, 0.014), (0.05, 0.07, 0.13), 0.8)
        mond = mat("Mond", (1, 1, 1), 0.5, leucht=(0.85, 0.9, 1.0), staerke=12)
        bpy.ops.mesh.primitive_uv_sphere_add(radius=6, location=(-90, 420, 70))
        bpy.context.active_object.data.materials.append(mond)
        sonne(20, 160, 0.25, (0.65, 0.75, 1.0))
        lampe = mat("Lampe", (1, 1, 1), 0.2, leucht=(1.0, 0.8, 0.55), staerke=30)
        for i in range(-4, 5):
            wuerfel((i * 16, 13.8, 3.6), (0.5, 0.5, 0.2), lampe, "Lampe")
            punktlicht(900, (i * 16, 13.8, 3.3), (1.0, 0.8, 0.55), 0.3)
    else:
        himmel(6, 0.45, 180)
        sonne(6, 180, 3.0, (1.0, 0.8, 0.6))
    nebel(0.0015, (0.7, 0.75, 0.85) if not NACHT else (0.2, 0.25, 0.4))

elif SZENE == "tiefgarage":
    beton = mat("Beton", (0.32, 0.32, 0.31) if not NACHT else (0.2, 0.2, 0.2), 0.35)
    rausch_boden(beton, 1.5, 0.2, 0.6, 0.8)
    ebene(200, (0, 60, 0), beton)
    decke = mat("Decke", (0.25, 0.25, 0.25), 0.8)
    ebene(200, (0, 60, 3.2), decke, (math.radians(180), 0, 0))
    saeule = mat("Saeule", (0.45, 0.45, 0.44), 0.6)
    streifen = mat("Streifen", (0.9, 0.75, 0.1) if not NACHT else (0.1, 0.55, 0.65), 0.5)
    for y in range(6, 80, 9):
        for x in (-7.5, 7.5, -16, 16):
            wuerfel((x, y, 1.6), (0.7, 0.7, 3.2), saeule, "Saeule")
            wuerfel((x, y, 0.5), (0.72, 0.72, 0.25), streifen, "Band")
    lichtband = mat("Lichtband", (1, 1, 1), 0.2, leucht=(0.95, 0.98, 1.0) if not NACHT else (0.7, 0.92, 1.0),
                    staerke=14 if not NACHT else 10)
    for x in (-4, 4):
        l = wuerfel((x, 45, 3.15), (0.12, 80, 0.05), lichtband, "Lichtband")
    linie = mat("Markierung", (0.85, 0.85, 0.82), 0.6)
    for y in range(4, 70, 6):
        for x in (-10.5, 10.5):
            m = ebene(1, (x, y, 0.004), linie)
            m.scale = (5, 0.1, 1)
    farbwelt((0.01, 0.01, 0.012), (0.01, 0.01, 0.012), 0.1)
    for y in range(2, 80, 12):
        flaechenlicht(500 if not NACHT else 260, (0, y, 3.0), (0, y, 0), 2, (0.95, 0.98, 1.0), "RECTANGLE", 9, 0.4)

elif SZENE == "ladepark":
    belag = mat("Belag", (0.12, 0.12, 0.125) if not NACHT else (0.04, 0.04, 0.045), 0.45 if not NACHT else 0.2)
    rausch_boden(belag, 3, 0.2 if NACHT else 0.4, 0.6 if NACHT else 0.85, 0.7)
    ebene(400, (0, 100, 0), belag)
    dach = mat("Dach", (0.85, 0.86, 0.88), 0.4, 0.2)
    wuerfel((0, 9, 5.2), (34, 10, 0.35), dach, "Dach")
    leiste = mat("Leiste", (1, 1, 1), 0.2, leucht=(0.5, 0.9, 1.0), staerke=8 if NACHT else 1.5)
    wuerfel((0, 4.0, 5.0), (34, 0.12, 0.08), leiste, "Leiste")
    stuetze = mat("Stuetze", (0.7, 0.71, 0.73), 0.35, 0.7)
    for x in (-15, 15):
        wuerfel((x, 9, 2.6), (0.35, 0.35, 5.2), stuetze, "Stuetze")
    saeule = mat("Ladesaeule", (0.9, 0.9, 0.92), 0.3)
    anzeige = mat("Anzeige", (0.02, 0.03, 0.04), 0.1, leucht=(0.4, 0.95, 0.75), staerke=4)
    for x in (-10, -3.5, 3.5, 10):
        wuerfel((x, 11.5, 1.0), (0.6, 0.4, 2.0), saeule, "Saeule")
        wuerfel((x, 11.29, 1.45), (0.4, 0.02, 0.3), anzeige, "Anzeige")
    linie = mat("Markierung", (0.8, 0.82, 0.85), 0.6)
    for x in (-13.25, -6.75, 0, 6.75, 13.25):
        m = ebene(1, (x, 6, 0.004), linie)
        m.scale = (0.1, 9, 1)
    gruen = mat("Hecke", (0.04, 0.09, 0.04) if not NACHT else (0.01, 0.02, 0.015), 0.9)
    wuerfel((0, 17, 0.6), (60, 1.2, 1.2), gruen, "Hecke")
    if NACHT:
        farbwelt((0.004, 0.006, 0.014), (0.04, 0.05, 0.08), 0.8)
        flaechenlicht(1200, (0, 8, 5.0), (0, 8, 0), 2, (0.8, 0.93, 1.0), "RECTANGLE", 28, 6)
    else:
        himmel(35, 0.4, 210)
        sonne(35, 210, 3.5)
    nebel(0.003, (0.75, 0.8, 0.88) if not NACHT else (0.25, 0.3, 0.45))
else:
    raise SystemExit("unbekannte Szene " + SZENE)

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
scene.render.resolution_x = BREITE
scene.render.resolution_y = HOEHE
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGB"
try:
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.look = "AgX - Medium High Contrast" if NACHT else "AgX - Base Contrast"
except Exception:
    pass
scene.view_settings.exposure = float(os.environ.get("PN_BELICHTUNG", "0" if NACHT else "-0.4"))
scene.render.filepath = AUS
bpy.ops.render.render(write_still=True)
print("PN_SZENE_FERTIG", SZENE, ZEIT)
