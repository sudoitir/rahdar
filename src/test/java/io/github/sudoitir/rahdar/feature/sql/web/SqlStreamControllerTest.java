package io.github.sudoitir.rahdar.feature.sql.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET .../sql/stream} — the console's one execution path.
 *
 * <p>Execution is POST-then-stream (ADR-0064): the query text is posted and the
 * stream is opened by a short-lived, single-use reference, so an operator's
 * predicates never reach a URL — or a proxy's access log.
 *
 * <p>That splits refusals in two, and both halves are pinned here. A query that can
 * be judged before it runs is refused by the POST, where a real status code and a
 * readable body reach the caller. Anything the stream itself refuses — an expired or
 * reused reference, a cost ceiling, a broker that will not answer — has to arrive as
 * a {@code failed} frame on a 200 stream, because an {@code EventSource} cannot read
 * the body of a non-200 and "connection failed" with no reason is not actionable.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class SqlStreamControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /** Post the query and return the reference the stream is opened with. */
    private String reference(String sql) throws Exception {
        String body = mvc.perform(post("/api/v1/clusters/{c}/sql/query", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":" + quote(sql) + ",\"tail\":false}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.queryId");
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Runs the query and returns everything written to the stream before it completed. */
    private String stream(String sql) throws Exception {
        return streamOf(reference(sql));
    }

    private String streamOf(String queryId) throws Exception {
        MvcResult result = mvc.perform(
                        get("/api/v1/clusters/{c}/sql/stream", clusterId).param("queryId", queryId))
                .andExpect(status().isOk())
                .andReturn();
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString();
    }

    @Test
    void aSyntaxRefusalIsRefusedAtSubmission() throws Exception {
        // Judged before anything is streamed, so the refusal can travel as a status
        // code with a body the caller can actually read.
        mvc.perform(post("/api/v1/clusters/{c}/sql/query", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"DELETE FROM \\\"ORDER.IN\\\"\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aQueryReferenceIsSingleUseAndSaysSoOnTheStream() throws Exception {
        String queryId = reference("SELECT * FROM \"NOTHING.HERE\"");
        assertThat(streamOf(queryId)).contains("event:done");

        // The second attempt is a refusal the stream has to carry itself, because by
        // then the client is an EventSource and cannot read a status code's body.
        String replayed = streamOf(queryId);
        assertThat(replayed).contains("event:failed").contains("single use");
    }

    @Test
    void theQueryTextNeverAppearsInTheStreamUrl() throws Exception {
        String queryId = reference("SELECT * FROM \"NOTHING.HERE\" WHERE body LIKE '%PAYROLL%'");

        // The whole point of the reference: an operator's predicate — and the value
        // they are searching for — is not in a URL any proxy will log.
        assertThat(queryId).doesNotContain("PAYROLL").doesNotContain("SELECT");
    }

    @Test
    void aQueryOverNoQueueStillCompletesWithADoneFrame() throws Exception {
        // No node, no snapshot: the target list is empty. That is an answer — an empty
        // target list, not an empty queue — and it arrives as a normal completion so
        // the console can render the difference.
        String body = stream("SELECT * FROM \"NOTHING.HERE\"");

        assertThat(body).contains("event:done").doesNotContain("event:failed");
    }

    @Test
    void theStreamIsAnEventStream() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/clusters/{c}/sql/stream", clusterId)
                        .param("queryId", reference("SELECT * FROM \"NOTHING.HERE\"")))
                .andExpect(status().isOk())
                .andReturn();
        result.getAsyncResult(5_000);

        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        // Proxies buffer an event stream into uselessness unless told not to.
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void anEmptyQueryIsRefusedRatherThanTreatedAsSelectEverything() throws Exception {
        mvc.perform(post("/api/v1/clusters/{c}/sql/query", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"\"}"))
                .andExpect(status().is4xxClientError());
    }
}
