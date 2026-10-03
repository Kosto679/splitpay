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
| `PUBLIC_URL` | Public origin shown on Phone setup and used in email links (Tailscale, tunnel, or LAN URL). Trailing slashes are stripped. |
| `TZ` | Timezone for billing periods and “today” (default `Europe/Athens`). |
| `PORT` | HTTP port (default `9000`). |
| `DATA_DIR` | Directory for the H2 file `splitpay` (default `./data`; Docker uses `/data`). |
| `MAIL_HOST` | (Optional) SMTP host (e.g. `smtp.gmail.com`). If unset, email features are disabled. |
| `MAIL_PORT` | (Optional) SMTP port (default `587` for STARTTLS). |
| `MAIL_USERNAME` | (Optional) SMTP account username. |
| `MAIL_PASSWORD` | (Optional) SMTP account password or App Password. |
| `MAIL_FROM` | (Optional) Sender address shown on outgoing emails (e.g. `notifications@splitpay.local`). |
| `SUPPORT_EMAIL` | (Optional) Reply-To address included in emails for member questions. |

Admin sessions last 30 days (`SPLITPAY_SESSION` cookie). After 10 failed logins from one address, login is paused for 15 minutes.

## Concepts

- **People** exist once and can be on any number of subscriptions. Each person has bank **aliases** (the name as it appears in transfer notifications, comma-separated) used for auto-matching, and an optional **email address** for notifications.
- A **membership** puts a person on a subscription with their share amount.
- One membership per subscription can be the **owner**: they hold the plan with the provider, so Splitpay always treats their share as paid and never matches transfers to them.
- A **payment** covers one or more consecutive billing periods.
  - **Overpayment / Advance**: When someone pays more than their share (or multiple months at once), payments fill the earliest unpaid month first and roll excess amounts into future periods as advance credit.
  - **Underpayment**: If a payment is less than the due share, the remaining due amount is tracked and remains clearly indicated.
  - Skipped months remain visible as overdue.
- If one person is on several plans and sends the sum of their shares in one transfer, it is split across those plans.

For each membership Splitpay shows whether the current period is paid, the date it is paid through, the **next billing date**, and remaining amounts. Billing periods follow the subscription’s **billing day**.

## Email Notifications & Automated Alerts

When SMTP is configured (`MAIL_HOST`, `MAIL_FROM`), Splitpay provides built-in email notifications and automated background alerts:

- **Automated Overdue Alerts (Scheduled Job)**: Runs periodically in the background (default: every morning at 10:00 AM Europe/Athens) via Spring's scheduler. Scans for members with unpaid or overdue dues:
  - Dispatches personalized reminder emails to members who have an email address, respecting a configurable cooldown period (default: 3 days) so members are not spammed.
  - Sends a consolidated **Admin Overdue Digest** to the household admin with the total outstanding balance and an itemized breakdown of each overdue member.
- **Payment Reminders**: Sent to members with unpaid or overdue dues. Details each subscription, billing day, overdue period count, status badge, formatted total remaining due, and a direct button to their check-in page.
- **Check-in Password Email**: Sent when a member's check-in password is first generated or assigned.
- **Password Reset Email**: Sent when an admin triggers a password reset, containing the new password.
- **Welcome Email**: Introduces the member to Splitpay with their check-in address.
- **Manual Actions**: The **People** page allows sending reminders per person, batch reminders to all overdue members ("Send overdue reminders"), or triggering the automated job immediately ("Run automated alert job" / `POST /api/people/run-auto-reminders`).

### Automated Alerts Configuration

Configure the background scheduler via environment variables or `.env`:

| Variable | Default | Description |
| --- | --- | --- |
| `AUTO_REMINDERS_ENABLED` | `true` | Enables or disables the scheduled background job |
| `AUTO_REMINDERS_CRON` | `0 0 10 * * *` | Cron expression (Spring 6-part format: sec min hour day month weekday) |
| `REMINDER_COOLDOWN_DAYS` | `3` | Minimum days between automated reminder emails to the same member |
| `AUTO_REMINDERS_NOTIFY_ADMIN` | `true` | Whether to send the daily digest report to the admin |
| `AUTO_REMINDERS_ADMIN_EMAIL` | *(empty)* | Email address for admin reports (falls back to `SUPPORT_EMAIL` or `MAIL_FROM`) |

### Email Templates

Email templates are clean, responsive HTML files stored separately in `src/main/resources/templates/email/`:

- `admin-overdue-summary.html` — admin digest listing all overdue members and total outstanding
- `payment-reminder.html` — itemized due notice with subscription table
- `check-in-password.html` — initial check-in credentials notice
- `password-reset.html` — password reset notification with new password box
- `welcome.html` — introductory welcome email

You can customize these templates directly to fit your household's styling.

## Admin UI

Sign in with `APP_PASSWORD`, then use:

- **Ledger** — subscriptions, who is paid or overdue this period
- **People** — names, emails, aliases, and check-in passwords with one-click email actions
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

