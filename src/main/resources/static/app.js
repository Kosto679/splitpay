const root = document.getElementById("app");
const state = {
  session: null,
  dashboard: null,
  detail: null,
  inbox: null,
  people: null,
  error: "",
  notice: "",
};

function route() {
  const hash = location.hash.replace(/^#/, "") || "/";
  const parts = hash.split("/").filter(Boolean);
  if (parts[0] === "sub" && parts[1]) return { name: "detail", id: Number(parts[1]) };
  if (["people", "inbox", "capture", "settings"].includes(parts[0])) return { name: parts[0] };
  return { name: "home" };
}

function nav(current) {
  const links = [
    ["#/", "home", "Ledger"],
    ["#/people", "people", "People"],
    ["#/inbox", "inbox", state.dashboard?.inbox_count ? `Inbox (${state.dashboard.inbox_count})` : "Inbox"],
    ["#/capture", "capture", "Capture"],
    ["#/settings", "settings", "Phone setup"],
  ];
  return `
    <nav>
      ${links.map(([href, name, label]) => `<a class="${current === name ? "active" : ""}" href="${href}">${label}</a>`).join("")}
      <a href="/check" target="_blank" rel="noopener">Check-in page ↗</a>
    </nav>
  `;
}

function shell(current, inner) {
  return `
    <header class="top">
      <div class="brand">
        <h1>Splitpay</h1>
        <p>Shared subscriptions, matched from bank alerts.</p>
      </div>
      ${nav(current)}
    </header>
    ${state.error ? `<p class="notice flash">${escapeHtml(state.error)}</p>` : ""}
    ${state.notice ? `<p class="notice ok flash">${escapeHtml(state.notice)}</p>` : ""}
    ${inner}
  `;
}

function loginView() {
  return `
    <section class="panel login">
      <p class="kicker">Household ledger</p>
      <h2>Sign in to Splitpay</h2>
      ${state.error ? `<p class="notice">${escapeHtml(state.error)}</p>` : ""}
      <form id="login-form">
        <label>Password <input type="password" name="password" autocomplete="current-password" required /></label>
        <button class="primary" type="submit">Open ledger</button>
      </form>
    </section>
  `;
}

// ---- ledger -------------------------------------------------------------------------

function dashboardView() {
  const subs = state.dashboard?.subscriptions || [];
  const cards = subs.length
    ? `<div class="grid">${subs.map(subCard).join("")}</div>`
    : `<section class="panel"><h2>No subscriptions yet</h2><p class="muted">Add Netflix, Spotify, or any shared plan, then add the people who share it.</p></section>`;
  return shell(
    "home",
    `
    ${cards}
    <section class="panel">
      <h2>New subscription</h2>
      <form id="new-sub" class="stack">
        <label>Name <input name="name" required placeholder="Netflix" /></label>
        <div class="row">
          <label>Total <input name="total_amount" type="number" step="0.01" min="0.01" required /></label>
          <label>Currency <input name="currency" value="EUR" /></label>
          <label>Billing day <input name="billing_day" type="number" min="1" max="28" value="1" /></label>
        </div>
        <label>Notes <input name="notes" placeholder="Optional" /></label>
        <button class="primary" type="submit">Add subscription</button>
      </form>
    </section>
    `
  );
}

function subCard(sub) {
  const pct = Math.min(100, Math.round((Number(sub.collected) / Number(sub.total_amount || 1)) * 100));
  return `
    <article class="card" data-open-sub="${sub.id}">
      <p class="kicker">${fmtDate(sub.period_start)} – ${fmtDate(sub.period_end)}</p>
      <h2>${escapeHtml(sub.name)}</h2>
      <div class="row">
        <strong>${money(sub.collected, sub.currency)} / ${money(sub.total_amount, sub.currency)}</strong>
        <span class="muted">${sub.paid_count}/${sub.member_count} paid</span>
      </div>
      <div class="progress"><span style="width:${pct}%"></span></div>
      <div class="people">
        ${(sub.members || []).map((m) => `<span class="chip ${m.owner ? "owner" : m.paid ? "paid" : "due"}">${escapeHtml(m.name)}${m.owner ? " · owner" : ""}</span>`).join("") || `<span class="muted">No people yet</span>`}
      </div>
    </article>
  `;
}

function memberRow(m, sub) {
  if (m.owner) {
    return `
    <div class="member">
      <div class="row">
        <div>
          <strong>${escapeHtml(m.name)}</strong>
          <div class="muted">${money(m.share_amount, sub.currency)} / month</div>
          <div class="muted">${escapeHtml(statusText(m))}</div>
        </div>
        ${statusChip(m)}
      </div>
      <details>
        <summary>Edit</summary>
        <form class="edit-share" data-member="${m.id}">
          <div class="row">
            <label>Share <input name="share_amount" type="number" step="0.01" min="0.01" value="${escapeHtml(m.share_amount)}" required /></label>
            <div class="actions">
              <button type="submit">Save share</button>
              <button type="button" class="ghost" data-owner="${m.id}" data-owner-value="false">Remove owner role</button>
              <button type="button" class="danger" data-remove-member="${m.id}">Remove from plan</button>
            </div>
          </div>
        </form>
      </details>
    </div>`;
  }
  return `
    <div class="member">
      <div class="row">
        <div>
          <strong>${escapeHtml(m.name)}</strong>
          <div class="muted">${money(m.share_amount, sub.currency)} / month</div>
          <div class="muted">${escapeHtml(statusText(m))}</div>
        </div>
        <div class="actions">
          ${statusChip(m)}
          <button class="primary" data-mark-paid="${m.id}">${m.paid ? "+1 month" : "Mark paid"}</button>
        </div>
      </div>
      <details>
        <summary>Advance payment or edit</summary>
        <form class="record-pay" data-member="${m.id}">
          <div class="row">
            <label>Months paid <input name="periods" type="number" min="1" max="24" value="3" required /></label>
            <label>Amount <input name="amount" type="number" step="0.01" min="0.01" placeholder="share × months" /></label>
            <label>First month <input name="first_period" type="month" /></label>
          </div>
          <p class="muted">Leave the first month blank to start at their earliest unpaid month.</p>
          <button class="primary" type="submit">Record advance payment</button>
        </form>
        <form class="edit-share" data-member="${m.id}">
          <div class="row">
            <label>Share <input name="share_amount" type="number" step="0.01" min="0.01" value="${escapeHtml(m.share_amount)}" required /></label>
            <div class="actions">
              <button type="submit">Save share</button>
              <button type="button" class="ghost" data-owner="${m.id}" data-owner-value="true">Make owner</button>
              <button type="button" class="danger" data-remove-member="${m.id}">Remove from plan</button>
            </div>
          </div>
        </form>
      </details>
    </div>`;
}

function paymentRow(p) {
  const covers = p.period_start
    ? `covers ${fmtDate(p.period_start)} – ${fmtDate(p.covers_until)}${p.periods > 1 ? ` (${p.periods} months)` : ""}`
    : escapeHtml(p.status);
  return `
    <div class="pay">
      <div class="row">
        <strong>${money(p.amount, p.currency)}</strong>
        <span class="muted">${fmtDate(p.paid_at)} · ${covers}</span>
      </div>
      <div class="row">
        <span class="muted">${escapeHtml(p.member_name || p.payer_hint || "Unassigned")} · ${escapeHtml(p.source)}</span>
        <button class="ghost" data-delete-payment="${p.id}">Undo</button>
      </div>
    </div>`;
}

function detailView() {
  const sub = state.detail;
  if (!sub) return shell("home", `<section class="panel">Loading…</section>`);
  const available = (state.people || []).filter((p) => !sub.members.some((m) => m.person_id === p.id));
  return shell(
    "home",
    `
    <section class="panel">
      <p class="kicker">This period ${fmtDate(sub.period_start)} – ${fmtDate(sub.period_end)}</p>
      <div class="row">
        <h2>${escapeHtml(sub.name)}</h2>
        <strong>${money(sub.collected, sub.currency)} collected</strong>
      </div>
      <p class="muted">${money(sub.remaining, sub.currency)} still due · billed on day ${sub.billing_day}</p>
      ${(sub.members || []).map((m) => memberRow(m, sub)).join("")}
      <form id="add-member">
        <h3>Add person</h3>
        <label>Person
          <select name="person_id">
            <option value="">New person…</option>
            ${available.map((p) => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join("")}
          </select>
        </label>
        <label>New person's name <input name="name" placeholder="Only if they're not in the list" /></label>
        <label>Share amount <input name="share_amount" type="number" step="0.01" min="0.01" required /></label>
        <label class="checkbox"><input type="checkbox" name="owner" /> Owner: holds the subscription and pays the provider, so always counted as paid</label>
        <button class="primary" type="submit">Add to plan</button>
      </form>
    </section>
    <section class="panel">
      <h2>Edit plan</h2>
      <form id="edit-sub">
        <label>Name <input name="name" value="${escapeHtml(sub.name)}" required /></label>
        <div class="row">
          <label>Total <input name="total_amount" type="number" step="0.01" value="${escapeHtml(sub.total_amount)}" required /></label>
          <label>Currency <input name="currency" value="${escapeHtml(sub.currency)}" /></label>
          <label>Billing day <input name="billing_day" type="number" min="1" max="28" value="${sub.billing_day}" /></label>
        </div>
        <label>Notes <input name="notes" value="${escapeHtml(sub.notes)}" /></label>
        <div class="actions">
          <button class="primary" type="submit">Save</button>
          <button class="danger" type="button" id="delete-sub">Delete</button>
        </div>
      </form>
    </section>
    <section class="panel">
      <h2>Recent payments</h2>
      ${(sub.payments || []).map(paymentRow).join("") || `<p class="muted">None yet.</p>`}
    </section>
    `
  );
}

// ---- people -------------------------------------------------------------------------

function peopleView() {
  const people = state.people || [];
  const origin = state.session?.public_url || location.origin;
  return shell(
    "people",
    `
    <section class="panel">
      <h2>People</h2>
      <p class="muted">Everyone who pays you, across all plans. Give someone an easy password and they can check their status at <span class="mono">${escapeHtml(origin)}/check</span>.</p>
      <form id="new-person">
        <div class="row">
          <label>Name <input name="name" required placeholder="Maria" /></label>
          <label>Bank aliases <input name="aliases" placeholder="MARIA PAPA, ΜΑΡΙΑ ΠΑΠΑ" /></label>
        </div>
        <button class="primary" type="submit">Add person</button>
      </form>
    </section>
    ${people.map(personCard).join("") || `<section class="panel"><p class="muted">No people yet.</p></section>`}
    `
  );
}

function personCard(p) {
  return `
    <article class="panel">
      <div class="row">
        <h3>${escapeHtml(p.name)}</h3>
        <span class="chip ${p.has_pin ? "paid" : ""}">${p.has_pin ? "Check-in on" : "No check-in"}</span>
      </div>
      ${p.aliases ? `<p class="muted">Aliases: ${escapeHtml(p.aliases)}</p>` : ""}
      ${
        p.memberships.length
          ? p.memberships
              .map(
                (m) => `
        <div class="member">
          <div class="row">
            <div>
              <strong>${escapeHtml(m.subscription_name)}</strong>
              <div class="muted">${money(m.share_amount, m.currency)} / month · ${escapeHtml(statusText(m))}</div>
            </div>
            ${statusChip(m)}
          </div>
        </div>`
              )
              .join("")
          : `<p class="muted">Not on any plan yet. Add them from a subscription.</p>`
      }
      <details ${state.pinResult?.personId === p.id ? "open" : ""}>
        <summary>Check-in password</summary>
        <form class="pin-form" data-person="${p.id}">
          <label>Easy password <input name="pin" minlength="4" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="Leave blank to generate one" /></label>
          <p class="muted">At least 4 characters, not case sensitive, and different for each person.</p>
          ${
            state.pinResult?.personId === p.id
              ? `<p class="notice ok">Saved. ${escapeHtml(p.name)}'s check-in password is <strong class="mono">${escapeHtml(state.pinResult.pin)}</strong>. Share it now; it won't be shown again.</p>`
              : ""
          }
          <div class="actions">
            <button class="primary" type="submit">${p.has_pin ? "Reset password" : "Set password"}</button>
            ${p.has_pin ? `<button class="ghost" type="button" data-clear-pin="${p.id}">Turn off check-in</button>` : ""}
          </div>
        </form>
      </details>
      <details>
        <summary>Edit</summary>
        <form class="person-form" data-person="${p.id}">
          <div class="row">
            <label>Name <input name="name" value="${escapeHtml(p.name)}" required /></label>
            <label>Bank aliases <input name="aliases" value="${escapeHtml(p.aliases)}" /></label>
          </div>
          <div class="actions">
            <button type="submit">Save</button>
            <button type="button" class="danger" data-delete-person="${p.id}">Delete person</button>
          </div>
        </form>
      </details>
    </article>`;
}

// ---- inbox, capture, settings -------------------------------------------------------

function inboxView() {
  const inbox = state.inbox || { payments: [], members: [] };
  return shell(
    "inbox",
    `
    <section class="panel">
      <h2>Unmatched transfers</h2>
      <p class="muted">Bank alerts land here when Splitpay cannot tell who paid. Assign them once; aliases make the next one automatic.</p>
      ${
        inbox.payments.length
          ? inbox.payments
              .map(
                (p) => `
          <div class="pay">
            <div class="row">
              <strong>${money(p.amount, p.currency)}</strong>
              <span class="muted">${escapeHtml(p.payer_hint || "No name found")} · ${fmtDate(p.paid_at)}</span>
            </div>
            <p>${escapeHtml(p.raw_text)}</p>
            <form class="assign" data-id="${p.id}">
              <div class="row">
                <label>Assign to
                  <select name="member_id" required>
                    <option value="">Pick a person and plan…</option>
                    ${inbox.members
                      .map((m) => `<option value="${m.id}">${escapeHtml(m.person_name)} · ${escapeHtml(m.subscription_name)} (${money(m.share_amount, m.currency)})</option>`)
                      .join("")}
                  </select>
                </label>
                <label>Months <input name="periods" type="number" min="1" max="24" placeholder="auto" /></label>
              </div>
              <div class="actions">
                <button class="primary" type="submit">Assign</button>
                <button class="ghost" type="button" data-ignore="${p.id}">Ignore</button>
              </div>
            </form>
          </div>`
              )
              .join("")
          : `<p class="muted">Inbox is clear.</p>`
      }
    </section>
    `
  );
}

function captureView() {
  return shell(
    "capture",
    `
    <section class="panel">
      <h2>Log a bank alert</h2>
      <p class="muted">Paste the notification text from Android or iPhone. Splitpay reads the amount and name, then matches it to a person. A multiple of their share counts as paying months ahead.</p>
      <form id="capture-form">
        <label>Notification text
          <textarea name="text" required placeholder="You received 12,50 EUR from Maria Papa"></textarea>
        </label>
        <button class="primary" type="submit">Add payment</button>
      </form>
      <p id="parse-preview" class="muted"></p>
    </section>
    `
  );
}

function settingsView() {
  const origin = state.session?.public_url || location.origin;
  return shell(
    "settings",
    `
    <section class="panel">
      <h2>Send phone alerts here</h2>
      <p>Point Android or iPhone automations at this ingest URL. Use the token from your <span class="mono">.env</span> file as a bearer token.</p>
      <p><span class="mono">${escapeHtml(origin)}/api/ingest</span></p>
      <pre class="mono">{
  "text": "You received 12.50 EUR from Maria",
  "source": "android"
}</pre>
      ${state.session?.ingest_configured ? "" : `<p class="notice">INGEST_TOKEN is not set, so phones cannot post yet.</p>`}
    </section>
    <section class="panel">
      <h3>Android</h3>
      <ol class="setup">
        <li>Install MacroDroid or Tasker.</li>
        <li>Trigger: notification posted, app = your bank (or Revolut / Wise / IRIS).</li>
        <li>Action: HTTP POST JSON to <span class="mono">/api/ingest</span>.</li>
        <li>Header: <span class="mono">Authorization: Bearer YOUR_INGEST_TOKEN</span>.</li>
        <li>Body field <span class="mono">text</span> = notification title + body.</li>
      </ol>
    </section>
    <section class="panel">
      <h3>iPhone</h3>
      <ol class="setup">
        <li>Open Shortcuts → Automation → Create Personal Automation.</li>
        <li>Choose When I receive a notification from your bank app. Confirm if iOS asks.</li>
        <li>Add Get Contents of URL: method POST, URL <span class="mono">${escapeHtml(origin)}/api/ingest</span>.</li>
        <li>Headers: <span class="mono">Authorization</span> = <span class="mono">Bearer YOUR_INGEST_TOKEN</span> and <span class="mono">Content-Type</span> = <span class="mono">application/json</span>.</li>
        <li>Request body: JSON with <span class="mono">text</span> set to the notification text, <span class="mono">source</span> set to <span class="mono">ios</span>.</li>
      </ol>
      <p class="muted">If iOS will not run that automation quietly, copy the alert into the Capture page instead.</p>
    </section>
    <section class="panel">
      <h3>Check-in page for your people</h3>
      <p>Send people <a class="mono" href="/check" target="_blank" rel="noopener">${escapeHtml(origin)}/check</a>. They pick their name and type the easy password you set on the People page.</p>
    </section>
    <section class="panel">
      <h3>Reaching the home lab from outside</h3>
      <p class="muted">Keep the container on your LAN and reach it with Tailscale, WireGuard, or a Cloudflare Tunnel. Set <span class="mono">PUBLIC_URL</span> to that address so these pages show the right links.</p>
    </section>
    `
  );
}

// ---- data + events ------------------------------------------------------------------

async function refresh() {
  const current = route();
  try {
    state.session = await api("/api/session");
    if (!state.session.authed) {
      root.innerHTML = loginView();
      return;
    }
    if (current.name === "detail") {
      [state.detail, state.people] = await Promise.all([api(`/api/subscriptions/${current.id}`), api("/api/people")]);
    }
    if (current.name === "people") state.people = await api("/api/people");
    if (current.name === "inbox") state.inbox = await api("/api/inbox");
    state.dashboard = await api("/api/dashboard");
  } catch (err) {
    state.error = err.message;
  }
  draw();
}

function draw() {
  if (!state.session?.authed) {
    root.innerHTML = loginView();
    return;
  }
  const views = { detail: detailView, people: peopleView, inbox: inboxView, capture: captureView, settings: settingsView };
  root.innerHTML = (views[route().name] || dashboardView)();
}

function formData(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function numberOrNull(value) {
  return value === "" || value == null ? null : Number(value);
}

function describeRecorded(result) {
  if (result.status !== "matched") return "Saved to inbox for assignment.";
  return `Matched ${result.payments.map((p) => `${p.member_name} · ${p.subscription_name}`).join(", ")}.`;
}

async function handleSubmit(form) {
  if (form.id === "login-form") {
    await api("/api/login", { method: "POST", body: formData(form) });
    return;
  }
  if (form.id === "new-sub") {
    const body = formData(form);
    body.total_amount = Number(body.total_amount);
    body.billing_day = Number(body.billing_day);
    const created = await api("/api/subscriptions", { method: "POST", body });
    location.hash = `#/sub/${created.id}`;
    return;
  }
  if (form.id === "edit-sub") {
    const body = formData(form);
    body.total_amount = Number(body.total_amount);
    body.billing_day = Number(body.billing_day);
    await api(`/api/subscriptions/${state.detail.id}`, { method: "PATCH", body });
    state.notice = "Subscription saved.";
    return;
  }
  if (form.id === "add-member") {
    const data = formData(form);
    await api(`/api/subscriptions/${state.detail.id}/members`, {
      method: "POST",
      body: {
        person_id: numberOrNull(data.person_id),
        name: data.name,
        share_amount: Number(data.share_amount),
        owner: data.owner === "on",
      },
    });
    state.notice = "Person added.";
    return;
  }
  if (form.classList.contains("record-pay")) {
    const data = formData(form);
    await api("/api/payments", {
      method: "POST",
      body: {
        member_id: Number(form.dataset.member),
        periods: Number(data.periods),
        amount: numberOrNull(data.amount),
        first_period: data.first_period || null,
      },
    });
    state.notice = `Recorded ${data.periods} month${data.periods === "1" ? "" : "s"}.`;
    return;
  }
  if (form.classList.contains("edit-share")) {
    await api(`/api/members/${form.dataset.member}`, {
      method: "PATCH",
      body: { share_amount: Number(formData(form).share_amount) },
    });
    state.notice = "Share saved.";
    return;
  }
  if (form.id === "new-person") {
    await api("/api/people", { method: "POST", body: formData(form) });
    state.notice = "Person added.";
    return;
  }
  if (form.classList.contains("person-form")) {
    await api(`/api/people/${form.dataset.person}`, { method: "PATCH", body: formData(form) });
    state.notice = "Saved.";
    return;
  }
  if (form.classList.contains("pin-form")) {
    const { pin } = await api(`/api/people/${form.dataset.person}/pin`, { method: "POST", body: formData(form) });
    state.pinResult = { personId: Number(form.dataset.person), pin };
    return;
  }
  if (form.id === "capture-form") {
    const created = await api("/api/payments", { method: "POST", body: { raw_text: formData(form).text, source: "capture" } });
    state.notice = describeRecorded(created);
    state.keepNotice = true;
    location.hash = created.status === "unmatched" ? "#/inbox" : "#/";
    return;
  }
  if (form.classList.contains("assign")) {
    const data = formData(form);
    await api(`/api/payments/${form.dataset.id}/assign`, {
      method: "POST",
      body: { member_id: Number(data.member_id), periods: numberOrNull(data.periods) },
    });
    state.notice = "Assigned.";
  }
}

async function handleClick(target) {
  const open = target.closest("[data-open-sub]");
  if (open) {
    location.hash = `#/sub/${open.dataset.openSub}`;
    return false;
  }
  const mark = target.closest("[data-mark-paid]");
  if (mark) {
    const member = state.detail.members.find((m) => String(m.id) === mark.dataset.markPaid);
    await api("/api/payments", { method: "POST", body: { member_id: member.id, periods: 1, source: "manual" } });
    state.notice = `Recorded a month for ${member.name}.`;
    return true;
  }
  const owner = target.closest("[data-owner]");
  if (owner) {
    const makeOwner = owner.dataset.ownerValue === "true";
    await api(`/api/members/${owner.dataset.owner}/owner`, { method: "POST", body: { owner: makeOwner } });
    state.notice = makeOwner ? "Owner updated." : "Owner role removed.";
    return true;
  }
  const removeMember = target.closest("[data-remove-member]");
  if (removeMember) {
    if (!confirm("Remove this person from the plan? Their past payments stay in the history.")) return false;
    await api(`/api/members/${removeMember.dataset.removeMember}`, { method: "DELETE" });
    return true;
  }
  const deletePayment = target.closest("[data-delete-payment]");
  if (deletePayment) {
    if (!confirm("Undo this payment?")) return false;
    await api(`/api/payments/${deletePayment.dataset.deletePayment}`, { method: "DELETE" });
    return true;
  }
  const ignore = target.closest("[data-ignore]");
  if (ignore) {
    await api(`/api/payments/${ignore.dataset.ignore}/ignore`, { method: "POST" });
    return true;
  }
  const clearPin = target.closest("[data-clear-pin]");
  if (clearPin) {
    await api(`/api/people/${clearPin.dataset.clearPin}/pin`, { method: "DELETE" });
    state.notice = "Check-in turned off.";
    return true;
  }
  const deletePerson = target.closest("[data-delete-person]");
  if (deletePerson) {
    if (!confirm("Delete this person and remove them from every plan?")) return false;
    await api(`/api/people/${deletePerson.dataset.deletePerson}`, { method: "DELETE" });
    return true;
  }
  if (target.id === "delete-sub") {
    if (!confirm("Delete this subscription?")) return false;
    await api(`/api/subscriptions/${state.detail.id}`, { method: "DELETE" });
    location.hash = "#/";
    return false;
  }
  return false;
}

root.addEventListener("submit", async (event) => {
  const form = event.target;
  if (!(form instanceof HTMLFormElement)) return;
  event.preventDefault();
  state.error = "";
  state.notice = "";
  state.pinResult = null;
  try {
    await handleSubmit(form);
    await refresh();
  } catch (err) {
    showFormError(form, err.message);
  }
});

function showFormError(form, message) {
  if (!form.isConnected) {
    state.error = message;
    draw();
    return;
  }
  let box = form.querySelector(".form-error");
  if (!box) {
    box = document.createElement("p");
    box.className = "notice form-error";
    form.append(box);
  }
  box.textContent = message;
  box.scrollIntoView({ block: "nearest", behavior: "smooth" });
}

root.addEventListener("click", async (event) => {
  if (!(event.target instanceof Element) || event.target.closest("summary, input, select, label")) return;
  state.error = "";
  try {
    if (await handleClick(event.target)) await refresh();
  } catch (err) {
    state.error = err.message;
    draw();
  }
});

root.addEventListener("input", async (event) => {
  if (event.target.name !== "text" || !event.target.closest("#capture-form")) return;
  const preview = document.getElementById("parse-preview");
  if (!preview || event.target.value.trim().length < 4) return;
  try {
    const parsed = await api(`/api/parse?text=${encodeURIComponent(event.target.value)}`);
    preview.textContent = parsed.amount
      ? `Looks like ${parsed.amount} ${parsed.currency}${parsed.payer_hint ? ` from ${parsed.payer_hint}` : ""}.`
      : "No amount found yet.";
  } catch {
    preview.textContent = "";
  }
});

window.addEventListener("hashchange", () => {
  if (!state.keepNotice) state.notice = "";
  state.keepNotice = false;
  state.error = "";
  state.pinResult = null;
  refresh();
});
refresh();
