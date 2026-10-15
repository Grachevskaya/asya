#!/system/bin/sh
# Install hooks before the framework starts. The policy arrives later, from the
# userspace half, over netlink.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"

# Everything this module keeps for itself lives here, beside the module and not in the host's data
# directory: /data/adb is decrypted long before the user's storage is, so a boot has the rules from
# its first moment. Permissions are unchanged: a separate root state daemon owns reads/writes;
# system_server never opens this directory.
mkdir -p /data/adb/asya
# The manager apk lives here too, and the host never reads it by path: the loader opens it while it
# is still root and hands the descriptor to the process that will show it.
[ -f "$MODDIR/manager.apk" ] && cp -f "$MODDIR/manager.apk" /data/adb/asya/manager.apk 2>/dev/null
# One secret per installation, made here and never written anywhere else: it is what turns a name
# both halves can work out into one nobody can link to this module. It is made once and kept.
SALT="/data/adb/asya/.salt"
if [ ! -s "$SALT" ]; then
  dd if=/dev/urandom of="$SALT" bs=32 count=1 2>/dev/null
fi

# The salt is the only secret left: the state daemon derives its words from these bytes.

for f in config.json native_libs.json runtime.log filter_counts.json; do
  [ -f "/data/adb/asya/$f" ] || : > "/data/adb/asya/$f" 2>/dev/null
    done

# StateDaemon alone owns the library cache in /data/adb/asya; never truncate it at boot.

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
rm -f "$MODDIR/boot-completed.sh"

# Names left in a shared directory are dropped here, before the framework starts: no one holds them
# open yet.
rm -rf /data/misc/asya /data/misc/asya_*

# One boot is one load: the marker is what the service stage reads, so that a second call says so
# instead of loading again into a module that hides itself from lsmod.
rm -f "$MODDIR/state/ko-loaded"

. "$MODDIR/load.sh"
tosya_load "$MODDIR" >>"$LOG" 2>&1
