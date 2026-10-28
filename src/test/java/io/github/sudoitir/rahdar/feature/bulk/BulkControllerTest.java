package io.github.sudoitir.rahdar.feature.bulk;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bulk API over HTTP, with the payloads the frontend sends: the service tests call {@link BulkService}
 * directly and cannot see a body Jackson refuses before it runs (ADR-0096).
 */
class BulkControllerTest extends BulkTestSupport {

    @Autowired
    WebApplicationContext webContext;

    private final JsonMapper json = new JsonMapper();

    MockMvc mvc;

    @BeforeEach
    void seed() {
        mvc = webAppContextSetup(webContext).build();
        queue(nodeA, "orders.a", 3, 0, false);
        queue(nodeA, "orders.b", 4, 0, false);
        queue(nodeA, "payments", 5, 0, false);
    }

    private ResultActions preview(String body) throws Exception {
        return mvc.perform(post("/api/v1/clusters/{c}/bulk/preview", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @ParameterizedTest
    @EnumSource(BulkOperation.class)
    void previewsTheNamesTheFrontendSends(BulkOperation operation) throws Exception {
        preview("""
                        {"operation":"%s","ids":["artemis.queue/orders.a","artemis.queue/orders.b"],"q":null,"disconnectConsumers":false}
                        """.formatted(operation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.operation").value(operation.name()))
                .andExpect(jsonPath("$.run.total").value(2))
                .andExpect(jsonPath("$.items[0].subscription").value("artemis.queue/orders.a"));
    }

    @ParameterizedTest
    @EnumSource(BulkOperation.class)
    void previewsTheFilterTheFrontendSends(BulkOperation operation) throws Exception {
        preview("""
                        {"operation":"%s","ids":null,"q":"orders","disconnectConsumers":false}
                        """.formatted(operation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.total").value(2))
                .andExpect(jsonPath("$.run.selection.q").value("orders"));
    }

    @Test
    void theHistoryIsPagedAndLeavesOutPreviews() throws Exception {
        for (String name : new String[] {"orders.a", "orders.b", "payments"}) {
            preview("""
                            {"operation":"PAUSE","ids":["artemis.queue/%s"],"q":null,"disconnectConsumers":false}
                            """.formatted(name)).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/clusters/{c}/bulk/runs", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        jdbc.update("UPDATE bulk_run SET status = 'SUCCEEDED' WHERE cluster_id = ?", clusterId);
        mvc.perform(get("/api/v1/clusters/{c}/bulk/runs", clusterId).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/clusters/{c}/bulk/runs", clusterId)
                        .param("size", "2")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void previewsAPartialSelection() throws Exception {
        preview("""
                        {"operation":"PURGE","ids":["artemis.queue/payments"],"q":null,"disconnectConsumers":false}
                        """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.total").value(1))
                .andExpect(jsonPath("$.run.estimate").value(5));
    }

    @Test
    void refusesAnEmptySelection() throws Exception {
        preview("""
                        {"operation":"PAUSE","ids":[],"q":null,"disconnectConsumers":false}
                        """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(endsWith("bulk-nothing-selected")));
    }

    @Test
    void refusesAMissingOperation() throws Exception {
        preview("""
                        {"ids":["artemis.queue/orders.a"],"q":null,"disconnectConsumers":false}
                        """).andExpect(status().isBadRequest());
    }

    @Test
    void refusesAMissingPrimitive() throws Exception {
        // Jackson 3 fails on a missing primitive: this is why the contract marks it required.
        preview("""
                        {"operation":"PAUSE","ids":["artemis.queue/orders.a"],"q":null}
                        """).andExpect(status().isBadRequest());
    }

    @Test
    void refusesASelectionOverTheQueueCap() throws Exception {
        settings.put(BrokerSettings.BULK_SUBSCRIPTION_CAP, "1");

        preview("""
                        {"operation":"PAUSE","ids":null,"q":"orders","disconnectConsumers":false}
                        """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(endsWith("bulk-subscription-cap-exceeded")));
    }

    @Test
    void refusesToExecuteAPlanThatWasNotPreviewed() throws Exception {
        String body = preview("""
                        {"operation":"PAUSE","ids":["artemis.queue/orders.a"],"q":null,"disconnectConsumers":false}
                        """)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String runId = json.readTree(body).path("run").path("id").asString();

        mvc.perform(post("/api/v1/clusters/{c}/bulk/runs/{r}/execute", clusterId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planHash":"not-the-plan","override":false,"continueOnFailure":false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("bulk-plan-mismatch")));
    }
}
