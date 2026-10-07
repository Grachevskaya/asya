#!/system/bin/sh
# Load the module if post-fs-data did not. The policy comes from the userspace half over netlink;
# this package ships no such program and works without one.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"
. "$MODDIR/load.sh"
tosya_load "$MODDIR" >>"$LOG" 2>&1

# The manager apk and its oat are not this stage's business: the installer puts them together
# (customize.d/60-prepare-manager.sh), and the action button does it when it is needed. Doing it
# here would put a compiler between this boot and the lockscreen.
