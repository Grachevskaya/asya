#!/system/bin/sh
# The rule above carries a placeholder where the root domain goes, and this is where it is filled in:
# the installer runs in the same domain the module's processes will have, so it is the one process
# that can answer for them. Nothing is written down in the package that would only be true elsewhere.
#
# This file is sourced by the installer along with the rest of customize.d, so it must not exit: an
# exit here would take the installer with it, and every step after this one would be skipped.

MODPATH=${MODPATH:-${0%/*}}
RULE="$MODPATH/sepolicy.rule"

if [ -f "$RULE" ]; then
  su_secon=$(id -Z 2>/dev/null | cut -d: -f3)
  if [ -n "$su_secon" ]; then
    sed -i "s|%su_secon%|$su_secon|g" "$RULE" 2>/dev/null
    echo "sepolicy: the root domain written down as $su_secon"
  else
    echo "sepolicy: the root domain could not be read; the rule is left as it is"
  fi
fi
