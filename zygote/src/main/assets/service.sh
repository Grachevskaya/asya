#!/system/bin/sh
# Load the module if post-fs-data did not. The policy comes from the userspace half over netlink;
# this package ships no such program and works without one.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"
. "$MODDIR/load.sh"
tosya_load "$MODDIR" >>"$LOG" 2>&1

# The manager apk and its oat are not this stage's business: the installer puts them together
# # The table the loader reads is written beside the module by the service, so nothing has to be
# carried over from anywhere else at boot.
