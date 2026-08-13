package com.lifeos.identity.authorization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resource facts supplied by a protected service after it has loaded the resource itself.
 *
 * <p>These facts are deliberately represented separately from client JSON. The identity service
 * never looks up domain resources, and callers must never copy client-supplied owner or tenant
 * values into this object.
 *
 * @param resourceType exact resource family
 * @param resourceId stable resource identifier; collection actions may omit it
 * @param tenantId trusted resource tenant
 * @param attributes trusted, bounded policy-relevant attributes
 */
public record AuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    /**
     * Makes a defensive snapshot without throwing for malformed transport input. The decision
     * service classifies malformed facts as a deterministic denial rather than leaking parser
     * implementation detail from an internal endpoint.
     */
    public AuthorizationResource {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
