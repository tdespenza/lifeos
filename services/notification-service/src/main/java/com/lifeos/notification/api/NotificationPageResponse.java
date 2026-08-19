package com.lifeos.notification.api;

import com.lifeos.notification.read.NotificationView;
import java.util.List;

/** Bounded cursor page returned only for the authenticated recipient's notification history. */
public record NotificationPageResponse(List<NotificationView> items, long nextCursor) {
}
