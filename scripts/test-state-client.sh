#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
work="$repo/.scratch/state-client-test"
mkdir -p "$work/tmp"
export TMPDIR="$work/tmp"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
jar() { find "$cache/$1" -name "$2" -print | sort -V | tail -1; }
compiler=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-*.jar')
version=$(basename "$compiler" .jar); version=${version#kotlin-compiler-embeddable-}
stdlib=$(jar "org.jetbrains.kotlin/kotlin-stdlib/$version" "kotlin-stdlib-$version.jar")
script=$(jar "org.jetbrains.kotlin/kotlin-script-runtime/$version" '*.jar')
reflect=$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-*.jar')
coroutines=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar')
annotations=$(jar org.jetbrains/annotations 'annotations-[0-9]*.jar')
cp="$compiler:$stdlib:$script:$reflect:$coroutines:$annotations"
src="$repo/zygote/src/main/java/io/github/grachevskaya/asya/zygote/service"
java -Djava.io.tmpdir="$TMPDIR" --enable-native-access=ALL-UNNAMED -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib:$annotations" -d "$work/probe.jar" "$repo"/scripts/state-client-test/*.kt "$src/StateClient.kt" "$src/StateWire.kt" "$src/StateStore.kt"
java -Djava.io.tmpdir="$TMPDIR" -cp "$work/probe.jar:$stdlib" io.github.grachevskaya.asya.zygote.service.StateClientProbeKt "$work"
