# 3D-Automodelle – Lizenzen

Diese Modelle werden unter https://byd.serveone.at/tiles/models/ ausgeliefert und in Panoptes und im EV-Cockpit angezeigt.

## 2024 BYD Seal Performance AWD
- Autor: [Nazh Design](https://sketchfab.com/NazhDesign)
- Quelle: https://sketchfab.com/3d-models/2024-byd-seal-performance-awd-96ec5c34852f4c3090a755d4445f5e98
- Lizenz: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Änderungen: Für Panoptes angepasst: Herstellerlogos entfernt, Netz reduziert (Decimate), Farben zu Materialien gebündelt (Lack = „bodypaint“), Draco-komprimiert, Ausrichtung Front -Z.

## 2023 BYD Song Plus
- Autor: [Nazh Design](https://sketchfab.com/NazhDesign)
- Quelle: https://sketchfab.com/3d-models/2023-byd-song-plus-46546075cf4a40d4b75f010685168b9f
- Lizenz: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Änderungen: Für Panoptes angepasst: Herstellerlogos entfernt, Netz reduziert (Decimate), Farben zu Materialien gebündelt (Lack = „bodypaint“), Draco-komprimiert, Ausrichtung Front -Z.

## 2024 BYD Dolphin Premium Long Range
- Autor: [Nazh Design](https://sketchfab.com/NazhDesign)
- Quelle: https://sketchfab.com/3d-models/2024-byd-dolphin-premium-long-range-8d30880667524cf3abb9e3e66c5299b5
- Lizenz: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Änderungen: Für Panoptes angepasst: Herstellerlogos entfernt, Netz reduziert (Decimate), Farben zu Materialien gebündelt (Lack = „bodypaint“), Draco-komprimiert, Ausrichtung Front -Z.

## 2024 BYD Atto 3 Superior Extended Range
- Autor: [Nazh Design](https://sketchfab.com/NazhDesign)
- Quelle: https://sketchfab.com/3d-models/2024-byd-atto-3-superior-extended-range-692058cf280d49408c2fb0a10157343e
- Lizenz: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Änderungen: Für Panoptes angepasst: Herstellerlogos entfernt, Netz reduziert (Decimate), Farben zu Materialien gebündelt (Lack = „bodypaint“), Draco-komprimiert, Ausrichtung Front -Z.

## 2023 BYD Seagull
- Autor: [Nazh Design](https://sketchfab.com/NazhDesign)
- Quelle: https://sketchfab.com/3d-models/2023-byd-seagull-33660cc157df4e2485ae37e8235041b7
- Lizenz: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Änderungen: Für Panoptes angepasst: Herstellerlogos entfernt, Netz reduziert (Decimate), Farben zu Materialien gebündelt (Lack = „bodypaint“), Draco-komprimiert, Ausrichtung Front -Z.

Herstellernamen und Fahrzeugbezeichnungen dienen nur der Zuordnung; Logos wurden aus den Modellen entfernt.

## Panoptes-Eigenbau (CC0 1.0)

Eigene Modelle des Panoptes-Projekts, in Blender gebaut (Skript build_blender.py), nach Herstellermaßen,
ohne Logos und ohne fremde Vorlagen. Lizenz: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) – frei nutzbar, keine Namensnennung nötig.

- BYD Sealion 7 (Panoptes-Eigenbau)
- BYD Seal U DM-i (Panoptes-Eigenbau)
- BYD Atto 3 Evo (Panoptes-Eigenbau)
- BYD Atto 2 (Panoptes-Eigenbau)
- BYD Han (Panoptes-Eigenbau)
- BYD Tang (Panoptes-Eigenbau)
- BYD M6 (Panoptes-Eigenbau)
- BYD Seal 5 DM-i (Panoptes-Eigenbau)
- BYD Shark (Panoptes-Eigenbau; eigene Pickup-Geometrie, kein Seal-Ersatzmodell)
- BYD Seal 6 GT (Panoptes-Eigenbau)
- BYD Seal 6 DM-i Touring (Panoptes-Eigenbau)

Maßquellen für die drei Ergänzungen (nur Maße/technische Daten, keine fremde Geometrie oder Texturen übernommen):

- Shark: https://media.byd.com/section/byd-shark/?lang=eng
- Seal 6 GT / Seal 06 GT: https://prod.byd.com/cn/ocean-home/models/haibao/26-haibao-06-GT
- Seal 6 DM-i Touring: https://media.byd.com/byd-launches-seal-6-dm-i-sedan-and-wagon-bringing-the-freedom-of-super-hybrid-technology/?lang=eng

## Fahrzeugbilder der Startkarte (seit 0.1.20)

Die Bilder `vehicle_*.webp` sind für Panoptes mit `tools/render_vehicle_art.py` (Blender) aus den oben genannten CC-BY-4.0-Modellen von Nazh Design gerendert und zugeschnitten (verändert): Seagull/Dolphin Surf, Seal, Seal U („2023 BYD Song Plus“), Dolphin, Atto 3. Die neutrale Silhouette (`vehicle_fallback.webp`) ist aus dem Seal-U-Render abgeleitet. Lizenz: CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).

## Launcher-Fahrzeugbild (0.1.43.4)

Der Panoptes Launcher zeigt eine Dreiviertel-Ansicht des eigenen Autos, gerendert mit Blender aus denselben Modellen
wie oben (eine Modellquelle: `web/shared/models/manifest.json`, Einträge mit `"top": true`, gleiche GLB per SHA-256;
`tools/launcher_fahrzeugbilder.py`): CC-BY-Modelle von Nazh Design (seagull, seal, seal-u, dolphin, atto3) mit
sichtbarer Namensnennung „3D: Nazh Design · CC BY 4.0 · verändert“. Nur diese fünf; die Panoptes-Eigenbauten haben
dort kein Rendering (13.11.). Verändert:
eigene Kamera und Studiolicht, Lack als eigener Auszug (die echte Lackfarbe rechnet der Launcher ein), Zuschnitt 16:9.
Dazu eigene Szenen-Hintergründe aus selbst gebauten Blender-Szenen (`tools/render_launcher_szenen.py`). Keine KI-Bilder,
keine fremden Bilder. Liste im Launcher: `assets/fahrzeug/QUELLEN.md`.

Fotos statt Zeichnung (13.11.): Modelle ohne gutes 3D-Modell können im Manifest ein frei lizenziertes Foto
tragen (`"photo"`: Datei, SHA-256, Urheber, Lizenz, Quelle). Erlaubt sind nur CC0, gemeinfrei, CC BY und CC BY-SA;
die Namensnennung „Foto: <Urheber> · <Lizenz>“ steht sichtbar unter dem Foto, der Zuschnitt auf 16:9 gilt als
Veränderung. Urheber und Quelle stehen je Foto im Manifest und in `assets/fahrzeug/katalog.json`.

Dieselben Fotos zeigt auch Panoptes statt des 3D-Modells (`web/shared/models/fotos/<id>.webp`), mit derselben
Namensnennung. Fotos (Wikimedia Commons, Lizenz und Urheber am 13.11.2026 geprüft):
- BYD Sealion 7 (`sealion7_foto.webp`): „BYD Sealion 7 Performance AWD Shark Grey 01.jpg“ von Ethan Llamas, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Sealion_7_Performance_AWD_Shark_Grey_01.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Shark (`shark_foto.webp`): „BYD Shark 6 DMO Premium Aurora White.jpg“ von Ethan Llamas, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Shark_6_DMO_Premium_Aurora_White.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Seal U DM-i (`seal-u-dmi_foto.webp`): „BYD Seal U DM-i DSC 9308.jpg“ von Alexander Migl, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Seal_U_DM-i_DSC_9308.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Atto 2 (`atto2_foto.webp`): „BYD Atto 2 DSC 8763.jpg“ von Alexander Migl, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Atto_2_DSC_8763.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Tang (`tang_foto.webp`): „BYD Tang EV Silver Sand Black - front.jpg“ von Ethan Llamas, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Tang_EV_Silver_Sand_Black_-_front.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD M6 (`m6_foto.webp`): „BYD M6, Soekarno-Hatta International Airport, 2025 (01).jpg“ von Bahnfrend, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_M6,_Soekarno-Hatta_International_Airport,_2025_(01).jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Seal 6 GT (`seal6-gt_foto.webp`): „BYD Seal 06 GT 003.jpg“ von Quzhouliulian, CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Seal_06_GT_003.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht.
