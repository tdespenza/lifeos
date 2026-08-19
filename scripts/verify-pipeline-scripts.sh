#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PIPELINE_SCRIPTS=(
    build-container-images.sh
    scan-source-security.sh
    scan-container-images.sh
    verify-sbom.sh
    deploy-staging.sh
    staging-smoke-test.sh
    verify-architecture.sh
    end-to-end-smoke-test.sh
    performance-smoke-test.sh
    run-chaos-experiment.sh
    provision-local-databases.sh
    start-local-blockchain.sh
    verify-blockchain-foundation.sh
)

for script in "${PIPELINE_SCRIPTS[@]}"; do
    bash -n "${REPOSITORY_ROOT}/scripts/${script}"
done

printf '%s\n' "Validated ${#PIPELINE_SCRIPTS[@]} CI/CD and operational shell scripts"
