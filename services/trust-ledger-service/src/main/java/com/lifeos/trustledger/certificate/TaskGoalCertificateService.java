package com.lifeos.trustledger.certificate;

import com.lifeos.trustledger.access.TrustSubject;
import java.time.Instant;
import java.util.UUID;

public interface TaskGoalCertificateService {

    GoalCertificateFacts load(TrustSubject subject, UUID goalId);

    record GoalCertificateFacts(UUID goalId, long goalVersion, Instant completedAt) {}
}
