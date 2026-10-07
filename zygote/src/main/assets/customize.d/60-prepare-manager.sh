#!/system/bin/sh

# The manager apk and its oat are put together while the installer still holds the package: the
# host's data directory is there on a booted system, and no boot then waits on a compiler.
MODPATH=${MODPATH:-${0%/*}/..}
. "$MODPATH/prep-manager.sh"

if prep_manager "$MODPATH"; then
  ui_print "- The manager is in place next to the host, oat included"
else
  ui_print "! The manager could not be prepared here; the action button does it when it is asked"
fi

true
