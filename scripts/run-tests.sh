#!/usr/bin/env bash
# Needs a scratch schema:  mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS portflow_test"
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PF_TEST_USER:=root}"; : "${PF_TEST_PASSWORD:=}"
mvn test -Dportflow.test.url="${PF_TEST_URL:-jdbc:mysql://localhost:3306/portflow_test?useSSL=false&allowPublicKeyRetrieval=true}" \
         -Dportflow.test.user="$PF_TEST_USER" -Dportflow.test.password="$PF_TEST_PASSWORD"
