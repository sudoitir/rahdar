package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqController;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The routes reach the right service: a {@code :preview} never reaches the real call, and an id travels encoded. */
class RabbitMqControllerTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String BASE = "/clusters/" + CLUSTER + "/rabbitmq";
    private static final String JSON = "{\"pattern\":\"a\",\"definition\":{\"a\":\"1\"},\"name\":\"n\",\"vhost\":\"/\","
            + "\"type\":\"classic\",\"source\":\"s\",\"destination\":\"d\",\"destinationType\":\"queue\","
            + "\"configure\":\"\",\"write\":\"\",\"read\":\"\",\"etag\":\"e\"}";

    private final RabbitMqNodes nodes = mock(RabbitMqNodes.class);
    private final RabbitMqVhosts vhosts = mock(RabbitMqVhosts.class);
    private final RabbitMqQueues queues = mock(RabbitMqQueues.class);
    private final RabbitMqExchanges exchanges = mock(RabbitMqExchanges.class);
    private final RabbitMqBindings bindings = mock(RabbitMqBindings.class);
    private final RabbitMqPolicies policies = mock(RabbitMqPolicies.class);
    private final RabbitMqPermissions permissions = mock(RabbitMqPermissions.class);
    private final RabbitMqShovels shovels = mock(RabbitMqShovels.class);
    private final RabbitMqFederation federation = mock(RabbitMqFederation.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RabbitMqController(
                    nodes, vhosts, queues, exchanges, bindings, policies, permissions, shovels, federation))
            .build();

    @Test
    void theReadsAreGets() throws Exception {
        for (String path : new String[] {
            "/nodes", "/vhosts", "/bindings", "/policies", "/users", "/permissions", "/shovels", "/federation-links"
        }) {
            mvc.perform(get(BASE + path)).andExpect(status().isOk());
        }

        verify(nodes).list(CLUSTER);
        verify(vhosts).list(CLUSTER, null, null);
        verify(bindings).list(CLUSTER, null, null, null, null, null);
        verify(policies).list(CLUSTER, null, null, null);
        verify(permissions).users(CLUSTER, null, null);
        verify(permissions).list(CLUSTER, null, null, null, null);
        verify(shovels).list(CLUSTER, null, null, null);
        verify(federation).list(CLUSTER, null, null, null);
    }

    @Test
    void aPreviewIsNotTheRealCall() throws Exception {
        mvc.perform(delete(BASE + "/vhosts/orders:preview")).andExpect(status().isOk());
        mvc.perform(patch(BASE + "/vhosts/orders:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/vhosts:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON))
                .andExpect(status().isOk());

        verify(vhosts).previewDelete(CLUSTER, "orders");
        verify(vhosts).previewUpdate(eq(CLUSTER), eq("orders"), any());
        verify(vhosts).previewCreate(eq(CLUSTER), any());
        verify(vhosts, never()).delete(any(), any(), any());
        verify(vhosts, never()).update(any(), any(), any());
        verify(vhosts, never()).create(any(), any());
    }

    @Test
    void theRealCallsCarryTheEtag() throws Exception {
        mvc.perform(delete(BASE + "/vhosts/orders").param("etag", "e1")).andExpect(status().isOk());
        mvc.perform(patch(BASE + "/vhosts/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/vhosts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON))
                .andExpect(status().isOk());

        verify(vhosts).delete(CLUSTER, "orders", "e1");
        verify(vhosts).update(eq(CLUSTER), eq("orders"), any());
        verify(vhosts).create(eq(CLUSTER), any());
    }

    @Test
    void anIdInAPathIsItsEncodedText() throws Exception {
        String id = "rabbitmq.binding%2F%252F%2Fevents%2Forders%2Fk";
        mvc.perform(delete(URI.create(BASE + "/bindings/" + id + ":preview"))).andExpect(status().isOk());
        mvc.perform(delete(URI.create(BASE + "/shovels/" + id)).param("etag", "e"))
                .andExpect(status().isOk());

        verify(bindings).previewDelete(CLUSTER, "rabbitmq.binding/%2F/events/orders/k");
        verify(bindings).delete(CLUSTER, "rabbitmq.binding/%2F/events/orders/k", "e");
        verify(bindings, never()).delete(any(), any(), eq(null));
    }

    @Test
    void policiesPermissionsAndTopicPermissionsAreAddressedByTheirNames() throws Exception {
        for (String path :
                new String[] {"/policies/%2F/ha", "/permissions/%2F/app", "/topic-permissions/%2F/app/events"}) {
            mvc.perform(put(URI.create(BASE + path + ":preview"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isOk());
            mvc.perform(put(URI.create(BASE + path))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isOk());
            mvc.perform(delete(URI.create(BASE + path + ":preview"))).andExpect(status().isOk());
            mvc.perform(delete(URI.create(BASE + path)).param("etag", "e")).andExpect(status().isOk());
        }

        verify(policies).previewSet(eq(CLUSTER), eq("/"), eq("ha"), any());
        verify(policies).set(eq(CLUSTER), eq("/"), eq("ha"), any());
        verify(policies).previewClear(CLUSTER, "/", "ha");
        verify(policies).clear(CLUSTER, "/", "ha", "e");
        verify(permissions).previewSet(eq(CLUSTER), eq("/"), eq("app"), any());
        verify(permissions).set(eq(CLUSTER), eq("/"), eq("app"), any());
        verify(permissions).previewClear(CLUSTER, "/", "app");
        verify(permissions).clear(CLUSTER, "/", "app", "e");
        verify(permissions).previewSetTopic(eq(CLUSTER), eq("/"), eq("app"), eq("events"), any());
        verify(permissions).setTopic(eq(CLUSTER), eq("/"), eq("app"), eq("events"), any());
        verify(permissions).previewClearTopic(CLUSTER, "/", "app", "events");
        verify(permissions).clearTopic(CLUSTER, "/", "app", "events", "e");
    }

    @Test
    void queuesExchangesAndBindingsArePostedWithTheirPreviews() throws Exception {
        for (String path : new String[] {"/queues", "/exchanges", "/bindings"}) {
            mvc.perform(post(BASE + path + ":preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isOk());
            mvc.perform(post(BASE + path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isOk());
        }

        verify(queues).preview(eq(CLUSTER), any());
        verify(queues).create(eq(CLUSTER), any());
        verify(exchanges).preview(eq(CLUSTER), any());
        verify(exchanges).create(eq(CLUSTER), any());
        verify(bindings).previewCreate(eq(CLUSTER), any());
        verify(bindings).create(eq(CLUSTER), any());
    }
}
