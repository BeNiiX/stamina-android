#!/usr/bin/env bash
# Lance le serveur de conformité, exécute les tests Robolectric du SDK, arrête le serveur.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/../stamina-sdk-conformance/server.js"
PORT="${CONFORMANCE_PORT:-4999}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

node "$CONF" --port "$PORT" > /tmp/stamina-conformance.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
sleep 1

cd "$ROOT"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
STAMINA_CONFORMANCE_URL="http://127.0.0.1:$PORT" ./gradlew --console=plain :stamina:testDebugUnitTest
