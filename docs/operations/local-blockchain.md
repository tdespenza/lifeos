# Local blockchain foundation

The repository includes an explicitly local-only Hyperledger Besu development profile and a
digest-only `AnchorRegistry` contract source. It is a reproducible development foundation, not a
production consensus network: the profile runs one Besu `dev` node, exposes JSON-RPC only on
loopback, and must not be used to make production immutability claims.

Start the node with:

```bash
bash scripts/start-local-blockchain.sh
```

The node reports chain ID `1337` at `http://127.0.0.1:8545`. Compile and deploy
`contracts/trust-ledger/src/main/solidity/AnchorRegistry.sol` with the reviewed Solidity/Web3j
deployment toolchain used by the target environment, then set `TRUST_LEDGER_BESU_CONTRACT_ADDRESS`,
`TRUST_LEDGER_BESU_PRIVATE_KEY`, and `TRUST_LEDGER_BESU_ENABLED=true` for Trust Ledger.

`anchorRoot(bytes32)` records only a digest and first-anchor timestamp. It rejects the zero digest
and is idempotent for an already anchored digest. No document bytes, prompts, account identifiers,
or filenames are accepted by the contract.

The remaining production work is intentionally explicit: a multi-node IBFT/QBFT topology, validator
key management, contract deployment/upgrade controls, TLS/authenticated RPC, backup/recovery, and
staging evidence. Verify the checked-in foundation without Docker using:

```bash
bash scripts/verify-blockchain-foundation.sh
```
