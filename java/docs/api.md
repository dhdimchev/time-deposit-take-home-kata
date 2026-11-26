# Time Deposit API

The service exposes exactly two operations. Both are defined in the OpenAPI contract
`src/main/resources/static/openapi.yaml`, which is the source of truth: the server interface and models are generated
from it at build time.

| Operation | Method and path | Result |
|---|---|---|
| `getAllTimeDeposits` | `GET /time-deposits` | `200`: all time deposits with `id`, `planType`, `balance`, `days`, `withdrawals` |
| `updateAllBalances` | `POST /time-deposits/balance-updates` | `204`: applies one month of interest to every deposit, then ages every deposit by 30 days |

## Prerequisites
- JDK 17 or later
- Maven
- Docker (running)

## Start the service
From the `java/` directory:

```bash
mvn spring-boot:run
```

Spring Boot's Docker Compose support starts PostgreSQL from `compose.yaml`. Flyway then creates the tables and loads
the sample data. The service listens on `http://localhost:8080`.

## Use Swagger UI
1. Open <http://localhost:8080/swagger-ui/index.html>. It shows the hand-written contract (`/openapi.yaml`).
2. **List deposits:** expand `GET /time-deposits`, click **Try it out**, then **Execute**. You get the six sample
   deposits.
3. **Update balances:** expand `POST /time-deposits/balance-updates`, click **Try it out**, then **Execute**.
   The response is `204 No Content`.
4. Run `GET /time-deposits` again to see the new balances.

Each `POST` represents **one month passing**, so it is not idempotent:
1. interest is applied using each deposit's current `days`;
2. then every deposit's `days` increases by 30. A month counts as 30 days, following the standard 30/360
   day-count convention.

So the same deposit can start (or stop) earning interest on a later update as it ages.

### Sample data and expected results
| id | planType | days | balance | withdrawals | after 1st update (balance / days) | after 2nd update (balance / days) |
|---|---|---|---|---|---|---|
| 1 | basic | 45 | 1200.00 | none | 1201.00 / 75 | 1202.00 / 105 |
| 2 | student | 100 | 400.00 | 1 | 401.00 / 130 | 402.00 / 160 |
| 3 | premium | 60 | 240.00 | 2 | 241.00 / 90 | 242.00 / 120 |
| 4 | basic | 20 | 1000.00 | none | 1000.00 / 50 (first 30 days: no interest) | 1000.83 / 80 |
| 5 | student | 400 | 400.00 | none | 400.00 / 430 (student interest stops after one year) | 400.00 / 460 |
| 6 | premium | 40 | 240.00 | none | 240.00 / 70 (premium interest starts after 45 days) | 241.00 / 100 |

Withdrawals are history only: they are returned with each deposit but do not affect interest.

## Using curl instead

```bash
curl -s http://localhost:8080/time-deposits
curl -s -X POST -o /dev/null -w '%{http_code}\n' http://localhost:8080/time-deposits/balance-updates
```

## Resetting the data
Stop the application and remove the database container (`docker compose down` in `java/`). The next start recreates
the tables and the sample data.
