#!/bin/sh
# Host-side fault-injection harness for the Leo HiFi controller.
#
#   usage: tests/host-mock-leo-hifi/run.sh <patched-audio-hal-root>
#
# <patched-audio-hal-root> is a checkout of
#   MoKee/android_hardware_qcom_audio @ mkq-mr1-caf-msm8994 (7f4cac74)
# with patches/phase5b-m3/000{1..5} applied.  The harness copies the unmodified
# hal/msm8974/leo_hifi.{c,h} out of it and links them against mock tinyalsa /
# property / log surfaces.
#
# THIS IS NOT AN ANDROID BUILD.  It exercises the controller's decision logic
# against failures that cannot be injected on a device.  It proves logic, not
# that the HAL module builds or runs on the target.  Do not report a green run
# here as "M3-0 build passed".
#
# Exit status: 0 = every scenario passed.

set -u
SRC="${1:-}"
DIR=$(dirname "$0")

if [ -z "$SRC" ] || [ ! -f "$SRC/hal/msm8974/leo_hifi.c" ]; then
    echo "usage: $0 <patched-audio-hal-root>" >&2
    echo "       (expected $SRC/hal/msm8974/leo_hifi.c)" >&2
    exit 2
fi

CC=${CC:-clang}
TMP=$(mktemp -d)
MOCK_ESS_DIR="$TMP/ess"
trap 'rm -rf "$TMP"' EXIT

cp "$SRC/hal/msm8974/leo_hifi.c" "$SRC/hal/msm8974/leo_hifi.h" "$SRC/hal/msm8974/leo_hifi_flow.h" "$TMP/"
cp "$DIR/mock.c" "$DIR/mock.h" "$DIR/test_leo_hifi.c" "$TMP/"
cp -R "$DIR/include" "$TMP/include"

echo "== strict syntax/type gate on the unmodified leo_hifi.c =="
$CC -std=c99 -Wall -Wextra -Wno-unused-parameter -Wshadow -Wsign-compare \
    -Wformat=2 -c "$TMP/leo_hifi.c" -I "$TMP/include" -I "$TMP" \
    -DLEO_ESS_SYSFS_DRIVER=\""$MOCK_ESS_DIR/driver"\" -DESS_DIR=\""$MOCK_ESS_DIR"\" -o "$TMP/leo_hifi.o" || exit 1
echo "   clean (no warnings)"

echo "== undefined symbols required by leo_hifi.o =="
nm -u "$TMP/leo_hifi.o" 2>/dev/null | sed 's/^ *//' | sort | sed 's/^/   /'

echo "== build and run the fault-injection scenarios =="
$CC -std=gnu99 -Wall -Wextra -Wno-unused-parameter -I "$TMP/include" -I "$TMP" \
    -DLEO_ESS_SYSFS_DRIVER=\""$MOCK_ESS_DIR/driver"\" -DESS_DIR=\""$MOCK_ESS_DIR"\" \
    "$TMP/leo_hifi.c" "$TMP/mock.c" "$TMP/test_leo_hifi.c" -o "$TMP/t" || exit 1
"$TMP/t" 2>/dev/null
rc=$?
echo
if [ $rc -eq 0 ]; then
    echo "host-mock-leo-hifi: ALL SCENARIOS PASSED"
    echo "NOTE: host mock only.  NOT an Android build, NOT device evidence."
else
    echo "host-mock-leo-hifi: FAILURES PRESENT"
fi
exit $rc
