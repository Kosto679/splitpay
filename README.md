# Splitpay

Household ledger for shared subscriptions. People pay you by bank transfer; Splitpay tracks who is paid up for each billing period, including people who pay months in advance. Phone automations can POST bank notifications so you do not retype amounts, and each person can check their own status on a small public page.

Spring Boot 3.5 on Java 21, with an embedded H2 file database under `DATA_DIR` (default `./data`). The UI is plain HTML and JavaScript in `src/main/resources/static`. Default listen port is **9000**.

## Run on the home lab

```bash
cp .env.example .env
# set APP_PASSWORD, INGEST_TOKEN and PUBLIC_URL
docker compose up -d --build
```

Open `http://HOST:9000`. Compose mounts `./data` into the container, sets timezone from `TZ` (default `Europe/Athens`), and health-checks `GET /api/health`.

## Configuration

Copy `.env.example` to `.env`. Spring Boot also reads `.env` for local `mvn spring-boot:run`.

| Variable | Purpose |
| --- | --- |
| `APP_PASSWORD` | Admin login. If empty, the admin UI is open (useful only on a trusted LAN). |
| `INGEST_TOKEN` | Shared secret for `POST /api/ingest`. If empty, phone ingest is disabled. |
| `PUBLIC_URL` | Public origin shown on Phone setup (Tailscale, tunnel, or LAN URL). Trailing slashes are stripped. |
| `TZ` | Timezone for billing periods and “today” (default `Europe/Athens`). |
| `PORT` | HTTP port (default `9000`). |
| `DATA_DIR` | Directory for the H2 file `splitpay` (default `./data`; Docker uses `/data`). |

Admin sessions last 30 days (`SPLITPAY_SESSION` cookie). After 10 failed logins from one address, login is paused for 15 minutes.

## Concepts

- **People** exist once and can be on any number of subscriptions. Each person has bank **aliases** (the name as it appears in transfer notifications, comma-separated) used for auto-matching.
- A **membership** puts a person on a subscription with their share amount.
- One membership per subscription can be the **owner**: they hold the plan with the provider, so Splitpay always treats their share as paid and never matches transfers to them.
- A **payment** covers one or more consecutive billing periods (up to 24). A transfer that is a multiple of someone's share (for example 3 × €3.00, within €0.05) is booked as months in advance. Payments fill the earliest unpaid month first, so a skipped month stays visible as overdue.
- If one person is on several plans and sends the sum of their shares in one transfer, it is split across those plans.

For each membership Splitpay shows whether the current period is paid, the date it is paid through, and the **next billing date**. Billing periods follow the subscription’s **billing day**.

## Admin UI

Sign in with `APP_PASSWORD`, then use:

- **Ledger** — subscriptions, who is paid or overdue this period
- **People** — names, aliases, and check-in passwords
- **Inbox** — transfers that could not be matched; assign or ignore
- **Capture** — paste a notification by hand
- **Phone setup** — ingest URL, Android/iPhone steps, check-in link

Install the admin app as a PWA. The share target posts to `/api/ingest/form` (signed-in session or ingest token) and then opens Ledger or Inbox.

## Check-in page

`/check` lets people see their own status. They pick their name and type an easy password (for example `tiger42`) that you set or generate on the **People** page. Passwords are not case sensitive, at least 4 characters, must be unique, and are stored hashed (PBKDF2). Only people with a password appear on the public name list.

After 5 wrong attempts for a person, or 30 from one IP, check-in is paused for 15 minutes.

The admin pages stay behind `APP_PASSWORD`. If you expose Splitpay through a tunnel for the check-in page, consider publishing only `/check`, `/check.html`, `/check.js`, `/common.js`, `/styles.css` and `/api/public/*`. Keep `/api/ingest` reachable from your phone if you use automations.

## Phone ingest

`POST /api/ingest` with `Authorization: Bearer INGEST_TOKEN` (or header `X-Ingest-Token`, or query `token`):

```json
{
  "text": "You received 12,50 EUR from Maria Papa",
  "source": "android"
}
```

Optional `paid_at` is an ISO date or datetime. The parser looks for an amount (`12,50 €`, `EUR 12.50`, `€12.50`) and a payer (`from …`, `από …`, `… sent you`). Unclear transfers wait in **Inbox**. The **Phone setup** page has Android (MacroDroid/Tasker) and iPhone (Shortcuts) steps.

Keep the container on your LAN and reach it with Tailscale, WireGuard, or a Cloudflare Tunnel. Set `PUBLIC_URL` to that address so Phone setup and check-in links are correct.

## Local development

Needs JDK 21 and Maven.

```bash
mvn spring-boot:run
mvn test
```

Local runs read `.env` from the project root. Data is written to `./data/splitpay.mv.db` unless you set `DATA_DIR`.
