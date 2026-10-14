#!/usr/bin/env bash
# Host + VM environment block. Run on the macOS host; writes $1/env.txt.
set -euo pipefail
out=$1 profile=${RTOKBENCH_PROFILE:-rtok-bench} image=$2
{
  echo "host_model: $(sysctl -n hw.model)"
  echo "host_cpu: $(sysctl -n machdep.cpu.brand_string)"
  echo "host_ncpu: $(sysctl -n hw.ncpu)"
  echo "host_ram_bytes: $(sysctl -n hw.memsize)"
  echo "macos: $(sw_vers -productVersion)"
  echo "colima: $(colima version | head -1)"
  colima list -p "$profile" 2>/dev/null || colima list | grep "$profile"
  grep -E '^(cpu|memory|disk|vmType|arch|runtime):' "$HOME/.colima/$profile/colima.yaml"
  echo "image_id: $(docker image inspect "$image" -f '{{.Id}}')"
  echo "base_image: $(docker image inspect "$image" -f '{{index .Config.Labels "bench.base"}}')"
  echo "docker_run: --cpus 4 --memory 8g --memory-swap 8g"
} > "$out/env.txt"
