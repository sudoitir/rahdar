package io.github.sudoitir.rahdar.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.apitokens.TokenPermissions;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.FakeHandle;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.TestProvider;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.GateContext;
import io.github.sudoitir.rahdar.kernel.gate.GateDecision;
import io.github.sudoitir.rahdar.kernel.gate.GatePreview;
import io.github.sudoitir.rahdar.kernel.gate.GateRequest;
import io.github.sudoitir.rahdar.kernel.gate.HeldState;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.gate.Vote;
import io.github.sudoitir.rahdar.kernel.lifecycle.DataPermissions;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleService;
import io.github.sudoitir.rahdar.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.rahdar.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.rahdar.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.GrantLoader;
import io.github.sudoitir.rahdar.kernel.security.PermissionHolders;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.SessionAuthentication;
import io.github.sudoitir.rahdar.kernel.security.SessionFacts;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import io.github.sudoitir.rahdar.kernel.security.TokenGrant;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.settings.SecretRotationService;
import io.github.sudoitir.rahdar.kernel.settings.SettingChange;
import io.github.sudoitir.rahdar.kernel.settings.SettingDef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsInvalidException;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.settings.web.SettingsViews.PendingChange;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Settings change sets, key rotation and API tokens behind the approval gate: with no provider they run as before, with
 * a holding provider they are held and change nothing until approved, and an approved change set refuses to run when a
 * setting it touches has changed since.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GateTestKit.class)
class SettingsAndTokenGatingIntegrationTest extends PostgresIntegrationTest {

    private static final String PROVIDER = "gate-test-provider";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String GATE_KEY = ApprovalSettings.MAX_HOLD;

    @LocalServerPort
    int port;

    @Autowired
    TestProvider provider;

    @Autowired
    SettingsService settings;

    @Autowired
    SecretRotationService rotation;

    @Autowired
    ApiTokenService tokens;

    @Autowired
    Approvals approvals;

    @Autowired
    ApprovalProviderRegistry providers;

    @Autowired
    PluginStore pluginStore;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    LifecycleService lifecycle;

    @Autowired
    LifecycleRegistry stores;

    @MockitoBean
    PermissionHolders holders;

    @MockitoSpyBean
    SessionAuthentication sessions;

    private FakeHandle handle;

    private record Person(UUID id, String username) {}

    @BeforeEach
    void armTheGate() {
        provider.reset();
        jdbc.update("UPDATE plugin_install SET approval_provider = false");
        installs.findById(PROVIDER).ifPresent(installs::delete);
        String sha = pluginStore.put(("settings-gate-test-" + UUID.randomUUID()).getBytes());
        PluginInstallEntity entity = new PluginInstallEntity(PROVIDER, "1.0.0", "Acme", sha, "tester", "{}");
        entity.approvalProvider(true);
        installs.save(entity);
        jdbc.update("UPDATE plugin_install SET status = 'active' WHERE id = ?", PROVIDER);
        handle = new FakeHandle(PROVIDER, provider);
        providers.attach(handle);
    }

    @AfterEach
    void disarm() {
        providers.detach(handle);
        installs.findById(PROVIDER).ifPresent(installs::delete);
        SecurityContextHolder.clearContext();
        jdbc.update(
                "DELETE FROM rahdar_setting WHERE key IN (?, ?, ?)",
                BrokerSettings.BULK_CAP,
                BrokerSettings.BULK_SUBSCRIPTION_CAP,
                GATE_KEY);
        settings.applyRuntime();
    }

    // ---- set-up -------------------------------------------------------------------------------------

    private Person newUser(String... permissions) {
        String username = "sg-" + UUID.randomUUID().toString().substring(0, 12);
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        users.save(user);
        if (permissions.length > 0) {
            RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
            for (String permission : permissions) {
                rolePermissions.save(new RolePermissionEntity(role.getId(), permission));
            }
            userRoles.save(
                    new UserRoleEntity(user.getId(), role.getId(), Grant.ScopeType.GLOBAL.name(), ScopeIds.GLOBAL));
            var signedIn = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.clearContext();
            accessChanges.changedFor(user.getId());
            if (signedIn != null) {
                SecurityContextHolder.getContext().setAuthentication(signedIn);
            }
        }
        return new Person(user.getId(), username);
    }

    private Person settingsAdmin() {
        return newUser(SettingsPermissions.SETTINGS_READ, SettingsPermissions.SETTINGS_WRITE);
    }

    private Person approver() {
        return newUser(GateTestKit.APPROVER_PERMISSION);
    }

    private void signIn(Person person) {
        RahdarPrincipal principal =
                new RahdarPrincipal(person.id(), person.username(), grants.loadFor(person.id()), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        Instant now = Instant.now();
        doReturn(Optional.of(new SessionFacts(now, null, null, now, "127.0.0.1", "test")))
                .when(sessions)
                .current();
    }

    private void approversAre(Person... people) {
        when(holders.holders(any(), eq(GateTestKit.APPROVER_PERMISSION), anyInt()))
                .thenReturn(Arrays.stream(people).map(Person::id).toList());
    }

    /** Runs {@code work} as if no approval provider were installed. */
    private void whileDisarmed(Runnable work) {
        jdbc.update("UPDATE plugin_install SET approval_provider = false WHERE id = ?", PROVIDER);
        try {
            work.run();
        } finally {
            jdbc.update("UPDATE plugin_install SET approval_provider = true WHERE id = ?", PROVIDER);
        }
    }

    private UUID held(Runnable request) {
        try {
            request.run();
        } catch (OperationHeldException e) {
            return e.heldId();
        }
        throw new AssertionError("expected the request to be held");
    }

    private void approve(Person approver, UUID id) {
        signIn(approver);
        Approvals.Detail detail = approvals.get(id);
        approvals.decide(id, Vote.APPROVE, null, detail.view().paramsHash(), detail.version());
    }

    private HeldState stateOf(UUID id) {
        return HeldState.valueOf(
                jdbc.queryForObject("SELECT state FROM held_operation WHERE id = ?", String.class, id));
    }

    private void awaitState(UUID id, HeldState expected) {
        await("held operation " + id + " becomes " + expected)
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> stateOf(id) == expected);
    }

    private GateRequest lastRequest() {
        return provider.requests.getLast();
    }

    // ---- settings -----------------------------------------------------------------------------------

    @Test
    void withNoProviderAChangeSetRunsAsBefore() {
        whileDisarmed(() -> {
            signIn(settingsAdmin());
            settings.apply(List.of(
                    SettingChange.set(BrokerSettings.BULK_CAP, "3"),
                    SettingChange.set(BrokerSettings.BULK_SUBSCRIPTION_CAP, "2")));
        });

        assertThat(settings.intValue(BrokerSettings.BULK_CAP)).isEqualTo(3);
        assertThat(settings.intValue(BrokerSettings.BULK_SUBSCRIPTION_CAP)).isEqualTo(2);
        assertThat(provider.decisions.get()).isZero();
    }

    @Test
    void aChangeSetWithAnInvalidValueNeverReachesTheProviderAndChangesNothing() {
        signIn(settingsAdmin());

        assertThatThrownBy(() -> settings.apply(List.of(
                        SettingChange.set(BrokerSettings.BULK_CAP, "3"),
                        SettingChange.set(BrokerSettings.BULK_SUBSCRIPTION_CAP, "0"))))
                .isInstanceOfSatisfying(
                        SettingsInvalidException.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys(BrokerSettings.BULK_SUBSCRIPTION_CAP));

        assertThat(provider.decisions.get()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM rahdar_setting WHERE key = ?", Long.class, BrokerSettings.BULK_CAP))
                .isZero();
    }

    @Test
    void thePreviewSaysRunHoldOrDeny() {
        Person alice = settingsAdmin();
        approversAre(approver());
        signIn(alice);
        List<SettingChange> change = List.of(SettingChange.set(BrokerSettings.BULK_CAP, "3"));

        whileDisarmed(() -> assertThat(settings.preview(change).outcome()).isEqualTo(GatePreview.Outcome.RUN));

        provider.decide = request -> new GateDecision.Hold(GateTestKit.POLICY, Duration.ofHours(1), "a lead");
        assertThat(settings.preview(change)).satisfies(preview -> {
            assertThat(preview.outcome()).isEqualTo(GatePreview.Outcome.HOLD);
            assertThat(preview.policyLabel()).isEqualTo("Two people for purges");
        });

        provider.decide = request -> new GateDecision.Deny("Not on Fridays");
        assertThat(settings.preview(change)).satisfies(preview -> {
            assertThat(preview.outcome()).isEqualTo(GatePreview.Outcome.DENY);
            assertThat(preview.denyReason()).isEqualTo("Not on Fridays");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM rahdar_setting WHERE key = ?", Long.class, BrokerSettings.BULK_CAP))
                .isZero();
    }

    @Test
    void aHeldChangeSetChangesNothingAndMarksItsSettingsPending() {
        Person alice = settingsAdmin();
        approversAre(approver());
        signIn(alice);
        settings.effective();
        whileDisarmed(() -> settings.put(BrokerSettings.BULK_SUBSCRIPTION_CAP, "2"));

        UUID id = held(() -> settings.apply(List.of(
                SettingChange.set(BrokerSettings.BULK_CAP, "3"),
                SettingChange.reset(BrokerSettings.BULK_SUBSCRIPTION_CAP))));

        assertThat(settings.effective().get(BrokerSettings.BULK_CAP).overridden())
                .isFalse();
        assertThat(settings.intValue(BrokerSettings.BULK_SUBSCRIPTION_CAP)).isEqualTo(2);
        GateRequest request = lastRequest();
        assertThat(request.type()).isEqualTo("settings.apply");
        assertThat(request.traits()).containsExactly(Trait.SETTINGS);
        assertThat(request.display())
                .extracting(DisplayRow::from, DisplayRow::to)
                .containsExactlyInAnyOrder(
                        Assertions.tuple(settings.value(BrokerSettings.BULK_CAP), "3"),
                        Assertions.tuple(
                                "2",
                                "default ("
                                        + settings.definition(BrokerSettings.BULK_SUBSCRIPTION_CAP)
                                                .defaultValue()
                                                .get() + ")"));
        var cap = settings.effective().get(BrokerSettings.BULK_CAP).pending().stream()
                .filter(pending -> pending.heldId().equals(id))
                .toList();
        assertThat(cap).singleElement().satisfies(pending -> {
            assertThat(pending.heldId()).isEqualTo(id);
            assertThat(pending.value()).isEqualTo("3");
            assertThat(pending.reset()).isFalse();
            assertThat(pending.requester()).isEqualTo(alice.username());
            assertThat(pending.requestedAt()).isNotNull();
        });
        assertThat(settings.effective().get(BrokerSettings.BULK_SUBSCRIPTION_CAP).pending().stream()
                        .filter(pending -> pending.heldId().equals(id))
                        .toList())
                .singleElement()
                .satisfies(pending -> {
                    assertThat(pending.reset()).isTrue();
                    assertThat(pending.value()).isNull();
                });
    }

    @Test
    void anApprovedChangeSetRunsOnceWithItsOriginalValues() {
        Person alice = settingsAdmin();
        Person bob = approver();
        approversAre(bob);
        signIn(alice);
        UUID id = held(() -> settings.apply(List.of(
                SettingChange.set(BrokerSettings.BULK_CAP, "3"),
                SettingChange.set(BrokerSettings.BULK_SUBSCRIPTION_CAP, "2"))));

        approve(bob, id);

        awaitState(id, HeldState.SUCCEEDED);
        assertThat(settings.intValue(BrokerSettings.BULK_CAP)).isEqualTo(3);
        assertThat(settings.intValue(BrokerSettings.BULK_SUBSCRIPTION_CAP)).isEqualTo(2);
        signIn(alice);
        assertThat(settings.effective().get(BrokerSettings.BULK_CAP).pending())
                .extracting(PendingChange::heldId)
                .doesNotContain(id);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'UPDATE_SETTING' AND target_name = ?",
                        Long.class,
                        BrokerSettings.BULK_CAP))
                .isEqualTo(1);
    }

    @Test
    void anApprovedChangeSetIsRefusedWhenATouchedSettingChangedMeanwhile() {
        Person alice = settingsAdmin();
        Person bob = approver();
        approversAre(bob);
        signIn(alice);
        UUID id = held(() -> settings.apply(List.of(
                SettingChange.set(BrokerSettings.BULK_CAP, "3"),
                SettingChange.set(BrokerSettings.BULK_SUBSCRIPTION_CAP, "2"))));
        whileDisarmed(() -> settings.put(BrokerSettings.BULK_SUBSCRIPTION_CAP, "5"));

        approve(bob, id);

        awaitState(id, HeldState.REFUSED);
        signIn(alice);
        assertThat(settings.effective().get(BrokerSettings.BULK_CAP).overridden())
                .isFalse();
        assertThat(settings.intValue(BrokerSettings.BULK_SUBSCRIPTION_CAP)).isEqualTo(5);
    }

    @Test
    void theGatesOwnSettingsAndTheProvidersCarryTheIntegrityTrait() {
        approversAre(approver());
        signIn(settingsAdmin());
        settings.addSettings(
                PROVIDER,
                List.of(new SettingDef(
                        PROVIDER + ".limit",
                        "g",
                        "Limit",
                        "h",
                        SettingDef.Kind.DURATION,
                        () -> "7d",
                        null,
                        "1d",
                        "90d")),
                SettingsPermissions.SETTINGS_WRITE);
        try {
            held(() -> settings.put(GATE_KEY, "20d"));
            assertThat(lastRequest().traits()).containsExactlyInAnyOrder(Trait.SETTINGS, Trait.GATE_INTEGRITY);

            held(() -> settings.put(PROVIDER + ".limit", "30d"));
            assertThat(lastRequest().traits()).containsExactlyInAnyOrder(Trait.SETTINGS, Trait.GATE_INTEGRITY);

            held(() -> settings.put(BrokerSettings.BULK_CAP, "3"));
            assertThat(lastRequest().traits()).containsExactly(Trait.SETTINGS);
        } finally {
            settings.removeSettings(PROVIDER);
        }
    }

    // ---- secrets and tokens -------------------------------------------------------------------------

    @Test
    void aKeyRotationIsHeldAndStartsNothing() {
        approversAre(approver());
        signIn(settingsAdmin());

        held(() -> rotation.rotate());

        assertThat(lastRequest().type()).isEqualTo("secrets.rotate");
        assertThat(lastRequest().traits()).containsExactly(Trait.SETTINGS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secret_rotation WHERE status = 'RUNNING'", Long.class))
                .isZero();
    }

    private final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser signIn(Person person) throws Exception {
            send("GET", "/api/v1/auth/providers", null, Map.of());
            assertThat(send(
                                    "POST",
                                    "/api/v1/auth/login",
                                    "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(person.username(), PASSWORD),
                                    Map.of())
                            .statusCode())
                    .isEqualTo(200);
            return this;
        }

        HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
                throws Exception {
            String xsrf = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body))
                    .header("X-XSRF-TOKEN", xsrf);
            headers.forEach(request::header);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            return http.send(request.build(), BodyHandlers.ofString());
        }
    }

    @Test
    void overHttpAHeldChangeSetIsA202AndAPreviewSaysHold() throws Exception {
        Person alice = settingsAdmin();
        approversAre(approver());
        Browser a = new Browser().signIn(alice);
        String body = "{\"changes\":[{\"key\":\"%s\",\"value\":\"3\"}]}".formatted(BrokerSettings.BULK_CAP);

        HttpResponse<String> preview = a.send("POST", "/api/v1/settings/changes/preview", body, Map.of());
        HttpResponse<String> held = a.send("POST", "/api/v1/settings/changes", body, Map.of());
        HttpResponse<String> invalid = a.send(
                "POST",
                "/api/v1/settings/changes",
                "{\"changes\":[{\"key\":\"%s\",\"value\":\"0\"}]}".formatted(BrokerSettings.BULK_CAP),
                Map.of());

        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(preview.body(), "$.outcome")).isEqualTo("HOLD");
        assertThat(JsonPath.<String>read(preview.body(), "$.policyLabel")).isEqualTo("Two people for purges");
        assertThat(held.statusCode()).isEqualTo(202);
        assertThat(JsonPath.<String>read(held.body(), "$.outcome")).isEqualTo("held");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(invalid.body(), "$.errors[0].field")).isEqualTo(BrokerSettings.BULK_CAP);
        String listed = a.send("GET", "/api/v1/settings", null, Map.of()).body();
        assertThat(JsonPath.<String>read(listed, "$.settings['" + BrokerSettings.BULK_CAP + "'].pending[0].value"))
                .isEqualTo("3");
    }

    @Test
    void aStorePolicyIsHeldAsOneChangeSetOfTheValuesItChanges() {
        Person alice = newUser(DataPermissions.DATA_READ, DataPermissions.DATA_WRITE);
        approversAre(approver());
        signIn(alice);
        String retention = settings.value(stores.key("audit", "retention"));

        assertThatThrownBy(() -> lifecycle.update("audit", retention, 1234, 61))
                .isInstanceOf(OperationHeldException.class);

        List<String> held = jdbc.queryForList(
                "SELECT params::text FROM held_operation WHERE requester_id = ?", String.class, alice.id());
        assertThat(held)
                .singleElement()
                .satisfies(params -> assertThat(params)
                        .contains(stores.key("audit", "quota"), "1234", stores.key("audit", "quota-warn-percent"), "61")
                        .doesNotContain(stores.key("audit", "retention")));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM rahdar_setting WHERE key LIKE ?",
                        Long.class,
                        stores.key("audit", "") + "%"))
                .isZero();
    }

    @Test
    void aChangeSetIsHeldTheSameThroughRestAndTheMcpTool() throws Exception {
        Person alice = settingsAdmin();
        approversAre(approver());
        AtomicReference<McpFixture.Key> minted = new AtomicReference<>();
        whileDisarmed(() -> minted.set(McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(SettingsPermissions.SETTINGS_READ, SettingsPermissions.SETTINGS_WRITE))));
        McpFixture.Key agent = minted.get();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        HttpResponse<String> rest = new Browser()
                .signIn(alice)
                .send(
                        "POST",
                        "/api/v1/settings/changes",
                        "{\"changes\":[{\"key\":\"%s\",\"value\":\"3\"}]}".formatted(BrokerSettings.BULK_CAP),
                        Map.of());
        JsonNode mcp = McpFixture.callTool(
                mvc, agent, "rahdar_setting", Map.of("op", "set", "key", BrokerSettings.BULK_CAP, "value", "3"));

        assertThat(rest.statusCode()).as(rest.body()).isEqualTo(202);
        assertThat(mcp.path("result").path("content").get(0).path("text").asString())
                .as("%s", mcp)
                .startsWith("Held for approval: ");
        Map<String, Object> viaRest = heldRequestOf(alice.id());
        Map<String, Object> viaMcp = heldRequestOf(agent.userId());
        assertThat(viaRest.remove("auth_kind")).isEqualTo("SESSION");
        assertThat(viaMcp.remove("auth_kind")).isEqualTo("AGENT");
        assertThat(viaMcp).isEqualTo(viaRest);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM rahdar_setting WHERE key = ?", Long.class, BrokerSettings.BULK_CAP))
                .isZero();
    }

    /** What the requester's one held request asks for, as stored, and how they signed in. */
    private Map<String, Object> heldRequestOf(UUID requesterId) {
        return new HashMap<>(jdbc.queryForMap("""
                SELECT type, type_version, mode, auth_kind, summary, traits::text AS traits, params::text AS params,
                    display::text AS display, effect::text AS effect, encode(params_hash, 'hex') AS params_hash,
                    cluster_id, environment_id
                FROM held_operation WHERE requester_id = ?""", requesterId));
    }

    @Test
    void aNewTokenIsCompletedByItsRequesterAfterApprovalOverHttp() throws Exception {
        Person alice = newUser();
        Person bob = approver();
        approversAre(bob);
        Browser a = new Browser().signIn(alice);
        String body =
                "{\"name\":\"ci\",\"expiresAt\":\"%s\",\"grants\":[{\"action\":\"cluster:read\",\"scopeType\":\"GLOBAL\"}]}"
                        .formatted(Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));

        HttpResponse<String> first = a.send("POST", "/api/v1/tokens", body, Map.of());

        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        UUID id = UUID.fromString(
                first.headers().firstValue(GateContext.HELD_HEADER).orElseThrow());
        assertThat(tokens.listFor(alice.id())).isEmpty();
        assertThat(lastRequest().traits()).containsExactly(Trait.ACCESS_CONTROL);
        assertThat(lastRequest().params()).doesNotContain("as_");

        approve(bob, id);
        assertThat(stateOf(id)).isEqualTo(HeldState.APPROVED);
        assertThat(tokens.listFor(alice.id())).isEmpty();

        HttpResponse<String> second = a.send("POST", "/api/v1/tokens", body, Map.of());

        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(second.body(), "$.value")).startsWith("as_");
        assertThat(tokens.listFor(alice.id())).hasSize(1);
        assertThat(stateOf(id)).isEqualTo(HeldState.SUCCEEDED);
        assertThat(a.send("POST", "/api/v1/tokens", body, Map.of()).statusCode())
                .isEqualTo(202);
    }

    private UUID mintFor(Person owner, String name) {
        UUID[] created = new UUID[1];
        whileDisarmed(() -> created[0] = tokens.mint(
                        owner.id(),
                        name,
                        Instant.now().plus(1, ChronoUnit.DAYS),
                        List.of(TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, "cluster:read")),
                        List.of(),
                        false)
                .entity()
                .getId());
        return created[0];
    }

    @Test
    void revokingSomeoneElsesTokenIsHeldButYourOwnIsNot() {
        Person owner = newUser();
        Person admin = newUser(TokenPermissions.TOKEN_ADMIN);
        Person bob = approver();
        approversAre(bob);
        UUID theirs = mintFor(owner, "theirs");
        UUID mine = mintFor(admin, "mine");
        signIn(admin);

        tokens.revokeAny(mine);
        UUID id = held(() -> tokens.revokeAny(theirs));

        assertThat(revokedAt(mine)).isNotNull();
        assertThat(revokedAt(theirs)).isNull();
        assertThat(provider.decisions.get()).isEqualTo(1);
        assertThat(lastRequest().type()).isEqualTo("token.revoke-any");
        assertThat(lastRequest().traits()).containsExactly(Trait.ACCESS_CONTROL);

        approve(bob, id);

        awaitState(id, HeldState.SUCCEEDED);
        assertThat(revokedAt(theirs)).isNotNull();
    }

    private Object revokedAt(UUID tokenId) {
        return jdbc.queryForObject("SELECT revoked_at FROM api_token WHERE id = ?", Object.class, tokenId);
    }
}
