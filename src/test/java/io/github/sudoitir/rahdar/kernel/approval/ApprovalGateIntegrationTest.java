package io.github.sudoitir.rahdar.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.BulkParams;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.FakeHandle;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.PurgeParams;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.TestProvider;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.TestService;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.TestState;
import io.github.sudoitir.rahdar.kernel.approval.GateTestKit.TokenParams;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.rahdar.kernel.gate.ApproverQuorumException;
import io.github.sudoitir.rahdar.kernel.gate.AuthKind;
import io.github.sudoitir.rahdar.kernel.gate.EventCursor;
import io.github.sudoitir.rahdar.kernel.gate.GateContext;
import io.github.sudoitir.rahdar.kernel.gate.GateDecision;
import io.github.sudoitir.rahdar.kernel.gate.GatePreview;
import io.github.sudoitir.rahdar.kernel.gate.GateScope;
import io.github.sudoitir.rahdar.kernel.gate.GateTicket;
import io.github.sudoitir.rahdar.kernel.gate.HeldEvent;
import io.github.sudoitir.rahdar.kernel.gate.HeldOperations;
import io.github.sudoitir.rahdar.kernel.gate.HeldState;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationDeniedException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.gate.RunCheck;
import io.github.sudoitir.rahdar.kernel.gate.Vote;
import io.github.sudoitir.rahdar.kernel.gate.VoteCheck;
import io.github.sudoitir.rahdar.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.rahdar.kernel.plugin.PluginStatusChanged;
import io.github.sudoitir.rahdar.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.rahdar.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.rahdar.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.GrantLoader;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.PermissionHolders;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.SealedStore;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.kernel.security.SessionAuthentication;
import io.github.sudoitir.rahdar.kernel.security.SessionFacts;
import io.github.sudoitir.rahdar.kernel.security.TokenGrant;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.stream.UserSignals;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval gate's engine against PostgreSQL (ADR-0179..0181): the unchanged path without a provider; allow, deny,
 * hold, a slow, failing or absent provider; the decision rules; running exactly once and every check before it; the
 * jobs; removing the provider; and the HTTP mapping. The provider is a test bean attached the way the host attaches a
 * plugin's, and the gated operations are test-only types over a test service that authorizes and then passes the gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GateTestKit.class)
class ApprovalGateIntegrationTest extends PostgresIntegrationTest {

    private static final String PROVIDER = "gate-test-provider";
    private static final String PASSWORD = "correct-horse-battery";
    private static final PurgeParams ORDERS = new PurgeParams("orders", null);
    private static final TokenParams CI_TOKEN = new TokenParams("ci");

    @LocalServerPort
    int port;

    @Autowired
    TestService service;

    @Autowired
    TestProvider provider;

    @Autowired
    TestState state;

    @Autowired
    OperationGate gate;

    @Autowired
    Approvals approvals;

    @Autowired
    HeldMaintenance maintenance;

    @Autowired
    Executions executions;

    @Autowired
    HeldOperationsService heldOperations;

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
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    List<SealedStore> sealedStores;

    @Autowired
    SecretVault vault;

    @Autowired
    OperatorHandoff handoff;

    @MockitoBean
    PermissionHolders holders;

    @MockitoSpyBean
    SessionAuthentication sessions;

    @MockitoSpyBean
    UserSignals signals;

    private FakeHandle handle;
    private String sha;

    private record Person(UUID id, String username) {}

    // ---- set-up ------------------------------------------------------------------------------------

    @BeforeEach
    void armTheGate() {
        provider.reset();
        state.etag = "state-1";
        state.count = 7;
        state.purgeVersion = 1;
        service.purged.clear();
        service.duringPurge = () -> {};
        jdbc.update("UPDATE plugin_install SET approval_provider = false");
        installs.findById(PROVIDER).ifPresent(installs::delete);
        sha = pluginStore.put(("approval-gate-test-" + UUID.randomUUID()).getBytes());
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
    }

    private Person newUser(String... permissions) {
        String username = "gate-" + UUID.randomUUID().toString().substring(0, 12);
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
            // As an administrator would, not as whoever this thread has signed in: that would log an access change
            // by them, which the approver rules count against their requests.
            var signedIn = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.clearContext();
            accessChanges.changedFor(user.getId());
            if (signedIn != null) {
                SecurityContextHolder.getContext().setAuthentication(signedIn);
            }
        }
        return new Person(user.getId(), username);
    }

    private Person requester() {
        return newUser(GateTestKit.SERVICE_PERMISSION);
    }

    private Person approver() {
        return newUser(GateTestKit.APPROVER_PERMISSION);
    }

    /** Signs {@code person} in on this thread, as a browser session with a fresh sign-in. */
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

    /** Requests a purge as {@code person} and returns the hold. */
    private UUID hold(Person person, String queue) {
        signIn(person);
        try {
            service.purge(new PurgeParams(queue, "s3cret"));
        } catch (OperationHeldException e) {
            return e.heldId();
        }
        throw new AssertionError("expected the purge to be held");
    }

    private Approvals.Detail decide(Person person, UUID id, Vote vote, String reason) {
        signIn(person);
        Approvals.Detail detail = approvals.get(id);
        return approvals.decide(id, vote, reason, detail.view().paramsHash(), detail.version());
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

    private List<String> timeline(UUID id) {
        return jdbc.queryForList(
                "SELECT kind FROM held_operation_event WHERE held_id = ? ORDER BY seq", String.class, id);
    }

    private String detail(UUID id) {
        return jdbc.queryForObject("SELECT outcome_detail FROM held_operation WHERE id = ?", String.class, id);
    }

    /** Runs {@code sql} with the table's triggers off, as someone with direct database access could. */
    private void tamper(String sql, Object... args) {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(sql, args);
        });
    }

    // ---- no provider, allow, deny, unavailable ------------------------------------------------------

    @Test
    void withNoProviderTheActionRunsOnceAndNothingIsStored() {
        jdbc.update("UPDATE plugin_install SET approval_provider = false WHERE id = ?", PROVIDER);
        Person alice = requester();
        signIn(alice);

        assertThat(service.purge(ORDERS)).isEqualTo(1);

        assertThat(service.purged).containsExactly("orders");
        assertThat(provider.decisions).hasValue(0);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, alice.id()))
                .isZero();
        assertThat(gate.preview(Operation.of(ORDERS)).outcome()).isEqualTo(GatePreview.Outcome.RUN);
    }

    @Test
    void anAllowedOperationRunsAndCoversItsItemsWithoutAskingAgain() {
        provider.decide = request -> new GateDecision.Allow(GateTestKit.POLICY);
        signIn(requester());

        assertThat(service.bulk(new BulkParams(List.of("a", "b", "c")))).isEqualTo(3);

        assertThat(service.purged).containsExactly("a", "b", "c");
        assertThat(provider.decisions).hasValue(1);
        assertThat(provider.requests.getFirst().requester().authKind()).isEqualTo(AuthKind.SESSION);
    }

    @Test
    void workThatContinuesAnAllowedOperationAfterItReturnedIsStillCovered() throws Exception {
        provider.decide = request -> new GateDecision.Allow(GateTestKit.POLICY);
        signIn(requester());

        Operator worker = gate.run(Operation.of(new BulkParams(List.of("a", "b"))), handoff::capture);
        Thread run = Thread.ofVirtual()
                .start(() -> handoff.runAs(worker, () -> {
                    service.purge(new PurgeParams("a", null));
                    handoff.runAs(worker, () -> service.purge(new PurgeParams("b", null)));
                }));
        run.join();

        assertThat(service.purged).containsExactly("a", "b");
        assertThat(provider.decisions).hasValue(1);
    }

    @Test
    void aCapturedOperatorRunAgainAfterItsWorkEndedIsNoLongerCovered() {
        provider.decide = request -> new GateDecision.Allow(GateTestKit.POLICY);
        signIn(requester());
        Operator worker = gate.run(Operation.of(new BulkParams(List.of("a"))), handoff::capture);
        handoff.runAs(worker, () -> service.purge(new PurgeParams("a", null)));
        assertThat(provider.decisions).hasValue(1);

        provider.decide = request -> new GateDecision.Hold(GateTestKit.POLICY, Duration.ofHours(1), "a platform lead");
        approversAre(approver());

        assertThatThrownBy(() -> handoff.runAs(worker, () -> service.purge(new PurgeParams("b", null))))
                .isInstanceOf(OperationHeldException.class);
        assertThat(service.purged).containsExactly("a");
        assertThat(provider.decisions).hasValue(2);
    }

    @Test
    void aDeniedOperationIsRefusedWithTheReasonAndAudited() {
        provider.decide = request -> new GateDecision.Deny("Not on Fridays");
        Person alice = requester();
        signIn(alice);

        assertThatThrownBy(() -> service.purge(ORDERS))
                .isInstanceOf(OperationDeniedException.class)
                .hasMessage("Not on Fridays");

        assertThat(service.purged).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'OPERATION_DENIED' AND user_id = ?"
                                + " AND outcome = 'REFUSED'",
                        Long.class,
                        alice.id()))
                .isEqualTo(1);
    }

    @Test
    void aSlowProviderFailsClosed() {
        provider.decide = request -> {
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return new GateDecision.Allow(GateTestKit.POLICY);
        };
        signIn(requester());
        long started = System.nanoTime();

        assertThatThrownBy(() -> service.purge(ORDERS)).isInstanceOf(ApprovalUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        assertThat(service.purged).isEmpty();
    }

    @Test
    void aProviderThatThrowsOrAnswersNothingFailsClosed() {
        signIn(requester());
        provider.decide = request -> {
            throw new IllegalStateException("policy store down");
        };
        assertThatThrownBy(() -> service.purge(ORDERS))
                .isInstanceOf(ApprovalUnavailableException.class)
                .hasMessageNotContaining("policy store");
        provider.decide = request -> null;
        assertThatThrownBy(() -> service.purge(ORDERS)).isInstanceOf(ApprovalUnavailableException.class);
        assertThat(service.purged).isEmpty();
    }

    @Test
    void anArmedProviderThatIsNotAttachedHereFailsClosed() {
        providers.detach(handle);
        signIn(requester());

        assertThatThrownBy(() -> service.purge(ORDERS)).isInstanceOf(ApprovalUnavailableException.class);

        assertThat(service.purged).isEmpty();
        assertThat(provider.decisions).hasValue(0);
    }

    @Test
    void theGateRefusesToRunInsideATransaction() {
        signIn(requester());

        assertThatThrownBy(() -> tx.execute(status -> service.purge(ORDERS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");

        assertThat(service.purged).isEmpty();
        assertThat(provider.decisions).hasValue(0);
    }

    @Test
    void aForgedTicketIsIgnored() {
        Person alice = requester();
        approversAre(approver());
        signIn(alice);

        GateTicket unissued = new GateTicket(null, "test.purge", null);
        GateTicket guessed = new GateTicket(UUID.randomUUID(), "test.purge", "00");

        assertThatThrownBy(() -> purgeCoveredBy(unissued)).isInstanceOf(OperationHeldException.class);
        assertThatThrownBy(() -> purgeCoveredBy(guessed)).isInstanceOf(OperationHeldException.class);

        assertThat(service.purged).isEmpty();
        assertThat(provider.decisions).hasValue(2);
    }

    // ---- holding ------------------------------------------------------------------------------------

    @Test
    void aHoldStoresTheRequestRedactedAndSealedAndTellsTheApprovers() {
        Person alice = requester();
        Person bob = approver();
        Person carol = approver();
        approversAre(alice, bob, carol);

        UUID id = hold(alice, "orders");

        assertThat(service.purged).isEmpty();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM held_operation WHERE id = ?", id);
        assertThat(row).containsEntry("state", "HELD");
        assertThat(row).containsEntry("auth_kind", "SESSION");
        assertThat(row).containsEntry("provider_id", PROVIDER);
        assertThat(row.get("params").toString()).contains("[redacted]").doesNotContain("s3cret");
        assertThat((byte[]) row.get("sealed_payload")).isNotEmpty();
        assertThat(new String((byte[]) row.get("sealed_payload"))).doesNotContain("s3cret");
        assertThat(provider.requests.getFirst().params()).doesNotContain("s3cret");
        assertThat(timeline(id)).containsExactly("REQUESTED");
        assertThat(jdbc.queryForList(
                        "SELECT recipient_id FROM inbox_item WHERE source = 'approvals' AND dedupe_key = ?",
                        UUID.class,
                        "held:" + id))
                .containsExactlyInAnyOrder(bob.id(), carol.id());
        assertThat(jdbc.queryForObject(
                        "SELECT outcome FROM audit_event WHERE id = ?", String.class, row.get("request_audit_id")))
                .isEqualTo("SUCCESS");
        verify(signals).signal(UserSignals.HELD, alice.id());
        verify(signals).signal(UserSignals.HELD, bob.id());
    }

    @Test
    void aRequestMadeWithAnApiTokenNamesTheToken() {
        Person alice = requester();
        approversAre(approver());
        TokenPrincipal token = new TokenPrincipal(
                alice.id(),
                alice.username(),
                Set.of(TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, GateTestKit.SERVICE_PERMISSION)),
                UUID.randomUUID(),
                "ci-deploy",
                Set.of());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(token, null, token.getAuthorities()));
        UUID id;
        try {
            service.purge(ORDERS);
            throw new AssertionError("expected a hold");
        } catch (OperationHeldException e) {
            id = e.heldId();
        }
        signIn(alice);

        Approvals.Detail detail = approvals.get(id);

        assertThat(detail.view().requester().authKind()).isEqualTo(AuthKind.TOKEN);
        assertThat(detail.tokenName()).isEqualTo("ci-deploy");
    }

    @Test
    void theSameRequestAskedAgainIsTheSameHold() {
        Person alice = requester();
        approversAre(approver());

        UUID first = hold(alice, "orders");
        UUID second = hold(alice, "orders");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, alice.id()))
                .isEqualTo(1);
    }

    @Test
    void withNoOtherApproverTheRequestIsDenied() {
        Person alice = newUser(GateTestKit.SERVICE_PERMISSION, GateTestKit.APPROVER_PERMISSION);
        approversAre(alice);
        signIn(alice);

        assertThatThrownBy(() -> service.purge(ORDERS))
                .isInstanceOf(OperationDeniedException.class)
                .hasMessage(GateEngine.NO_APPROVER);
        assertThat(gate.preview(Operation.of(ORDERS)).outcome()).isEqualTo(GatePreview.Outcome.DENY);
    }

    @Test
    void aRequesterHasAtMostTheConfiguredOpenRequests() {
        Person alice = requester();
        approversAre(approver());
        for (int i = 0; i < 20; i++) {
            hold(alice, "q" + i);
        }

        PurgeParams oneMore = new PurgeParams("one-more", null);

        assertThatThrownBy(() -> service.purge(oneMore)).isInstanceOf(TooManyHeldOperationsException.class);
    }

    @Test
    void aProviderHoldUnderTheMinimumFailsClosed() {
        provider.decide = request -> new GateDecision.Hold(GateTestKit.POLICY, Duration.ofSeconds(5), null);
        approversAre(approver());
        signIn(requester());

        assertThatThrownBy(() -> service.purge(ORDERS)).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void aProviderHoldOverTheMaximumIsShortenedToIt() {
        provider.decide = request -> new GateDecision.Hold(GateTestKit.POLICY, Duration.ofDays(365), null);
        approversAre(approver());
        signIn(requester());

        assertThatThrownBy(() -> service.purge(ORDERS))
                .isInstanceOfSatisfying(
                        OperationHeldException.class,
                        held -> assertThat(held.expiresAt())
                                .isBetween(
                                        Instant.now().plus(Duration.ofDays(30)).minusSeconds(60),
                                        Instant.now().plus(Duration.ofDays(30)).plusSeconds(60)));
    }

    @Test
    void anAllowWithoutAPolicyFailsClosedAsAnUnavailableProvider() {
        assertThatThrownBy(() -> new GateDecision.Allow(null)).isInstanceOf(NullPointerException.class);
        provider.decide = request -> new GateDecision.Allow(null);
        signIn(requester());

        assertThatThrownBy(() -> service.purge(ORDERS)).isInstanceOf(ApprovalUnavailableException.class);

        assertThat(service.purged).isEmpty();
    }

    @Test
    void aPreviewHoldsNothing() {
        Person alice = requester();
        approversAre(approver());
        signIn(alice);

        GatePreview preview = gate.preview(Operation.of(ORDERS));

        assertThat(preview.outcome()).isEqualTo(GatePreview.Outcome.HOLD);
        assertThat(preview.effect().count()).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, alice.id()))
                .isZero();
    }

    // ---- deciding -----------------------------------------------------------------------------------

    @Test
    void anApprovedRequestRunsOnceAsTheRequesterAndEveryoneIsTold() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");

        Approvals.Detail decided = decide(bob, id, Vote.APPROVE, null);

        assertThat(decided.view().state()).isIn(HeldState.APPROVED, HeldState.EXECUTING, HeldState.SUCCEEDED);
        awaitState(id, HeldState.SUCCEEDED);
        assertThat(service.purged).containsExactly("orders");
        assertThat(timeline(id)).containsExactly("REQUESTED", "APPROVED", "EXECUTING", "SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT sealed_payload FROM held_operation WHERE id = ?", byte[].class, id))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT read_at IS NOT NULL FROM inbox_item WHERE recipient_id = ? AND dedupe_key = ?",
                        Boolean.class,
                        bob.id(),
                        "held:" + id))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT title FROM inbox_item WHERE recipient_id = ? AND dedupe_key = ?",
                        String.class,
                        alice.id(),
                        "held-outcome:" + id))
                .startsWith("Done:");

        executions.runApproved(id);
        executions.runDue(Duration.ZERO);
        assertThat(service.purged).containsExactly("orders");
    }

    @Test
    void anApprovedRunInterruptedWhileRunningEndsWithAnUnknownOutcomeNotAFailure() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        service.duringPurge = () -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("stopping");
        };

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.OUTCOME_UNKNOWN);
        assertThat(detail(id)).contains("Interrupted");
        assertThat(timeline(id)).containsExactly("REQUESTED", "APPROVED", "EXECUTING", "OUTCOME_UNKNOWN");
        assertThat(service.purged).isEmpty();
    }

    @Test
    void theRequesterMayNotApproveTheirOwnRequest() {
        Person alice = newUser(GateTestKit.SERVICE_PERMISSION, GateTestKit.APPROVER_PERMISSION);
        approversAre(approver());
        UUID id = hold(alice, "orders");

        assertThatThrownBy(() -> decide(alice, id, Vote.APPROVE, null))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining("Another person");

        assertThat(stateOf(id)).isEqualTo(HeldState.HELD);
        assertThat(timeline(id)).containsExactly("REQUESTED", "VOTE_REFUSED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'OPERATION_APPROVED' AND outcome = 'REFUSED'"
                                + " AND user_id = ?",
                        Long.class,
                        alice.id()))
                .isEqualTo(1);
    }

    @Test
    void anApiTokenOrAnAssistantMayNotDecide() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        signIn(bob);
        Approvals.Detail detail = approvals.get(id);

        TokenPrincipal token =
                new TokenPrincipal(bob.id(), bob.username(), Set.of(), UUID.randomUUID(), "cli", Set.of());
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(token, null, List.of()));
        String hash = detail.view().paramsHash();
        int version = detail.version();
        assertThatThrownBy(() -> approvals.decide(id, Vote.APPROVE, null, hash, version))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining("API token");

        signIn(bob);
        assertThatThrownBy(() -> decideAsAssistant(id, hash, version))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining("assistant");

        doReturn(Optional.empty()).when(sessions).current();
        assertThatThrownBy(() -> approvals.decide(id, Vote.APPROVE, null, hash, version))
                .isInstanceOf(VoteRefusedException.class);

        assertThat(stateOf(id)).isEqualTo(HeldState.HELD);
        assertThat(timeline(id)).containsExactly("REQUESTED", "VOTE_REFUSED", "VOTE_REFUSED", "VOTE_REFUSED");
    }

    @Test
    void aStaleSignInMustBeConfirmedFirst() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        signIn(bob);
        Approvals.Detail detail = approvals.get(id);
        Instant old = Instant.now().minus(Duration.ofMinutes(30));
        doReturn(Optional.of(new SessionFacts(old, null, null, old, "127.0.0.1", "test")))
                .when(sessions)
                .current();

        String paramsHash = detail.view().paramsHash();
        int version = detail.version();

        assertThatThrownBy(() -> approvals.decide(id, Vote.APPROVE, null, paramsHash, version))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(stateOf(id)).isEqualTo(HeldState.HELD);
    }

    @Test
    void theDetailSaysWhetherTheViewersSessionVerifiedASecondFactor() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        signIn(bob);

        assertThat(approvals.get(id).mfaVerified()).isFalse();

        Instant now = Instant.now();
        doReturn(Optional.of(new SessionFacts(now, now, SessionFacts.Method.TOTP, now, "127.0.0.1", "test")))
                .when(sessions)
                .current();
        assertThat(approvals.get(id).mfaVerified()).isTrue();
    }

    @Test
    void anAccountCreatedAfterTheRequestMayNotDecideIt() {
        Person alice = requester();
        approversAre(approver());
        UUID id = hold(alice, "orders");
        Person latecomer = approver();

        assertThatThrownBy(() -> decide(latecomer, id, Vote.APPROVE, null))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining("created after");
    }

    @Test
    void anApproverWithTheRequestersEmailInAnotherCaseMayNotDecide() {
        Person alice = requester();
        Person twin = approver();
        AppUserEntity entity = users.findById(twin.id()).orElseThrow();
        entity.setEmail((alice.username() + "@EXAMPLE.test").toUpperCase(Locale.ROOT));
        users.save(entity);
        approversAre(approver());
        UUID id = hold(alice, "orders");

        assertThatThrownBy(() -> decide(twin, id, Vote.APPROVE, null))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining("email");
    }

    @Test
    void someoneWithoutTheApproverPermissionCannotSeeTheRequestAndIsRefusedADecision() {
        Person alice = requester();
        approversAre(approver());
        Person mallory = newUser(GateTestKit.SERVICE_PERMISSION);
        UUID id = hold(alice, "orders");
        Approvals.Detail shown = approvals.get(id);
        signIn(mallory);

        assertThatThrownBy(() -> approvals.get(id)).isInstanceOf(NotFoundException.class);
        String hash = shown.view().paramsHash();
        int version = shown.version();
        assertThatThrownBy(() -> approvals.decide(id, Vote.APPROVE, null, hash, version))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining(GateTestKit.APPROVER_PERMISSION);
        assertThat(timeline(id)).containsExactly("REQUESTED", "VOTE_REFUSED");
    }

    @Test
    void aRequesterWhoChangedSomeonesAccessAfterAskingCannotBeApproved() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        jdbc.update(
                "INSERT INTO access_change_log (at, actor_id, subject_id) VALUES (now(), ?, ?)", alice.id(), bob.id());

        assertThatThrownBy(() -> decide(bob, id, Vote.APPROVE, null))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessageContaining("changed another user's access");

        Approvals.Detail rejected = decide(bob, id, Vote.REJECT, "Ask again without the grant");
        assertThat(rejected.view().state()).isEqualTo(HeldState.REJECTED);
    }

    @Test
    void aDecisionMustEchoTheRequestItWasShown() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        signIn(bob);
        Approvals.Detail detail = approvals.get(id);

        assertThatThrownBy(() -> approvals.decide(id, Vote.APPROVE, null, "00".repeat(32), detail.version()))
                .isInstanceOfSatisfying(
                        VoteRefusedException.class, e -> assertThat(e.slug()).isEqualTo("held-operation-changed"));
        String hash = detail.view().paramsHash();
        assertThatThrownBy(() -> approvals.decide(id, Vote.APPROVE, null, hash, 7))
                .isInstanceOf(VoteRefusedException.class);

        assertThat(stateOf(id)).isEqualTo(HeldState.HELD);
    }

    @Test
    void aRejectionNeedsAReasonAndTellsTheRequesterWhy() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");

        assertThatThrownBy(() -> decide(bob, id, Vote.REJECT, " "))
                .isInstanceOfSatisfying(
                        VoteRefusedException.class, e -> assertThat(e.slug()).isEqualTo("decision-reason-required"));
        decide(bob, id, Vote.REJECT, "Wrong queue");

        assertThat(stateOf(id)).isEqualTo(HeldState.REJECTED);
        assertThat(service.purged).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT body FROM inbox_item WHERE recipient_id = ? AND dedupe_key = ?",
                        String.class,
                        alice.id(),
                        "held-outcome:" + id))
                .contains("Wrong queue");
    }

    @Test
    void theProvidersVoteCheckApplies() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        provider.vote = VoteCheck.refuse("Needs a second factor");

        assertThatThrownBy(() -> decide(bob, id, Vote.APPROVE, null))
                .isInstanceOf(VoteRefusedException.class)
                .hasMessage("Needs a second factor");
    }

    @Test
    void ofTwoApproversRacingOneWins() throws Exception {
        Person alice = requester();
        Person bob = approver();
        Person carol = approver();
        approversAre(bob, carol);
        UUID id = hold(alice, "orders");
        signIn(bob);
        Approvals.Detail detail = approvals.get(id);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (Person person : List.of(bob, carol)) {
            results.add(pool.submit(() -> {
                RahdarPrincipal principal =
                        new RahdarPrincipal(person.id(), person.username(), grants.loadFor(person.id()), false);
                SecurityContextHolder.getContext()
                        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, principal.getAuthorities()));
                start.await();
                try {
                    approvals.decide(id, Vote.APPROVE, null, detail.view().paramsHash(), detail.version());
                    return "won";
                } catch (ConflictException _) {
                    return "conflict";
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("won", "conflict");
        awaitState(id, HeldState.SUCCEEDED);
        assertThat(service.purged).containsExactly("orders");
    }

    @Test
    void aRequestPastItsExpiryByTheDatabaseClockCannotBeDecidedAndIsExpired() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        tamper(
                "UPDATE held_operation SET requested_at = now() - interval '2 hours',"
                        + " expires_at = now() - interval '1 hour' WHERE id = ?",
                id);

        assertThatThrownBy(() -> decide(bob, id, Vote.APPROVE, null)).isInstanceOf(ConflictException.class);
        maintenance.sweep();

        assertThat(stateOf(id)).isEqualTo(HeldState.EXPIRED);
        assertThat(timeline(id)).containsExactly("REQUESTED", "EXPIRED");
        assertThat(jdbc.queryForObject("SELECT sealed_payload FROM held_operation WHERE id = ?", byte[].class, id))
                .isNull();
    }

    @Test
    void theRequesterMayCancelAndNobodyElse() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");

        signIn(bob);
        assertThatThrownBy(() -> approvals.cancel(id)).isInstanceOf(AccessDeniedException.class);
        signIn(alice);
        assertThat(approvals.cancel(id).view().state()).isEqualTo(HeldState.CANCELLED);
        assertThatThrownBy(() -> approvals.cancel(id)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> decide(bob, id, Vote.APPROVE, null)).isInstanceOf(ConflictException.class);
        assertThat(service.purged).isEmpty();
    }

    // ---- running ------------------------------------------------------------------------------------

    @Test
    void aChangedTargetIsRefusedAtRunTime() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        state.etag = "state-2";

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).contains("changed");
        assertThat(service.purged).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'HELD_OPERATION_REFUSED' AND parent_id ="
                                + " (SELECT request_audit_id FROM held_operation WHERE id = ?)",
                        Long.class,
                        id))
                .isEqualTo(1);
    }

    @Test
    void theProvidersRunCheckApplies() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        provider.run = RunCheck.refuse("The count grew past 10%");

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).isEqualTo("The count grew past 10%");
        assertThat(service.purged).isEmpty();
    }

    /** Disables {@code person} the way the user service does: a write and the announcement, in one transaction. */
    private void disable(Person person) {
        tx.executeWithoutResult(status -> {
            AppUserEntity entity = users.findById(person.id()).orElseThrow();
            entity.setDisabled(true);
            users.save(entity);
            accessChanges.changedFor(person.id());
        });
    }

    /** The suite's users accumulate; a quorum test starts from none, so the approvers it makes are all there are. */
    private void startWithNoOneEnabled() {
        jdbc.update("UPDATE app_user SET disabled = true");
        // The registry reads the approver permission from the installed descriptor, as it does in production.
        jdbc.update(
                "UPDATE plugin_install SET descriptor = ?::jsonb WHERE id = ?",
                "{\"approvalProvider\":{\"approverPermission\":\"" + GateTestKit.APPROVER_PERMISSION + "\"}}",
                PROVIDER);
    }

    @Test
    void anAccessChangeThatLeavesOneApproverIsRefusedAndNothingIsChanged() {
        startWithNoOneEnabled();
        approver();
        Person second = approver();

        assertThatThrownBy(() -> disable(second)).isInstanceOf(ApproverQuorumException.class);

        assertThat(users.findById(second.id()).orElseThrow().isDisabled()).isFalse();
    }

    @Test
    void anAccessChangeThatLeavesTwoApproversIsAllowed() {
        startWithNoOneEnabled();
        approver();
        approver();
        Person third = approver();

        disable(third);

        assertThat(users.findById(third.id()).orElseThrow().isDisabled()).isTrue();
    }

    @Test
    void aChangeThatDoesNotLowerTheApproversIsNeverRefusedEvenBelowQuorum() {
        startWithNoOneEnabled();
        approver();
        Person unrelated = requester();

        disable(unrelated);

        assertThat(users.findById(unrelated.id()).orElseThrow().isDisabled()).isTrue();
    }

    @Test
    void nothingIsRefusedWhileTheProviderHoldsNothing() {
        startWithNoOneEnabled();
        provider.enforcing = false;
        approver();
        Person second = approver();

        disable(second);

        assertThat(users.findById(second.id()).orElseThrow().isDisabled()).isTrue();
    }

    @Test
    void aDisabledRequesterIsRefusedAtRunTime() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        AppUserEntity entity = users.findById(alice.id()).orElseThrow();
        entity.setDisabled(true);
        users.save(entity);

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).contains("disabled");
        assertThat(service.purged).isEmpty();
    }

    @Test
    void aRequesterWhoLostThePermissionIsRefusedAtRunTime() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        userRoles.deleteAll(userRoles.findByIdUserId(alice.id()));
        accessChanges.changedFor(alice.id());
        jdbc.update("DELETE FROM access_change_log WHERE actor_id = ?", alice.id());

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).contains("no longer holds the permission");
        assertThat(service.purged).isEmpty();
    }

    @Test
    void anOperationTypeThatChangedVersionIsRefused() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        state.purgeVersion = 2;

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).contains("changed this operation");
        assertThat(service.purged).isEmpty();
    }

    @Test
    void theTriggerKeepsWhatWasRequestedAndTheLifeOfARequest() {
        Person alice = requester();
        approversAre(approver());
        UUID id = hold(alice, "orders");

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE held_operation SET params = '{\"queue\":\"payments\"}'::jsonb WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE held_operation SET summary = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE held_operation SET state = 'SUCCEEDED', finished_at = now(), sealed_payload = NULL"
                                + " WHERE id = ?",
                        id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE held_operation SET sealed_payload = '\\x01'::bytea WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM held_operation WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE held_operation_event SET detail = 'x' WHERE held_id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM held_operation_event WHERE held_id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThat(stateOf(id)).isEqualTo(HeldState.HELD);
    }

    @Test
    void aKeyRotationMayRewrapTheSealsInPlace() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        decide(bob, id, Vote.REJECT, "No");
        byte[] decision =
                jdbc.queryForObject("SELECT sealed_decision FROM held_operation WHERE id = ?", byte[].class, id);

        int rewrapped = 0;
        for (SealedStore store : sealedStores) {
            if (store.name().startsWith("held_operation.")) {
                rewrapped += store.rewrapBatch(
                                null, Integer.MAX_VALUE, Integer.MAX_VALUE, blob -> vault.rewrap(blob, 1))
                        .updated();
            }
        }

        assertThat(rewrapped).isPositive();
        assertThat(jdbc.queryForObject("SELECT sealed_decision FROM held_operation WHERE id = ?", byte[].class, id))
                .isNotEqualTo(decision);
        assertThat(SecretVault.MIN_BLOB_BYTES)
                .as("the trigger's well-formed length")
                .isEqualTo(93);
    }

    @Test
    void aTamperedHashIsRefusedAsIntegrity() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        byte[] other = new byte[32];
        other[0] = 1;
        tamper("UPDATE held_operation SET params_hash = ? WHERE id = ?", other, id);

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).startsWith("Integrity check failed");
        assertThat(service.purged).isEmpty();
    }

    @Test
    void aSealCopiedFromAnotherRequestDoesNotOpen() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID harmless = hold(alice, "harmless");
        UUID id = hold(alice, "orders");
        tamper(
                "UPDATE held_operation SET sealed_payload = (SELECT sealed_payload FROM held_operation WHERE id = ?)"
                        + " WHERE id = ?",
                harmless,
                id);

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.REFUSED);
        assertThat(detail(id)).startsWith("Integrity check failed");
        assertThat(service.purged).isEmpty();
    }

    @Test
    void anItemOfAnApprovedBulkRunPassesWithoutAnotherApproval() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        signIn(alice);
        UUID id;
        try {
            service.bulk(new BulkParams(List.of("a", "b")));
            throw new AssertionError("expected a hold");
        } catch (OperationHeldException e) {
            id = e.heldId();
        }

        decide(bob, id, Vote.APPROVE, null);

        awaitState(id, HeldState.SUCCEEDED);
        assertThat(service.purged).containsExactly("a", "b");
        assertThat(provider.decisions).hasValue(1);
    }

    @Test
    void aRunWhoseReplicaVanishedEndsAsOutcomeUnknownAndNeverRunsAgain() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        signIn(alice);
        UUID id = holdToken(alice, "ci");
        decide(bob, id, Vote.APPROVE, null);
        jdbc.update(
                "UPDATE held_operation SET state = 'EXECUTING', claimed_at = now() - interval '1 hour', claimed_by = ?"
                        + " WHERE id = ?",
                UUID.randomUUID(),
                id);

        maintenance.sweep();

        assertThat(stateOf(id)).isEqualTo(HeldState.OUTCOME_UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT sealed_payload FROM held_operation WHERE id = ?", byte[].class, id))
                .isNull();
        signIn(alice);
        assertThatThrownBy(() -> service.createToken(CI_TOKEN)).isInstanceOf(OperationHeldException.class);
    }

    private int purgeCoveredBy(GateTicket ticket) throws Exception {
        return ScopedValue.where(GateScope.COVERED, ticket).call(() -> service.purge(ORDERS));
    }

    private void decideAsAssistant(UUID id, String hash, int version) throws Exception {
        ScopedValue.where(GateContext.ORIGIN, AuthKind.AGENT).call(() -> {
            approvals.decide(id, Vote.APPROVE, null, hash, version);
            return null;
        });
    }

    private UUID holdToken(Person person, String name) {
        signIn(person);
        try {
            service.createToken(new TokenParams(name));
        } catch (OperationHeldException e) {
            return e.heldId();
        }
        throw new AssertionError("expected the token to be held");
    }

    @Test
    void aSecretResultIsCompletedByItsRequesterOnceAndByNobodyElse() throws Exception {
        Person alice = requester();
        Person bob = approver();
        Person eve = requester();
        approversAre(bob);
        UUID id = holdToken(alice, "ci");
        decide(bob, id, Vote.APPROVE, null);
        assertThat(stateOf(id)).isEqualTo(HeldState.APPROVED);

        UUID evesOwn = holdToken(eve, "ci");
        assertThat(evesOwn).isNotEqualTo(id);

        signIn(alice);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var context = SecurityContextHolder.getContext();
        Callable<String> complete = () -> {
            SecurityContextHolder.setContext(context);
            start.await();
            try {
                return service.createToken(CI_TOKEN);
            } catch (OperationDeniedException | OperationHeldException _) {
                return "refused";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        Future<String> one = pool.submit(complete);
        Future<String> two = pool.submit(complete);
        start.countDown();
        List<String> outcomes = List.of(one.get(), two.get());
        pool.shutdown();

        assertThat(outcomes).filteredOn(o -> o.startsWith("secret-ci-")).hasSize(1);
        assertThat(stateOf(id)).isEqualTo(HeldState.SUCCEEDED);
        assertThat(service.tokens).hasValue(1);
    }

    // ---- the provider ---------------------------------------------------------------------------------

    @Test
    void removingTheProviderCancelsEveryOpenRequestAndIsAudited() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID held = hold(alice, "orders");
        UUID approved = holdToken(alice, "ci");
        decide(bob, approved, Vote.APPROVE, null);

        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE plugin_install SET status = 'disabled' WHERE id = ?", PROVIDER);
            events.publishEvent(new PluginStatusChanged(
                    PROVIDER, PluginInstallStatus.ACTIVE, PluginInstallStatus.DISABLED, "tester"));
        });

        assertThat(stateOf(held)).isEqualTo(HeldState.CANCELLED);
        assertThat(stateOf(approved)).isEqualTo(HeldState.CANCELLED);
        assertThat(detail(held)).isEqualTo(HeldMaintenance.PROVIDER_REMOVED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'GATE_PROVIDER_REMOVED' AND target_name = ?",
                        Long.class,
                        PROVIDER))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inbox_item WHERE recipient_id = ? AND dedupe_key IN (?, ?)",
                        Long.class,
                        alice.id(),
                        "held-outcome:" + held,
                        "held-outcome:" + approved))
                .isEqualTo(2);
    }

    @Test
    void theSweepCancelsRequestsOfAProviderNoLongerMeantToRun() {
        Person alice = requester();
        approversAre(approver());
        UUID id = hold(alice, "orders");
        jdbc.update("UPDATE plugin_install SET status = 'uninstalled' WHERE id = ?", PROVIDER);

        maintenance.sweep();

        assertThat(stateOf(id)).isEqualTo(HeldState.CANCELLED);
    }

    @Test
    void theSweepCancelsRequestsOfAClusterThatWasDeleted() {
        Person alice = requester();
        approversAre(approver());
        UUID id = hold(alice, "orders");
        // The guard trigger keeps what was requested from changing; the test stands in for a request made of a cluster.
        jdbc.execute("ALTER TABLE held_operation DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE held_operation SET cluster_id = ? WHERE id = ?", UUID.randomUUID(), id);
        } finally {
            jdbc.execute("ALTER TABLE held_operation ENABLE TRIGGER USER");
        }

        maintenance.sweep();

        assertThat(stateOf(id)).isEqualTo(HeldState.CANCELLED);
        assertThat(detail(id)).isEqualTo(HeldMaintenance.CLUSTER_DELETED);
    }

    @Test
    void theProviderReadsItsOwnRequestsAndTheirEventsInCommitOrder() {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        UUID id = hold(alice, "orders");
        decide(bob, id, Vote.REJECT, "No");
        HeldOperations mine = (HeldOperations) heldOperations.beansFor(PROVIDER).get(HeldOperationsService.BEAN_NAME);
        HeldOperations other =
                (HeldOperations) heldOperations.beansFor("someone-else").get(HeldOperationsService.BEAN_NAME);

        List<HeldEvent> all = new ArrayList<>();
        EventCursor cursor = EventCursor.START;
        List<HeldEvent> page;
        do {
            page = mine.eventsAfter(cursor, 2);
            all.addAll(page);
            if (!page.isEmpty()) {
                cursor = page.getLast().cursor();
            }
        } while (!page.isEmpty());

        assertThat(all).isSortedAccordingTo(Comparator.comparing(HeldEvent::cursor));
        assertThat(all.stream().filter(e -> e.heldId().equals(id)).map(HeldEvent::kind))
                .containsExactly(HeldEvent.Kind.REQUESTED, HeldEvent.Kind.REJECTED);
        assertThat(mine.eventsAfter(cursor, 10)).isEmpty();
        assertThat(mine.get(id)).isPresent();
        assertThat(other.get(id)).isEmpty();
        assertThat(other.eventsAfter(EventCursor.START, 10))
                .noneMatch(e -> e.heldId().equals(id));
    }

    @Test
    void theProviderMayExpireItsOwnOpenRequest() {
        Person alice = requester();
        approversAre(approver());
        UUID id = hold(alice, "orders");
        HeldOperations mine = (HeldOperations) heldOperations.beansFor(PROVIDER).get(HeldOperationsService.BEAN_NAME);
        HeldOperations other =
                (HeldOperations) heldOperations.beansFor("someone-else").get(HeldOperationsService.BEAN_NAME);

        assertThat(other.expire(id, "Not mine")).isFalse();
        assertThat(mine.expire(id, "Policy withdrawn")).isTrue();
        assertThat(stateOf(id)).isEqualTo(HeldState.EXPIRED);
        assertThat(detail(id)).isEqualTo("Policy withdrawn");
    }

    // ---- HTTP ---------------------------------------------------------------------------------------

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
    void overHttpAHoldIsA202AndTheApproverDecidesInAFreshSession() throws Exception {
        Person alice = requester();
        Person bob = approver();
        approversAre(bob);
        Browser a = new Browser().signIn(alice);

        HttpResponse<String> held = a.send("POST", "/api/v1/test-gate/purge", "{\"queue\":\"orders\"}", Map.of());

        assertThat(held.statusCode()).isEqualTo(202);
        String id = held.headers().firstValue(GateContext.HELD_HEADER).orElseThrow();
        assertThat(held.headers().firstValue("Location")).contains("/api/v1/held-operations/" + id);
        assertThat(JsonPath.<String>read(held.body(), "$.outcome")).isEqualTo("held");
        assertThat(JsonPath.<String>read(held.body(), "$.heldOperation.id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(held.body(), "$.heldOperation.summary"))
                .isEqualTo("Purge queue orders");
        assertThat(JsonPath.<String>read(
                        a.send("GET", "/api/v1/held-operations?scope=MINE", null, Map.of())
                                .body(),
                        "$.items[0].id"))
                .isEqualTo(id);
        assertThat(JsonPath.<Boolean>read(
                        a.send("GET", "/api/v1/gate/status", null, Map.of()).body(), "$.armed"))
                .isTrue();

        Browser b = new Browser().signIn(bob);
        String shown =
                b.send("GET", "/api/v1/held-operations/" + id, null, Map.of()).body();
        assertThat(JsonPath.<Boolean>read(shown, "$.canDecide")).isTrue();
        assertThat(JsonPath.<String>read(
                        b.send("GET", "/api/v1/held-operations?scope=DECIDABLE", null, Map.of())
                                .body(),
                        "$.items[0].id"))
                .isEqualTo(id);
        String decision = "{\"vote\":\"APPROVE\",\"paramsHash\":\"%s\",\"version\":%d}"
                .formatted(JsonPath.<String>read(shown, "$.paramsHash"), JsonPath.<Integer>read(shown, "$.version"));
        HttpResponse<String> approved =
                b.send("POST", "/api/v1/held-operations/" + id + "/decision", decision, Map.of());

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        awaitState(UUID.fromString(id), HeldState.SUCCEEDED);
        assertThat(service.purged).containsExactly("orders");
        assertThat(new Browser()
                        .signIn(newUser())
                        .send("GET", "/api/v1/held-operations/" + id, null, Map.of())
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void overHttpTheGatesRefusalsHaveTheirProblemTypes() throws Exception {
        Person alice = requester();
        Browser a = new Browser().signIn(alice);

        provider.decide = request -> new GateDecision.Deny("Frozen");
        HttpResponse<String> denied = a.send("POST", "/api/v1/test-gate/purge", "{\"queue\":\"q\"}", Map.of());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(denied.body(), "$.type")).endsWith("/operation-denied");

        providers.detach(handle);
        HttpResponse<String> unavailable = a.send("POST", "/api/v1/test-gate/purge", "{\"queue\":\"q\"}", Map.of());
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(unavailable.body(), "$.type")).endsWith("/approval-unavailable");
    }
}
