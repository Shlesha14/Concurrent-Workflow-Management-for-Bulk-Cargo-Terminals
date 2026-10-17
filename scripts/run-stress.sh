#!/usr/bin/env bash
# Usage: ./scripts/run-stress.sh cargo=100 cranes=2 forklifts=2 trucks=2 workers=8 policy=PERISHABILITY speed=6000   (resets data!)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/portflow-1.0.0.jar ] || mvn -q clean package -DskipTests
java -cp target/portflow-1.0.0.jar com.portflow.simulation.StressTest "$@"
