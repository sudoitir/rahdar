package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;

/** The object names of an Artemis broker's management beans. */
public final class MBeanNames {

    public static final String DOMAIN = ArtemisMBeans.DOMAIN;
    public static final String BROKER_SEARCH_PATTERN = ArtemisMBeans.BROKER_SEARCH_PATTERN;

    private MBeanNames() {}

    public static String acceptorsPattern(String broker) {
        return ArtemisMBeans.acceptorsPattern(broker);
    }

    public static String addressesPattern(String broker) {
        return ArtemisMBeans.addressesPattern(broker);
    }

    public static String address(String broker, String address) {
        return ArtemisMBeans.address(broker, address);
    }

    public static String queue(String broker, String address, String queue, String routingType) {
        return ArtemisMBeans.queue(broker, address, queue, routingType);
    }

    public static String queuePattern(String broker, String queue) {
        return ArtemisMBeans.queuePattern(broker, queue);
    }

    public static String divertsPattern(String broker) {
        return ArtemisMBeans.divertsPattern(broker);
    }

    public static String bridgesPattern(String broker) {
        return ArtemisMBeans.bridgesPattern(broker);
    }
}
