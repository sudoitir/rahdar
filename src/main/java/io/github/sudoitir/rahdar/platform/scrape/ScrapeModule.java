package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;

/** Tiered broker polling, queue snapshots and metric samples. Module descriptor (ADR-0070). */
public final class ScrapeModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("scrape")
            .title("Scraping")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .settingKey(ScrapeSettings.TIER_A)
            .settingKey(ScrapeSettings.TIER_B)
            .settingKey(ScrapeSettings.TIER_C)
            .settingKey(ScrapeSettings.DISCOVERY)
            .streamTopic(TopicDef.signal("subscriptions", Permissions.SUBSCRIPTION_READ))
            .build();

    private ScrapeModule() {}
}
