#!/usr/bin/env bash
# rtok vs rtk benchmark. Run on the macOS host from the repository root:
#   bench/competitors/rtk/run.sh
# One fixed Colima profile and one fixed container limit for every competitor:
#   colima profile rtok-bench: 4 CPUs, 8 GiB, vz; docker run --cpus 4 --memory 8g.
set -euo pipefail
PROFILE=${RTOKBENCH_PROFILE:-rtok-bench}
BASE_TAG=${BASE_TAG:-rust:1.98.1-bookworm}
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/../../.." && pwd)
out="$here/results/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$out"

colima status -p "$PROFILE" >/dev/null 2>&1 || \
  colima start -p "$PROFILE" --cpu 4 --memory 8 --disk 60 --vm-type vz
export DOCKER_HOST="unix://$HOME/.colima/$PROFILE/docker.sock"
export DOCKER_CONFIG=${DOCKER_CONFIG:-/tmp/dcfg}; mkdir -p "$DOCKER_CONFIG"

docker pull -q "$BASE_TAG" >/dev/null
BASE=$(docker image inspect "$BASE_TAG" -f '{{index .RepoDigests 0}}')
docker build -f "$here/Dockerfile" --build-arg BASE="$BASE" -t rtokbench/rtk "$root"
bash "$here/../_lib/env.sh" "$out" rtokbench/rtk
docker run --rm --cpus 4 --memory 8g --memory-swap 8g --network none \
  -e RUNS="${RUNS:-30}" -v "$out:/out" rtokbench/rtk
echo "results: $out"
