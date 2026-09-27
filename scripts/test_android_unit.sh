#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Fail closed when a new suite has not been assigned to a feature job.
shopt -s globstar nullglob
for suite in app/src/test/java/**/*Test.kt; do
    case "${suite}" in
        */network/*|*/MihomoControllerTest.kt|*/Vpn*Test.kt|*/WebView*Test.kt|*/ProcessMatchingModeTest.kt|*/RuntimeLogLevelTest.kt) ;;
        *) echo "Assign new test suite to a CI feature group: ${suite}" >&2; exit 2 ;;
    esac
done
case "${1:-}" in
    network) filters=(--tests '*.network.*') ;;
    controller) filters=(--tests '*MihomoControllerTest') ;;
    lifecycle) filters=(--tests '*Vpn*Test') ;;
    webview) filters=(--tests '*WebView*Test') ;;
    settings) filters=(--tests '*ProcessMatchingModeTest' --tests '*RuntimeLogLevelTest') ;;
    *) echo "Usage: $0 network|controller|lifecycle|webview|settings" >&2; exit 2 ;;
esac
./gradlew :app:testDebugUnitTest -Pandroidcyaml.unitTestsOnly=true "${filters[@]}" \
    --max-workers=2 --no-daemon --stacktrace
