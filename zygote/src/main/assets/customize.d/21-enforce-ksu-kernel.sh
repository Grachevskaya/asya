#!/system/bin/sh

# Enforce KernelSU kernel and manager versions matched

if [ "$KSU" ]; then
    # get language
    [ -z "$SYSTEM_LANG" ] && SYSTEM_LANG=$(getprop persist.sys.locale 2>/dev/null)
    [ -z "$SYSTEM_LANG" ] && SYSTEM_LANG=$(getprop persist.sys.language 2>/dev/null)
    [ -z "$SYSTEM_LANG" ] && SYSTEM_LANG=$(settings get system system_locales 2>/dev/null)

    # default language
    KSU_VERSION_MISMATCH_WARN="! KernelSU manager version ($KSU_VER_CODE) does not match kernel driver version ($KSU_KERNEL_VER_CODE). asya will not take any responsibilities for incompatibilities!"
    INSTALLER_CONTINUE_MSG(){
        echo "- The installer will continue in $1 seconds"
    }

    # language pack
    if echo "$SYSTEM_LANG" | grep -q "zh"; then
        KSU_VERSION_MISMATCH_WARN="! Warning: the KernelSU manager ($KSU_VER_CODE) and the driver ($KSU_KERNEL_VER_CODE) do not match; asya is not responsible for what that causes, and the module may not activate"
        INSTALLER_CONTINUE_MSG(){
            echo "- Installation continues in $1 second(s)"
        }
    fi

    # kernel and manager version mismatch
    if [ "$KSU_VER_CODE" != "$KSU_KERNEL_VER_CODE" ]; then
        ui_print "$KSU_VERSION_MISMATCH_WARN"
        ui_print "$(INSTALLER_CONTINUE_MSG 5)"
        sleep 5
    fi
fi
