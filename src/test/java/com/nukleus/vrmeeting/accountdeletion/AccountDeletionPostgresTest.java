package com.nukleus.vrmeeting.accountdeletion;

import com.nukleus.vrmeeting.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.nukleus.vrmeeting.accountdeletion.AccountDeletionUnitTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in ONLY: new random schema in explicitly named loopback test database.
 * Does not load Spring Boot, application.properties, .env, GCP beans or production credentials.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDeletionPostgresTest {
    DriverManagerDataSource rootDataSource, dataSource;
    JdbcTemplate jdbc;
    String schema;
    LocalContainerEntityManagerFactoryBean factory;
    EntityManager entityManager;
    JpaTransactionManager manager;
    TransactionTemplate transactions;
    AccountDeletionRepository repository;
    AccountDeletionProperties properties;
    AccountDeletionSafetyGateway gateway;
    AccountDeletionService service;

    @BeforeAll void setupDatabase() throws Exception {
        String url = System.getenv("ACCOUNT_DELETION_TEST_JDBC_URL");
        Assumptions.assumeTrue("I_UNDERSTAND_LOCAL_SYNTHETIC_ONLY".equals(
            System.getenv("ACCOUNT_DELETION_TEST_OPT_IN")), "Isolated local PostgreSQL tests not enabled");
        if (url == null || !url.matches(
            "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/nukleus_deletion_test")) {
            throw new IllegalArgumentException("Only loopback nukleus_deletion_test with no URL options allowed");
        }
        rootDataSource = new DriverManagerDataSource(url,
            System.getenv().getOrDefault("ACCOUNT_DELETION_TEST_DB_USER", "deletion_test"),
            System.getenv().getOrDefault("ACCOUNT_DELETION_TEST_DB_PASSWORD", ""));
        schema = "deletion_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(rootDataSource).execute("CREATE SCHEMA " + schema);
        dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema,
            rootDataSource.getUsername(), rootDataSource.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.nukleus.vrmeeting.model", "com.nukleus.vrmeeting.accountdeletion");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
            "hibernate.hbm2ddl.auto", "update",
            "hibernate.default_schema", schema,
            "hibernate.hbm2ddl.schema_filter_provider", new AccountDeletionSchemaFilter(),
            "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        assertNull(jdbc.queryForObject("select to_regclass('account_deletion_requests')", String.class),
            "Hibernate must not create the deletion table");
        jdbc.update("insert into users(email) values ('migration-canary@example.invalid')");
        try (var stream = getClass().getResourceAsStream(
                "/db/account-deletion/V20261008_01__create_account_deletion_requests.sql");
             var c = dataSource.getConnection(); var statement = c.createStatement()) {
            assertNotNull(stream);
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from users where email='migration-canary@example.invalid'", Integer.class));
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        manager = new JpaTransactionManager(factory.getObject());
        manager.setDataSource(dataSource);
        transactions = new TransactionTemplate(manager);
        repository = new JpaRepositoryFactory(entityManager).getRepository(AccountDeletionRepository.class);
        properties = new AccountDeletionProperties();
        properties.setApiEnabled(true); properties.setSchedulerEnabled(true); properties.setDestructiveEnabled(true);
        gateway = mock(AccountDeletionSafetyGateway.class);
        service = serviceAt(CLOCK);
    }
    AccountDeletionService serviceAt(Clock clock) {
        return new AccountDeletionService(repository, entityManager, manager, properties, gateway, clock);
    }
    AccountDeletionWorkStore workAt(Clock clock) {
        return new AccountDeletionWorkStore(repository, entityManager, manager, properties, clock);
    }
    Clock dueClock() { return Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC); }
    long newUser() {
        return transactions.execute(tx -> {
            User u = new User(); u.setEmail(UUID.randomUUID() + "@example.invalid");
            u.setName("Synthetic User"); u.setAccountStatus("ACTIVE");
            entityManager.persist(u); entityManager.flush(); return u.getId();
        });
    }
    AccountDeletionRequest requestFor(long id) {
        return repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(id).orElseThrow();
    }
    @AfterAll void cleanupDatabase() {
        if (factory != null && factory.getObject() != null) factory.destroy();
        if (rootDataSource != null && schema != null && schema.matches("deletion_test_[0-9a-f]{32}")) {
            // Drops ONLY the random schema created by this test run, including synthetic tables.
            new JdbcTemplate(rootDataSource).execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    @Test void successfulRequestDeadlineDuplicatesAndCorrectIdentity() {
        long id = newUser(), other = newUser();
        var first = service.request(id);
        assertTrue(first.created());
        assertEquals(NOW.plusSeconds(604800), first.response().scheduledDeletionAt());
        var duplicate = serviceAt(Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC)).request(id);
        assertFalse(duplicate.created()); assertEquals(first.response(), duplicate.response());
        assertEquals("NONE", service.status(other).status());
        assertEquals("ACTIVE", jdbc.queryForObject("select account_status from users where id=?", String.class, id));
    }
    @Test void concurrentRequestsProduceExactlyOneActiveRequest() throws Exception {
        long id = newUser();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<AccountDeletionDtos.RequestResult>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(executor.submit(() -> { start.await(); return service.request(id); }));
            start.countDown();
            int created = 0;
            for (var future : results) if (future.get(20, TimeUnit.SECONDS).created()) created++;
            assertEquals(1, created);
        }
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from account_deletion_requests where user_id=?", Integer.class, id));
    }
    @Test void partialUniqueIndexAndForeignKeyAreRealPostgresConstraints() throws Exception {
        long id = newUser(); service.request(id);
        long requestId = requestFor(id).getId();
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
            insert into account_deletion_requests
            (user_id, requested_at, scheduled_deletion_at, status, created_at, updated_at)
            values (?, now(), now() + interval '7 days', 'PENDING', now(), now())
            """)) {
            s.setLong(1, id);
            assertEquals("23505", assertThrows(SQLException.class, s::executeUpdate).getSQLState());
            s.setLong(1, Long.MAX_VALUE);
            assertEquals("23503", assertThrows(SQLException.class, s::executeUpdate).getSQLState());
        }
        jdbc.update("delete from users where id=?", id);
        assertNull(jdbc.queryForObject("select user_id from account_deletion_requests where id=?",
            Long.class, requestId));
    }
    @Test void cancellationBeforeDeadlineIsIdempotentAndRemovesEligibility() {
        long id = newUser(); service.request(id);
        long requestId = requestFor(id).getId();
        assertEquals("CANCELLED", service.cancel(id).status());
        assertEquals("CANCELLED", service.cancel(id).status());
        assertFalse(repository.findDueIds(dueClock().instant(), 5, PageRequest.of(0, 100)).contains(requestId));
        assertTrue(workAt(dueClock()).claim(requestId).isEmpty());
    }
    @Test void cancellationAtOrAfterDeadlineIsRejected() {
        long id = newUser(); service.request(id);
        for (long seconds : List.of(604800L, 604801L)) {
            var at = serviceAt(Clock.fixed(NOW.plusSeconds(seconds), ZoneOffset.UTC));
            assertEquals("CANCELLATION_WINDOW_CLOSED",
                assertThrows(AccountDeletionException.class, () -> at.cancel(id)).getCode());
        }
    }
    @Test void cancelledRequestCanBeReplacedAndDeadlineUsesNewRequestTime() {
        long id = newUser(); service.request(id); service.cancel(id);
        var later = serviceAt(Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC)).request(id);
        assertTrue(later.created());
        assertEquals(NOW.plusSeconds(604900), later.response().scheduledDeletionAt());
    }
    @Test void dueSelectionRespectsDeadlineAndRetryWindow() {
        long id = newUser(); service.request(id);
        long requestId = requestFor(id).getId();
        assertFalse(repository.findDueIds(NOW, 5, PageRequest.of(0, 100)).contains(requestId));
        assertTrue(repository.findDueIds(dueClock().instant(), 5, PageRequest.of(0, 100)).contains(requestId));
        var work = workAt(dueClock()); var claim = work.claim(requestId).orElseThrow();
        work.fail(claim, "STORAGE_FAILURE", true);
        assertFalse(repository.findDueIds(dueClock().instant(), 5, PageRequest.of(0, 100)).contains(requestId));
        assertTrue(repository.findDueIds(dueClock().instant().plusSeconds(900), 5, PageRequest.of(0, 100))
            .contains(requestId));
    }
    @Test void retryRecoveryStopsAtConfiguredBound() {
        long id = newUser(); service.request(id); long requestId = requestFor(id).getId();
        for (int attempt = 1; attempt <= 5; attempt++) {
            var clock = Clock.fixed(dueClock().instant().plus(Duration.ofDays(attempt)), ZoneOffset.UTC);
            var work = workAt(clock);
            var claim = work.claim(requestId).orElseThrow();
            work.fail(claim, "STORAGE_FAILURE", true);
        }
        var r = requestFor(id);
        assertEquals(5, r.getRetryCount()); assertNull(r.getNextAttemptAt());
        assertTrue(workAt(Clock.fixed(NOW.plus(Duration.ofDays(100)), ZoneOffset.UTC)).claim(requestId).isEmpty());
        assertEquals("FAILED", service.status(id).status());
    }
    @Test void advisoryLockPreventsDuplicateWorkerAndReleases() throws Exception {
        var locks = new AccountDeletionWorkerLock(dataSource);
        try (var first = locks.tryAcquire(123456789).orElseThrow()) {
            first.assertLive(); assertTrue(locks.tryAcquire(123456789).isEmpty());
        }
        try (var next = locks.tryAcquire(123456789).orElseThrow()) { next.assertLive(); }
    }
    @Test void permanentDeletionIsAtomicAnonymizedAndIdempotent() {
        long id = newUser(), other = newUser();
        String email = jdbc.queryForObject("select email from users where id=?", String.class, id);
        jdbc.update("insert into email_otp(email, otp) values (?, 'synthetic')", email);
        jdbc.update("insert into email_otp(email, otp) values ('other@example.invalid', 'synthetic')");
        service.request(id); service.cancel(id); service.request(id);
        long requestId = requestFor(id).getId();
        var work = workAt(dueClock()); var claim = work.claim(requestId).orElseThrow();
        work.complete(claim);
        assertEquals(0, jdbc.queryForObject("select count(*) from users where id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("select count(*) from users where id=?", Integer.class, other));
        assertEquals(0, jdbc.queryForObject("select count(*) from email_otp where email=?", Integer.class, email));
        assertEquals(1, jdbc.queryForObject("select count(*) from email_otp where email='other@example.invalid'", Integer.class));
        var audit = repository.findById(requestId).orElseThrow();
        assertEquals(AccountDeletionRequest.Status.COMPLETED, audit.getStatus());
        assertNull(audit.getUserId()); assertNull(audit.getLastError());
        assertTrue(work.claim(requestId).isEmpty());
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from account_deletion_requests where user_id=?", Integer.class, id));
    }
    @Test void staleClaimCannotCompleteAfterCrashRecovery() {
        long id = newUser(); service.request(id); long requestId = requestFor(id).getId();
        var work = workAt(dueClock());
        var first = work.claim(requestId).orElseThrow();
        // Represents the next worker after the first advisory-lock session was lost.
        var second = work.claim(requestId).orElseThrow();
        assertThrows(AccountDeletionException.class, () -> work.complete(first));
        work.complete(second);
    }
    @Test void sharedMeetingsPreventFinalDeletion() {
        long id = newUser();
        String email = jdbc.queryForObject("select email from users where id=?", String.class, id);
        jdbc.update("""
            insert into meetings(meeting_id, room_code, host_email, status, participant_emails)
            values (?, '123456', 'other@example.invalid', 'ENDED', ?)
            """, UUID.randomUUID().toString(), " " + email.toUpperCase(Locale.ROOT) + " , other@example.invalid");
        service.request(id);
        var work = workAt(dueClock()); var claim = work.claim(requestFor(id).getId()).orElseThrow();
        assertEquals("MEETING_REVIEW_REQUIRED",
            assertThrows(AccountDeletionException.class, () -> work.validateCleanup(claim)).getCode());
        assertEquals("MEETING_REVIEW_REQUIRED",
            assertThrows(AccountDeletionException.class, () -> work.complete(claim)).getCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from users where id=?", Integer.class, id));
    }
    @Test void unexpectedForeignKeyRollsBackUserDeletionAndCompletion() {
        long id = newUser(); service.request(id);
        jdbc.execute("create table retention_guard (user_id bigint references users(id))");
        jdbc.update("insert into retention_guard(user_id) values (?)", id);
        var work = workAt(dueClock()); var claim = work.claim(requestFor(id).getId()).orElseThrow();
        assertThrows(RuntimeException.class, () -> work.complete(claim));
        assertEquals(1, jdbc.queryForObject("select count(*) from users where id=?", Integer.class, id));
        assertEquals(AccountDeletionRequest.Status.PROCESSING, requestFor(id).getStatus());
    }
}
