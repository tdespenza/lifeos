package com.lifeos.labs.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic AI engineering exercises with no external model, vector database, or tool
 * execution. Prompts and generated content are transient; audit records retain only keyed
 * fingerprints and bounded classifications.
 */
public final class AiLab {

    private static final int VECTOR_DIMENSIONS = 8;

    private AiLab() {}

    public static void main(String[] args) {
        VectorIndex index = new VectorIndex(16);
        index.add(new DocumentChunk("doc-1", "Goals are versioned and owner scoped."));
        index.add(new DocumentChunk("doc-2", "Budgets use integer minor units and immutable postings."));
        List<RetrievedChunk> matches = index.search("owner scoped goals", 2);
        ToolProposal proposal = ToolPolicy.propose("DRAFT_TASK", "Create a follow-up task");
        AuditRecord audit = AuditRecord.from("GOAL_PLANNING", "synthetic prompt", matches, proposal, 14);
        EvaluationReport evaluation = OutputEvaluator.evaluate(
                List.of(new EvaluationCase("owner-scoping", "owner scoped", "Goals are owner scoped.")),
                new DeterministicProvider().generate("Goals are owner scoped.", 32).content());
        System.out.println("{\"retrieved\":" + matches.size() + ",\"toolState\":\""
                + proposal.executionState() + "\",\"promptFingerprint\":\"" + audit.promptFingerprint()
                + "\",\"evaluationPassRate\":" + evaluation.passRate() + "}");
    }

    public record DocumentChunk(String id, String text) {
        public DocumentChunk {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || text == null || text.isBlank() || text.length() > 4_096) {
                throw new IllegalArgumentException("document chunk is invalid or unbounded");
            }
        }
    }

    public record RetrievedChunk(String id, double score) {}

    public static final class VectorIndex {

        private final int maximumChunks;
        private final List<DocumentChunk> chunks = new ArrayList<>();

        public VectorIndex(int maximumChunks) {
            if (maximumChunks < 1 || maximumChunks > 1_000) {
                throw new IllegalArgumentException("maximumChunks must be between 1 and 1000");
            }
            this.maximumChunks = maximumChunks;
        }

        public void add(DocumentChunk chunk) {
            Objects.requireNonNull(chunk, "chunk must not be null");
            if (chunks.size() >= maximumChunks) {
                throw new IllegalStateException("vector index capacity exceeded");
            }
            chunks.add(chunk);
        }

        public List<RetrievedChunk> search(String query, int topK) {
            if (query == null || query.isBlank() || topK < 1 || topK > 10) {
                throw new IllegalArgumentException("query/topK is invalid");
            }
            double[] queryVector = embedding(query);
            return chunks.stream()
                    .map(chunk -> new RetrievedChunk(chunk.id(), cosine(queryVector, embedding(chunk.text()))))
                    .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed().thenComparing(RetrievedChunk::id))
                    .limit(topK)
                    .filter(match -> match.score() > 0.10d)
                    .toList();
        }
    }

    public record PromptTemplate(String id, String purpose) {
        public PromptTemplate {
            if (id == null || !id.matches("[A-Z0-9_-]{1,64}") || purpose == null || purpose.isBlank()) {
                throw new IllegalArgumentException("prompt template is invalid");
            }
        }

        public String render(String userMessage, List<RetrievedChunk> context) {
            if (userMessage == null || userMessage.isBlank() || userMessage.length() > 4_096) {
                throw new IllegalArgumentException("user message is invalid");
            }
            if (context == null || context.size() > 10) {
                throw new IllegalArgumentException("context is unbounded");
            }
            return purpose + "\ncontext=" + context.stream().map(RetrievedChunk::id).toList()
                    + "\nmessage=" + userMessage;
        }
    }

    public interface Provider {
        ProviderResponse generate(String prompt, int maxOutputTokens);
    }

    public record ProviderResponse(String providerId, String modelName, String content, boolean retained) {}

    public static final class DeterministicProvider implements Provider {
        @Override
        public ProviderResponse generate(String prompt, int maxOutputTokens) {
            if (prompt == null || prompt.isBlank() || maxOutputTokens < 1 || maxOutputTokens > 2_048) {
                throw new IllegalArgumentException("provider request is invalid");
            }
            String content = "deterministic response for " + prompt.substring(0, Math.min(64, prompt.length()));
            return new ProviderResponse("local-fixture", "fixture-v1", content, false);
        }
    }

    /** A bounded fixture for a cloud-compatible provider contract; it never makes a network call. */
    public static final class CloudCompatibleProvider implements Provider {
        @Override
        public ProviderResponse generate(String prompt, int maxOutputTokens) {
            if (prompt == null || prompt.isBlank() || maxOutputTokens < 1 || maxOutputTokens > 2_048) {
                throw new IllegalArgumentException("provider request is invalid");
            }
            return new ProviderResponse(
                    "cloud-compatible-fixture",
                    "fixture-v1",
                    "cloud-compatible response for " + prompt.substring(0, Math.min(64, prompt.length())),
                    false);
        }
    }

    public record EvaluationCase(String id, String requiredPhrase, String expectedAnswer) {
        public EvaluationCase {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")
                    || requiredPhrase == null || requiredPhrase.isBlank() || requiredPhrase.length() > 256
                    || expectedAnswer == null || expectedAnswer.isBlank() || expectedAnswer.length() > 4_096) {
                throw new IllegalArgumentException("evaluation case is invalid or unbounded");
            }
        }
    }

    public record EvaluationResult(String caseId, boolean passed, int outputCharacters) {}

    public record EvaluationReport(List<EvaluationResult> results, double passRate) {
        public EvaluationReport {
            results = List.copyOf(results);
            if (results.isEmpty() || results.size() > 100 || passRate < 0 || passRate > 1) {
                throw new IllegalArgumentException("evaluation report is invalid or unbounded");
            }
        }
    }

    public static final class OutputEvaluator {
        private static final int MAX_OUTPUT_CHARACTERS = 8_192;

        private OutputEvaluator() {}

        public static EvaluationReport evaluate(List<EvaluationCase> cases, String output) {
            if (cases == null || cases.isEmpty() || cases.size() > 100 || output == null
                    || output.isBlank() || output.length() > MAX_OUTPUT_CHARACTERS) {
                throw new IllegalArgumentException("evaluation input is invalid or unbounded");
            }
            String normalized = output.toLowerCase(Locale.ROOT);
            List<EvaluationResult> results = cases.stream()
                    .map(testCase -> new EvaluationResult(
                            testCase.id(),
                            normalized.contains(testCase.requiredPhrase().toLowerCase(Locale.ROOT)),
                            output.length()))
                    .toList();
            long passed = results.stream().filter(EvaluationResult::passed).count();
            return new EvaluationReport(results, (double) passed / results.size());
        }
    }

    public record ToolProposal(String operation, String executionState, boolean requiresConfirmation, String reason) {}

    public static final class ToolPolicy {
        private ToolPolicy() {}

        public static ToolProposal propose(String operation, String reason) {
            String normalized = operation == null ? "NONE" : operation.toUpperCase(Locale.ROOT);
            if (!List.of("NONE", "DRAFT_TASK", "DRAFT_GOAL", "DRAFT_FINANCIAL_NOTE").contains(normalized)) {
                throw new IllegalArgumentException("tool operation is not allow-listed");
            }
            return new ToolProposal(normalized, "NOT_EXECUTED", !"NONE".equals(normalized), reason);
        }
    }

    public record AuditRecord(
            String purpose,
            String promptFingerprint,
            List<String> contextIds,
            String toolOperation,
            String toolState,
            int latencyMillis) {

        public static AuditRecord from(
                String purpose, String prompt, List<RetrievedChunk> context, ToolProposal proposal, int latencyMillis) {
            if (purpose == null || prompt == null || prompt.isBlank() || context == null || context.size() > 10
                    || proposal == null || latencyMillis < 0 || latencyMillis > 60_000) {
                throw new IllegalArgumentException("audit input is invalid");
            }
            return new AuditRecord(
                    purpose,
                    fingerprint(prompt),
                    context.stream().map(RetrievedChunk::id).toList(),
                    proposal.operation(),
                    proposal.executionState(),
                    latencyMillis);
        }
    }

    private static double[] embedding(String value) {
        byte[] digest = sha256(value);
        double[] vector = new double[VECTOR_DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (digest[i] & 0xff) / 255.0d;
        }
        return vector;
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static String fingerprint(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte item : sha256(value)) {
            encoded.append(String.format(Locale.ROOT, "%02x", item));
        }
        return encoded.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
