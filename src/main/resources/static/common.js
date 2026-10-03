async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body && !(options.body instanceof FormData) && typeof options.body !== "string") {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(options.body);
  }
  const res = await fetch(path, { credentials: "same-origin", ...options, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail || res.statusText);
  return data;
}

function money(amount, currency = "EUR") {
  return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(Number(amount));
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function fmtDate(iso) {
  if (!iso) return "";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function statusChip(m) {
  if (m.owner) return `<span class="chip owner">Owner</span>`;
  if (m.overdue) {
    if (m.months_due > 1) {
      return `<span class="chip due">Overdue · ${m.months_due} mos</span>`;
    }
    const rem = m.remaining_amount != null && Number(m.remaining_amount) < Number(m.share_amount)
      ? ` · ${money(m.remaining_amount, m.currency)} left`
      : "";
    return `<span class="chip due">Overdue${rem}</span>`;
  }
  if (!m.paid) {
    if (m.months_due > 1) {
      return `<span class="chip due">Due · ${m.months_due} mos</span>`;
    }
    const rem = m.remaining_amount != null && Number(m.remaining_amount) < Number(m.share_amount)
      ? ` · ${money(m.remaining_amount, m.currency)} left`
      : "";
    return `<span class="chip due">Due${rem}</span>`;
  }
  return `<span class="chip paid">${m.months_ahead ? `Paid · ${m.months_ahead} ahead` : "Paid"}</span>`;
}

function statusText(m) {
  if (m.owner) return "Holds the subscription, so their share is always covered";
  const dueDetails = m.remaining_amount != null
    ? (m.months_due > 1
        ? ` · ${m.months_due} months (${money(m.remaining_amount, m.currency)}) still due`
        : ` · ${money(m.remaining_amount, m.currency)} still due`)
    : (m.months_due > 1 ? ` · ${m.months_due} months still due` : "");
  if (m.overdue) return `Overdue since ${fmtDate(m.next_billing_date)}${dueDetails}`;
  if (!m.paid) return `Due since ${fmtDate(m.next_billing_date)}${dueDetails}`;
  const ahead = m.months_ahead ? ` · ${m.months_ahead} month${m.months_ahead === 1 ? "" : "s"} ahead` : "";
  const nextRem = m.remaining_amount != null && Number(m.remaining_amount) < Number(m.share_amount)
    ? ` (${money(m.remaining_amount, m.currency)} due)`
    : "";
  return `Paid through ${fmtDate(m.paid_through)}${ahead} · next payment ${fmtDate(m.next_billing_date)}${nextRem}`;
}
