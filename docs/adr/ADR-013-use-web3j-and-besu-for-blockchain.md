# ADR-013: Use Web3j + Hyperledger Besu for blockchain document-integrity proofs

## Context

LifeOS's document vault needs to prove that a stored document has not been altered since upload, and let a user (or a third party they choose to share with) verify that proof independently of LifeOS's own database. A database row saying "unmodified" is not credible evidence — an operator with write access to Postgres can also rewrite the audit trail. We want a verification mechanism whose trust model does not reduce to "trust the LifeOS backend."

At the same time, this is a personal productivity application, not a financial or multi-party settlement system. There is no shared ledger of value between mutually distrusting organizations, no token, and no requirement for public, permissionless consensus. Per the project's design principle, private document contents must never be written on-chain — only content hashes, Merkle roots, and minimal verification metadata are anchored.

The project also has a secondary, explicit goal: demonstrate blockchain engineering competency (smart contracts, a Java client library, running and operating a private network) as part of a FAANG-style portfolio, which rules out silently dropping blockchain in favor of a pattern that produces equivalent guarantees without it.

## Options Considered

1. **Public chain (Ethereum mainnet or a public L2 such as Optimism/Arbitrum).** Provides real decentralization and third-party verifiability without LifeOS operating any infrastructure. Rejected because it imposes real gas costs per anchoring event on a free personal app, exposes anchoring transactions and timing metadata to public observers, and provides a security/decentralization guarantee (resistance to a colluding global validator set) that this single-tenant personal app has no threat model requiring.

2. **Non-blockchain tamper-evident log (append-only signed hash chain / Merkle-tree transparency log, à la Certificate Transparency).** Operationally far simpler — no consensus, no smart contract, no node to run — and gives comparable tamper-evidence via periodic signed checkpoints. Rejected as the primary mechanism because it does not demonstrate blockchain/smart-contract engineering, and it still concentrates root-of-trust in whichever party countersigns and publishes checkpoints, which is a weaker independence guarantee than an immutable ledger with its own execution semantics.

3. **A different Java-friendly chain SDK (e.g., Hyperledger Fabric Java SDK, Corda).** Fabric/Corda are built for multi-organization consortiums with channel-based privacy and are heavier to operate for a single-operator app; Web3j + Besu gives an Ethereum-compatible, well-documented, actively maintained Java client against an enterprise-grade Ethereum client (Besu) with a much larger contract-tooling ecosystem (Solidity, standard ABI tooling), which better serves both the app and the portfolio-demonstration goal.

## Decision Made

Run a private Hyperledger Besu network (IBFT 2.0 or QBFT consensus, single- or few-node for this deployment) and use Web3j as the Java client to deploy a Solidity smart contract that anchors Merkle roots of document hashes. Documents and their hashes stay in the private data store; only the Merkle root and minimal metadata (timestamp, document ID reference, proof version) are written on-chain.

## Why

A private Besu network gives us an immutable, independently-inspectable append log with real smart-contract semantics (the same primitives used in production blockchain integrity systems) while keeping full control over cost (no real gas market — we set the private network's gas parameters), data residency (nothing leaves LifeOS infrastructure), and operational blast radius (compromise of the network doesn't put user funds at risk because there is no token of value). Web3j is the most mature Java Ethereum client library, integrates cleanly with a Spring Boot service, and lets the same skill set (Solidity, EVM semantics, contract deployment/verification) that's valuable for public-chain work be demonstrated without inheriting public-chain costs or exposure.

## Tradeoffs

- We give up the strongest form of third-party verifiability: on a private network, LifeOS controls the validator set, so a sufficiently determined operator could theoretically rewrite history by colluding with/controlling the validating nodes — something a public chain would make practically infeasible. We accept this because the threat model here is accidental corruption and internal tamper-evidence, not adversarial multi-party disputes.
- We take on real operational burden: running Besu nodes (or a small IBFT cluster), managing validator keys, monitoring block production, and handling contract upgrades/migrations — work a managed non-blockchain log would not require.
- Smart contract bugs are effectively immutable once deployed (short of a proxy-upgrade pattern), so the anchoring contract needs the same rigor as a production financial contract even though no money moves through it.

## Consequences

- The document vault service gains a hard dependency on Besu node liveness for the "anchor" write path; anchoring must be decoupled (async, queued via Kafka) from the synchronous upload path so a slow or down node never blocks document upload.
- We need key management for the deploying/anchoring account (HSM or at minimum a securely stored keystore, never in application config).
- The team must own Solidity contract testing (unit tests via Hardhat/Foundry-equivalent or web3j-native test harness) as a first-class part of CI, not an afterthought.
- Verification tooling (recomputing a Merkle proof and checking it against the on-chain root) must be exposed to users/clients so the "verify independently" promise is real and not just internal.

## When This Decision Would Be Wrong

If LifeOS ever needed cross-organization document verification — e.g., a legal or compliance partner needing to independently verify integrity without trusting any LifeOS-operated infrastructure at all — a private network stops being sufficient and the anchoring root would need to be periodically checkpointed to a public chain or transparency log instead. Similarly, if the validator set never grows past a single LifeOS-controlled node in production (i.e., we never actually achieve multi-node consensus), the "immutability" guarantee is illusory and we should be honest that we're running a signed hash chain with extra steps — at that point, replacing Besu with the simpler non-blockchain transparency log (Option 2) and being explicit about the tradeoff would be the more defensible engineering choice.

## How We Will Validate It

- **Throughput/latency benchmark:** measure end-to-end anchoring latency (hash generation → Merkle proof → transaction inclusion) under a synthetic load of 100 concurrent document uploads; target p95 anchor-confirmation time under 5 seconds on the private network (IBFT block time tuned accordingly), verified via an OpenTelemetry trace spanning the async anchoring pipeline.
- **Verification correctness test:** an automated integration test that uploads N documents, tampers with one off-chain copy, and asserts the Merkle-proof verification against the on-chain root fails exactly for the tampered document and passes for all others (zero false positives/negatives across a 10,000-document synthetic corpus).
- **Node resilience drill:** kill a validator node mid-anchoring-batch and confirm the queue-backed anchoring pipeline retries and eventually succeeds with no lost or duplicated anchors, measuring recovery time to healthy quorum.
- **Cost/operational check:** track private-network resource usage (CPU/memory per validator, storage growth rate of the chain) monthly against the resource budget for the self-hosted deployment tier, to catch unbounded chain growth before it becomes an operational problem.
