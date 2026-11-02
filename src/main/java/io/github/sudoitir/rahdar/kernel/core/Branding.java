package io.github.sudoitir.rahdar.kernel.core;

/**
 * Single source of every user-visible product name and mark.
 *
 * <p><strong>Rename insurance.</strong> "Rahdar" applies an Apache Software
 * Foundation trademark (the bare project name {@code Artemis}) to this product.
 * ASF trademark policy discourages that, and precedent exists (Kafka Tool → Offset
 * Explorer). The decision to keep the name was made deliberately; see
 * {@code docs/adr/0001-project-name-and-trademark-risk.md}.
 *
 * <p>If the project is ever renamed, changing the constants here and their two
 * counterparts in {@code web/src/branding.ts}, plus the Maven {@code artifactId}
 * and image coordinates, is the entire job. Nothing else in the codebase should
 * hard-code the name.
 */
public final class Branding {

    private Branding() {}

    /** Full product name, as shown in titles and the About dialog. */
    public static final String PRODUCT_NAME = "Rahdar";

    /** Short name for compact UI (tab titles, breadcrumbs). */
    public static final String PRODUCT_SHORT_NAME = "Rahdar";

    public static final String TAGLINE =
            "Cluster-wide management and observability for Apache Kafka, RabbitMQ and Apache ActiveMQ Artemis";

    /**
     * Mandatory disclaimer. Rendered in the About dialog and the docs footer.
     */
    public static final String TRADEMARK_NOTICE =
            "Apache Kafka, Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the "
                    + "Apache Software Foundation. RabbitMQ is a trademark of its owner. " + PRODUCT_NAME
                    + " is an independent project and is not produced by, endorsed by, or affiliated with the "
                    + "Apache Software Foundation or the owner of RabbitMQ.";

    public static final String PROJECT_URL = "https://github.com/sudoitir/rahdar";
}
