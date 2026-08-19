package com.lifeos.labs.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiLabTest {

    @Test
    void retrievalIsBoundedAndDeterministic() {
        AiLab.VectorIndex index = new AiLab.VectorIndex(4);
        index.add(new AiLab.DocumentChunk("goal", "owner scoped goals"));
        index.add(new AiLab.DocumentChunk("finance", "minor unit finance postings"));

        List<AiLab.RetrievedChunk> first = index.search("owner scoped goals", 2);
        List<AiLab.RetrievedChunk> second = index.search("owner scoped goals", 2);

        assertEquals(first, second);
        assertTrue(first.size() <= 2);
    }

    @Test
    void providerDoesNotClaimToRetainContent() {
        AiLab.ProviderResponse response = new AiLab.DeterministicProvider().generate("synthetic", 32);

        assertFalse(response.retained());
        assertEquals("local-fixture", response.providerId());
    }

    @Test
    void localAndCloudCompatibleProvidersShareTheBoundedContract() {
        AiLab.ProviderResponse local = new AiLab.DeterministicProvider().generate("synthetic", 32);
        AiLab.ProviderResponse cloud = new AiLab.CloudCompatibleProvider().generate("synthetic", 32);

        assertNotEquals(local.providerId(), cloud.providerId());
        assertFalse(cloud.retained());
        assertTrue(cloud.content().length() <= 8_192);
    }

    @Test
    void outputEvaluationUsesBoundedDeterministicCorpus() {
        AiLab.EvaluationReport report = AiLab.OutputEvaluator.evaluate(
                List.of(
                        new AiLab.EvaluationCase("scope", "owner scoped", "owner scoped"),
                        new AiLab.EvaluationCase("version", "versioned", "versioned")),
                "The result is owner scoped and versioned.");

        assertEquals(1.0d, report.passRate());
        assertTrue(report.results().stream().allMatch(AiLab.EvaluationResult::passed));
        assertThrows(
                IllegalArgumentException.class,
                () -> AiLab.OutputEvaluator.evaluate(List.of(), "unbounded corpus is not accepted"));
    }

    @Test
    void toolPolicyOnlyProducesConfirmationBoundProposals() {
        AiLab.ToolProposal proposal = AiLab.ToolPolicy.propose("DRAFT_TASK", "test");

        assertEquals("NOT_EXECUTED", proposal.executionState());
        assertTrue(proposal.requiresConfirmation());
        assertThrows(IllegalArgumentException.class, () -> AiLab.ToolPolicy.propose("EXECUTE_SHELL", "bad"));
    }

    @Test
    void auditStoresFingerprintAndContextIdsButNotRawPrompt() {
        AiLab.ToolProposal proposal = AiLab.ToolPolicy.propose("NONE", "none");
        AiLab.AuditRecord audit = AiLab.AuditRecord.from(
                "GENERAL", "private synthetic prompt", List.of(new AiLab.RetrievedChunk("goal", 0.9)), proposal, 3);

        assertNotEquals("private synthetic prompt", audit.promptFingerprint());
        assertEquals(List.of("goal"), audit.contextIds());
        assertEquals("NONE", audit.toolOperation());
    }
}
