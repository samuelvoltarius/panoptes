#!/bin/bash
# Panoptes-APK mit dem Release-Schlüssel signieren (APK-Signaturschema v3 mit Herkunftsnachweis/Lineage).
#
#   deploy/signatur/apk-signieren.sh <ein.apk> <aus.apk>
#
# Umgebung (nie hart verdrahtet, nie ausgeben):
#   PANOPTES_RELEASE_KEYSTORE  Pfad zum PKCS12-Keystore (Alias „panoptes“)
#   PANOPTES_RELEASE_KS_PASS   Passwort (Store = Key)
# Lokal setzt beides deploy/signatur/mit-release-schluessel.ps1 aus DPAPI, in der CI der Release-Workflow aus Secrets.
#
# Rotation ab API 28 (= minSdk): Der v3-Block trägt den neuen Schlüssel plus Lineage, in der das alte
# Debug-Zertifikat 02d5a37b… als vergangene Signatur steht. Damit nimmt Android ein Update auf eine mit dem
# alten Schlüssel installierte App ohne Neuinstallation und ohne Datenverlust an. Der alte Schlüssel wird zum
# Signieren nicht mehr gebraucht. Deshalb nur v3: v1/v2 müssten mit dem ÄLTESTEN Schlüssel der Lineage
# signiert werden (apksigner verlangt dann den alten Schlüssel); ab API 28 prüft Android ohnehin v3 zuerst.
# Danach harte Prüfung: v3, Unterzeichner = neu, Lineage = alt → neu.
set -euo pipefail
EIN="${1:?Eingabe-APK fehlt}"; AUS="${2:?Ausgabe-APK fehlt}"
[ -f "$EIN" ] || { echo "ABBRUCH: $EIN fehlt"; exit 2; }
[ "$EIN" != "$AUS" ] || { echo "ABBRUCH: Ein- und Ausgabe gleich"; exit 2; }
: "${PANOPTES_RELEASE_KEYSTORE:?PANOPTES_RELEASE_KEYSTORE fehlt}"
: "${PANOPTES_RELEASE_KS_PASS:?PANOPTES_RELEASE_KS_PASS fehlt}"
HIER="$(cd "$(dirname "$0")" && pwd)"
LINEAGE="$HIER/panoptes.lineage"
CERT_ALT=02d5a37bf0c1e334f56277af5669efc536fa96921fde0138d7e88dea7d7d8006
CERT_NEU=287c31e78288a440be4d23eb59f58e415d77aca54def67e0e6bf271f1959be81
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.0.0"
SIGNER="$BT/apksigner"; [ -f "$SIGNER.bat" ] && SIGNER="$SIGNER.bat"

"$SIGNER" sign \
  --ks "$PANOPTES_RELEASE_KEYSTORE" --ks-key-alias panoptes \
  --ks-pass env:PANOPTES_RELEASE_KS_PASS --key-pass env:PANOPTES_RELEASE_KS_PASS \
  --lineage "$LINEAGE" --rotation-min-sdk-version 28 --min-sdk-version 28 \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --out "$AUS" "$EIN"
rm -f "$AUS.idsig"

V=$("$SIGNER" verify -v --print-certs --min-sdk-version 28 "$AUS" | tr -d '\r')
echo "$V" | grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true' || { echo "ABBRUCH: kein v3"; exit 1; }
C=$(echo "$V" | grep -m1 'Signer #1 certificate SHA-256 digest' | awk '{print $NF}')
[ "$C" = "$CERT_NEU" ] || { echo "ABBRUCH: Unterzeichner $C ≠ $CERT_NEU"; exit 1; }
L=$("$SIGNER" lineage --in "$AUS" --print-certs | tr -d '\r' | grep 'certificate SHA-256 digest' | awk '{print $NF}' | tr '\n' ' ')
[ "$L" = "$CERT_ALT $CERT_NEU " ] || { echo "ABBRUCH: Lineage im APK ist '$L', erwartet alt → neu"; exit 1; }
echo "Signiert: $AUS · v3 · Unterzeichner ${CERT_NEU:0:8}… · Lineage ${CERT_ALT:0:8}… → ${CERT_NEU:0:8}…"
