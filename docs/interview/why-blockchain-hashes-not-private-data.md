# Why Only Hashes, Never Private Data, Go On-Chain

If you ask me this in an interview, the honest first sentence is: LifeOS has a Trust Ledger proof
foundation, not a production blockchain network. `contracts:trust-ledger` and
`trust-ledger-service` generate bounded stateless document hashes and Merkle proofs, verify paths
locally, and expose an opt-in digest-only Web3j/Besu anchor boundary with receipt tracking. The
private Besu network, production key management, and staging evidence remain deployment work.
AI audit commitments now have a separate hash-only outbox and opt-in Trust Ledger projection, but
no AI chain anchor is claimed. The design principle behind that boundary is captured in ADR-013,
ADR-025, and ADR-045.

The principle is simple: a blockchain, public or private, is a write-once, broadcast-to-every-node data structure. Anything you put there is either permanent or extremely hard to remove, and in the private-Besu-network design I'm planning, every validator node holds a full copy. That's the opposite of what you want for personal data — documents, journal entries, financial records. So the plan is to keep all of that in Postgres (or object storage for file bytes), and only ever anchor a hash: a Merkle root over document hashes, plus minimal metadata like a timestamp and a document ID reference. The chain's job isn't to store what the document says — it's to let anyone independently prove the document *hasn't changed* since a given point in time, without having to trust LifeOS's own database not to have been quietly edited.

That's the actual problem on-chain anchoring would solve: a database row that says "unmodified" is
not credible evidence, because whoever has write access to the database can also rewrite the audit
trail. The current stateless proof API deliberately does not make that claim. An immutable log with
its own execution semantics, even a private one, changes the evidence boundary only after a root is
durably submitted and confirmed. That only holds if the anchored material is genuinely
non-sensitive, though — a hash is not reversible, but it is not a zero-information commitment
either. A low-entropy document can be brute-forced offline, and surrounding metadata can leak what
happened and when. The honest claim is "the hash alone does not expose document contents," not
"anchoring leaks zero private data." A verifier must be able to recompute a proof, so a public salt
does not protect a small guessable input. A per-document owner-controlled commitment design remains
an explicit future decision; adding a salt is not a complete solution.

Why private Besu and not a public chain, given the goal is just tamper-evidence? That's really ADR-013's territory — gas costs, public exposure of anchoring metadata, and a decentralization guarantee this single-tenant app's threat model doesn't call for. Worth being precise about what "tamper-evident" actually buys me here, though: a private Besu network with a small, LifeOS-operated validator set doesn't move trust to some external decentralized authority the way a public chain would — if I control the validators, I still control what the ledger says. What it does give me is a structural one: rewriting history now means rewriting an append-only log with its own consensus rules across every validator, not just editing a row in a database I already have write access to, which is a meaningfully higher bar even under single-operator governance. If that governance model ever changes — e.g., multiple independent operators running validators — the trust story gets strictly stronger, which is itself worth documenting as an explicit assumption rather than leaving implicit.

Relevant ADRs: [ADR-013](../adr/ADR-013-use-web3j-and-besu-for-blockchain.md), [ADR-025](../adr/ADR-025-bounded-document-proof-core.md)
