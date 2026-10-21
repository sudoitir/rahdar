package io.github.sudoitir.rahdar.platform.governance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DetectorsTest {

    @Test
    void cardNumbersNeedTheLuhnChecksum() {
        assertThat(Detectors.classify("4242 4242 4242 4242")).isEqualTo(DataClass.PAN);
        assertThat(Detectors.classify("5555555555554444")).isEqualTo(DataClass.PAN);
        assertThat(Detectors.classify("4242424242424241")).isNull();
    }

    @Test
    void epochMillisecondsAreNotCardNumbers() {
        assertThat(Detectors.classify("1757840000000")).isNull();
        assertThat(Detectors.classify("ts=1757840000000")).isNull();
    }

    @Test
    void ibansNeedTheMod97Checksum() {
        assertThat(Detectors.classify("GB82 WEST 1234 5698 7654 32")).isEqualTo(DataClass.IBAN);
        assertThat(Detectors.classify("DE89370400440532013000")).isEqualTo(DataClass.IBAN);
        assertThat(Detectors.classify("DE89370400440532013001")).isNull();
    }

    @Test
    void emailsAndPhones() {
        assertThat(Detectors.classify("contact jane.doe@example.com today")).isEqualTo(DataClass.EMAIL);
        assertThat(Detectors.classify("+4915112345678")).isEqualTo(DataClass.PHONE);
        assertThat(Detectors.classify("(555) 123-4567")).isEqualTo(DataClass.PHONE);
        assertThat(Detectors.classify("order 12345")).isNull();
    }

    @Test
    void bearerTokensAndJwtsAreCredentials() {
        assertThat(Detectors.classify("Bearer abc.def-123")).isEqualTo(DataClass.CREDENTIAL);
        assertThat(Detectors.classify("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sig_nature"))
                .isEqualTo(DataClass.CREDENTIAL);
    }

    @Test
    void scanReturnsNonOverlappingSpansInOrder() {
        String text = "card 4242424242424242 mail a@b.io";
        var hits = Detectors.scan(text);
        assertThat(hits).extracting(Detectors.Hit::dataClass).containsExactly(DataClass.PAN, DataClass.EMAIL);
        assertThat(text.substring(hits.get(0).start(), hits.get(0).end())).isEqualTo("4242424242424242");
    }

    @Test
    void destinationPatternsFollowArtemisWildcards() {
        var orders = DestinationPattern.compile("orders.#");
        assertThat(orders.matcher("orders").matches()).isTrue();
        assertThat(orders.matcher("orders.eu.north").matches()).isTrue();
        assertThat(orders.matcher("billing.eu").matches()).isFalse();
        var one = DestinationPattern.compile("orders.*");
        assertThat(one.matcher("orders.eu").matches()).isTrue();
        assertThat(one.matcher("orders.eu.north").matches()).isFalse();
        assertThat(DestinationPattern.compile("#.dlq").matcher("orders.eu.dlq").matches())
                .isTrue();
        assertThat(DestinationPattern.compile("#.dlq").matcher("dlq").matches()).isTrue();
    }

    @Test
    void namesAreCaseInsensitiveGlobs() {
        assertThat(DestinationPattern.glob("*token*").matcher("X-Auth-TOKEN-Id").matches())
                .isTrue();
        assertThat(DestinationPattern.glob("authorization")
                        .matcher("Authorization")
                        .matches())
                .isTrue();
        assertThat(DestinationPattern.glob("authorization")
                        .matcher("authorizationId")
                        .matches())
                .isFalse();
    }
}
