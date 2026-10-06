#!/system/bin/sh
# Shared by the two boot stages. A loaded module stays loaded until reboot, and it hides itself,
# so what says it is loaded is the marker the first call leaves behind; lsmod is a second opinion.
tosya_load() {
  local moddir="$1"
  local ko="$moddir/ko/tosya.ko"
  local marker="$moddir/state/ko-loaded"
  local loader rc

  [ -f "$ko" ] || { echo "[load] no selected kernel module"; return 1; }
  if [ -e "$marker" ] || lsmod | grep -q '^tosya '; then
    echo "[load] already loaded; a reboot is what replaces it"
    return 0
  fi

  # The installed KernelSU loader is used where it exists; where it does not, the loader the
  # package carries takes its place. A load that fails is not retried through the other one, and no
  # shell-evaluated parameter file is involved.
  if [ -x /data/adb/ksud ]; then
    loader=ksud
    /data/adb/ksud insmod "$ko"
    rc=$?
  elif [ -x "$moddir/lkmloader" ]; then
    loader=lkmloader
    "$moddir/lkmloader" "$ko"
    rc=$?
  else
    echo "[load] no ksud or bundled lkmloader"
    return 1
  fi
  echo "[load] $loader rc=$rc $(date '+%m-%d %H:%M:%S')"
  if [ "$rc" -eq 0 ] || lsmod | grep -q '^tosya '; then
    : > "$marker" 2>/dev/null
    return 0
  fi
  echo "[load] the module did not load; see the loader error above"
  return 1
}
