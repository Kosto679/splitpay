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
  if (m.overdue) return `<span class="chip due">Overdue</span>`;
  if (!m.paid) return `<span class="chip due">Due</span>`;
  return `<span class="chip paid">${m.months_ahead ? `Paid · ${m.months_ahead} ahead` : "Paid"}</span>`;
}

function statusText(m) {
  if (m.owner) return "Holds the subscription, so their share is always covered";
  if (m.overdue) return `Overdue since ${fmtDate(m.next_billing_date)}`;
  if (!m.paid) return `Due since ${fmtDate(m.next_billing_date)}`;
  const ahead = m.months_ahead ? ` · ${m.months_ahead} month${m.months_ahead === 1 ? "" : "s"} ahead` : "";
  return `Paid through ${fmtDate(m.paid_through)}${ahead} · next payment ${fmtDate(m.next_billing_date)}`;
}
