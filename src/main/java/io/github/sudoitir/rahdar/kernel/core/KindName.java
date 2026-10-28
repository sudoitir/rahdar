package io.github.sudoitir.rahdar.kernel.core;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** A kind's name for people, as its driver gives it (ADR-0214): "Queue" and "Queues", "Topic" and "Topics". */
public record KindName(
        @Schema(requiredMode = REQUIRED, description = "The name of one, such as Queue.")
        String singular,

        @Schema(requiredMode = REQUIRED, description = "The name of several, such as Queues.")
        String plural) {}
