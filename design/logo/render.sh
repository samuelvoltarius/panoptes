#!/bin/sh
# render.sh <in.svg> <out.png> <size>  (headless Chrome, throwaway profile)
IN="$1"; OUT="$2"; SZ="$3"
PROF="${TMPDIR:-${TEMP:-/tmp}}/surfmate-logo-chrome"
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --hide-scrollbars \
  --user-data-dir="$PROF" \
  --default-background-color=00000000 --window-size=$SZ,$SZ --screenshot="$OUT" "file:///$IN" >/dev/null 2>&1
