# AI Lab

Bounded executable exercises cover deterministic embeddings/vector search, prompt templates,
local and cloud-compatible provider abstraction, confirmation-bound tool proposals, a deterministic
output-evaluation corpus, and privacy-preserving audit records. Every exercise records provider
identifiers, retrieved context IDs, safety flags, and latency without retaining raw private prompts
or outputs by default.

The assistant service owns the provider boundary and fails closed when no reviewed provider is
configured. This lab supplies deterministic fakes and evaluation fixtures; it is not a production
model deployment or a license to bypass service authorization. Qdrant, cloud model credentials,
and production tool execution remain deployment work.

```bash
./gradlew :labs:ai-lab:run
./gradlew :labs:ai-lab:test
```
