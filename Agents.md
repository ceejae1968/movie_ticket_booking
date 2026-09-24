# Project context

This project is a Movie Ticket Booking System assignment implemented in Java
and Spring Boot.The application serves multiple cities, theaters, screens, and shows, with
seat-level booking. Customers browse shows, hold seats, pay, cancel bookings,
and view booking history. Administrators manage the catalog, seat layouts,
pricing, discounts, and refund policies.

## Required scope

- REST APIs with database persistence.
- Basic admin/customer role-based access control and ownership checks.
- Time-bound seat holds that expire automatically.
- Correct serialization of competing seat requests without double allocation.
- Regular/premium seat pricing, weekend pricing, and discount codes.
- Payment, booking confirmation, cancellation, and configurable refunds.
- Non Blocking confirmation and reminder notifications.
- Input validation, consistent error handling, and core unit/integration tests.
- README documentation of meaningful assumptions, tradeoffs, and run instructions.

Do not spend assignment time on frontend work, deployment, containerization,
CI/CD, microservices, advanced authentication, or production observability.

## Working design assumptions

These are initial defaults from the scoping discussion, not additional assignment
requirements. Update them and the README when an implementation decision changes.

- Build a modular monolith. H2 is the default disposable local database; use
  the explicit postgres profile and PostgreSQL for concurrency integration tests.
- A theater contains screens; a screen contains physical seats; a show runs on
  one screen. For the current hold endpoint, derive occupancy from bookings
  and lock physical seat rows; a ShowSchedule identifies each screening.
- A booking covers one show and at most eight seats. Acquisition is all-or-nothing.
- Represent a hold as a booking in `IN_PROGRESS` state, with a default five-minute expiry.
- Do not extend holds during payment. A hold is expired at `now >= expiresAt`.
- Model regular/premium as seat categories and weekend pricing as a modifier.
  Determine weekends using the theater's timezone.
- Support one currency and one fixed-amount or percentage discount per booking.
  Freeze the price breakdown when the hold is created.
- Use a simulated payment provider supporting success, failure, and delayed
  outcomes. Keep provider behavior behind an interface.
- Support whole-booking cancellation before show start. Snapshot the applicable
  refund policy and calculate refunds from the amount actually paid.
- Use persisted database jobs and an in-process worker for notifications and
  refund retries. A simulated notification sender is sufficient.
- Basic Spring Security authentication with seeded users is sufficient initially.
- Defer partial cancellations, real payment integration, discount usage quotas,
  waitlists, loyalty features, and bulk show cancellation.

## Basic implementation rules

### Structure and maintainability

- Inspect existing code and build configuration before choosing versions or
  adding dependencies. Follow established project conventions.
- Organize by business capability with clear boundaries between API, application
  logic, domain rules, and persistence. Avoid speculative abstractions.
- Keep controllers thin. Put business rules and transaction boundaries in
  application/domain services. Use request/response DTOs rather than exposing
  persistence entities directly.
- Use database migrations for schema changes and database constraints for
  invariants that can be enforced there.
- Prefer small, reviewable changes. Do not introduce unrelated refactors.
- Never commit secrets or log credentials or sensitive payment information.

### Dependency injection style

- Use field injection with `@Autowired` for Spring-managed dependencies.
- Do not use constructor injection or Lombok `@RequiredArgsConstructor`
  for dependency injection.

### Request correlation and logging

- Assign a server-generated UUID correlationId to every incoming HTTP
  request, including requests that fail validation or return errors.
- Preserve the same correlationId throughout the request and redispatches.
- Return it in the `X-Correlation-ID` response header and error bodies.
- Store it in SLF4J MDC under `correlationId` so request logs include it.
- Remove the MDC entry after completion, failure, or asynchronous handoff
  to prevent leakage between requests on reused threads.
- When adding asynchronous request processing, explicitly propagate the
  correlationId and clean up the worker thread's MDC afterward.
- Test correlation ID generation, response consistency, and MDC cleanup.

### Booking and concurrency

- The database is the source of truth for seat ownership. Do not rely on JVM
  locks or a read-then-write availability check to prevent double booking.
- Acquire seat row locks in deterministic order. Define and use one consistent
  lock order across all booking mutation paths.
- Check hold timestamps inside transactions. Correctness must not depend on
  the expiry worker running on time.
- Release seats only when they still belong to the booking being released.
  Stale cleanup must not release a newer customer's allocation.
- Revalidate hold validity and seat ownership before confirming a booking.
- Never hold a database transaction open across a payment or notification call.
- A successful payment received after hold expiry must trigger a compensating
  refund rather than confirm an invalid booking.
- Make payment and cancellation operations idempotent. Reject reuse of an
  idempotency key with a different request. Handle duplicate payment outcomes.
- Keep booking, payment, and refund statuses separate and enforce explicit
  state transitions. Resolve a pending payment before starting another attempt.

### Money, time, and background work

- Use `BigDecimal` or integer minor units for money, with explicit rounding.
  Never use floating-point arithmetic for prices or refunds.
- Inject a `Clock`; use instants for persisted deadlines and explicit timezones
  for local show dates. Define boundary behavior precisely.
- Persist notification jobs atomically with the business transaction that
  creates them. Notification failures must not roll back confirmed bookings.
- Design background jobs for retries and duplicate execution. Document
  at-least-once delivery instead of claiming exactly-once external delivery.
- Recheck booking state before sending reminders. Keep failed refunds retryable
  without restoring cancelled bookings or their seat ownership.

### APIs and security

- When introducing a new exception, ensure `GlobalExceptionHandler`
  handles it with the appropriate HTTP status and a safe message.
  Add or update a handler if existing mappings do not cover it.
  Preserve the standard error response: status, message, timestamp,
  and correlationId.
- Validate inputs and return consistent structured errors with appropriate HTTP
  status codes. Do not expose stack traces to API clients.
- Enforce both role permissions and resource ownership on the server.
- Paginate collection endpoints and apply sensible request size limits.
- Prevent overlapping shows on a screen and changes to layouts already used by
  published shows. Preserve historical pricing and refund information.

## Verification and documentation

- Unit-test pricing, discounts, refund boundaries, and state transitions.
- Test locking and concurrency against PostgreSQL; an in-memory substitute is
  not sufficient evidence for database concurrency behavior.
- Cover competing holds, atomic multi-seat acquisition, expiry before cleanup,
  stale cleanup after reassignment, payment/expiry races, duplicate requests,
  refund retries, notification failures, and unauthorized booking access.
- Use a controlled clock and barriers/latches instead of timing-based sleeps.
- Run relevant tests after changes and report what passed, failed, or could not
  be run. Do not claim unexecuted tests passed.
- Keep the README current with setup, seed credentials, API examples, assumptions,
  state transitions, concurrency strategy, and known limitations.
- Prioritize a complete tested booking lifecycle over additional CRUD breadth.
