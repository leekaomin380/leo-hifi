#!/system/bin/sh
# Leo HiFi boot tuning (2026-10-20). Started by /system/etc/init/leo-tuning.rc after boot.
#   CPU config D: both clusters capped at 1.248 GHz; core_ctl keeps >=1 A57 available.
#   ES9018 digital filter: Fast Rolloff.
# Caps are only ever LOWERED to the target: a lower value written by thermal
# mitigation is left alone. Re-checked every 60 s because a cluster's cpufreq
# policy can be recreated with defaults after all of its cores went offline.
CAP=1248000
LOG=/data/local/tmp/leo-tuning.log
C=/sys/devices/system/cpu
log() { echo "$(date +%m-%d\ %H:%M:%S) $*" >> $LOG; }
cap() {  # $1 = cpu number
  f=$C/cpu$1/cpufreq/scaling_max_freq
  [ -w $f ] || return
  v=$(cat $f 2>/dev/null) || return
  if [ -n "$v" ] && [ "$v" -gt $CAP ]; then echo $CAP > $f && log "cpu$1 max $v -> $CAP"; fi
}
sleep 20
log "start"
while true; do
  for n in 0 1 2 3 4 5 6 7; do [ "$(cat $C/cpu$n/online 2>/dev/null)" = 1 ] && cap $n; done
  # A57 cluster entirely offline while core_ctl expects one (seen 2026-10-20): bring cpu4
  # back, unless the battery is warm (then thermal mitigation may be holding it down).
  big=0; for n in 4 5 6 7; do [ "$(cat $C/cpu$n/online)" = 1 ] && big=1; done
  t=$(cat /sys/class/power_supply/battery/temp)
  if [ $big = 0 ] && [ "$(cat $C/cpu4/core_ctl/min_cpus)" -ge 1 ] && [ "$t" -lt 400 ]; then
    echo 1 > $C/cpu4/online && log "cpu4 re-onlined (batt $t)" && cap 4
  fi
  /system/bin/tinymix 'Filter Shape' | grep -q '>Fast Rolloff' || {
    /system/bin/tinymix 'Filter Shape' 'Fast Rolloff'; log "filter -> Fast Rolloff"; }
  sleep 60
done
