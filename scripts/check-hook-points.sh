#!/usr/bin/env bash
# Which hook sites answer before the framework's own call, and which put the argument back.
#
# A hidden lookup is given a name no package carries instead of the asked one, and a generator is
# given no package setting to build a result from, so the framework itself produces the answer it
# produces for a package that is not installed, with its own permission checks in front of it. Every
# such site is a hookBefore: the hook never replaces the answer. The argument it wrote over is put
# back by the restore step of the same registration, which runs when the call is done, whatever the
# call answered. An after hook would answer the call instead, so no site here has one.
set -euo pipefail
cd "$(dirname "$0")/.."

# Every site that must answer before the call, each with its restore step. The two names Android
# gives the archived-package lookup are listed apart: whichever the release carries is rewritten.
rewritten='getPackageInfoInternalBody getApplicationInfoInternalBody generatePackageInfo getPackageSettingInternal getArchivedPackageInternal getArchivedPackage'

# The one site whose answer is a decision rather than a value. There is no name argument to rewrite and
# no restore to make: the filter is asked whether a caller may see a package, and a hidden one answers
# "no" exactly as it would for a package that is not installed -- the caller's view is the same either
# way, and the filter performs no permission check of its own. Intent resolution and package lists reach
# this and nothing else.
filtering='shouldFilterApplication'

status=0

# One line per hook registration in the target files: file|token|method|restore. The call is read
# across as many lines as it spans, and a method that is not written as a literal comes out as
# <expression>, because a check that cannot read the name cannot check the site.
sites=$(
  for file in zygote/src/main/java/io/github/grachevskaya/asya/zygote/hook/PmsHookTarget3*.kt; do
    awk -v file="$(basename "$file")" '
      { text = text $0 "\n" }
      END {
        while ((start = match(text, /hook[A-Za-z]+\(/)) > 0) {
          token = substr(text, start, RLENGTH - 1)
          rest = substr(text, start + RLENGTH)
          depth = 1
          used = 0
          while (used < length(rest) && depth > 0) {
            used++
            character = substr(rest, used, 1)
            if (character == "(") depth++
            else if (character == ")") depth--
          }
          args = substr(rest, 1, used - 1)
          text = substr(rest, used + 1)

          count = 0
          depth = 0
          current = ""
          for (at = 1; at <= length(args); at++) {
            character = substr(args, at, 1)
            if (character == "(" || character == "[" || character == "{") depth++
            if (character == ")" || character == "]" || character == "}") depth--
            if (character == "," && depth == 0) {
              arguments[++count] = current
              current = ""
              continue
            }
            current = current character
          }
          if (current != "" && current !~ /^[ \t\n]*$/) arguments[++count] = current

          method = ""
          if (count >= 2) {
            second = arguments[2]
            gsub(/^[ \t\n]+/, "", second)
            gsub(/[ \t\n]+$/, "", second)
            if (second ~ /^".*"$/) {
              gsub(/"/, "", second)
              method = second
            } else if (second != "") {
              method = "<" second ">"
            }
          }

          restore = (args ~ /(^|,)[ \t\n]*restore[ \t\n]*=/) ? "yes" : "no"
          print file "|" token "|" method "|" restore
        }
      }
    ' "$file"
  done
)

# A method a hook names through an expression cannot be read, so it is not accepted.
while IFS='|' read -r file token method restore; do
  [ -n "${method:-}" ] || continue
  case "$method" in
    \<*\>)
      echo "  $file: $token names its method as $method; a hook point must name its method"
      status=1
      ;;
  esac
done <<< "$sites"

# The after half of a rewritten call is the restore step of its own registration: a hook that runs
# after the call and answers is what these sites must not be.
while IFS='|' read -r file token method restore; do
  case "$token" in
    hookAfter)
        case "$method" in
          getChangedPackages) ;;
          *)
      echo "  $file: $token answers after the call; a rewritten site restores its argument instead"
            status=1
            ;;
        esac
      ;;
  esac
done <<< "$sites"

# Each site of the list answers before the call, and says where its argument comes back.
for method in $rewritten; do
  registered=0

  while IFS='|' read -r file token siteMethod restore; do
    [ "${siteMethod:-}" = "$method" ] || continue
    registered=1

    case "$token" in
      hookBefore)
        if [ "$restore" != "yes" ]; then
          echo "  $file: $method answers before the call and puts no argument back"
        fi
        ;;
      *)
        echo "  $file: $method must answer before the call, not with $token"
        status=1
        ;;
    esac
  done <<< "$sites"

  if [ "$registered" -eq 0 ]; then
    echo "  $method: no hook point registers it"
    status=1
  fi
done

if [ "$status" -ne 0 ]; then
  echo "hook points: FAILED"
  exit 1
fi
echo "hook points: OK"
