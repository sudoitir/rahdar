package io.github.sudoitir.rahdar.kernel.approval;

import io.github.sudoitir.rahdar.kernel.gate.AuthKind;
import io.github.sudoitir.rahdar.kernel.gate.CanonicalJson;
import io.github.sudoitir.rahdar.kernel.gate.PolicyRef;
import io.github.sudoitir.rahdar.kernel.gate.Vote;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binds a held request and its decision with {@link SecretVault} seals, whose key lives outside the database
 * (ADR-0180). The additional data names the row, so a seal copied from another row does not open. Before a request
 * runs, {@link #verify} opens both and checks them against the row; any difference is an {@link Integrity} failure.
 */
@Component
class Seals {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecretVault vault;

    Seals(SecretVault vault) {
        this.vault = vault;
    }

    /** What the request seal binds: the exact parameters, who asked and how, the policy and the precondition. */
    record Payload(
            String canonical,
            String paramsHash,
            UUID requesterId,
            AuthKind authKind,
            UUID tokenId,
            String policyDigest,
            String etag) {}

    /** What the decision seal binds. {@code decidedAt} is ISO-8601, as the database stores it. */
    record Decision(UUID approverId, Vote vote, String decidedAt) {}

    /** A seal that does not open, or does not match its row. */
    static final class Integrity extends RuntimeException {
        Integrity(String message) {
            super(message);
        }
    }

    byte[] sealPayload(UUID id, String type, Payload payload) {
        return vault.seal(payloadAad(id, type), JSON.writeValueAsString(payload));
    }

    byte[] sealDecision(UUID id, String paramsHash, Decision decision) {
        return vault.seal(decisionAad(id, paramsHash), JSON.writeValueAsString(decision));
    }

    static String policyDigest(PolicyRef policy) {
        return sha256(CanonicalJson.write(policy));
    }

    /**
     * Opens both seals of an approved request and checks them against its row: the parameters hash again to the
     * stored hash, and the requester, policy and decision are the ones recorded.
     *
     * @return the sealed request, the only source of the parameters that run
     * @throws Integrity naming what does not match
     */
    Payload verify(HeldRow row) {
        if (row.sealedPayload() == null || row.sealedDecision() == null) {
            throw new Integrity("its seals are missing");
        }
        Payload payload = openPayload(row);
        Decision decision;
        try {
            decision = JSON.readValue(
                    vault.open(decisionAad(row.id(), row.paramsHashHex()), row.sealedDecision()), Decision.class);
        } catch (SecretVault.SecretDecryptException | JacksonException _) {
            throw new Integrity("the decision seal does not open for this request");
        }
        String rehash =
                HexFormat.of().formatHex(CanonicalJson.hash(row.type(), row.typeVersion(), payload.canonical()));
        if (!rehash.equals(row.paramsHashHex()) || !rehash.equals(payload.paramsHash())) {
            throw new Integrity("its parameters do not match what was requested");
        }
        if (!Objects.equals(payload.requesterId(), row.requesterId())
                || payload.authKind() != row.authKind()
                || !Objects.equals(payload.tokenId(), row.tokenId())) {
            throw new Integrity("its requester does not match who asked");
        }
        if (!payload.policyDigest().equals(policyDigest(row.policy()))) {
            throw new Integrity("its policy does not match the one that held it");
        }
        if (decision.vote() != Vote.APPROVE
                || !Objects.equals(decision.approverId(), row.approverId())
                || row.decidedAt() == null
                || !Instant.parse(decision.decidedAt()).equals(row.decidedAt())) {
            throw new Integrity("its approval does not match the recorded decision");
        }
        return payload;
    }

    /** The sealed request of an open row, unchecked against the row but for opening under its own id and type. */
    Payload openPayload(HeldRow row) {
        if (row.sealedPayload() == null) {
            throw new Integrity("its request seal is gone");
        }
        try {
            return JSON.readValue(vault.open(payloadAad(row.id(), row.type()), row.sealedPayload()), Payload.class);
        } catch (SecretVault.SecretDecryptException | JacksonException _) {
            throw new Integrity("the request seal does not open for this request");
        }
    }

    private static String payloadAad(UUID id, String type) {
        return "held-operation|" + id + "|" + type;
    }

    private static String decisionAad(UUID id, String paramsHash) {
        return "held-decision|" + id + "|" + paramsHash;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
