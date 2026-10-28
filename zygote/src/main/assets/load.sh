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

  # bin/lkmloader: the install script restores the mode the tree's normalisation takes away.
  loader="$moddir/bin/lkmloader"
  if [ ! -x "$loader" ]; then
    echo "[load] no executable loader at $loader"
    return 1
  fi
  "$loader" "$ko"
  rc=$?
  echo "[load] $loader rc=$rc $(date '+%m-%d %H:%M:%S')"
  if [ "$rc" -eq 0 ] || lsmod | grep -q '^tosya '; then
    mkdir -p "$moddir/state" 2>/dev/null
    : > "$marker" 2>/dev/null
    return 0
  fi
  echo "[load] the module did not load; see the loader error above"
  return 1
}
