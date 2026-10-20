package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.DataPlaneCheck;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.SubscriptionVerdict;
import java.util.List;
import java.util.function.Function;
import org.mockito.stubbing.Answer;

/** A stubbed data-plane check that answers each node it would ask by its data-plane address. */
public final class DataPlaneChecks {

    private DataPlaneChecks() {}

    public static Answer<DataPlaneCheck.Result> answering(Function<String, AccountResult> byCoreUrl) {
        return invocation -> {
            List<NodeEndpoint> endpoints = invocation.getArgument(0);
            return new DataPlaneCheck.Result(
                    endpoints.stream()
                            .map(e -> DataPlaneCheck.asked(e) ? byCoreUrl.apply(e.coreUrl()) : AccountResult.NOT_TRIED)
                            .toList(),
                    new SubscriptionVerdict.NotAttempted());
        };
    }
}
