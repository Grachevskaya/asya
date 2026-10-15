#!/system/bin/sh
# The installer writes over the live module directory without removing anything from it, so files
# from an older package survive a re-flash. The loader would go on to read every dex file it finds
# there. Clean the live copy while the installer still has it in hand, so the reboot starts clean.

LIVE="/data/adb/modules/${MODPATH##*/}"
[ -d "$LIVE" ] || return 0

for f in "$LIVE"/classes*.dex; do
  [ -f "$f" ] || continue
  case "${f##*/}" in
    classes.dex) ;;
    *) rm -f "$f" ;;
  esac
done
# Names an older package wrote and nothing reads any more: the host package it resolved for itself,
# and the launch token that was replaced by the receiver's permission.
rm -f "$LIVE/boot-completed.sh" "$LIVE/sepolicy.rule" "$LIVE/system_ui" "$LIVE/state/host_token"
