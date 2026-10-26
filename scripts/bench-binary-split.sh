#!/bin/bash
# usage: bench.sh <worktree> <label>
set -u; cd "$1"; L=$2; OUT=~/Grok/rtok-kotlin-split/bench/$L; mkdir -p $OUT
G="mise exec -- ./gradlew -q --no-build-cache"
t(){ local s=$(python3 -c 'import time;print(time.time())'); "$@" >/dev/null 2>&1; python3 -c "import time;print(round(time.time()-$s,1))"; }
$G :rtok:linkDebugExecutableMacosArm64 >/dev/null 2>&1   # warm daemon + ~/.konan dep caches
: > $OUT/times.txt
for i in 1 2 3; do $G clean >/dev/null 2>&1; echo "clean_debug $(t $G :rtok:linkDebugExecutableMacosArm64)" >> $OUT/times.txt; done
F=rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt; cp $F /tmp/fs.$L
for i in 1 2 3; do echo "// bench $i" >> $F; echo "incr_debug $(t $G :rtok:linkDebugExecutableMacosArm64)" >> $OUT/times.txt; done
cp /tmp/fs.$L $F; $G :rtok:linkReleaseExecutableMacosArm64 >/dev/null 2>&1
for i in 1 2 3; do echo "release_link $(t $G :rtok:linkReleaseExecutableMacosArm64 --rerun)" >> $OUT/times.txt; done
$G :rtok:linkDebugExecutableMacosArm64 >/dev/null 2>&1
B=rtok/build/bin/macosArm64; { stat -f "release %z" $B/releaseExecutable/rtok.kexe; stat -f "debug %z" $B/debugExecutable/rtok.kexe; find rtok-sys/build/native -name '*.a' -exec stat -f "%N %z" {} \; ; } > $OUT/sizes.txt
R=$B/releaseExecutable/rtok.kexe; H=$(mktemp -d); export HOME=$H
hyperfine -N -w 5 -r 50 --export-json $OUT/hf.json "$R --version" "$R plugins" "$R config validate" > $OUT/hf.txt 2>&1
for c in "--version" "plugins" "config validate"; do echo "$c $( /usr/bin/time -l $R $c 2>&1 >/dev/null | awk '/maximum resident/{print $1}')" ; done > $OUT/rss.txt
echo done
