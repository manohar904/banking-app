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

Then open https://localhost:8443 (http://localhost:8080 sends you there). To create the first staff
login, set `BANK_ADMIN_EMAIL` and `BANK_ADMIN_PASSWORD` (12 characters or more) first.

The certificate comes from the stack's own certificate authority, which your browser does not know,
so it warns the first time. Either accept the warning for localhost, or trust the authority on this
computer:

1. Save its certificate: `docker run --rm -v pract_certs:/c alpine cat /c/ca/ca.crt > bank-ca.crt`
2. Double-click `bank-ca.crt`, choose **Install Certificate**, **Current User**, then **Place all
   certificates in the following store**, **Trusted Root Certification Authorities**. Edge and
   Chrome then trust the bank's pages; Firefox has its own list under Settings → Certificates.

Trusting it means your computer accepts anything signed with the authority's key, which only the
`certs` step holds. Remove it again from **Manage user certificates** (`certmgr.msc`) if you delete
the stack.

To run a service from the IDE or Gradle instead, start the database, Kafka and Redis with
`docker compose up -d customer-migrate account-migrate auth-migrate kafka-1 kafka-2 kafka-3 redis`,
then `.\gradlew.bat :customer-service:bootRun`, and likewise for the others.

## How a request travels

The browser only ever talks to the gateway, which is the only service with a published port.

1. The gateway checks the session and CSRF token. Passwords are checked by `auth-service`, but the
   session is held by the gateway.
2. It forwards `/api/**` to the service that owns the data, with a 60-second signed token saying who
   is calling. Cookies and caller-supplied `Authorization` headers are not forwarded.
3. That service decides what the caller may do, so the access rules sit next to the data.

Services call each other on `/internal/**`, which requires a token with the `SERVICE` role and which
the gateway does not route.

There is no shared secret for these tokens. The gateway and each service make their own key pair when
they start, keep the private half to themselves, and publish the public half at
`/.well-known/internal-jwks.json`. A token is checked with the key of whoever it says issued it, and:

- a token for a person is only accepted from the gateway, and
- a service token can only name the service that signed it.

So a service that is broken into can call the others as itself, but cannot act as a person, as staff,
or as another service. Restarting a part gives it a new key; the others fetch it the first time they
see a token signed with it.

### HTTPS between the parts

Every call between the gateway and the services, and every fetch of a public key, is HTTPS. The
`certs` step makes the stack's own certificate authority on first start, and a certificate for each
part, kept in the `certs` volume. Each part sees only its own key; the authority's key never leaves the
`certs` step. Internal calls trust only that authority, so a certificate from anywhere else is
refused; email still uses the usual public ones. Certificates last about two years and are renewed on
the next start once fewer than 30 days are left.

- The services answer only HTTPS. Their health checks and Prometheus use it too.
- The gateway serves browsers over HTTPS on 8443; plain HTTP on 8080 only redirects there. Its
  session cookie is sent over HTTPS only. It sends no HSTS header: for `localhost` that would cover
  every port, including Kibana and Grafana, which are plain HTTP. Its public key and metrics are on
  its private management port (8090), also HTTPS, and the services fetch the key from there.
- To start over with new certificates, remove the volume: `docker compose down` and then
  `docker volume rm pract_certs`.
- Kafka is encrypted too inside the Docker network (`kafka-1:9092` and so on), also between its
  brokers. The ports published to this computer alone (9094 to 9096), for running a service from the
  IDE, are not: that traffic never leaves it.
- Elasticsearch answers only HTTPS, so Kibana, Logstash and Grafana use it too. In a browser it is
  https://localhost:9200, which warns about the certificate because the browser does not know the
  stack's authority.
- PostgreSQL accepts network connections only over TLS (`postgres/pg_hba.conf`). The services, the
  migration steps, `db-init` and the backups all check its certificate. Its health check uses the
  local socket inside the container.
- Redis accepts only TLS. The gateway's sessions use it, trusting only the stack's authority.
- Tests and `bootRun` use plain HTTP, as before. A service run from the IDE still reaches PostgreSQL
  on port 5432: the driver uses TLS by itself when the server offers it. The gateway needs the
  authority's certificate to reach Redis. Save it with
  `docker run --rm -v pract_certs:/c alpine cat /c/ca/ca.crt > ca.crt` and start the gateway with
  `SPRING_DATA_REDIS_SSL_BUNDLE=internal` and
  `SPRING_SSL_BUNDLE_PEM_INTERNAL_TRUSTSTORE_CERTIFICATE=file:ca.crt`.

So everything that travels between the parts of the bank, and between the browser and the bank, is
encrypted. Kibana, Grafana and Prometheus are still plain HTTP, but only reachable from this computer.

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
- A write sent without an `Idempotency-Key` that gets no answer may or may not have been applied, and
  sending it again could apply it twice. The web page always sends a key; other API clients should too.

### Sending a write again safely

Add an `Idempotency-Key` header, any value of up to 100 letters, digits or `_ . : -`, new for each
change. A UUID is ideal. If a request with a key gets no answer, send exactly the same request with
the same key: it is carried out at most once, and a repeat gets the first answer back, marked
`Idempotent-Replayed: true`.

- The service that owns the data stores the key and its answer in the same database transaction as
  the change, so the two can't disagree. A repeat sent while the first is still running waits for it.
- Only successful answers are kept. A failure changed nothing, so the same key can be used again, for
  example after a 409, or once there is enough money.
- The same key with a different request gets a 422. Keys belong to the person using them and are
  forgotten after 24 hours; a repeat after that is carried out again.
- The web page sends a key with every write, and sends it again by itself, up to twice, when no answer
  comes back.

### Transfers

Staff can move money between any two accounts with `POST /api/accounts/{id}/transfer`, giving the
destination's 12-digit account number. Both balances and both ledger entries change in one database
transaction, so money is never taken without arriving. Each side gets its own ledger entry
(`TRANSFER_OUT`, `TRANSFER_IN`) and event (`TRANSFER_SENT`, `TRANSFER_RECEIVED`), linked by a transfer id.
Send an `Idempotency-Key` so that a repeat can't move the money twice. If another request changed one
of the accounts at the same moment, the answer is a 409 and nothing was applied.

Customers can send transfers too, but only from their own accounts, and only up to a daily limit:
1000.00 by default, counted over all their accounts from midnight UTC. Set `CUSTOMER_DAILY_TRANSFER_LIMIT`
and `TRANSFER_LIMIT_TIMEZONE` in `.env` to change them. A transfer over the limit gets a 422 saying how
much is left. Transfers staff make have no limit and do not count towards it.
`GET /api/accounts/{id}/transfer-allowance` shows the limit, what the owner has sent today and what is left.
Two transfers sent at the same moment are counted one after the other, so they can't together pass the limit.

### Audit log

Every change that produces an event also gets an `audit_log` row saying who did it, in the same database
transaction as the change. Actions that fail leave no row. Staff can read each service's log, newest first,
at `/api/accounts/audit`, `/api/customers/audit` and `/api/auth/audit`; add `actorUserId=` or `targetId=` to
filter. Events carry `actorUserId` and `actorRole` too, so Kibana shows all three services together.

In the app, staff can use the **Audit log** button, which shows one service's log at a time with email
addresses instead of user ids.

Database triggers refuse to change or remove entries, which stops mistakes and application bugs, but
not someone with full control of the database. When one service calls another, such as `customer-service`
removing a login when a customer is deleted, the second service records the actor as `SERVICE`; the first
service's entry says which person started it. Reading records and signing in aren't audited.

### Forgot password

**Forgot password?** on the sign-in page emails a link that works once, for 30 minutes. It uses the
same email account as alerts (`.env`); without one, nothing is sent and `auth-service` logs a warning.
Set `APP_BASE_URL` to the address people reach the app at, because links are built from it.

The answer is the same whether or not the email has an account, the email is sent in the background so
timing doesn't reveal it either, and only a hash of the link is stored. Each address can ask 3 times in
15 minutes. Changing a password or email cancels an outstanding link. Sessions already signed in are not
ended by a reset; they expire after 30 minutes unused.

### Transaction emails

Customers get an email for every deposit, withdrawal, transfer and loan payment, unless staff turn it off on the
customer's page. Customers can see the setting but not change it. They use the same email account as alerts
(`.env`); without one, none are sent. Emails show only the last four digits of the account number.

`customer-service` reads the events from Kafka, so email trouble can't affect the transaction itself, and
emails arrive late if Kafka is down. Failed sends are retried for about an hour, a repeated event doesn't
send a second email, and turning this on doesn't email anyone about earlier transactions.

### Loans

Customers apply for a loan on their page (**Apply for a loan**): an amount from 100.00 to 50,000.00, a
length from 6 to 60 months, the account it is paid into and repaid from, and what it is for. Before
sending, they see the monthly payment and the total cost. Staff decide on the **Loans** tile, or on
the customer's page: approving pays the money into the account straight away; rejecting can give a
reason, which the customer sees. A customer can cancel a request staff haven't decided yet.

The interest rate is fixed for each loan when it is asked for: 9.50% a year, or `LOAN_ANNUAL_INTEREST_RATE`
in `.env`. Changing it only affects new requests. Loans are repaid in equal monthly payments; each first
pays the month's interest on what is still owed, and the last one clears the loan to the cent.

Each payment is taken automatically from the loan's account on its due date, a month after approval and
then monthly. `account-service` looks for due payments every hour, at five past. With too little money in
the account nothing is taken, the loan shows as overdue, the customer gets one email (and the audit log
one `LOAN_PAYMENT_MISSED`) per missed payment, and the next hourly run tries again; a payment more than
a month behind is taken one per run. Taken payments are recorded as done by `SYSTEM`. The customer (or
staff) can also **Pay early**, which moves the next due date a month on, so nothing is taken twice.
**Pay off** repays everything still owed at once, with no fee and no further interest.

Each customer may have one request waiting and up to 3 loans being repaid at once. The money moves
through the account's ledger (`LOAN_PAYOUT`, `LOAN_REPAYMENT`) in the same database transaction as the
loan's own change, and both send a transaction email. An account with a request waiting can't be
deleted. The API is `/api/loans` in `account-service`: `offer` and `quote?amount=&months=` show the
terms; `POST` applies (customers only); `GET` lists (a customer's own, or for staff everyone's, with
`customerId=` and `status=`); `/{id}/approve` and `/{id}/reject` (staff), `/{id}/cancel` (the customer),
and `/{id}/repay` and `/{id}/pay-off`. The audit log and events name loans `loan-<id>`.

### Credit cards

Staff issue a credit card from the customer's page (**Issue card**) with a spending limit of up to
20,000.00. A customer may have 3 open cards. Only the last four digits of a card's number exist: the
bank never needs the rest, so it is never made or stored. Cards are valid to the end of the month four
years after they are issued.

There are no real shops, so staff **record purchases** on the card's page. A purchase adds to what is
owed and is refused if the card is frozen, closed or expired, or over the available credit. The customer
or staff **pay** the card from any of the customer's accounts, up to what is owed; the card and the
account's balance change in one database transaction (the ledger entry is `CARD_PAYMENT`), and with too
little money in the account nothing changes. Either side can **freeze** a card, which stops purchases but
not payments, and unfreeze it. Staff can change the limit (never below what is owed) and close a card
once nothing is owed. A customer with an open card can't be deleted. There is no interest on cards yet.

Purchases and payments both send a transaction email. The API is `/api/cards` in `account-service`:
`POST` issues (staff); `GET` lists a customer's cards (`customerId=` for staff); `/{id}` and
`/{id}/transactions` read; `/{id}/purchases`, `/{id}/limit` and `/{id}/close` are for staff;
`/{id}/payments`, `/{id}/freeze` and `/{id}/unfreeze` for both. The audit log and events name cards
`card-<id>`, and keep shop names out.

### Help

Everyone has a **Help** button. Customers see answers to common questions and can ask the bank their
own; staff see every customer's questions (also from the **Help requests** tile), newest activity first,
and answer them. Either side can close a question, after which nobody can reply to it. Customers see
staff answers as from "the bank"; which member of staff wrote each one is still stored.

The API is `/api/help-requests` in `customer-service`: `POST` asks (customers only), `GET` lists (a
customer's own, or for staff everyone's, filtered with `status=WAITING_FOR_BANK`, `ANSWERED` or `CLOSED`),
and `/{id}`, `/{id}/messages` and `/{id}/close` read, reply and close. A customer gets a 404 for anyone
else's question. Each customer may have 5 open questions at a time; subjects are up to 120 characters and
messages up to 2000. Deleting a customer deletes their questions. Events and the audit log record only
ids (`HELP_REQUEST_OPENED`, `HELP_MESSAGE_SENT`, `HELP_REQUEST_CLOSED`, with the question as `help-<id>`),
never what was written.

### Availability

A service being down now only affects what needs it. Accounts can still be read if `customer-service`
is down, but opening one fails with a 503. If `auth-service` is down nobody can sign in, and customer
updates and deletes fail, but existing sessions can still read.

The gateway keeps sessions in Redis, so restarting or redeploying it signs nobody out, and more than
one copy could run behind a load balancer. Redis saves to disk, so restarting it doesn't sign anyone
out either. While Redis is down, signing in and anything needing a session answers with a 503 (the
start page still loads), and the gateway reports itself unhealthy, which raises `ServiceUnhealthy`.
Sessions end after 30 minutes unused. If a gateway upgrade changes what a session holds, sessions it
can't read count as signed out.

### Sign-in limits

Each email gets 5 sign-in attempts in a 15-minute window. After that the answer is a 429 and no password
is checked, even a correct one, until the window ends. Signing in successfully or staff setting a new
password clears the count. Emails with no account are limited the same way, so the limit doesn't reveal
who has one, and only a hash of the email is stored.

Each browser address also gets 20 failed sign-ins in 15 minutes, over all emails, so one source trying
a few guesses against many emails is stopped too. Only failures count: many people signing in from one
office are not limited, and a correct password does not wipe out earlier failures. An attempt refused
for its address is not counted against the email. Set `SIGN_IN_MAX_FAILURES_PER_ADDRESS` in `.env` to
change the 20. Only a hash of the address is stored.

Limits on both still have gaps. Someone who knows an email can keep that person out for 15 minutes at
a time, and anyone sharing an address with an attacker is stopped with them. `ManyFailedSignIns` still
catches both. The gateway uses the address the connection comes from:

- Here, Docker may show every browser as coming from one address on its own network, so the address
  limit can apply to all browsers together.
- Behind a proxy or load balancer in production, every browser comes from the proxy. Set
  `GATEWAY_FORWARD_HEADERS=native` so the gateway reads `X-Forwarded-For` instead, but only when that
  proxy is the only way in, because anyone reaching the gateway directly could send that header and
  pick their own address.

## Events and monitoring

```
docker compose --profile monitoring up -d --build
```

| Tool | Address | Sign-in | What it's for |
|---|---|---|---|
| Grafana | http://localhost:3000 | `admin` / `admin` | The **Bank health** dashboard: is each service up, traffic, errors, response times |
| Kibana | http://localhost:5601 | `elastic` / `elastic_dev_password` | Searching logs, events and heartbeats (open **Discover**) |
| Prometheus | http://localhost:9090 | none | Raw metrics and **Alerts** |
| Alertmanager | http://localhost:9093 | none | Which alerts are being sent, and silencing them |
| Elasticsearch | https://localhost:9200 (the browser warns about the certificate) | `elastic` / `elastic_dev_password` | Stores what Kibana searches |
| Tempo | through Grafana | | Stores traces: one request followed through every service it touched |

The monitoring tools add about 4 GB of memory, and the logs take some minutes to appear after the first
start. Without the profile, the bank and Kafka run alone. To use your own Grafana password, set
`GRAFANA_ADMIN_PASSWORD` before its first start.

Kafka and Elasticsearch ask everyone to sign in, each with a login of its own that can do only its job:

| Login | Signs in to | Used by | May |
|---|---|---|---|
| `bank` | Kafka | the four apps | send events and heartbeats; read events (for emails) |
| `filebeat` | Kafka | Filebeat | send log lines |
| `logstash` | Kafka | Logstash | read logs, events and heartbeats |
| `admin` | Kafka | the brokers, among themselves, and their health check | anything |
| `elastic` | Elasticsearch, Kibana | you, and the setup scripts | anything |
| `kibana_system` | Elasticsearch | Kibana | what Kibana needs |
| `logstash_bank` | Elasticsearch | Logstash | create and write only the `bank-*` indices |
| `grafana_bank` | Elasticsearch | Grafana's "Bank logs" | read only the logs |

Their passwords have local defaults; set your own in `.env` (see `.env.example`), then run
`docker compose --profile monitoring up -d`. The Elasticsearch passwords must not contain `"` or `\`.
Both enforce the "May" column. For Kafka, `kafka-setup` writes the rules (`kafka/setup.sh`) on
every start, and Kafka refuses anything they do not allow: Filebeat cannot read bank events, Logstash
cannot write anything, and the apps cannot read the logs. The four apps share the `bank` login, so
each of them may also read events, which only customer-service does (for transaction emails).

**Traces** show where a request's time went. In Grafana, open **Explore**, choose **Traces**, and search,
for example for slow requests (`{duration > 500ms}`) or failed ones (`{status = error}`). A trace shows
the gateway, each service it called and how long each took. **Logs for this span** opens that request's
log lines; in Kibana, search for `traceId:"<id>"`. Every request is traced and kept for 3 days; set
`TRACING_SAMPLING_PROBABILITY` (for example `0.1`) to trace fewer. Health checks, metric scrapes and
background jobs are not traced. Without the monitoring profile, traces are dropped.

An event sent to Kafka is not yet linked to the request that caused it: the outbox sends it later, in
the background.

**Kafka topics**

| Topic | Contains | Written by |
|---|---|---|
| `bank.events` | What happened, such as `MONEY_DEPOSITED` or `CUSTOMER_CREATED` | Each service's outbox |
| `bank.health` | A heartbeat from every service every 30 seconds | Each service |
| `bank.logs` | Every service's log lines | Filebeat |

Logstash copies all three into Elasticsearch, which keeps 14 days. Kafka keeps 7 days. `kafka-setup`
makes the topics, each with one partition, so events stay in the order they were sent.

**Kafka runs as three brokers** (`kafka-1`, `kafka-2`, `kafka-3`), and each topic has a copy on all
three. Kafka counts a write once two copies have it, so it keeps working, and loses nothing it
confirmed, while any one broker is down. With two down it refuses writes until one is back; the
services' events then wait, as below. The three take about 1.9 GB of memory together.

**Events can't get out of step with the data.** A service writes an event to its own `outbox_events`
table in the same transaction as the change, and a background job then sends it to Kafka. So if Kafka is
down, banking carries on and events wait: nothing is lost, and the **Events waiting for Kafka** panel
shows the queue. An event can be delivered twice after a crash, so anything consuming the topic should
ignore a repeated `eventId`, as the Logstash pipeline does.

**Events hold ids and amounts, never names, emails or passwords.** Keep it that way when adding one,
because they get copied to Kafka and Elasticsearch.

**Alerts** show in Prometheus and on the dashboard, and Alertmanager can email them. To turn that on:

1. Copy `.env.example` to `.env` and fill it in. `.env` stays out of Git.
2. Run `docker compose --profile monitoring up -d alertmanager`.
3. Send yourself a test:
   ```
   docker compose exec alertmanager amtool alert add TestAlert severity=info --annotation=summary="Test alert from the bank" --alertmanager.url=http://localhost:9093
   ```
   It should arrive within about a minute. If not, `docker compose logs alertmanager` says why.

The alerts are `ServiceDown`, `ServiceUnhealthy`, `KafkaBrokerDown`, `KafkaDown`, `HighErrorRate`, `EventsNotReachingKafka`,
`BackupFailed`, `BackupTooOld`
and `ManyFailedSignIns`. `KafkaBrokerDown` means one broker can't be reached: Kafka still works, but
bring it back. `KafkaDown` means fewer than two can, so Kafka takes no events. Both only check that the
brokers accept connections, so one that accepts them but has stopped working shows up later, as
`EventsNotReachingKafka`.

You get one email per problem and service, a reminder every 4 hours while it lasts, and another when it
has gone away. Going on holiday or doing maintenance? Silence alerts at http://localhost:9093.

## Database logins

Each service's database has two logins of its own, and neither can connect to any other database:

| Login | Used by | May |
|---|---|---|
| `customer_owner`, `account_owner`, `auth_owner` | the `*-migrate` steps only | own the database and change its tables |
| `customer_service`, `account_service`, `auth_service` | the running service | read and write rows; add to and read the audit log |

The services never get the owner passwords, so one that is broken into cannot change or drop its
tables, empty them with `TRUNCATE`, or remove the trigger that protects the audit log. It cannot change
or delete audit log rows either way. The main user (`DB_USERNAME`, `bank` by default) is used only by
`db-init` and the backups.

On every `up`, `db-init` creates the logins, sets their passwords from `.env` and hands each owner
anything in its database that someone else owns. Then each `*-migrate` step runs that service's
migrations with Flyway (the same version the services' tests use), and grants the service's login its
rights again (`db-init/after-migrate/afterMigrate.sql`). Only then do the services start. So `up`
brings an older database up to date by itself. To change a password, set it in `.env` and run
`docker compose up -d`. Restoring a backup runs `db-init` and the migration steps again before the
services start.

The services still run their migrations themselves in tests, where there is only one login.

## Backups

The `backup` container backs up every database once a day, into the `backups` folder next to this file
(not a Docker volume, so `docker compose down -v` cannot remove them). Each backup is then restored into
a scratch database to prove it works, and only backups that pass count. The newest 7 are kept.

It backs up at `BACKUP_TIME` (02:00 UTC by default), or as soon after as the computer is on. If the
newest good backup is more than a day old, it backs up straight away. Change the settings in `.env`;
see `.env.example`. A folder on another disk (`BACKUP_DIR`) protects against losing this one.

```
docker compose exec backup sh /backup.sh list      # the backups, and whether each passed its test
docker compose exec backup sh /backup.sh now       # take one now, for example before an upgrade
sh scripts/restore-backup.sh 2026-11-06_020000     # put one back
```

Restoring stops the services, loads every database from the backup under a temporary name, and only
swaps them in when all of them loaded. If it fails, nothing has changed. The databases it replaced are
kept as `<name>_before_restore` until the next restore, so you can still copy something out of them.
Restore all the databases together: they refer to each other's ids. Events sent to Kafka after the
backup was taken are not undone.

The alerts `BackupFailed` and `BackupTooOld` (no good backup for 30 hours) tell you when backups stop
working.

## Before production

This is set up for local development. Before real use:

- Set your own `DB_PASSWORD`, the six `*_DB_PASSWORD` and `*_DB_OWNER_PASSWORD` values,
  `REDIS_PASSWORD` and `GRAFANA_ADMIN_PASSWORD`. Give the gateway a certificate for its real domain
  name from a public authority, and turn on HSTS there.
- Set your own passwords for Kafka and Elasticsearch (the `KAFKA_*_PASSWORD`, `ELASTIC_PASSWORD` and
  other values in `.env.example`). Kafka's three brokers run on one computer here; on real servers,
  put each on its own machine, or one machine failing still takes all three down.

## Tests

`.\gradlew.bat build` runs everything and needs Docker.

- Each service's tests use real PostgreSQL, with the other services stubbed.
- `gateway` tests check forwarding and what is stripped, against a stub service.
- `e2e-tests` starts all four packaged jars and drives them through the gateway, as a browser would.
