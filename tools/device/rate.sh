#!/bin/bash
# Effective consumption rate of a running playback PCM, timed by the kernel's
# own hw_ptr timestamps (two reads N seconds apart). Usage: rate.sh pcm0p 10
P=${1:-pcm0p}; N=${2:-10}
${ADB:-adb} -s "${LEO_SERIAL:?set LEO_SERIAL}" shell "f=/proc/asound/card0/$P/sub0/status; cat \$f; sleep $N; cat \$f; grep -E 'rate|format' /proc/asound/card0/$P/sub0/hw_params" | tr -d '\r' | awk '
/^tstamp/ {split($3,t,"."); ts[++n]=t[1]+t[2]/1e9}
/^hw_ptr/ {hp[++m]=$3}
/^(rate|format)/ {print}
END { if (n>=2 && m>=2) printf "hw_ptr %d -> %d over %.3f s = %.1f frames/s\n", hp[1], hp[2], ts[2]-ts[1], (hp[2]-hp[1])/(ts[2]-ts[1]); else print "PCM not running" }'
