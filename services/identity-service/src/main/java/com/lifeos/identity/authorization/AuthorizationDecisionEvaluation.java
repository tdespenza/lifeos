package com.lifeos.identity.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal authorization evaluation result.
 *
 * <p>The public HTTP contract returns only {@link #decision()}. The optional verified subject is
 * internal audit evidence: it is populated only after the durable session and active-account
 * checks succeed, preventing an authenticated workload from attributing a denial to an arbitrary
 * account identifier it placed in a request.
 *
 * @param decision client-safe deterministic decision
 * @param verifiedSubjectId subject proven by the durable session check, or {@code null}
 */
public record AuthorizationDecisionEvaluation(
        AuthorizationDecision decision, UUID verifiedSubjectId) {

    /** Creates an internally consistent evaluation result. */
    public AuthorizationDecisionEvaluation {
        Objects.requireNonNull(decision, "decision must not be null");
    }
}
