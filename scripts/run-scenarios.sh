#!/usr/bin/env bash
# Usage: ./scripts/run-scenarios.sh [A-H|ALL]   WARNING: resets data in the configured database (portflow.properties)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/portflow-1.0.0.jar ] || mvn -q clean package -DskipTests
java -cp target/portflow-1.0.0.jar com.portflow.simulation.ScenarioCli "${1:-ALL}"
