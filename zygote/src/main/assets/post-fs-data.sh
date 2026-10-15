#!/system/bin/sh
# Install hooks before the framework starts. The policy arrives later, from the
# userspace half, over netlink.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"

# Everything this module keeps for itself lives here, beside the module and not in the host's data
# directory: /data/adb is decrypted long before the user's storage is, so a boot has the rules from
# its first moment. The service runs as the system user, so the directory and the files in it are
# writable by it as well as by root.
mkdir -p /data/adb/asya
chown 0:0 /data/adb/asya 2>/dev/null
chmod 0700 /data/adb/asya 2>/dev/null
# The manager apk lives here too, and the host never reads it by path: the loader opens it while it
# is still root and hands the descriptor to the process that will show it.
[ -f "$MODDIR/manager.apk" ] && cp -f "$MODDIR/manager.apk" /data/adb/asya/manager.apk 2>/dev/null
[ -f /data/adb/asya/manager.apk ] && chmod 0600 /data/adb/asya/manager.apk 2>/dev/null

for f in config.json native_libs.json runtime.log filter_counts.json; do
  [ -f "/data/adb/asya/$f" ] || : > "/data/adb/asya/$f" 2>/dev/null
  chown 0:0 "/data/adb/asya/$f" 2>/dev/null
  chmod 0600 "/data/adb/asya/$f" 2>/dev/null
done

# What the service worked out last time about the apps' libraries, and what it will add to. It is
# read before the user's storage is decrypted, so it has to be writable by the service (system) and
# not only by root.
: > "$MODDIR/state/native_libs.json" 2>/dev/null
chmod 666 "$MODDIR/state/native_libs.json" 2>/dev/null

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
