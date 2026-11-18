-- Transmit backend schema. Times are Unix milliseconds; money is USD.
-- Nothing here identifies a person: device IDs and purchase tokens are stored
-- as keyed hashes, purchase tokens additionally encrypted (the scheduled job
-- has to show them to Google again), and OpenRouter keys only by their hash.

-- One row per device that ever started a trial. Kept after the trial ends, so
-- a device gets one trial.
CREATE TABLE trials (
  device_hash   TEXT PRIMARY KEY,      -- HMAC of ANDROID_ID
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,      -- the key's expires_at
  allowance_usd REAL    NOT NULL,
  key_hash      TEXT,                  -- OpenRouter key hash; NULL once deleted
  ended_at      INTEGER,               -- when the key was deleted
  converted_at  INTEGER                -- when the device subscribed
);
CREATE INDEX trials_live_keys ON trials (expires_at) WHERE key_hash IS NOT NULL;

-- Trial allowance granted per UTC day, for the daily cap.
CREATE TABLE trial_days (
  day         TEXT PRIMARY KEY,        -- YYYY-MM-DD (UTC)
  granted_usd REAL    NOT NULL,
  trials      INTEGER NOT NULL
);

-- One row per Play purchase token.
CREATE TABLE subscriptions (
  token_hash     TEXT PRIMARY KEY,     -- HMAC of the purchase token
  token_enc      TEXT    NOT NULL,     -- AES-GCM encrypted purchase token
  product_id     TEXT    NOT NULL,
  base_plan_id   TEXT,
  state          TEXT    NOT NULL,     -- active, canceled, grace, on_hold, paused, pending, expired, canceled_pending, refunded, replaced
  expiry_time    INTEGER NOT NULL,     -- end of the paid period, from Google
  auto_renewing  INTEGER NOT NULL DEFAULT 0,
  order_id       TEXT,                 -- latest Play order whose allowance was credited
  allowance_usd  REAL    NOT NULL,     -- per billing period
  key_hash       TEXT,                 -- OpenRouter key hash; NULL after an upgrade moved it
  key_disabled   INTEGER NOT NULL DEFAULT 0,
  limit_usd      REAL,                 -- the key's limit as last set
  voided_orders  TEXT    NOT NULL DEFAULT '', -- space-separated refunded order IDs
  acknowledged   INTEGER NOT NULL DEFAULT 0,
  replaced_by    TEXT,                 -- token_hash of the purchase that replaced this one
  next_check_at  INTEGER,              -- when the scheduled job asks Google again; NULL = never
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  checked_at     INTEGER NOT NULL
);
CREATE INDEX subscriptions_due ON subscriptions (next_check_at) WHERE next_check_at IS NOT NULL;

-- Short leases so two calls for the same device or purchase don't race.
CREATE TABLE locks (
  name  TEXT PRIMARY KEY,
  until INTEGER NOT NULL
);

-- Small bits of scheduled-job state (the refund scan's progress).
CREATE TABLE job_state (
  name  TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
