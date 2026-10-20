package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EmailSenderTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final EmailChannelConfig config = new EmailChannelConfig(
            "smtp.example.com",
            587,
            "STARTTLS",
            "alerts",
            "rahdar@example.com",
            List.of("oncall@example.com", "team@example.com"),
            "[Artemis]");

    @Test
    void starttlsIsRequiredAndTimeoutsAreBounded() {
        JavaMailSenderImpl sender = EmailSender.sender(config, "pw", Duration.ofSeconds(7));
        Properties props = sender.getJavaMailProperties();
        assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("7000");
        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(sender.getPort()).isEqualTo(587);
    }

    @Test
    void implicitTlsUsesSmtps() {
        var tls = new EmailChannelConfig(
                "smtp.example.com", 0, "TLS", null, "a@example.com", List.of("b@example.com"), null);
        JavaMailSenderImpl sender = EmailSender.sender(tls, null, Duration.ofSeconds(5));
        assertThat(sender.getProtocol()).isEqualTo("smtps");
        assertThat(sender.getPort()).isEqualTo(465);
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtps.auth"))
                .isEqualTo("false");
    }

    @Test
    void theSubjectIsOneLineAndTheMessageHasBothParts() throws Exception {
        String payload = """
                {"ruleId":"%s","ruleName":"Depth\\r\\nBcc: attacker@example.com","severity":"WARNING","clusterName":"prod",
                 "transitions":[{"subject":"subscription:orders","subjectLabel":"subscription orders","kind":"FIRED","value":900}]}""".formatted(UUID.randomUUID());
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));

        EmailSender.compose(mime, config, AlertMessage.parse(payload, mapper), 42);
        mime.saveChanges();

        assertThat(mime.getSubject()).doesNotContain("\n").doesNotContain("\r").startsWith("[Artemis] [WARNING] Depth");
        assertThat(mime.getHeader("Bcc")).isNull();
        assertThat(mime.getAllRecipients())
                .extracting(Address::toString)
                .containsExactly("oncall@example.com", "team@example.com");
        assertThat(mime.getHeader("X-Rahdar-Delivery")).containsExactly("42");
    }

    @Test
    void everyRecipientRefusedIsPermanentOtherwiseRetried() {
        Address bad = address("nobody@example.com");
        var allRefused = new MailSendException(Map.of(
                new Object(),
                new SendFailedException("550", null, new Address[0], new Address[0], new Address[] {bad})));
        assertThat(EmailSender.classify(allRefused).permanent()).isTrue();

        var connection = new MailSendException("Mail server connection failed");
        assertThat(EmailSender.classify(connection).permanent()).isFalse();
    }

    private static Address address(String value) {
        try {
            return new InternetAddress(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
