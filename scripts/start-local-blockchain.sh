#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/infrastructure/docker-compose/docker-compose.yml"

: "${LIFEOS_POSTGRES_USER:=local}"
: "${LIFEOS_POSTGRES_PASSWORD:=local}"
export LIFEOS_POSTGRES_USER LIFEOS_POSTGRES_PASSWORD

docker compose -f "$compose_file" --profile blockchain up -d besu

deadline=$((SECONDS + 30))
while (( SECONDS < deadline )); do
  if curl --fail --silent --show-error \
      -H 'Content-Type: application/json' \
      --data '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' \
      http://127.0.0.1:8545/ | grep -q '0x539'; then
    printf '%s\n' 'Local Besu dev network is ready at http://127.0.0.1:8545 (chain id 1337).'
    printf '%s\n' 'Compile/deploy contracts/trust-ledger/src/main/solidity/AnchorRegistry.sol with a reviewed toolchain, then set TRUST_LEDGER_BESU_CONTRACT_ADDRESS.'
    exit 0
  fi
  sleep 1
done

printf '%s\n' 'Timed out waiting for the local Besu JSON-RPC endpoint.' >&2
exit 69
