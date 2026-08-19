# Blockchain Lab

The executable slice covers Merkle construction, document hash proofs, Bloom-filter lookup, a
bounded majority-consensus simulator, a local chain-client/receipt boundary, Bloom-assisted
transaction indexing, and credential proof verification. Private document content never enters a
transaction; only a root and minimal versioned metadata may be anchored. Exercises use synthetic
inputs, bounded counts, explicit confirmation deadlines, and a deterministic local verifier.

The Trust Ledger service's stateless proof primitives are production code; these exercises are
integration learning material and do not claim a live Besu/Web3j chain. Live network, wallet,
transaction submission, and external key-management integration remain deployment work.

```bash
./gradlew :labs:blockchain-lab:run
./gradlew :labs:blockchain-lab:test
```
