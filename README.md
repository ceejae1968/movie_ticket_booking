# movie_ticket_booking

## Multi-seat booking holds

`POST /api/v1/bookings/hold` returns HTTP 201 with one booking for 1–8 seats.

```json
{"showScheduleId":101,"seatIds":[21,22],"userId":7}
```

Supply existing database IDs. No authentication or role header is required in
this temporary assignment setup; userId is caller-supplied and checked for existence.
The response includes id, showScheduleId, userId, status, heldAt, expiresAt,
currency, total, and seat snapshots. `IN_PROGRESS` is the existing enum's name
for an active hold. Errors include status, message, timestamp, and correlationId.
The interceptor also returns X-Correlation-ID and populates request logging MDC.

### Availability and locking

The service uses a READ_COMMITTED transaction and locks physical SeatDetails rows
with PESSIMISTIC_WRITE in one query ordered by ascending ID. All requested IDs
must be returned before processing continues. It then checks booking_seats joined
to bookings for the same schedule. A seat is unavailable when its booking is
CONFIRMED, or IN_PROGRESS with expiresAt strictly greater than the current time.
Cancelled, released, expired, and time-expired holds do not block new bookings.

All requested seats succeed or the entire transaction rolls back. The clock is
read after acquiring all locks. Default expiry is five minutes, capped at show
start. Expired holds are immediately reclaimable without a cleanup job; old rows
can still have IN_PROGRESS stored until a future cleanup implementation.

Physical seat locking briefly serializes requests for the same seat across
screenings; conflict checks are scoped to the schedule. Every future payment,
cancellation, or allocation writer MUST follow the same seat-lock order and
recheck status/expiry inside its transaction. Never lock only matching booking
rows: a first hold has no existing booking row to lock. Locks are released at
commit; no transaction remains open while a customer pays.

### Scope and assumptions

- ShowDetails is catalog metadata; ShowSchedule links a show to a venue and times.
- A venue is the seating room for this slice. Schedules and seat layouts are
  preconfigured and immutable during booking. Schedule management, overlapping-show
  prevention, and a separate theater/screen hierarchy are future work.
- Disabled seats are blocked. Seats must belong to the scheduled venue.
- Existing seat prices are snapshotted when holding. Currency defaults to INR,
  configurable through booking.currency. Weekend modifiers and discounts are
  not wired into this endpoint yet.
- Payment initiation/reconciliation and compensating refunds are implemented below.
  Customer cancellation, reminders, and general expiry-status cleanup remain future work.
- No separate schedule-seat inventory table is used.

### Default local database: H2

The default profile is h2. Run `mvn spring-boot:run` without database environment
variables to use jdbc:h2:mem:movie_booking;DB_CLOSE_DELAY=-1 (username sa, blank
password). Hibernate creates tables from entity mappings with create-drop.
This is an empty, disposable in-memory database: data is lost on shutdown and
must be reseeded on restart. PostgreSQL migrations do not run under this profile.
The H2 console has not been enabled by this change.

H2 supports FOR UPDATE SKIP LOCKED in its current SQL grammar:
https://h2database.github.io/html/commands.html#select
The default startup smoke test also executes the outbox claim query. This is not
a substitute for PostgreSQL concurrency tests or migration/schema verification;
Hibernate-generated H2 tables do not include all constraints/indexes from the SQL migrations.

### PostgreSQL database setup

Use Java 25, Maven, and PostgreSQL. Dependency versions remain as configured in
pom.xml. Configure SPRING_DATASOURCE_URL (jdbc:postgresql://...),
SPRING_DATASOURCE_USERNAME, and SPRING_DATASOURCE_PASSWORD externally.

For a fresh schema, apply these scripts in order using psql or a database client:
1. src/main/resources/db/migration/V1__booking_baseline.sql
2. src/main/resources/db/migration/V2__booking_seat_entity.sql
3. src/main/resources/db/migration/V3__payments_and_outbox.sql

Apply only migrations not yet applied: after V1 apply V2 and V3; after V2 apply V3. V2 preserves existing seat snapshots,
adds generated seat-record IDs and audit timestamps, and retains uniqueness of
(booking_id, seat_id). Review/back up existing data before applying migrations.
The application uses Hibernate validate and does not auto-apply migrations.
No database schema or data was changed during this code edit. An existing database
requires a separately reviewed data migration: legacy single-seat bookings cannot
be safely backfilled to screening IDs without a mapping.

Create users, venue, show_details, seat_details, and show_schedule records before
calling the endpoint. No admin creation endpoints or seed accounts were added.

Run PostgreSQL with `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`
(or SPRING_PROFILES_ACTIVE=postgres). The checked-in mvnw script is missing
.mvn/wrapper/maven-wrapper.properties; use installed Maven until it is restored.

### Tests

```bash
mvn -Dtest=BookingServiceTests,BookingControllerTests,RequestInterceptorTests test
```

PostgreSQL integration tests:

```bash
export BOOKING_TEST_DB_URL=jdbc:postgresql://localhost:5432/movie_test
export BOOKING_TEST_DB_USER=postgres
# Set BOOKING_TEST_DB_PASSWORD locally if required; do not commit it.
mvn -Dtest=BookingConcurrencyTests test
```

Integration tests are skipped unless BOOKING_TEST_DB_URL is set. They create a
uniquely named schema, load the baseline, and drop only that schema afterward.
Use a dedicated test database with schema creation permission. They cover competing
holds, overlapping multi-seat requests, exact expiry, confirmed bookings after hold
expiry, terminal statuses, and different screenings.

Tests could not be run in the editing environment: Java, Maven, PostgreSQL CLI,
and Maven wrapper configuration were unavailable. Source/XML checks are not proof
of database concurrency correctness; run the PostgreSQL tests before relying on it.

### BookingSeat entity mapping

BookingSeat is an independent JPA entity extending BaseEntity, with its own id,
created_at, and updated_at. Booking has a cascading OneToMany relationship;
BookingSeat owns the booking_id foreign key through a lazy ManyToOne relationship.
Use Booking.addSeat to maintain both sides. Saving the parent cascades persistence
to its children; the booking response and availability query remain unchanged.
Relationship fields are excluded from Lombok toString/equals/hashCode to avoid
recursive traversal. No new seat-level cancellation or API was introduced.

## UPI payment and reconciliation

### REST API

```http
POST /api/v1/payments
Idempotency-Key: 501
Content-Type: application/json

{"bookingId":501,"amount":500.00,"payMode":"UPI"}
```

The key must be the canonical decimal booking ID. Amount is in major currency
units (e.g. rupees), positive, at most two fractional decimal places, and must
match the persisted total. Only UPI is supported. One payment operation per
booking is enforced by a unique constraint and booking-row locking. Same input
returns that operation; changed input returns 409. A definitively failed payment
cannot be restarted under this key: create a new seat hold instead.

HTTP 202 means the payment outcome is pending; HTTP 200 returns a known payment
outcome. A successful charge that cannot confirm the booking returns HTTP 409
with the standard error body AFTER committing a full-refund job. That refund may
already have completed by the time the response is returned.

```http
GET /api/v1/payments/bookings/501
```

This reads local persisted state and returns id, bookingId, status, bookingStatus,
amount, currency, payMode, and refund (id/status/amount/currency, or null).
It does not call the provider or require an idempotency header. An expired hold
is shown as EXPIRED even if cleanup has not updated the stored booking yet.
No customer authentication/ownership enforcement is provided in this temporary setup.

### Assumed provider API (fictional, not real Razorpay)

Set PAYMENT_PROVIDER_URL to a local/provider test stub; default is
http://localhost:8089. No real Razorpay credentials or production integration
are configured. These endpoints are deliberately assumed for the assignment:

- POST /payments: Idempotency-Key is bookingId. Body includes bookingId, amount,
  currency, payMode="UPI", and validUntil (ISO-8601 hold deadline).
- GET /payments/by-booking/{bookingId}: lookup by our booking reference.
- POST /payments/{paymentId}/refunds: key is refund-{bookingId}. Body contains
  bookingId, amount, and currency. Replays return the same refund operation.

Payment response: bookingId, paymentId, amount, currency, status.
Refund response: bookingId, paymentId, refundId, amount, currency, status.
Statuses: SUCCESS, FAILED, PENDING; payment lookup may also return NOT_FOUND.
NOT_FOUND is an HTTP 200 body with the bookingId, not an HTTP 404. Other fields
may be null for NOT_FOUND. Pending/failed payment responses still echo amount
and currency; successful responses require their provider IDs.

Example success:

```json
{"bookingId":501,"paymentId":"pay-501","amount":500.00,"currency":"INR","status":"SUCCESS"}
```

Provider assumptions: payment/refund idempotency is durable, definitive FAILED
payments will not later become successful, lookups eventually reflect accepted
charges, and a new charge cannot be initiated after validUntil. An already
accepted payment may finish later. Refund retries reuse one logical refund;
a provider that permanently refuses it requires remediation rather than issuing
new refund keys. Response references, amount, and currency are validated before
an outcome is applied. HTTP errors/timeouts are uncertain, never inferred failures.
No UPI app approval/QR flow is modeled; the stub simulates the full outcome.

### Transaction boundaries and recovery

1. Lock booking; persist PENDING payment and a reconciliation job in one transaction.
   The request claims that job with a token and lease, then commits.
2. Call provider through OpenFeign OUTSIDE a transaction. Automatic Feign retries
   are disabled; the outbox owns retry behavior.
3. Apply the outcome in a new transaction: lock physical seats in ascending ID
   order, then booking, payment, and outbox. Verify token and lease before changes.
   Confirm only an unexpired IN_PROGRESS booking with no competing allocation.
4. A successful late/invalid-hold payment creates one full refund and refund job
   atomically with the payment update. Immediately attempt the refund after commit;
   the scheduler recovers crashes and retries failures.

The worker runs every five seconds. One short transaction selects up to 20 jobs
using SELECT ... FOR UPDATE SKIP LOCKED ordered by next_attempt_at and id, marks
PROCESSING, assigns fresh claim tokens and ten-minute leases, and commits. No
offset pagination is used. Jobs are then processed serially without DB locks held
during network calls. Connect/read timeouts are 2/5 seconds. If these limits or
batch size change, reassess the lease duration. A slow/stale worker cannot save
an outcome after lease expiry or after another worker changes its token.

Each outcome transaction commits state and DONE together. If it fails, those
updates roll back. A separate short transaction requeues the job with bounded
exponential backoff (10–300 seconds); if that also fails, the lease permits recovery.
A claim committed before the provider call cannot itself be rolled back by a later
transaction. Provider idempotency protects replay after a crash between a charge
and our commit. Worker MDC restores the originating correlationId and clears or
restores the previous thread context afterward.

The hold deadline limits CONFIRMATION, not financial reconciliation. Once it
expires, the booking becomes EXPIRED and seats are reclaimable, but unknown
payments remain PENDING and are polled for late charges requiring refunds.
Unknown payment and failed-refund jobs retry indefinitely with bounded backoff;
no false financial failure is asserted merely due to elapsed time. Provider
outages may therefore leave visible pending/failed refund state requiring attention.

### Payment tests

```bash
mvn -Dtest=PaymentControllerTests,PaymentJobProcessorTests test
# With BOOKING_TEST_DB_URL / USER / PASSWORD as described above:
mvn -Dtest=BookingConcurrencyTests test
```

The PostgreSQL suite includes concurrent initiation/idempotency, timeout recovery,
late success after seat reassignment, refund retry, stale-lease fencing, rollback,
and simultaneous workers proving SKIP LOCKED and the 20-row batch bound.
It applies V1–V3 only inside a uniquely generated test schema. Scheduling is disabled
in the suite; the provider is mocked, and mocks assert calls occur outside transactions.
A real provider stub is needed to exercise Feign HTTP serialization end-to-end.

Spring Cloud 2025.1.3 is managed through its BOM. Spring's compatibility table
lists Boot 4.1 support starting at Cloud 2025.1.2:
https://spring.io/projects/spring-cloud/
OpenFeign retry configuration reference:
https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html

## Payment Strategy and Builder patterns

`service.handler.PaymentJobHandler` is the strategy contract:
- supports() declares PAYMENT or REFUND.
- process(claim, initiate) processes an already-claimed job and optionally returns
  a committed follow-up job ID (for an immediate refund); null means no follow-up.
  The initiate flag preserves the initial request's direct payment call instead
  of adding an unnecessary provider lookup. Refund handling ignores that flag.

PaymentReconciliationHandler owns initiation/status lookup and payment response
validation. RefundHandler owns refund calls and refund response validation.
PaymentJobProcessor builds its handler registry at startup and rejects duplicate
or missing handlers. It owns correlation context, retry scheduling, and dispatch
of committed follow-up jobs, with no payment/refund conditional branch.

`client.PaymentGateway` separates these workflows from Feign. Its implementation,
RazorpayPaymentGateway, delegates to RazorpayClient and supplies the stable payment/
refund idempotency keys and MDC correlation header. Transaction, lease, and seat-lock
rules remain in the existing services. No external call spans a transaction.

Lombok @Builder is used for JobClaim, PaymentWork, ProviderPaymentRequest,
ProviderPaymentResponse, ProviderRefundRequest, ProviderRefundResponse,
PaymentResponse, and PaymentRefundResponse. Creation sites in production and tests
use named fields. Canonical record constructors and JSON shapes remain available;
builders do not replace business validation. Small request/context records keep
constructors. JPA entities keep their current lifecycle methods and constructors,
and Spring dependencies still use @Autowired field injection.

Focused tests:

```bash
mvn -Dtest=PaymentJobProcessorTests,RazorpayPaymentGatewayTests,PaymentControllerTests test
```

The existing PostgreSQL suite still tests payment/hold concurrency through the
real strategies and gateway adapter with the Feign client mocked. Tests remain
unexecuted in this editing environment because Java/Maven and the wrapper
configuration are unavailable.

Default H2 startup check: `mvn -Dtest=MovieApplicationTests test`.
The PostgreSQL integration suite explicitly activates postgres, overrides its
connection with BOOKING_TEST_DB_* settings, and retains ddl-auto=validate.
Do not combine the h2 and postgres profiles. Database startup/tests could not be
executed in the editing environment because Java/Maven and wrapper configuration
are unavailable.
