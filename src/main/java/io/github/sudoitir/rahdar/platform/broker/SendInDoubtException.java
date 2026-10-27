package io.github.sudoitir.rahdar.platform.broker;

/**
 * A send whose outcome is unknown: the message left Rahdar, and the broker's confirmation never came
 * back before the connection was lost (ADR-0211). It may or may not be on the broker. It is neither a
 * success nor a failure, and sending it again may duplicate it.
 */
public class SendInDoubtException extends RuntimeException {

    public SendInDoubtException(String message, Throwable cause) {
        super(message, cause);
    }
}
