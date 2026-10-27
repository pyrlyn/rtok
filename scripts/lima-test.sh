#!/bin/bash
# Runs the linuxArm64 test binaries in a Lima VM (docs/testing.md "Linux"). Kotlin/Native cannot build on a Linux
# arm64 host, so the binaries are cross-linked on the Mac, copied into the VM's writable mount and run there with the
# environment the Gradle test tasks give them. Unit binaries print gtest lines; the Kotest specs (rtok CLI, e2e) a summary.
#
#   ./gradlew -Prtok.allTargets=true linkDebugTestLinuxArm64 :rtok:linkDebugExecutableLinuxArm64
#   LIMA_VM=rtok-linux LIMA_MOUNT=~/lima-rtok scripts/lima-test.sh [module...]
#
# LIMA_MOUNT must be a host directory the VM mounts writable at the same path. Exit 1 if any binary failed.
set -u
VM=${LIMA_VM:?LIMA_VM: the Lima instance name}
MNT=${LIMA_MOUNT:?LIMA_MOUNT: a host dir mounted writable in the VM at the same path}
SRC=$(cd "$(dirname "$0")/.." && pwd)
DST=$MNT/rtok-kotlin
mkdir -p "$DST"
rsync -a --delete --exclude .gradle --exclude .kotlin --exclude node_modules --include '*/' \
  --include '**/build/bin/linuxArm64/debugTest/**' --include '**/build/bin/linuxArm64/debugExecutable/**' \
  --exclude '**/build/**' "$SRC/" "$DST/"
cat > "$MNT/run-in-vm.sh" <<'IN'
#!/bin/bash
R=$1; shift; OUT=$R/build/lima-results; rm -rf "$OUT"; mkdir -p "$OUT"; bad=0
cd "$R" || exit 2
git config --global user.email >/dev/null || { git config --global user.email t@example.com; git config --global user.name t; }
for k in */build/bin/linuxArm64/debugTest/test.kexe libs/*/build/bin/linuxArm64/debugTest/test.kexe; do
  [ -f "$k" ] || continue
  m=${k%%/build/*}; [ $# -gt 0 ] && [[ " $* " != *" $m "* ]] && continue
  name=$(echo "$m" | tr / _); H=$(mktemp -d); mkdir -p "$H/.rtok"
  envs=(HOME=$H RTOK_HOME=$H/.rtok)
  case $m in
    rtok) envs+=(RTOK_FIXTURES=$R/rtok/src/commonTest/fixtures RTOK_WASM_FIXTURES=$R/rtok-wasm/src/nativeTest/fixtures SNAPSHOT_DIR=$R/rtok/src/commonTest/snapshots) ;;
    rtok-store) envs+=(RTOK_FIXTURES=$R/rtok-store/src/nativeTest/fixtures) ;;
    rtok-wasm) envs+=(RTOK_WASM_FIXTURES=$R/rtok-wasm/src/nativeTest/fixtures) ;;
    rtok-e2e) envs+=(RTOK_BIN=$R/rtok/build/bin/linuxArm64/debugExecutable/rtok.kexe) ;;
  esac
  (cd "$R/$m" && env "${envs[@]}" timeout 1800 ./build/bin/linuxArm64/debugTest/test.kexe >"$OUT/$name.log" 2>&1); rc=$?
  ok=$(grep -c '^\[       OK \]' "$OUT/$name.log"); failed=$(grep '^\[  FAILED  \] [a-z]' "$OUT/$name.log" | grep -vc ' ms)$')
  kt=$(sed 's/\x1b\[[0-9;]*m//g' "$OUT/$name.log" | grep -E '^Tests:' | tail -1)
  case $kt in *", 0 failed"*|"") ;; *) rc=1 ;; esac
  [ "$rc" = 0 ] || bad=1
  echo "$name rc=$rc gtest_ok=$ok gtest_failed=$failed ${kt:+kotest: $kt}"
done
exit $bad
IN
chmod +x "$MNT/run-in-vm.sh"
exec limactl shell "$VM" -- "$MNT/run-in-vm.sh" "$DST" "$@"
