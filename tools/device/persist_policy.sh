#!/bin/bash
# Make the candidate-B policy (deep_buffer = 44100 only) permanent by replacing
# /system/vendor/etc/audio_policy_configuration.xml.  Backs up the stock file on
# the host and on the device first.  Usage: persist_policy.sh install|rollback
set -euo pipefail
A="${ADB:-adb} -s ${LEO_SERIAL:?set LEO_SERIAL}"
HERE=$(cd "$(dirname "$0")" && pwd)
NEW="${LEO_POLICY_XML:?path of the patched audio_policy_configuration.xml}"
# Hashes below are for MoKee MK100.0-leo-221019-RELEASE; override for other builds.
STOCK_SHA=${LEO_POLICY_STOCK_SHA:-b299109dceeb1ffce8b022e2213dc0cced1472e2cbc5e6486d4c36687d3d6d03}
NEW_SHA=${LEO_POLICY_NEW_SHA:-56fba0754617f13dfa353506b6933db3ce91bd7386e063c34b5370906327c2a4}
TARGET=/system/vendor/etc/audio_policy_configuration.xml
CTX=u:object_r:vendor_configs_file:s0
BK_HOST="${LEO_BACKUP_DIR:-$HERE/policy-backup}/audio_policy_configuration.stock.xml"
BK_DEV=/data/local/tmp/leo-policy-backup/audio_policy_configuration.stock.xml

sh_() { $A shell "$1" | tr -d '\r'; }
dev_sha() { sh_ "sha256sum $1" | cut -d' ' -f1; }
mode() { sh_ "grep ' / ext4 ' /proc/mounts" | awk '{print $4}' | tr ',' '\n' | grep -xE 'ro|rw'; }
die() { echo "ERROR: $*" >&2; exit 1; }

preflight() {
    [ "$(sh_ 'getprop ro.product.device')" = leo ] || die "wrong device"
    [ "$(sh_ 'id -u')" = 0 ] || die "root shell required"
    [ "$(mode)" = ro ] || die "system not read-only before the window"
    [ "$(sh_ "grep -c ' $TARGET ' /proc/mounts || true")" = 0 ] || die "a bind overlay is still mounted; run policy_b.sh revert first"
    local open
    open=$(sh_ 'for f in /proc/asound/card0/pcm*p/sub0/status; do head -1 $f; done' | grep -vc closed || true)
    [ "$open" = 0 ] || die "playback PCM open; pause audio first"
}

write_file() {  # $1 = device source, $2 = expected sha
    local blk stage="$TARGET.leo-stage"
    blk=$(sh_ "grep ' / ext4 ' /proc/mounts" | awk '{print $1}')
    sh_ "mount -o rw,remount $blk /"
    [ "$(mode)" = rw ] || die "remount rw failed"
    sh_ "test ! -e $stage && cp $1 $stage && chown 0:0 $stage && chmod 644 $stage && chcon $CTX $stage"
    [ "$(dev_sha $stage)" = "$2" ] || die "staged content mismatch"
    sh_ "mv $stage $TARGET && sync"
    [ "$(dev_sha $TARGET)" = "$2" ] || die "target content mismatch after write"
    sh_ "ls -lZ $TARGET"
    sh_ "mount -o ro,remount $blk /" || true
    if [ "$(mode)" != ro ]; then
        echo "remount ro refused; rebooting to restore read-only system"
        $A reboot; $A wait-for-device
        until [ "$(sh_ 'getprop sys.boot_completed')" = 1 ]; do sleep 2; done
        [ "$(mode)" = ro ] || die "system still not read-only after reboot"
        [ "$(dev_sha $TARGET)" = "$2" ] || die "content changed across reboot"
    fi
}

case "${1:-}" in
install)
    preflight
    [ "$(dev_sha $TARGET)" = "$STOCK_SHA" ] || die "target is not the stock file"
    [ "$(shasum -a 256 "$NEW" | cut -d' ' -f1)" = "$NEW_SHA" ] || die "local policy changed"
    mkdir -p "$(dirname "$BK_HOST")"
    $A exec-out cat $TARGET > "$BK_HOST.tmp"
    [ "$(shasum -a 256 "$BK_HOST.tmp" | cut -d' ' -f1)" = "$STOCK_SHA" ] || die "host backup mismatch"
    mv "$BK_HOST.tmp" "$BK_HOST"
    sh_ "mkdir -p $(dirname $BK_DEV) && cp $TARGET $BK_DEV"
    [ "$(dev_sha $BK_DEV)" = "$STOCK_SHA" ] || die "device backup mismatch"
    $A push "$NEW" /data/local/tmp/leo-policy-backup/candidate-b.xml >/dev/null
    write_file /data/local/tmp/leo-policy-backup/candidate-b.xml "$NEW_SHA"
    echo "POLICY_INSTALLED (audioserver reads it on its next start)" ;;
rollback)
    preflight
    [ "$(shasum -a 256 "$BK_HOST" | cut -d' ' -f1)" = "$STOCK_SHA" ] || die "host backup missing"
    $A push "$BK_HOST" /data/local/tmp/leo-policy-backup/stock-restore.xml >/dev/null
    write_file /data/local/tmp/leo-policy-backup/stock-restore.xml "$STOCK_SHA"
    echo "POLICY_RESTORED" ;;
*) echo "usage: $0 install|rollback" >&2; exit 2 ;;
esac
