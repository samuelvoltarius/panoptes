#!/bin/sh
# Baut und startet die nativen Tests des Clean-Room-Kamera-Clients (Linux, g++). Aufruf aus dem Repo-Wurzelordner.
set -e
D=$(dirname "$0")
OUT=${TMPDIR:-/tmp}/pkam_client_test
g++ -std=c++17 -O1 -g -Wall -Wextra -Werror -pthread -I"$D/../../main/cpp/include" -I"$D/../../main/cpp/camera" "$D/pkam_client_test.cpp" "$D/../../main/cpp/camera/pkam_client.cpp" -o "$OUT"
"$OUT"
