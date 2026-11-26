package io.github.sudoitir.rahdar.kernel.core.internal;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.core.pattern.DynamicConverter;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Makes every Logback pattern word that prints a message or a stack trace mask credential-like values
 * (ADR-0133). It replaces the words in Logback's own defaults before Boot configures logging, so Boot's
 * console, file and custom {@code logging.pattern.*} work unchanged. Registered in {@code spring.factories}.
 */
public class RedactingLogging implements ApplicationListener<ApplicationStartingEvent>, Ordered {

    private static final Map<String, Supplier<DynamicConverter>> WORDS = Map.of(
            "m", RedactingMessageConverter::new,
            "msg", RedactingMessageConverter::new,
            "message", RedactingMessageConverter::new,
            "ex", RedactingThrowableConverter::new,
            "exception", RedactingThrowableConverter::new,
            "throwable", RedactingThrowableConverter::new,
            "xEx", RedactingExtendedThrowableConverter::new,
            "xException", RedactingExtendedThrowableConverter::new,
            "xThrowable", RedactingExtendedThrowableConverter::new,
            "redactedEx", RedactingWhitespaceThrowableConverter::new);

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        register();
    }

    /** Idempotent, and also callable by a test that builds a layout without starting Boot. */
    public static void register() {
        WORDS.forEach(PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP::put);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
