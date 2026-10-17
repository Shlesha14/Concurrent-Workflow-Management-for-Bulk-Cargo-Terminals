#!/usr/bin/env bash
# Usage: ./scripts/setup-db.sh [mysql-user]   (prompts for the password). Creates schema 'portflow' with demo data.
set -euo pipefail
cd "$(dirname "$0")/.."
USER_NAME="${1:-root}"
for f in 01_schema 02_triggers 03_views 04_seed; do
  echo "running database/$f.sql"; mysql -u "$USER_NAME" -p < "database/$f.sql"
done
echo "Database 'portflow' is ready."
