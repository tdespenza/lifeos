#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/infrastructure/docker-compose/docker-compose.yml"
contract="$repo_root/contracts/trust-ledger/src/main/solidity/AnchorRegistry.sol"
starter="$repo_root/scripts/start-local-blockchain.sh"
certificate_controller="$repo_root/services/trust-ledger-service/src/main/java/com/lifeos/trustledger/api/TrustLedgerController.java"
certificate_entity="$repo_root/services/trust-ledger-service/src/main/java/com/lifeos/trustledger/certificate/TrustGoalCertificate.java"
certificate_projection="$repo_root/services/task-goal-service/src/main/java/com/lifeos/taskgoal/projection/TaskGoalCertificateProjectionController.java"

[[ -s "$contract" ]] || { printf 'missing digest-only contract source\n' >&2; exit 65; }
[[ -x "$starter" ]] || { printf 'blockchain starter must be executable\n' >&2; exit 65; }
[[ -s "$certificate_entity" && -s "$certificate_projection" ]] || {
  printf 'missing completed-goal certificate projection boundary\n' >&2
  exit 65
}
rg -q 'image: hyperledger/besu:' "$compose_file"
rg -q 'profiles: \["blockchain"\]' "$compose_file"
rg -q -- '--network=dev' "$compose_file"
rg -q 'function anchorRoot\(bytes32 digest\)' "$contract"
rg -q 'mapping\(bytes32 => uint256\) public anchoredAt' "$contract"
rg -q 'TRUST_LEDGER_BESU_CONTRACT_ADDRESS' "$starter"
rg -q '/api/v1/trust/goal-certificates' "$certificate_controller"
rg -q 'PENDING_EXTERNAL_ANCHOR' "$certificate_entity"
printf '%s\n' 'local blockchain foundation verified (profile, digest-only contract, bounded readiness helper)'
