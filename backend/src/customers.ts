// Customer news for the developer: free weeks and subscriptions, in one email per run of the hourly
// alert job (alerts.ts), sent only when something happened. Nothing in the trial or purchase code
// takes part:
//   - a purchase is compared with what the last email said about it (subscriptions.notified, a
//     FINGERPRINT of its product, state, order, pending switch and refunded orders), so every state
//     the scheduled job or the app recorded is reported once, however it got there;
//   - free weeks are found by their timestamps since the last email (customersThrough in the alert
//     job's state), read TRIAL_LAG_MS late so a request still in flight isn't skipped.
// Changes inside one hour fold into one: a purchase canceled and turned back on within the hour
// shows no change. Emails name plans, states and times only: no keys, tokens, order IDs or device IDs.

/** SQL for what an email says about a purchase. Product and order IDs never contain "|". */
export const FINGERPRINT =
  "product_id || '|' || state || '|' || COALESCE(order_id, '') || '|' || COALESCE(pending_product_id, '') || '|' || voided_orders";

export const TRIAL_LAG_MS = 5 * 60_000;
/** Purchases read per run; any more wait for the next run. */
const PURCHASES_PER_RUN = 100;
/** Event lines per email; the rest are counted. */
const MAX_LINES = 40;

const PLANS: Record<string, string> = { transmit_monthly: "Transmit", transmit_plus: "Transmit Plus" };
const plan = (productId: string) => PLANS[productId] ?? productId;

type Kind =
  | "new" | "trial_started" | "refunded" | "payment_problem" | "plan_change" | "resubscribed" | "canceled" | "ended"
  | "trial_converted" | "trial_ended" | "renewed" | "switch_scheduled" | "switch_done" | "switch_canceled"
  | "uncanceled" | "payment_fixed" | "paused" | "other";

/** Subject wording, in the subject's order. */
const KINDS: Record<Kind, [string, string]> = {
  new: ["new subscriber", "new subscribers"],
  trial_started: ["free week started", "free weeks started"],
  refunded: ["refund", "refunds"],
  payment_problem: ["payment problem", "payment problems"],
  plan_change: ["plan change", "plan changes"],
  resubscribed: ["returning subscriber", "returning subscribers"],
  canceled: ["cancellation", "cancellations"],
  ended: ["subscription ended", "subscriptions ended"],
  trial_converted: ["free week converted", "free weeks converted"],
  trial_ended: ["free week ended", "free weeks ended"],
  renewed: ["renewal", "renewals"],
  switch_scheduled: ["plan switch scheduled", "plan switches scheduled"],
  switch_done: ["plan switch", "plan switches"],
  switch_canceled: ["plan switch called off", "plan switches called off"],
  uncanceled: ["auto-renew back on", "auto-renew back on"],
  payment_fixed: ["payment fixed", "payments fixed"],
  paused: ["pause", "pauses"],
  other: ["other change", "other changes"],
};

interface Line {
  at: number;
  kind: Kind;
  text: string;
  test: boolean;
}

interface PurchaseChange {
  token_hash: string;
  product_id: string;
  state: string;
  expiry_time: number;
  created_at: number;
  updated_at: number;
  test_purchase: number;
  notified: string | null;
  fp: string;
}

interface Predecessor {
  replaced_by: string;
  product_id: string;
  state: string;
}

interface TrialTimes {
  created_at: number;
  converted_at: number | null;
  ended_at: number | null;
}

/**
 * The reads, for the alert job's first batch: free-week events after the stored customersThrough (none
 * stored: none, the first run starts the count) up to `to`, and counts at `now`.
 */
export function customerReads(db: D1Database, now: number, to: number): D1PreparedStatement[] {
  return [
    db
      .prepare(
        `SELECT token_hash, product_id, state, expiry_time, created_at, updated_at, test_purchase, notified, ${FINGERPRINT} AS fp
         FROM subscriptions WHERE notified IS NULL OR notified != ${FINGERPRINT} ORDER BY updated_at LIMIT ?1`,
      )
      .bind(PURCHASES_PER_RUN),
    // The purchase each new one replaced (an upgrade, a switch, a resubscribe).
    db.prepare(
      "SELECT replaced_by, product_id, state FROM subscriptions WHERE replaced_by IN (SELECT token_hash FROM subscriptions WHERE notified IS NULL)",
    ),
    db
      .prepare(
        `WITH w AS (SELECT MIN(COALESCE((SELECT json_extract(value, '$.customersThrough') FROM job_state WHERE name = 'alerts'), ?1), ?1) AS f)
         SELECT created_at, converted_at, ended_at FROM trials, w
         WHERE (created_at > w.f AND created_at <= ?1) OR (converted_at > w.f AND converted_at <= ?1)
           OR (ended_at > w.f AND ended_at <= ?1 AND converted_at IS NULL)`,
      )
      .bind(to),
    db
      .prepare(
        `SELECT product_id, test_purchase, COUNT(*) AS n FROM subscriptions
         WHERE replaced_by IS NULL AND (state IN ('active', 'grace') OR (state = 'canceled' AND expiry_time > ?1))
         GROUP BY product_id, test_purchase`,
      )
      .bind(now),
    db.prepare("SELECT COUNT(*) AS n FROM trials WHERE key_hash IS NOT NULL AND converted_at IS NULL AND expires_at > ?1").bind(now),
  ];
}

export interface CustomerNews {
  /** Event lines; 0: no email, just the marks. */
  lines: number;
  subject?: string;
  text?: string;
  /** Records what the email said; write them only once it's sent (or there was nothing to send). */
  marks: D1PreparedStatement[];
}

/** Reads the results of customerReads() (in order) into the email. */
export function customerNews(db: D1Database, results: D1Result[], o: { now: number; from: number; to: number; trialsToday: number }): CustomerNews {
  const [changes, preds, trials, paying, running] = results as [D1Result, D1Result, D1Result, D1Result, D1Result];
  const predecessors = new Map((preds.results as unknown as Predecessor[]).map((p) => [p.replaced_by, p]));
  const lines: Line[] = [];
  const marks: D1PreparedStatement[] = [];

  for (const r of changes.results as unknown as PurchaseChange[]) {
    lines.push(...purchaseLines(r, predecessors.get(r.token_hash)));
    marks.push(db.prepare("UPDATE subscriptions SET notified = ?2 WHERE token_hash = ?1").bind(r.token_hash, r.fp));
  }
  const inWindow = (t: number | null): t is number => t !== null && t > o.from && t <= o.to;
  for (const t of trials.results as unknown as TrialTimes[]) {
    if (inWindow(t.created_at)) lines.push({ at: t.created_at, kind: "trial_started", text: "A free week started.", test: false });
    if (inWindow(t.converted_at)) lines.push({ at: t.converted_at, kind: "trial_converted", text: "A free week turned into a subscription.", test: false });
    else if (inWindow(t.ended_at)) lines.push({ at: t.ended_at, kind: "trial_ended", text: "A free week ended without a subscription.", test: false });
  }
  if (lines.length === 0) return { lines: 0, marks };
  lines.sort((a, b) => a.at - b.at);

  const body = [`Transmit customers, ${when(o.now)}`, ""];
  for (const l of lines.slice(0, MAX_LINES)) body.push(`- ${when(l.at)}: ${l.test ? "[test] " : ""}${l.text}`);
  if (lines.length > MAX_LINES) body.push(`- …and ${lines.length - MAX_LINES} more.`);
  body.push("", `Now: ${status(paying.results as unknown as { product_id: string; test_purchase: number; n: number }[], Number((running.results[0] as { n: number }).n), o.trialsToday)}`);
  body.push(
    "",
    "--",
    "Sent by the Transmit Worker's hourly alert job (backend/src/customers.ts) when something happened. Purchases appear when Google reports them: renewals, cancellations and lapses often at the end of a billing period. To find one in Play Console (Order management), go by its time.",
  );
  return { lines: lines.length, subject: subject(lines), text: body.join("\n"), marks };
}

function purchaseLines(r: PurchaseChange, pred: Predecessor | undefined): Line[] {
  const n = parse(r.fp);
  const P = plan(n.product);
  const test = r.test_purchase === 1;
  const out: Line[] = [];
  const add = (kind: Kind, text: string, at = r.updated_at) => out.push({ at, kind, text, test });

  if (r.notified === "") return out; // from before these emails: recorded, not reported
  if (r.notified === null) {
    if (pred && n.pending) add("switch_scheduled", `${plan(pred.product_id)} is switching to ${plan(n.pending)} at the renewal on ${day(r.expiry_time)}.`, r.created_at);
    else if (pred && pred.product_id !== n.product) add("plan_change", `Changed plan: ${plan(pred.product_id)} → ${P}.`, r.created_at);
    else if (pred) add("resubscribed", `Resubscribed to ${P}.`, r.created_at);
    else add("new", `New subscriber: ${P}.`, r.created_at);
    return out;
  }

  const o = parse(r.notified);
  if (n.state !== o.state) {
    switch (n.state) {
      case "canceled":
        add("canceled", `${P}: auto-renew turned off. The AI keeps working until ${day(r.expiry_time)}.`);
        break;
      case "active":
        if (o.state === "canceled") add("uncanceled", `${P}: auto-renew turned back on.`);
        else if (o.state === "grace" || o.state === "on_hold" || o.state === "paused") add("payment_fixed", `${P}: active again (${label(o.state)} before).`);
        else add("other", `${P}: active again (${label(o.state)} before).`);
        break;
      case "grace":
        add("payment_problem", `${P}: a renewal payment failed. Google is retrying, and the AI keeps working meanwhile (grace period).`);
        break;
      case "on_hold":
        add("payment_problem", `${P}: on hold after a failed payment. The AI is off until they fix it.`);
        break;
      case "paused":
        add("paused", `${P}: paused.`);
        break;
      case "expired":
        add("ended", `${P}: subscription ended${o.state === "canceled" ? " (auto-renew was off)" : ""}.`);
        break;
      case "refunded":
        add("refunded", `${P}: refunded. Its AI is off.`);
        break;
      case "canceled_pending":
        add("ended", `${P}: a purchase waiting for payment was canceled.`);
        break;
      case "replaced":
        break; // the purchase that replaced it is the news
      default:
        add("other", `${P}: ${label(o.state)} → ${label(n.state)}.`);
    }
  }
  const stateNews = out.length > 0;
  if (n.product !== o.product) add("switch_done", `Switched from ${plan(o.product)} to ${P} at the renewal.`);
  else if (n.order && n.order !== o.order && !out.some((l) => l.kind === "payment_fixed")) add("renewed", `${P}: renewed.`);
  const refunds = (s: string) => s.split(" ").filter(Boolean).length;
  if (refunds(n.voided) > refunds(o.voided) && n.state !== "refunded") add("refunded", `${P}: an earlier payment was refunded. The subscription goes on.`);
  if (n.pending && n.pending !== o.pending) add("switch_scheduled", `${P}: switching to ${plan(n.pending)} at the renewal on ${day(r.expiry_time)}.`);
  else if (!n.pending && o.pending && n.product === o.product && !stateNews) add("switch_canceled", `${P}: the switch to ${plan(o.pending)} was called off.`);
  return out;
}

function parse(fp: string): { product: string; state: string; order: string; pending: string; voided: string } {
  const [product = "", state = "", order = "", pending = "", voided = ""] = fp.split("|");
  return { product, state, order, pending, voided };
}

function label(state: string): string {
  return ({ on_hold: "on hold", grace: "grace period", canceled: "auto-renew off", canceled_pending: "pending purchase canceled" } as Record<string, string>)[state] ?? state;
}

function status(paying: { product_id: string; test_purchase: number; n: number }[], running: number, trialsToday: number): string {
  const real = paying.filter((p) => p.test_purchase !== 1);
  const tests = paying.filter((p) => p.test_purchase === 1).reduce((n, p) => n + p.n, 0);
  const total = real.reduce((n, p) => n + p.n, 0);
  const byPlan = real.sort((a, b) => a.product_id.localeCompare(b.product_id)).map((p) => `${plan(p.product_id)} ${p.n}`).join(", ");
  return [
    `${plural(total, "paying subscriber")}${total ? ` (${byPlan})` : ""}${tests ? `, plus ${plural(tests, "test purchase")}` : ""}`,
    `${plural(running, "free week")} running`,
    `${plural(trialsToday, "free week")} started today (UTC).`,
  ].join("; ");
}

function subject(lines: Line[]): string {
  const count = (test: boolean) => {
    const n = new Map<Kind, number>();
    for (const l of lines) if (l.test === test) n.set(l.kind, (n.get(l.kind) ?? 0) + 1);
    return (Object.keys(KINDS) as Kind[]).filter((k) => n.has(k)).map((k) => `${n.get(k)} ${KINDS[k][n.get(k) === 1 ? 0 : 1]}`);
  };
  const short = (parts: string[]) => parts.slice(0, 3).join(", ") + (parts.length > 3 ? ` (+${parts.length - 3} more)` : "");
  const real = count(false);
  const tests = lines.filter((l) => l.test).length;
  if (real.length === 0) return `Transmit test purchases: ${short(count(true))}`;
  return `Transmit: ${short(real)}${tests ? `, plus ${plural(tests, "test purchase event")}` : ""}`;
}

const when = (ms: number) => new Date(ms).toISOString().slice(0, 16).replace("T", " ") + " UTC";
const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
