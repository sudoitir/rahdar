package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Decodes {@code browse(int,int,String)} responses against the slice-0 fixtures. */
class MessageBrowserTest {

    private static final String URL = "http://broker:8161/console/jolokia";
    private static final String MBEAN = "org.apache.activemq.artemis:broker=\"primary\","
            + "component=addresses,address=\"PHASE3.SRC\",subcomponent=queues,"
            + "routing-type=\"anycast\",queue=\"PHASE3.SRC\"";

    private final JsonMapper mapper = new JsonMapper();
    private final MessageBrowser browser = new MessageBrowser();

    /** {@code batch()} wants a JSON array; wrap the single-op fixture plus a MessageCount entry. */
    private ArtemisNode client(String browseFixture, long messageCount) {
        String batch = "[" + body(browseFixture) + ",{\"value\":" + messageCount + ",\"status\":200}]";
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(URL)).andRespond(withSuccess(batch, MediaType.APPLICATION_JSON));
        return server.node();
    }

    private static String body(String fixture) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void decodesFourRowsWithTypedPropertyMapsFromAPlainArray() {
        BrowsePage page = browser.browse(client("browse.json", 4), MBEAN, 1, 50, "");

        assertThat(page.total()).isEqualTo(4L);
        assertThat(page.messages()).hasSize(4);

        BrowsedMessage first = page.messages().get(0);
        assertThat(first.messageId()).isEqualTo(127L);
        assertThat(first.type()).isEqualTo(3);
        assertThat(first.durable()).isTrue();
        assertThat(first.body()).isEqualTo("order body 1");
        assertThat(first.size()).isEqualTo(169L);
        assertThat(first.stringProperties()).containsEntry("orderId", "A-1").containsEntry("region", "eu");
        assertThat(first.propertyCount()).isEqualTo(2);
        assertThat(first.bodyTruncated()).isFalse();
    }

    @Test
    void flagsTheOversizedRowAsTruncatedWithAnObservedLimitNear256() {
        BrowsePage page = browser.browse(client("browse.json", 4), MBEAN, 1, 50, "");

        BrowsedMessage big = page.messages().get(3);
        assertThat(big.messageId()).isEqualTo(146L);
        assertThat(big.bodyTruncated()).isTrue();
        assertThat(big.observedLimitBytes()).isNotNull();
        assertThat(big.observedLimitBytes()).isBetween(200, 260);
        assertThat(big.stringProperties()).containsEntry("note", "oversized");
    }

    @Test
    void singleRowTruncatedFixtureAlsoTrips() {
        BrowsePage page = browser.browse(client("browse-truncated.json", 1), MBEAN, 1, 50, "orderId = 'BIG-1'");

        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().get(0).bodyTruncated()).isTrue();
    }

    @Test
    void mapsAnInvalidFilterToIllegalArgument() {
        ArtemisNode client = client("browse-bad-filter.json", 0);
        assertThatThrownBy(() -> browser.browse(client, MBEAN, 1, 50, "this is not a filter =="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid message filter");
    }

    /** A browse whose rows are bytes messages, answered with the address's size limit (or no settings). */
    private BrowsePage browseBytes(Integer sizeLimit, int[]... previews) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < previews.length; i++) {
            rows.append(i == 0 ? "" : ",")
                    .append("{\"messageID\":")
                    .append(i + 1)
                    .append(",\"type\":4,\"durable\":true,\"BodyPreview\":")
                    .append(Arrays.toString(previews[i]))
                    .append('}');
        }
        String settings = sizeLimit == null
                ? "{\"status\":404,\"error\":\"no\"}"
                : "{\"status\":200,\"value\":\"{\\\"managementMessageAttributeSizeLimit\\\":" + sizeLimit + "}\"}";
        String batch = "[{\"status\":200,\"value\":[" + rows + "]},{\"status\":200,\"value\":" + previews.length + "},"
                + settings + "]";
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(URL)).andRespond(withSuccess(batch, MediaType.APPLICATION_JSON));
        return browser.browse(server.node(), MBEAN, 1, 50, "");
    }

    private static int[] ints(byte[] bytes) {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[i];
        }
        return out;
    }

    @Test
    void aBytesMessageCarryingTextIsReadAsText() {
        String json = "{\"status\":\"FAILED\",\"note\":\"caf\u00e9\"}";
        BrowsedMessage m = browseBytes(-1, ints(json.getBytes(StandardCharsets.UTF_8)))
                .messages()
                .get(0);

        assertThat(m.body()).isEqualTo(json);
        assertThat(m.bodyEncoding()).isEqualTo(BodyEncoding.TEXT);
        assertThat(m.type()).isEqualTo(4);
        assertThat(m.bodyTruncated()).isFalse();
    }

    @Test
    void aBinaryBytesMessageStaysBase64() {
        BrowsedMessage m = browseBytes(-1, new int[] {1, 0, -1}).messages().get(0);

        assertThat(m.bodyEncoding()).isEqualTo(BodyEncoding.BASE64);
        assertThat(Base64.getDecoder().decode(m.body())).containsExactly(1, 0, -1);
    }

    @Test
    void aPreviewAtTheLimitIsTruncatedAndASplitCharacterIsDropped() {
        // "ab\u00e9" is 4 bytes; a limit of 3 cuts the two-byte character in half.
        byte[] cut = Arrays.copyOf("ab\u00e9".getBytes(StandardCharsets.UTF_8), 3);
        BrowsedMessage m = browseBytes(3, ints(cut)).messages().get(0);

        assertThat(m.body()).isEqualTo("ab");
        assertThat(m.bodyTruncated()).isTrue();
        assertThat(m.observedLimitBytes()).isEqualTo(3);
    }

    @Test
    void unreadableSettingsAssumeTheBrokerDefaultLimit() {
        byte[] full = "x".repeat(MessageBrowser.DEFAULT_ATTRIBUTE_SIZE_LIMIT).getBytes(StandardCharsets.UTF_8);
        BrowsePage page = browseBytes(null, ints(full), ints("short".getBytes(StandardCharsets.UTF_8)));

        assertThat(page.messages().get(0).bodyTruncated()).isTrue();
        assertThat(page.messages().get(1).bodyTruncated()).isFalse();
    }
}
