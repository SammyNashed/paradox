#!/usr/bin/env bash
# Compiles Paradox against the deobfuscated 26.2 client jar with Mojang's bundled JDK 25.
# No Gradle, no Loom: 26.x ships unobfuscated, so there is nothing to remap.
set -e
P="$(cd "$(dirname "$0")" && pwd)"
JH="/c/Users/sammy/AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/windows/java-runtime-epsilon/bin"
CP=$(for j in "$P"/libs/*.jar; do cygpath -w "$j"; done | paste -sd';' -)

rm -rf "$P/build/classes"; mkdir -p "$P/build/classes"
find "$P/src/main/java" -name '*.java' -exec cygpath -w {} \; > "$P/build/sources.txt"

"$JH/javac.exe" -encoding UTF-8 -nowarn \
  -cp "$CP" -d "$(cygpath -w "$P/build/classes")" \
  @"$(cygpath -w "$P/build/sources.txt")"

cp -r "$P/src/main/resources/." "$P/build/classes/"
VER=$(python -c "import json,io,sys;print(json.load(io.open(sys.argv[1],encoding='utf-8'))['version'])" "$(cygpath -w "$P/src/main/resources/fabric.mod.json")")
JAR="$P/build/paradox-$VER.jar"
rm -f "$P/build"/paradox-*.jar
(cd "$P/build/classes" && "$JH/jar.exe" --create --file "$(cygpath -w "$JAR")" .)
echo "built: $JAR"
