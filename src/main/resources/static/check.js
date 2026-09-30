const view = document.getElementById("check");
const LAST_PERSON_KEY = "splitpay.check.person";
let people = [];

async function start() {
  try {
    people = await api("/api/public/people");
  } catch (err) {
    view.innerHTML = `<p class="notice">${escapeHtml(err.message)}</p>`;
    return;
  }
  drawForm();
}

function drawForm(error = "") {
  if (!people.length) {
    view.innerHTML = `<section class="panel login"><h2>Not set up yet</h2><p class="muted">Ask the person who runs the subscriptions for your password.</p></section>`;
    return;
  }
  const last = localStorage.getItem(LAST_PERSON_KEY);
  view.innerHTML = `
    <section class="panel login">
      <p class="kicker">Check-in</p>
      <h2>Have I paid?</h2>
      ${error ? `<p class="notice">${escapeHtml(error)}</p>` : ""}
      <form id="check-form">
        <label>Who are you?
          <select name="person_id" required>
            <option value="">Pick your name</option>
            ${people
              .map((p) => `<option value="${p.id}" ${String(p.id) === last ? "selected" : ""}>${escapeHtml(p.name)}</option>`)
              .join("")}
          </select>
        </label>
        <label>Your password
          <input name="password" type="text" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" required />
        </label>
        <button class="primary" type="submit">Check</button>
      </form>
    </section>`;
  const form = document.getElementById("check-form");
  (last ? form.password : form.person_id).focus();
  form.addEventListener("submit", onSubmit);
}

async function onSubmit(event) {
  event.preventDefault();
  const form = event.target;
  const personId = Number(form.person_id.value);
  try {
    const result = await api("/api/public/status", {
      method: "POST",
      body: { person_id: personId, password: form.password.value },
    });
    localStorage.setItem(LAST_PERSON_KEY, String(personId));
    drawResult(result);
  } catch (err) {
    drawForm(err.message);
  }
}

function publicStatusText(s) {
  if (s.owner) return "You hold this subscription, so your share is always covered.";
  if (s.overdue) return `Payment overdue since ${fmtDate(s.next_billing_date)}.`;
  if (!s.paid) return `Payment due since ${fmtDate(s.next_billing_date)}.`;
  return `Paid through ${fmtDate(s.paid_through)}. Next payment due ${fmtDate(s.next_billing_date)}.`;
}

function drawResult(result) {
  const subs = result.subscriptions;
  const allPaid = subs.every((s) => s.paid);
  view.innerHTML = `
    <section class="panel login">
      <p class="kicker">Hi ${escapeHtml(result.name)}</p>
      <h2>${subs.length ? (allPaid ? "You're all paid up" : "You have a payment due") : "No subscriptions"}</h2>
      ${
        subs.length
          ? subs
              .map(
                (s) => `
        <div class="member">
          <div class="row">
            <div>
              <strong>${escapeHtml(s.subscription_name)}</strong>
              <div class="muted">${money(s.share_amount, s.currency)} / month</div>
            </div>
            ${statusChip(s)}
          </div>
          <div class="muted">${escapeHtml(publicStatusText(s))}</div>
        </div>`
              )
              .join("")
          : `<p class="muted">You're not on any shared subscriptions right now.</p>`
      }
      <button class="ghost" type="button" id="again">Done</button>
    </section>`;
  document.getElementById("again").addEventListener("click", () => drawForm());
}

start();
