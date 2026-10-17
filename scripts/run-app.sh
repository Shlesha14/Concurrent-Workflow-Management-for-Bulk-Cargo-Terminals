#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/portflow-1.0.0.jar ] || mvn -q clean package -DskipTests
java -jar target/portflow-1.0.0.jar
