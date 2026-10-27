# Third-Party Notices – Panoptes

Panoptes' own source code is licensed under the MIT License (see [LICENSE](LICENSE)). This includes the DiLink 5 camera library `libdilink5_camera.so`: since version 0.1.9 its connection to `fast_cam_capture` is Panoptes' own clean-room code (see "DiLink 5 camera library" below).

**The APK as a whole contains GPL-3.0 and AGPL-3.0 parts**: the separate program `fast_cam_capture` (GPL-3.0) and the detection models YOLO11n/YOLO26n (AGPL-3.0). The complete source code of Panoptes is published in this repository; the license texts are in [LICENSES/](LICENSES/) and inside the app (`assets/licenses/`).

Panoptes is based on **OverDrive** by Yash Srivastava, published under the MIT License. Its copyright
and permission notice is retained in [LICENSE](LICENSE) and in the app (`app/src/main/assets/licenses/LICENSES.txt`).

The MIT License applies **only** to the code of Panoptes and OverDrive. The app bundles the third-party
components listed below. Each of them stays under its own license, and those licenses, not MIT,
govern these components.

## DiLink 5 camera library (MIT since 0.1.9)

`libdilink5_camera.so` (JNI library of the DiLink 5 camera path) is Panoptes' own code under the MIT License.
Its client for the socket protocol of `fast_cam_capture` (`app/src/main/cpp/camera/pkam_client.{h,cpp}`,
`app/src/main/cpp/include/pkam_protokoll.h`) was written in version 0.1.9 as a clean-room implementation: from a
plain interface description only, by a developer who had not seen the earlier code. Up to version 0.1.8 this library
contained three files derived from [FAST_CAM_CAPTURE](https://github.com/francescodoffizi/FAST_CAM_CAPTURE)
(GPL-3.0-or-later); they are no longer part of the source or the build.
`app/src/main/cpp/camera/fast_cam_release_guard.c` is Panoptes/OverDrive's own code (MIT) and is built as a
**separate** library (`libfast_cam_release_guard.so`).

## Bundled native binaries (`app/src/main/jniLibs/`)

| Component | Upstream | Version | License | Modified? |
|-----------|----------|---------|---------|-----------|
| tailscale | https://github.com/tailscale/tailscale | v1.96.4 | BSD-3-Clause | **Yes**, see "Modifications" |
| wgproxy (`libwireguard.so`) | [`tools/wireguard`](tools/wireguard), built on [wireguard-go](https://git.zx2c4.com/wireguard-go) | pinned in `tools/wireguard/go.mod` | MIT; bundles gVisor netstack (Apache-2.0) and golang.org/x/{crypto,net,sys,time} (BSD-3-Clause) | No |
| fast_cam (`assets/dilink5/fast_cam_capture`, byte-identical copy `jniLibs/arm64-v8a/libfast_cam_capture.so`) | [francescodoffizi/FAST_CAM_CAPTURE](https://github.com/francescodoffizi/FAST_CAM_CAPTURE) | release v1.0.0 (tag `v1.0.0`, commit c15d7b6, 2026-09-13); the bundled binaries were taken over from OverDrive, the exact build commit is not recorded | **GPL-3.0-or-later** | No |

## Machine-learning models (`app/src/main/assets/models/`)

| Component | Upstream | License |
|-----------|----------|---------|
| YOLO11n (`yolo11n.tflite`) | https://github.com/ultralytics/ultralytics | **AGPL-3.0** |
| YOLO26n (`yolo26n.tflite`) | https://github.com/ultralytics/ultralytics | **AGPL-3.0** |
| Signage text detection/recognition (`signage_det.tflite`, `signage_rec.tflite`, `signage_charset.txt`) | [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) en_PP-OCRv3 mobile det/rec, converted to TFLite (fp16) by OverDrive | Apache-2.0 (modified: format conversion) |

## Native libraries linked into the app

| Component | Upstream | License |
|-----------|----------|---------|
| OpenCV | https://github.com/opencv/opencv | Apache-2.0 |
| OpenH264 | https://github.com/cisco/openh264 | BSD-2-Clause |
| TensorFlow Lite | https://github.com/tensorflow/tensorflow | Apache-2.0 |

## Java/Kotlin libraries (Gradle dependencies)

Including AndroidX and Material Components (Apache-2.0), MapLibre Native Android (BSD-2-Clause),
dadb (Apache-2.0), OkHttp (Apache-2.0), ZXing (Apache-2.0), TensorFlow Lite Java (Apache-2.0),
Eclipse Paho MQTT (EPL-2.0 / EDL-1.0), H2 Database (MPL-2.0 / EPL-1.0) and Java-WebSocket (MIT).
The exact list and versions are in `app/build.gradle.kts` and `gradle/libs.versions.toml`.

## Web libraries (`app/src/main/assets/web/shared/vendor/`, fonts in `.../fonts/`)

| Component | Upstream | Version | License |
|-----------|----------|---------|---------|
| MapLibre GL JS | https://github.com/maplibre/maplibre-gl-js | 4.7.1 | BSD-3-Clause (`vendor/maplibre-gl-LICENSE.txt`) |
| PMTiles (JavaScript) | https://github.com/protomaps/PMTiles | 3.2.1 | BSD-3-Clause (`vendor/pmtiles-LICENSE.txt`) |
| three.js, GLTFLoader, DRACOLoader, OrbitControls | https://github.com/mrdoob/three.js | see file headers | MIT |
| Draco decoder | https://github.com/google/draco | see file headers | Apache-2.0 |
| JMuxer | https://github.com/samirkumardas/jmuxer | 2.0.4 | MIT (`vendor/jmuxer-LICENSE.txt`) |
| GSAP | https://gsap.com | 3.12.5 | GSAP Standard "No Charge" License, https://gsap.com/standard-license (license header kept unchanged in `vendor/gsap.min.js`; Panoptes is free and not a visual animation builder) |
| QR Code Generator (Kazuhiko Arase) | https://github.com/kazuhikoarase/qrcode-generator | 1.4.4 | MIT |
| Manrope, Sora (fonts) | Google Fonts | – | SIL Open Font License 1.1 (`fonts/*-OFL.txt`) |

## Map data, 3D models and other data

- **Map data** © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/). Protomaps basemap, terrain © Mapterhorn. The attribution is shown on every map.
- **Speed cameras** from OpenStreetMap (ODbL), served via the project's own server.
- **3D vehicle models:** models by [Nazh Design](https://sketchfab.com/NazhDesign) under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) (logos removed, mesh reduced, Draco-compressed), plus models built for Panoptes under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). Full list: `app/src/main/assets/licenses/MODELLE_LIZENZEN.md`.
- **Vehicle pictures** (`app/src/main/res/drawable-nodpi/vehicle_*.webp`, since 0.1.20): rendered for Panoptes with `tools/render_vehicle_art.py` (Blender) from the CC BY 4.0 models by [Nazh Design](https://sketchfab.com/NazhDesign): “2023 BYD Seagull”, “2024 BYD Seal Performance AWD”, “2023 BYD Song Plus” (Seal U), “2024 BYD Dolphin Premium Long Range”, “2024 BYD Atto 3 Superior Extended Range” – changed (rendered, cropped). `vehicle_fallback.webp` is a neutral silhouette derived from the “2023 BYD Song Plus” render. Earlier press photos without a licence were removed.
- **Charger data:** source and license of the live data are shown in the app below the map (for example EnBW, CC BY 4.0).

## Modifications (Apache-2.0 §4(b) notice of changes)

- Releases up to 0.1.7.x also bundled modified builds of **cloudflared** (based on 2025.7.0) and **zrok** (based on v1.1.x), both Apache-2.0. Changes: cloudflared got a proxy-aware edge dialer (`edgediscovery/dial.go`), zrok a SOCKS5 proxy and DNS override path (`cmd/zrok/main.go`). Both were removed in 0.1.8.
- **tailscale** (BSD-3-Clause, listed for transparency): (1) the local API uses loopback TCP instead of a unix socket, (2) the ACME client gets a resolver when the platform has none, (3) tailnet connections are forwarded to 127.0.0.1 only for the ports listed in `TS_NETSTACK_LOCAL_FORWARD_PORTS`, so loopback-only services on the head unit are not exposed. The patches are in `tools/patches/` and are applied by `tools/build-libtailscale.sh`.

## Source-derived work

- **Bangcle / white-box AES port**: derived from reverse-engineering work by [Niek/BYD-re](https://github.com/Niek/BYD-re) and [jkaberg/pyBYD](https://github.com/jkaberg/pyBYD).

## Wallbox adapters: specifications and data (Panoptes 0.1.11+)

No third-party code is bundled for the wallbox adapters; the Java implementations are our own. Protocol details and
device data were taken from the following projects as specifications, with attribution:

| Project | License | Used for |
|---------|---------|----------|
| [evcc](https://github.com/evcc-io/evcc) – Copyright (c) evcc.io (andig, naltatis, premultiply) | MIT | evcc REST API; go-e (`charger/go-e*`), Shelly (`meter/shelly/*`), KEBA UDP (`charger/keba-udp.go`, `keba/*`), Modbus register maps for KEBA P40 and Heidelberg Energy Control (`charger/keba-modbus.go`, `heidelberg-ec.go`, data in `web/shared/wallbox-modbus-modelle.json`), Easee (`charger/easee*`) and Zaptec (`charger/zaptec*`) cloud endpoints |
| [openWB](https://github.com/snaptec/openWB) 1.x and [openWB core](https://github.com/openWB/core) 2.x | GPL-3.0 | MQTT topic names and values only (interface facts, no code taken) for the openWB adapter |
| [openHAB add-ons](https://github.com/openhab/openhab-addons), Easee binding | EPL-2.0 | Easee API paths and field names only (interface facts, no code taken) |
| [tuya-local](https://github.com/make-all/tuya-local) – Copyright 2018 Nik Rolls, 2026 Jason Rumney and contributors | MIT | DP layout of Tuya EV chargers (`web/shared/wallbox-tuya-modelle.json`, entry `qccdz-standard`) |
| [Home Assistant core](https://github.com/home-assistant/core), Tuya integration | Apache-2.0 | Tuya category `qccdz` standard codes (`switch`, `charge_cur_set`, `work_state`, `power_total`) |
| [tinytuya](https://github.com/jasonacox/tinytuya) – Copyright (c) 2024 Jason Cox | MIT | Test oracle only (frame test vectors, simulator cross-check in `tools/wallbox/`); not shipped |
| [pymodbus](https://github.com/pymodbus-dev/pymodbus) 3.6.x | BSD-3-Clause | Test oracle only (Modbus TCP server in `tools/wallbox/modbus_orakel.py`); not shipped |

## Corresponding source (copyleft components)

**FAST_CAM_CAPTURE v1.0.0** is licensed under **GPL-3.0-or-later**; **YOLO11n** and **YOLO26n** are licensed under **AGPL-3.0**.
`fast_cam_capture` runs as a separate program (own process) started by Panoptes; Panoptes' own code talks to it only through its command line and IPC socket.
The complete source code of Panoptes itself is published in this repository.
The corresponding source of FAST_CAM_CAPTURE is [tag v1.0.0](https://github.com/francescodoffizi/FAST_CAM_CAPTURE/tree/v1.0.0); the Ultralytics models come from https://github.com/ultralytics/ultralytics.

Releases up to 0.1.7.x also bundled **sing-box v1.12.14** (GPL-3.0-or-later; source: upstream tag [v1.12.14](https://github.com/SagerNet/sing-box/tree/v1.12.14)). It was removed in 0.1.8; the written offer below continues to cover those releases.

**Written offer:** For three years from the date of each release, we will provide, on request, the complete
corresponding source code of the GPL-3.0 and AGPL-3.0 components exactly as bundled in that release, at no
more than the cost of providing it. Contact: Alfred Aigner, **office (at) photo-aigner.at** (see [Impressum](https://byd.serveone.at/impressum)).

The full text of each license (GPL-3.0, AGPL-3.0, Apache-2.0, BSD-2-Clause, BSD-3-Clause, MPL-2.0, EPL-2.0,
OFL-1.1, CC BY 4.0, ODbL 1.0) is available from the respective upstream projects.
