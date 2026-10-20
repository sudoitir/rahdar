package io.github.sudoitir.rahdar.support.wire;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Tells the {@link WireRecorder} which test class is running; registered for every test class. */
public final class WireRecordingExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        WireRecorder.testClassStarted(context.getRequiredTestClass().getName());
    }
}
