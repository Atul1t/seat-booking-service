# Seat Booking Service

[![CI](https://github.com/Atul1t/seat-booking-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Atul1t/seat-booking-service/actions/workflows/ci.yml)

A seat reservation API built around a single guarantee: **the same seat is never sold twice**, no matter how many people click "Book" at the same moment.

Spring Boot 3 · Java 21 · Spring Security (JWT) · PostgreSQL · Docker · GitHub Actions

---

## The problem

The obvious implementation is wrong:

```java
// Broken. Do not ship this.
Seat seat = seatRepository.findById(seatId);
if (seat.getStatus() == AVAILABLE) {   // <-- two threads can both be here
    seat.setStatus(HELD);
    seatRepository.save(seat);
}
```

Two requests can both read `AVAILABLE` before either one writes. Both pass the check, both write, and the seat is sold twice. This is a check-then-act race, and it cannot be fixed with more application-level checking — the bug lives in the gap between the read and the write.

It also passes every single-threaded test you write, which is why this repository's headline test is a concurrent one.

## The fix

Take a row-level write lock on the seats **before** checking availability, and let the database serialise the contending transactions:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Seat s where s.id in :seatIds order by s.id")
List<Seat> lockSeatsForUpdate(@Param("seatIds") Collection<Long> seatIds);
```

In PostgreSQL this becomes `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`. The second transaction blocks on the lock, and when it resumes it re-reads the seat as `HELD` — so its availability check now fails correctly.

**The `ORDER BY id` is not cosmetic.** If one transaction locked seat 5 then seat 9 while another locked 9 then 5, they would deadlock. Locking every set in ascending id order means two overlapping requests always contend on their lowest shared seat first, so one waits instead of deadlocking. `SeatBookingService.hold()` sorts the requested ids for the same reason. There is a test for this.

The locked query deliberately does *not* join to `shows` to filter by show; that would make Postgres lock the show row too, serialising every booking for the show instead of only those competing for the same seats. Ownership is checked in Java afterwards.

## Design

```
POST /api/shows/{id}/holds          hold seats for 2 minutes
        |
        |  seats: AVAILABLE -> HELD
        v
POST /api/holds/{id}/confirm        turn the hold into a booking
        |
        |  seats: HELD -> BOOKED
        v
   booking reference

  DELETE /api/holds/{id}            release early     -> AVAILABLE
  HoldExpirySweeper (every 10s)     reclaim abandoned -> AVAILABLE
```

**Why a two-step hold instead of booking directly?** A customer picking seats should not lose them while entering card details, but an abandoned checkout must not take seats out of circulation forever. The hold window plus the sweeper is how real ticketing systems square that circle.

**Why both a sweeper and lazy cleanup?** The sweeper is the tidy-up: it marks lapsed holds `EXPIRED` and returns their seats. But availability must not depend on a background job staying alive, so `hold()` also reclaims any seat whose hold has already lapsed, and `confirm()` releases the seats of a hold it finds expired. The lazy paths touch only seat rows, never the hold row, which keeps the global lock order (hold before seats, seats ascending by id) intact.

**Why `Clock` is injected.** Testing a two-minute expiry window by sleeping for two minutes makes a test suite unusable. `MutableClock` in the tests moves time forward instead.

## Running it

With Docker (Postgres included):

```bash
docker compose up --build
```

Then open <http://localhost:8080>. A browser client ships with the service — plain
JavaScript, no build step — for browsing shows, picking seats off the map, and watching the
hold countdown run down during checkout.

Locally against your own Postgres:

```bash
docker compose up -d --wait db
mvn spring-boot:run
```

`--wait` blocks until the database reports healthy. Without it, Compose returns as soon as
the container process starts, and the app races ahead of Postgres creating its role — which
surfaces as `FATAL: role "seatbooking" does not exist`.

Tests (**Docker must be running** — see below):

```bash
mvn clean verify
```

## Try it

```bash
# 1. Register, then log in for a token
curl -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"hunter2hunter2","displayName":"You"}'

TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"hunter2hunter2"}' \
  | sed 's/.*"token":"\([^"]*\)".*/\1/')

# 2. Create a show with 3 rows of 4 seats
curl -X POST localhost:8080/api/shows \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Interstellar","startsAt":"2026-12-01T18:30:00Z","rows":3,"seatsPerRow":4}'

# 3. See the seat map — browsing is public, no token needed
curl localhost:8080/api/shows/1/seats

# 4. Hold two seats. Who you are comes from the token, not the body.
curl -X POST localhost:8080/api/shows/1/holds \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"seatIds":[1,2]}'

# 5. Confirm, within two minutes
curl -X POST localhost:8080/api/holds/1/confirm \
  -H "Authorization: Bearer $TOKEN"
```

## API

| Method | Path | Token | Returns | Notes |
|---|---|---|---|---|
| `POST` | `/api/auth/register` | — | 201 | Email, password (8+ chars), display name |
| `POST` | `/api/auth/login` | — | 200 | Returns a JWT, valid for two hours |
| `GET` | `/api/auth/me` | required | 200 | Who the token says you are |
| `GET` | `/api/shows` | — | 200 | Catalogue with live availability counts |
| `POST` | `/api/shows` | required | 201 | Creates a show and lays out its seat grid |
| `GET` | `/api/shows/{id}/seats` | — | 200 | Seat map with availability |
| `POST` | `/api/shows/{id}/holds` | required | 201 | **409** if any seat is already taken |
| `GET` | `/api/holds/{id}` | required | 200 | **403** if the hold is someone else's |
| `POST` | `/api/holds/{id}/confirm` | required | 200 | **410** if the hold expired first |
| `DELETE` | `/api/holds/{id}` | required | 204 | Releases the seats |
| `GET` | `/api/bookings` | required | 200 | Your bookings, newest first |
| `GET` | `/api/bookings/{reference}` | required | 200 | **403** if the booking is someone else's |

Browsing is public; anything that touches a seat or a booking needs a bearer token. Identity
is always read from the token, never from the request body — a client that could name its own
customer could name somebody else's.

A Postman collection lives in `postman/`. Import it, start the app, and run the requests
in order — ids are captured into collection variables automatically, so nothing needs
copying by hand.

409 and 410 are deliberately different. 409 means someone else got there first — pick another seat. 410 means your own hold timed out — start again. A client can act on that distinction; it could not if both were 400.

## Tests

| Test | What it pins down |
|---|---|
| `ConcurrentHoldTest` | 50 threads race for one seat; **exactly one wins** (real PostgreSQL) |
| `ConcurrentHoldTest` | 50 threads request the same two seats in opposite orders; no deadlock |
| `ConcurrentHoldTest` | 20 simultaneous confirmations of one hold produce one booking |
| `HoldExpiryTest` | Abandoned holds are swept; confirmed bookings are not |
| `SeatBookingServiceTest` | Hold / confirm / release rules, atomicity of partial failures |
| `SeatBookingApiTest` | HTTP contract and status codes |

Most tests use in-memory H2 for speed. `ConcurrentHoldTest` runs against a real PostgreSQL
started by Testcontainers, because row locking is the database's behaviour rather than the
application's — proving it on a different engine than production proves nothing. That is why
Docker has to be running.

**To watch the guarantee fail:** delete the `@Lock(LockModeType.PESSIMISTIC_WRITE)` line from `SeatRepository.lockSeatsForUpdate` and run `ConcurrentHoldTest` again. More than one thread will win. That one-line experiment is the most useful thing in this repository — it is the difference between knowing the pattern and having seen it break.

## Three-day build plan

**Day 1 — domain and layout.** `Show`, `Seat`, `SeatHold`, `Booking`; Postgres via docker-compose; create-show and seat-map endpoints; `SeatBookingServiceTest` for the sequential rules.

**Day 2 — the hard part.** Hold, confirm and release inside transactions; the pessimistic lock; the sorted lock order; `ConcurrentHoldTest`. Budget the most time here — this is the part worth talking about.

**Day 3 — make it real.** Expiry sweeper and `HoldExpiryTest`; error handling and status codes; `SeatBookingApiTest`; Dockerfile; CI; this README.

If you fall behind, cut in this order: CI, then the API tests, then the expiry sweeper. Keep the concurrency test — it is the project.

## Roadmap

One new thing per iteration, rather than all at once:

- **Optimistic locking** as an alternative strategy (`@Version`), and a benchmark of the two under contention
- **Redis** caching for the seat map, which is read far more often than it is written
- **Outbox pattern** for booking-confirmation events, then Kafka to carry them
- **Split into services**: inventory and payments, with the hold as the contract between them
- **Deployment** to a free tier, with health checks and structured logging
- **Load testing** with k6 or Gatling to find where lock contention actually starts to hurt

## Notes

Schema is generated by Hibernate (`ddl-auto: update`) to keep the first version small. Flyway migrations are the right next step before this goes anywhere real.
