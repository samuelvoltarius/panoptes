# Fahrzeugbilder des Launchers – Quellen (0.1.43.4)

Dreiviertel-Ansichten, gerendert mit Blender aus denselben 3D-Modellen, die Panoptes als 3D zeigt: eine Modellquelle
`app/src/main/assets/web/shared/models/manifest.json` (gleiche GLB-Dateien, SHA-256 geprüft), eine Lizenzliste
`MODELLE_LIZENZEN.md`. Erzeugt mit `python tools/launcher_fahrzeugbilder.py` (holt und prüft die GLB, rendert mit
`tools/render_launcher_fahrzeug.py`, schneidet mit `tools/launcher_bilder_bauen.py` zu und schreibt `katalog.json`). Keine KI-Bilder, keine Fotos,
keine fremden Bilder. Alle Bilder sind „verändert für Panoptes“ (eigene Kamera, Licht, Lackauszug).

## CC BY 4.0 – Nazh Design (Sketchfab), Herstellerlogos entfernt
- seagull (Dolphin Surf), seal, seal-u, dolphin, atto3
- Namensnennung sichtbar unter dem Bild: „3D: Nazh Design · CC BY 4.0 · verändert“
- Quellen und Lizenz: siehe `MODELLE_LIZENZEN.md` in Panoptes (assets/licenses)

Nur diese fünf Modelle haben ein Bild (13.11.: „nur die 5 guten Modelle“): im Manifest `"top": true`.
Ein neues oder besseres Modell bekommt dort seinen Eintrag mit `"top": true`, dann das Skript erneut laufen lassen. Die Panoptes-Eigenbauten
(Sealion 7, Seal U DM-i, Atto 3 Evo, Atto 2, Han, Tang, M6, Destroyer) bekommen kein Rendering im Launcher und
bleiben bei Kern-Renderbild bzw. gezeichneter Form.

## Fotos (`<id>_foto.webp`) für Modelle ohne gutes 3D-Modell
Frei lizenzierte Fotos (CC0, gemeinfrei, CC BY, CC BY-SA; nie NC/ND), Eintrag `"photo"` im Manifest mit Urheber,
Lizenz, Quelle und SHA-256. Namensnennung sichtbar unter dem Foto („Foto: <Urheber> · <Lizenz>“), Urheber und Quelle
je Foto in `katalog.json`. Darunter die Bitte um ein 3D-Modell mit Link (`modellBitteUrl`). Zuschnitt 16:9 = verändert.
Alle Fotos von Wikimedia Commons, Lizenz und Urheber am 13.11.2026 über die Commons-Metadaten geprüft (SHA-1 gleich).

- BYD Sealion 7 (`sealion7_foto.webp`): „BYD Sealion 7 Performance AWD Shark Grey 01.jpg“ von Ethan Llamas, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Sealion_7_Performance_AWD_Shark_Grey_01.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Shark (`shark_foto.webp`): „BYD Shark 6 DMO Premium Aurora White.jpg“ von Ethan Llamas, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Shark_6_DMO_Premium_Aurora_White.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Seal U DM-i (`seal-u-dmi_foto.webp`): „BYD Seal U DM-i DSC 9308.jpg“ von Alexander Migl, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Seal_U_DM-i_DSC_9308.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Atto 2 (`atto2_foto.webp`): „BYD Atto 2 DSC 8763.jpg“ von Alexander Migl, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Atto_2_DSC_8763.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Tang (`tang_foto.webp`): „BYD Tang EV Silver Sand Black - front.jpg“ von Ethan Llamas, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Tang_EV_Silver_Sand_Black_-_front.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD M6 (`m6_foto.webp`): „BYD M6, Soekarno-Hatta International Airport, 2025 (01).jpg“ von Bahnfrend, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_M6,_Soekarno-Hatta_International_Airport,_2025_(01).jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht. Die Bearbeitung steht ebenfalls unter CC BY-SA 4.0.
- BYD Seal 6 GT (`seal6-gt_foto.webp`): „BYD Seal 06 GT 003.jpg“ von Quzhouliulian, CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), Quelle https://commons.wikimedia.org/wiki/File:BYD_Seal_06_GT_003.jpg. Bearbeitet für Panoptes: Zuschnitt 16:9, Kennzeichen/Plaketten/Schilder/Personen unkenntlich gemacht.

## Szenen (`assets/szenen/`)
Eigene Blender-Szenen aus Grundkörpern, Rauschen und Himmelsmodell (`tools/render_launcher_szenen.py`):
Studio, Stadt, Berge, Küste, Tiefgarage, Ladepark, je Tag und Nacht. Keine Fremdtexturen, keine KI.
