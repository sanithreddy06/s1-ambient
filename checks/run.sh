#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${JAVA_HOME:=$PWD/.tools/jdk-17.0.20.1+1/Contents/Home}"
export JAVA_HOME
./gradlew assembleDebug lintDebug
S1_STDLIB=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.20" -name '*.jar' -print -quit)
S1_CP="app/build/tmp/kotlin-classes/debug:.tools/android-sdk/platforms/android-35/android.jar:$S1_STDLIB"
"$JAVA_HOME/bin/javac" -cp "$S1_CP" -d .tools/checks checks/LogicChecks.java checks/Milestone3Checks.java
"$JAVA_HOME/bin/java" -cp ".tools/checks:$S1_CP" com.s1ambient.LogicChecks
"$JAVA_HOME/bin/java" -cp ".tools/checks:$S1_CP" com.s1ambient.Milestone3Checks
