#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly PIPELINE_SCRIPTS=(
    build-container-images.sh
    scan-source-security.sh
    scan-container-images.sh
    verify-sbom.sh
    deploy-staging.sh
    https-authority-validation.sh
    staging-smoke-test.sh
    verify-architecture.sh
    end-to-end-smoke-test.sh
    performance-smoke-test.sh
    run-chaos-experiment.sh
    provision-local-databases.sh
    test-operational-scripts.sh
    test-provision-databases-concurrency.sh
)

if ! command -v node >/dev/null 2>&1; then
    echo "node is required to validate the k6 performance smoke script" >&2
    exit 69
fi

for script in "${PIPELINE_SCRIPTS[@]}"; do
    bash -n "${REPOSITORY_ROOT}/scripts/${script}"
done

node --check "${REPOSITORY_ROOT}/scripts/performance/readiness-smoke.js"
node --check "${REPOSITORY_ROOT}/scripts/validate-cyclonedx-schema.js"

printf '%s\n' "Validated ${#PIPELINE_SCRIPTS[@]} CI/CD and operational shell scripts"
