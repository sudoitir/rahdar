package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * What every access-control operation shares (users, roles, teams, plugins): installation-wide, run again by Rahdar once approved, and an
 * {@link Trait#ACCESS_CONTROL} one. An operation that could weaken the gate itself adds {@link
 * Trait#GATE_INTEGRITY} through {@link #access}.
 */
public abstract class AccessOperation<P extends Record> implements GatedOperation<P> {

    private final String type;
    private final Class<P> paramsType;

    protected AccessOperation(String type, Class<P> paramsType) {
        this.type = type;
        this.paramsType = paramsType;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public Class<P> paramsType() {
        return paramsType;
    }

    @Override
    public Set<Trait> traits(P params) {
        return access(false);
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.ON_APPROVAL;
    }

    @Override
    public OperationScope scope(P params) {
        return OperationScope.GLOBAL;
    }

    @Override
    public Set<String> redactedPaths() {
        return Set.of();
    }

    public static Set<Trait> access(boolean gateIntegrity) {
        return gateIntegrity
                ? EnumSet.of(Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY)
                : EnumSet.of(Trait.ACCESS_CONTROL);
    }

    /** A precondition key: the same parts give the same key, any other state a different one. */
    public static String etag(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            joined.append(part).append('\u001f');
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
