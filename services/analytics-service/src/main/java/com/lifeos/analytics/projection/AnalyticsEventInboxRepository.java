package com.lifeos.analytics.projection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsEventInboxRepository extends JpaRepository<AnalyticsEventInbox, UUID> {}
