#!/usr/bin/env bash
# Build the release binary and assemble it into HerdrWatchTray.app (a proper bundle,
# needed for Launch-at-Login / a menu-bar-only agent). Run from anywhere.
set -euo pipefail

cd "$(dirname "$0")/.."

swift build -c release

APP="HerdrWatchTray.app"
BIN="$(swift build -c release --show-bin-path)/HerdrWatchTray"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/HerdrWatchTray"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# Ad-hoc sign so UNUserNotificationCenter registers (stable code identity) and Gatekeeper
# is a little happier. Non-fatal if codesign is unavailable.
codesign --force --sign - "$APP" 2>/dev/null && echo "ad-hoc signed" || echo "codesign skipped"

echo "Built $(pwd)/$APP"
echo "Run:  open $APP     (or: ./$APP/Contents/MacOS/HerdrWatchTray)"
