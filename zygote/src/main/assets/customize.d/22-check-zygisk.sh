#!/system/bin/sh

# check for a Zygisk framework was installed and enabled

# get language
[ -z "$SYSTEM_LANG" ] && SYSTEM_LANG=$(getprop persist.sys.locale 2>/dev/null)
[ -z "$SYSTEM_LANG" ] && SYSTEM_LANG=$(getprop persist.sys.language 2>/dev/null)
[ -z "$SYSTEM_LANG" ] && SYSTEM_LANG=$(settings get system system_locales 2>/dev/null)

# default language
ZYGISK_DETECTED_MSG(){
    ui_print "- Found $1 framework"
}
FALLBACK_ZYGISK_NAME="Zygisk"
ZYGISK_MULTI_ERR="! Multiple Zygisk frameworks were found. Aborting installation to prevent conflicts"
ZYGISK_NOT_FOUND_ERR="! No known Zygisk frameworks (e.g. ZygiskNext) is found, asya requires Zygisk to work. Installation aborted"

# language pack
if echo "$SYSTEM_LANG" | grep -q "zh"; then
    ZYGISK_DETECTED_MSG(){
        ui_print "- Detected framework: $1"
    }
    ZYGISK_MULTI_ERR="! More than one Zygisk framework found; the installer stopped to avoid a conflict"
    ZYGISK_NOT_FOUND_ERR="! No known Zygisk framework (such as ZygiskNext) was found; asya needs Zygisk to run, so the installer stopped"
fi

for folder in /data/adb/modules/* /data/adb/modules_update/*
do
    ([ -f "$folder/disable" ] || [ -f "$folder/remove" ]) && continue
    [ ! -f "$folder/bin/zygiskd" ] && [ ! -f "$folder/bin/zygiskd64" ] && continue

    NAME=$(grep "^name=" "$folder/module.prop" | cut -f2 -d"=")
    [ -n "$ZYGISK_NAME" ] && [ "$NAME" != "$ZYGISK_NAME" ] && abort "$ZYGISK_MULTI_ERR"
    ZYGISK_NAME=$NAME
done

if [ -z "$ZYGISK_NAME" ]
then
    if [ "$ZYGISK_ENABLED" == "1" ]
    then
        ZYGISK_NAME="$FALLBACK_ZYGISK_NAME"
    else
        # because ZYGISK_ENABLED is not working properly
        MAGISK_ZYGISK=$(magisk --sqlite "SELECT value FROM settings WHERE key = 'zygisk'" 2> /dev/null | cut -f2 -d=)

        if [ "$MAGISK_ZYGISK" == "1" ]
        then
            ZYGISK_NAME="$FALLBACK_ZYGISK_NAME"
        fi
    fi
fi

# not installed zygisk
if [ -z "$ZYGISK_NAME" ]; then
    abort "$ZYGISK_NOT_FOUND_ERR"
else
    ZYGISK_DETECTED_MSG "$ZYGISK_NAME"
fi
