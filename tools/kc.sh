#!/bin/bash
# Local pure-JVM Kotlin compile (no Gradle/Maven needed). Usage:
#   tools/kc.sh <outdir> <extra-classpath-or-""> <source files/dirs...>
# Run:  java -cp "<outdir>:$(tools/kc.sh --cp)" <MainClass>
G=/opt/gradle-8.14.3/lib
RUN_CP=$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/junit-4.13.2.jar:$(ls $G/hamcrest-core-*.jar $G/hamcrest-*.jar 2>/dev/null | head -1)
if [ "$1" = "--cp" ]; then echo "$RUN_CP"; exit 0; fi
OUT=$1; EXTRA=$2; shift 2
COMP=$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-script-runtime-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/kotlin-daemon-embeddable-2.0.21.jar:$G/annotations-24.0.1.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar
mkdir -p "$OUT"
java -Xmx2g -cp "$COMP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn \
  -cp "$RUN_CP${EXTRA:+:$EXTRA}" -d "$OUT" "$@" 2>&1 | grep -v "^Picked up"
exit ${PIPESTATUS[0]}
