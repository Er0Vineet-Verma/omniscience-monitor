package com.omniscience.collector.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.omniscience.collector.config.MonitorProps;

/** Local-profile implementation: one static agent token, constant-time compared. */
@Component
public class StaticTokenAuthenticator implements AgentAuthenticator {

    private static final String PREFIX = "Bearer ";

    private final byte[] expectedToken;
    private final String orgId;

    public StaticTokenAuthenticator(MonitorProps props) {
        this.expectedToken = props.agentToken().getBytes(StandardCharsets.UTF_8);
        this.orgId = props.orgId();
    }

    @Override
    public Optional<AgentPrincipal> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return Optional.empty();
        }
        byte[] presented = authorizationHeader.substring(PREFIX.length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, presented)) {
            return Optional.empty();
        }
        return Optional.of(new AgentPrincipal(orgId));
    }
}
