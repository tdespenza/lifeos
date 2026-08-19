# LifeOS CLI

`cli:lifeos-cli` is a Java 25, read-only local helper for the Trust Ledger proof boundary. It never
uploads files, stores credentials, or calls a service. The `hash` command streams a regular file
through SHA-256 using a fixed 32 KiB buffer and refuses inputs larger than 64 MiB.

```bash
./gradlew :cli:lifeos-cli:installDist
cli/lifeos-cli/build/install/lifeos-cli/bin/lifeos-cli hash ./example.pdf
```

Output is a single JSON object containing `algorithm`, `bytes`, and the lowercase 64-character
`digest`. The digest can be submitted through the authenticated Trust Ledger document-proof API;
the CLI intentionally does not perform that network operation. `version` prints the CLI version.

The command is deterministic for a given byte stream, runs in O(n) time, and uses O(1) additional
memory relative to input size.
