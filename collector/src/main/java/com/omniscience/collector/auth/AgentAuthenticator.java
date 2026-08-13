package com.omniscience.collector.auth;

import java.util.Optional;

/**
 * The edge-auth seam (ADR-001). Week 1: static bearer token. Week 4: the mTLS
 * client-certificate identity satisfies this same contract — tenancy always comes
 * from the credential, never from the request payload.
 */
public interface AgentAuthenticator {

    Optional<AgentPrincipal> authenticate(String authorizationHeader);

    record AgentPrincipal(String orgId) {
    }
}
