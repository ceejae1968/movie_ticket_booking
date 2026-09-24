package com.booking.movie.service;

import com.booking.movie.client.RazorpayClient;

import com.booking.movie.entity.*;
import com.booking.movie.enums.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.booking.movie.exception.SeatUnavailableException;
import com.booking.movie.pojos.*;
import com.booking.movie.repositories.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("postgres")
@SpringBootTest(properties = {"payment.outbox.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@Import(BookingConcurrencyTests.FixedTime.class)
@EnabledIfEnvironmentVariable(named = "BOOKING_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingConcurrencyTests {
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final String SCHEMA = "hold_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private BookingService service;
    @Autowired private UserRepository users;
    @Autowired private VenueDetailsRepository venues;
    @Autowired private ShowDetailsRepository shows;
    @Autowired private ShowScheduleRepository schedules;
    @Autowired private SeatDetailsRepository seats;
    @Autowired private BookingRepository bookings;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentTransactions paymentTransactions;
    @Autowired private PaymentJobProcessor processor;
    @Autowired private OutboxClaims claims;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentOutboxRepository outbox;
    @MockitoBean private RazorpayClient gateway;
    private Long userId;
    private Long scheduleId;
    private List<Long> seatIds;

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    private static String username() {
        return System.getenv().getOrDefault("BOOKING_TEST_DB_USER", "postgres");
    }
    private static String password() {
        return System.getenv().getOrDefault("BOOKING_TEST_DB_PASSWORD", "");
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("BOOKING_TEST_DB_URL");
        try (var connection = DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", BookingConcurrencyTests::username);
        properties.add("spring.datasource.password", BookingConcurrencyTests::password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.sql.init.mode", () -> "always");
        properties.add("spring.sql.init.schema-locations",
                () -> "classpath:db/migration/V1__booking_baseline.sql,"
                        + "classpath:db/migration/V2__booking_seat_entity.sql,"
                        + "classpath:db/migration/V3__payments_and_outbox.sql");
    }

    @AfterAll
    static void removeTestSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                System.getenv("BOOKING_TEST_DB_URL"), username(), password());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("delete from payment_outbox");
        UserEntity user = new UserEntity();
        user.setUserName(UUID.randomUUID().toString());
        userId = users.saveAndFlush(user).getId();
        VenueDetails venue = venues.saveAndFlush(new VenueDetails());
        ShowDetails show = new ShowDetails();
        show.setActive(true);
        show = shows.saveAndFlush(show);
        ShowSchedule schedule = new ShowSchedule();
        schedule.setShowId(show.getId());
        schedule.setVenueId(venue.getId());
        schedule.setStartsAt(NOW.plusSeconds(3600));
        schedule.setEndsAt(NOW.plusSeconds(7200));
        schedule.setActive(true);
        scheduleId = schedules.saveAndFlush(schedule).getId();
        seatIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SeatDetails seat = new SeatDetails();
            seat.setVenueId(venue.getId());
            seat.setEnabled(true);
            seat.setLabel("A" + i);
            seat.setCategory("REGULAR");
            seat.setPrice(new BigDecimal("100.00"));
            seatIds.add(seats.saveAndFlush(seat).getId());
        }
    }

    private CreateHoldRequest request(List<Long> ids) {
        return new CreateHoldRequest(scheduleId, ids, userId);
    }

    private List<Boolean> compete(List<Long> first, List<Long> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (List<Long> ids : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start barrier timed out");
                    }
                    try {
                        service.holdBooking(request(ids));
                        return true;
                    } catch (SeatUnavailableException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void savingBookingCascadesToSeatEntitiesWithIndependentIds() {
        BookingResponse response = service.holdBooking(request(seatIds));
        assertEquals(3L, jdbc.queryForObject(
                "select count(distinct id) from booking_seats where booking_id = ?",
                Long.class, response.id()).longValue());
        assertEquals(3L, jdbc.queryForObject(
                "select count(*) from booking_seats where booking_id = ? and created_at is not null",
                Long.class, response.id()).longValue());
    }

    @Test
    void sameSeatHasExactlyOneWinner() throws Exception {
        List<Boolean> result = compete(List.of(seatIds.get(0)), List.of(seatIds.get(0)));
        assertEquals(1L, result.stream().filter(Boolean::booleanValue).count());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from bookings where show_schedule_id = ?", Long.class, scheduleId).longValue());
    }

    @Test
    void overlappingRequestsNeverPartiallyAllocate() throws Exception {
        List<Boolean> result = compete(List.of(seatIds.get(1), seatIds.get(0)),
                List.of(seatIds.get(2), seatIds.get(1)));
        assertEquals(1L, result.stream().filter(Boolean::booleanValue).count());
        assertEquals(2L, jdbc.queryForObject("""
                select count(*) from booking_seats s join bookings b on b.id = s.booking_id
                where b.show_schedule_id = ?
                """, Long.class, scheduleId).longValue());
    }

    @Test
    void expiryAtExactBoundaryAllowsReacquisitionWithoutCleanup() {
        BookingResponse first = service.holdBooking(request(List.of(seatIds.get(0))));
        jdbc.update("update bookings set start_at = ?, expires_at = ? where id = ?",
                NOW.minusSeconds(300).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), first.id());
        BookingResponse second = service.holdBooking(request(List.of(seatIds.get(0))));
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void confirmedBookingBlocksEvenAfterOriginalHoldExpiry() {
        BookingResponse first = service.holdBooking(request(List.of(seatIds.get(0))));
        jdbc.update("update bookings set status = 'CONFIRMED', start_at = ?, expires_at = ? where id = ?",
                NOW.minusSeconds(300).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), first.id());
        assertThrows(SeatUnavailableException.class,
                () -> service.holdBooking(request(List.of(seatIds.get(0)))));
    }

    @Test
    void releasedCancelledAndExpiredBookingsDoNotBlock() {
        for (BookingStatus status : List.of(BookingStatus.RELEASED,
                BookingStatus.CANCELLED, BookingStatus.EXPIRED)) {
            BookingResponse first = service.holdBooking(request(List.of(seatIds.get(0))));
            jdbc.update("update bookings set status = ? where id = ?", status.name(), first.id());
        }
        assertNotNull(service.holdBooking(request(List.of(seatIds.get(0)))).id());
    }

    @Test
    void samePhysicalSeatCanBeHeldForAnotherScreening() {
        service.holdBooking(request(List.of(seatIds.get(0))));
        ShowSchedule original = schedules.findById(scheduleId).orElseThrow();
        ShowSchedule another = new ShowSchedule();
        another.setShowId(original.getShowId());
        another.setVenueId(original.getVenueId());
        another.setStartsAt(NOW.plusSeconds(10800));
        another.setEndsAt(NOW.plusSeconds(14400));
        another.setActive(true);
        Long secondId = schedules.saveAndFlush(another).getId();
        assertNotNull(service.holdBooking(new CreateHoldRequest(secondId,
                List.of(seatIds.get(0)), userId)).id());
    }
    private BookingResponse holdForPayment() {
        return service.holdBooking(request(List.of(seatIds.get(0))));
    }

    private InitiatePaymentRequest paymentRequest(BookingResponse booking) {
        return new InitiatePaymentRequest(booking.id(), booking.total(), PayMode.UPI);
    }

    private PreparedPayment prepare(BookingResponse booking) {
        return paymentTransactions.prepare(paymentRequest(booking), booking.id().toString(), "test-correlation");
    }

    private ProviderPaymentResponse outcome(BookingResponse booking, ProviderStatus status) {
        return ProviderPaymentResponse.builder()
                .bookingId(booking.id())
                .paymentId("pay-" + booking.id())
                .amount(booking.total())
                .currency(booking.currency())
                .status(status)
                .build();
    }

    private void expireBooking(Long bookingId) {
        jdbc.update("update bookings set start_at = ?, expires_at = ? where id = ?",
                NOW.minusSeconds(300).atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), bookingId);
    }

    private void makeDue(Long jobId) {
        jdbc.update("update payment_outbox set next_attempt_at = ? where id = ?",
                NOW.atOffset(ZoneOffset.UTC), jobId);
    }

    @Test
    void paymentIsIdempotentAndProviderRunsOutsideTransaction() {
        BookingResponse booking = holdForPayment();
        when(gateway.initiate(anyString(), anyString(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return outcome(booking, ProviderStatus.SUCCESS);
        });
        PaymentResponse first = paymentService.initiate(paymentRequest(booking), booking.id().toString(), "request-1");
        PaymentResponse replay = paymentService.initiate(paymentRequest(booking), booking.id().toString(), "request-2");
        assertEquals(first.id(), replay.id());
        assertEquals(PaymentStatus.SUCCEEDED, replay.status());
        assertEquals(BookingStatus.CONFIRMED, replay.bookingStatus());
        verify(gateway, times(1)).initiate(eq(booking.id().toString()), anyString(), any());
        assertEquals(1L, jdbc.queryForObject("select count(*) from payments where booking_id = ?",
                Long.class, booking.id()).longValue());
    }

    @Test
    void changedIdempotentRequestIsRejected() {
        BookingResponse booking = holdForPayment();
        prepare(booking);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> paymentTransactions.prepare(new InitiatePaymentRequest(booking.id(),
                        booking.total().add(BigDecimal.ONE), PayMode.UPI), booking.id().toString(), "test"));
        assertEquals(409, exception.getStatusCode().value());
        verifyNoInteractions(gateway);
    }

    @Test
    void concurrentPaymentRequestsCreateOnlyOneOperationAndClaim() throws Exception {
        BookingResponse booking = holdForPayment();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<PreparedPayment> task = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return prepare(booking);
            };
            Future<PreparedPayment> first = executor.submit(task);
            Future<PreparedPayment> second = executor.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<PreparedPayment> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(value -> value.claim() != null).count());
            assertEquals(1L, jdbc.queryForObject("select count(*) from payments where booking_id = ?",
                    Long.class, booking.id()).longValue());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutKeepsPaymentPendingAndWorkerReconcilesIt() {
        BookingResponse booking = holdForPayment();
        when(gateway.initiate(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("simulated transport timeout"));
        PaymentResponse response = paymentService.initiate(paymentRequest(booking), booking.id().toString(), "test");
        assertEquals(PaymentStatus.PENDING, response.status());
        PaymentOutbox job = outbox.findAll().getFirst();
        assertEquals(OutboxStatus.PENDING, job.getStatus());
        makeDue(job.getId());
        when(gateway.status(eq(booking.id()), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return outcome(booking, ProviderStatus.SUCCESS);
        });
        claims.claimBatch().forEach(claim -> processor.process(claim, false));
        assertEquals(BookingStatus.CONFIRMED, paymentService.status(booking.id()).bookingStatus());
        assertEquals(OutboxStatus.DONE, outbox.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    void definitiveFailureReleasesSeats() {
        BookingResponse booking = holdForPayment();
        when(gateway.initiate(anyString(), anyString(), any())).thenReturn(outcome(booking, ProviderStatus.FAILED));
        PaymentResponse result = paymentService.initiate(paymentRequest(booking), booking.id().toString(), "test");
        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals(BookingStatus.RELEASED, result.bookingStatus());
        assertNotEquals(booking.id(), holdForPayment().id());
    }

    @Test
    void lateSuccessRefundsWithoutTouchingNewSeatOwner() {
        BookingResponse booking = holdForPayment();
        PreparedPayment pending = prepare(booking);
        expireBooking(booking.id());
        BookingResponse newOwner = holdForPayment();
        when(gateway.status(eq(booking.id()), anyString())).thenReturn(outcome(booking, ProviderStatus.SUCCESS));
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return ProviderRefundResponse.builder()
                    .bookingId(booking.id())
                    .paymentId("pay-" + booking.id())
                    .refundId("refund-1")
                    .amount(booking.total())
                    .currency(booking.currency())
                    .status(ProviderStatus.SUCCESS)
                    .build();
        });
        processor.process(pending.claim(), false);
        PaymentResponse result = paymentService.status(booking.id());
        assertEquals(PaymentStatus.SUCCEEDED, result.status());
        assertEquals(BookingStatus.EXPIRED, result.bookingStatus());
        assertEquals(RefundStatus.SUCCEEDED, result.refund().status());
        assertEquals(BookingStatus.IN_PROGRESS, bookings.findById(newOwner.id()).orElseThrow().getStatus());
        assertThrows(ResponseStatusException.class, () -> paymentService.initiate(
                paymentRequest(booking), booking.id().toString(), "replay"));
    }

    @Test
    void unknownOutcomeAfterExpiryRemainsReconcilable() {
        BookingResponse booking = holdForPayment();
        PreparedPayment prepared = prepare(booking);
        expireBooking(booking.id());
        when(gateway.status(eq(booking.id()), anyString())).thenReturn(
                ProviderPaymentResponse.builder()
                        .bookingId(booking.id())
                        .paymentId(null)
                        .amount(null)
                        .currency(null)
                        .status(ProviderStatus.NOT_FOUND)
                        .build());
        processor.process(prepared.claim(), false);
        PaymentResponse result = paymentService.status(booking.id());
        assertEquals(PaymentStatus.PENDING, result.status());
        assertEquals(BookingStatus.EXPIRED, result.bookingStatus());
        assertEquals(OutboxStatus.PENDING, outbox.findById(prepared.claim().id()).orElseThrow().getStatus());
        verify(gateway, never()).initiate(anyString(), anyString(), any());
    }

    @Test
    void refundTimeoutIsRetriedWithSameKey() {
        BookingResponse booking = holdForPayment();
        PreparedPayment prepared = prepare(booking);
        expireBooking(booking.id());
        when(gateway.status(eq(booking.id()), anyString())).thenReturn(outcome(booking, ProviderStatus.SUCCESS));
        when(gateway.refund(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("simulated refund timeout"));
        processor.process(prepared.claim(), false);
        assertEquals(RefundStatus.PENDING, paymentService.status(booking.id()).refund().status());
        PaymentOutbox job = outbox.findAll().stream().filter(value -> value.getType() == OutboxType.REFUND)
                .findFirst().orElseThrow();
        makeDue(job.getId());
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenReturn(
                ProviderRefundResponse.builder()
                        .bookingId(booking.id())
                        .paymentId("pay-" + booking.id())
                        .refundId("refund-1")
                        .amount(booking.total())
                        .currency(booking.currency())
                        .status(ProviderStatus.SUCCESS)
                        .build());
        claims.claimBatch().forEach(claim -> processor.process(claim, false));
        assertEquals(RefundStatus.SUCCEEDED, paymentService.status(booking.id()).refund().status());
        verify(gateway, times(2)).refund(eq("pay-" + booking.id()), eq("refund-" + booking.id()), anyString(), any());
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldWorkerCannotSaveOutcome() {
        BookingResponse booking = holdForPayment();
        PreparedPayment prepared = prepare(booking);
        jdbc.update("update payment_outbox set lease_until = ? where id = ?",
                NOW.atOffset(ZoneOffset.UTC), prepared.claim().id());
        JobClaim newer = claims.claimBatch().getFirst();
        assertNotEquals(prepared.claim().token(), newer.token());
        paymentTransactions.applyPayment(prepared.claim(), outcome(booking, ProviderStatus.SUCCESS));
        assertEquals(PaymentStatus.PENDING, paymentService.status(booking.id()).status());
        paymentTransactions.applyPayment(newer, outcome(booking, ProviderStatus.SUCCESS));
        assertEquals(BookingStatus.CONFIRMED, paymentService.status(booking.id()).bookingStatus());
    }

    @Test
    void failedOutcomeTransactionRollsBackPaymentAndDoneState() {
        BookingResponse booking = holdForPayment();
        PreparedPayment prepared = prepare(booking);
        // Violate the real DB length constraint after updating both payment and booking in memory.
        ProviderPaymentResponse invalid = ProviderPaymentResponse.builder()
                .bookingId(booking.id())
                .paymentId("x".repeat(300))
                .amount(booking.total())
                .currency(booking.currency())
                .status(ProviderStatus.SUCCESS)
                .build();
        assertThrows(RuntimeException.class, () -> paymentTransactions.applyPayment(prepared.claim(), invalid));
        assertEquals(PaymentStatus.PENDING, paymentService.status(booking.id()).status());
        assertEquals(BookingStatus.IN_PROGRESS, bookings.findById(booking.id()).orElseThrow().getStatus());
        assertEquals(OutboxStatus.PROCESSING, outbox.findById(prepared.claim().id()).orElseThrow().getStatus());
    }

    @Test
    void workersSkipEachOthersLocksAndClaimAtMostTwentyJobs() throws Exception {
        ShowSchedule original = schedules.findById(scheduleId).orElseThrow();
        for (int i = 1; i <= 21; i++) {
            ShowSchedule schedule = new ShowSchedule();
            schedule.setShowId(original.getShowId());
            schedule.setVenueId(original.getVenueId());
            schedule.setStartsAt(NOW.plusSeconds(i * 86400L));
            schedule.setEndsAt(NOW.plusSeconds(i * 86400L + 7200));
            schedule.setActive(true);
            Long id = schedules.saveAndFlush(schedule).getId();
            BookingResponse booking = service.holdBooking(new CreateHoldRequest(id, List.of(seatIds.get(0)), userId));
            PreparedPayment prepared = prepare(booking);
            paymentTransactions.retryLater(prepared.claim());
            makeDue(prepared.claim().id());
        }
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<JobClaim>> first = executor.submit(() ->
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                        List<JobClaim> batch = claims.claimBatch();
                        locked.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim barrier timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return batch;
                    }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            List<JobClaim> second = claims.claimBatch();
            assertEquals(1, second.size());
            release.countDown();
            List<JobClaim> firstBatch = first.get(20, TimeUnit.SECONDS);
            assertEquals(20, firstBatch.size());
            assertTrue(firstBatch.stream().noneMatch(job -> job.id().equals(second.getFirst().id())));
            assertTrue(claims.claimBatch().isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

}
