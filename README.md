# Banking application

A small banking system made of four services. Each one is built from its own Gradle module and runs
in its own container.

| Service            | Port | Owns                                              | Database         |
|--------------------|------|---------------------------------------------------|------------------|
| `gateway`          | 8080 | The web UI, browser sessions and CSRF protection  | none             |
| `customer-service` | 8081 | Customer records                                  | `bank_customers` |
| `account-service`  | 8082 | Accounts, balances and the ledger                 | `bank_accounts`  |
| `auth-service`     | 8083 | Sign-in accounts and passwords                    | `bank_auth`      |

`common` is a shared library, not a service.

## Running it

```
docker compose up -d --build
```

Then open http://localhost:8080. To create the first staff login, set `BANK_ADMIN_EMAIL` and
`BANK_ADMIN_PASSWORD` (12 characters or more) first.

To run a service from the IDE or Gradle instead, start the database with `docker compose up -d db-init`,
then `.\gradlew.bat :customer-service:bootRun`, and likewise for the others.

Moving a stack that was running the old single application? See `scripts/migrate-monolith-data.sh`.

## How a request travels

The browser only ever talks to the gateway, which is the only service with a published port.

1. The gateway checks the session and CSRF token. Passwords are checked by `auth-service`, but the
   session is held by the gateway.
2. It forwards `/api/**` to the service that owns the data, with a 60-second signed token saying who
   is calling. Cookies and caller-supplied `Authorization` headers are not forwarded.
3. That service decides what the caller may do, so the access rules sit next to the data.

Services call each other on `/internal/**`, which requires a token with the `SERVICE` role and which
the gateway does not route. All services share `INTERNAL_TOKEN_SECRET`, so **set your own value
outside local development**.

## What replaced the shared database

The services cannot share a transaction or a foreign key, so these rules are now enforced in code.

| Rule                                     | How it works now                                                                 |
|------------------------------------------|----------------------------------------------------------------------------------|
| An account belongs to a real customer    | `account-service` asks `customer-service` before opening one.                    |
| A customer with accounts can't be deleted | Deleting first *retires* the customer in `account-service`. That is refused if they have accounts, and it blocks new ones, under a lock shared with opening an account. |
| Deleting a customer removes their login  | `customer-service` retires them, deletes the login, then deletes the row. Each step can be repeated, so a failed delete can just be retried. |
| A login's email matches the customer's   | `customer-service` updates the login inside its own transaction, and rolls back if that is refused. |

### Known gaps

The monolith could not get into these states:

- If `auth-service` applies an email change but its answer is lost, or the customer's commit then fails,
  the login has the new email and the customer the old one. Saving the customer again repairs it.
- A delete that fails part-way leaves a customer who is retired, and perhaps without a login, until it is retried.
- Setting a login while the same customer is being deleted can leave a login with no customer. It can
  sign in, but sees no data.
- If the gateway gets no answer to a write, the caller is told it may or may not have been applied.
  Nothing is retried automatically, and the API has no idempotency keys yet.

### Availability

A service being down now only affects what needs it. Accounts can still be read if `customer-service`
is down, but opening one fails with a 503. If `auth-service` is down nobody can sign in, and customer
updates and deletes fail, but existing sessions can still read. The gateway keeps sessions in memory,
so restarting it signs everyone out, and it can't yet run as more than one instance.

## Events and monitoring

```
docker compose --profile monitoring up -d --build
```

| Tool | Address | Sign-in | What it's for |
|---|---|---|---|
| Grafana | http://localhost:3000 | `admin` / `admin` | The **Bank health** dashboard: is each service up, traffic, errors, response times |
| Kibana | http://localhost:5601 | none | Searching logs, events and heartbeats (open **Discover**) |
| Prometheus | http://localhost:9090 | none | Raw metrics and **Alerts** |
| Elasticsearch | http://localhost:9200 | none | Stores what Kibana searches |

The monitoring tools add about 4 GB of memory, and the logs take some minutes to appear after the first
start. Without the profile, the bank and Kafka run alone. To use your own Grafana password, set
`GRAFANA_ADMIN_PASSWORD` before its first start.

**Kafka topics**

| Topic | Contains | Written by |
|---|---|---|
| `bank.events` | What happened, such as `MONEY_DEPOSITED` or `CUSTOMER_CREATED` | Each service's outbox |
| `bank.health` | A heartbeat from every service every 30 seconds | Each service |
| `bank.logs` | Every service's log lines | Filebeat |

Logstash copies all three into Elasticsearch, which keeps 14 days. Kafka keeps 7 days.

**Events can't get out of step with the data.** A service writes an event to its own `outbox_events`
table in the same transaction as the change, and a background job then sends it to Kafka. So if Kafka is
down, banking carries on and events wait: nothing is lost, and the **Events waiting for Kafka** panel
shows the queue. An event can be delivered twice after a crash, so anything consuming the topic should
ignore a repeated `eventId`, as the Logstash pipeline does.

**Events hold ids and amounts, never names, emails or passwords.** Keep it that way when adding one,
because they get copied to Kafka and Elasticsearch.

**Alerts** show in Prometheus and on the dashboard, but nothing sends them anywhere yet. Sending email or
chat messages needs Alertmanager.

## Before production

This is set up for local development. Before real use:

- Set your own `INTERNAL_TOKEN_SECRET`, `DB_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` and
  `SESSION_COOKIE_SECURE=true`, and serve the gateway over HTTPS.
- Turn on security in Elasticsearch, Kibana and Kafka. They have no passwords here, and are only safe
  because they are published to this computer alone. Kafka also runs as a single broker, so it has no
  second copy of anything.
- Add TLS between services: passwords travel from the gateway to `auth-service` over plain HTTP, which
  is only as private as the Docker network.
- Give each service its own database user. They currently share one, so only convention keeps a
  service out of another's database.
- Any service holding the shared secret can issue a token for anyone. Signing with a private key held
  only by the gateway would limit the damage if one service is compromised.

## Tests

`.\gradlew.bat build` runs everything and needs Docker.

- Each service's tests use real PostgreSQL, with the other services stubbed.
- `gateway` tests check forwarding and what is stripped, against a stub service.
- `e2e-tests` starts all four packaged jars and drives them through the gateway, as a browser would.
