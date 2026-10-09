#!/bin/bash
# Candidate B policy overlay: bind-mount a deep_buffer=44100-only policy XML over
# /vendor/etc/audio_policy_configuration.xml. No partition write; a reboot or
# `revert` removes it. Usage: policy_b.sh apply|revert|status
set -euo pipefail
A="${ADB:-adb} -s ${LEO_SERIAL:?set LEO_SERIAL}"
HERE=$(cd "$(dirname "$0")" && pwd)
XML="${LEO_POLICY_XML:?path of the patched audio_policy_configuration.xml}"
# Hashes below are for MoKee MK100.0-leo-221019-RELEASE; override for other builds.
WANT=${LEO_POLICY_NEW_SHA16:-56fba0754617f13d}   # first 16 hex of the patched XML SHA-256
ORIG=${LEO_POLICY_STOCK_SHA16:-b299109dceeb1ffc}   # first 16 hex of the stock XML SHA-256
TARGET=/vendor/etc/audio_policy_configuration.xml
DIR=/data/local/tmp/leo-policy-b

restart() {
    local before after
    before=$($A shell pidof audioserver | tr -d '\r')
    $A shell setprop ctl.restart audioserver
    for _ in $(seq 1 30); do
        sleep 1
        after=$($A shell pidof audioserver | tr -d '\r')
        if [ -n "$after" ] && [ "$after" != "$before" ]; then
            echo "audioserver $before -> $after"; return 0
        fi
    done
    echo "audioserver did not restart" >&2; return 1
}

case "${1:-status}" in
apply)
    [ "$(shasum -a 256 "$XML" | cut -c1-16)" = "$WANT" ]
    [ "$($A shell "sha256sum $TARGET" | cut -c1-16)" = "$ORIG" ]
    $A shell "mkdir -p $DIR"
    $A push "$XML" $DIR/audio_policy_configuration.xml >/dev/null
    $A shell "chown root:root $DIR/audio_policy_configuration.xml; chmod 644 $DIR/audio_policy_configuration.xml; chcon u:object_r:vendor_configs_file:s0 $DIR/audio_policy_configuration.xml"
    $A shell "mount --bind $DIR/audio_policy_configuration.xml $TARGET"
    [ "$($A shell "sha256sum $TARGET" | cut -c1-16)" = "$WANT" ]
    restart ;;
revert)
    while $A shell "grep -q ' /system/vendor/etc/audio_policy_configuration.xml ' /proc/mounts"; do $A shell "umount $TARGET"; done
    [ "$($A shell "sha256sum $TARGET" | cut -c1-16)" = "$ORIG" ]
    restart ;;
status)
    $A shell "grep -c ' /system/vendor/etc/audio_policy_configuration.xml ' /proc/mounts; sha256sum $TARGET" ;;
esac
