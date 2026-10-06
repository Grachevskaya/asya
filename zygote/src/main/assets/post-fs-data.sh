#!/system/bin/sh
# Install hooks before the framework starts. The policy arrives later, from the
# userspace half, over netlink.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"

# A flash copies files over the module directory and deletes nothing, so whatever an older package
# left behind stays there -- and the loader reads every classes*.dex it finds, which means a stale
# dex keeps the code inside it running after that code has left the package. Drop what this package
# does not ship, every boot, before anything reads it.
for f in "$MODDIR"/classes*.dex; do
  [ -f "$f" ] || continue
  case "${f##*/}" in
    classes.dex) ;;
    *) rm -f "$f" ;;
  esac
done
rm -f "$MODDIR/boot-completed.sh" "$MODDIR/sepolicy.rule"

# The service used to keep its state under /data/misc, and that is a shared directory: a name with
# this module's mark on it, sitting where anything with root can list it, is a thing to be found.
# The state lives in the host's own private directory now, so nothing here is ours any more -- and
# this runs before the framework starts, so no one holds those names open while they are dropped.
rm -rf /data/misc/asya /data/misc/asya_*

# One boot is one load: the marker is what the service stage reads, so that a second call says so
# instead of loading again into a module that hides itself from lsmod.
rm -f "$MODDIR/state/ko-loaded"

. "$MODDIR/load.sh"
tosya_load "$MODDIR" >>"$LOG" 2>&1
