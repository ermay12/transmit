-- Email alerts (src/alerts.ts). Nothing here identifies a person: counts per hour, and error
-- messages as the Worker logs them (no keys, tokens, device IDs or IP addresses).

-- Errors counted as they happen, by UTC hour. The hourly alert job reads each hour once, after it
-- ends, and deletes hours older than a day.
CREATE TABLE alert_events (
  hour   INTEGER NOT NULL,           -- start of the UTC hour, Unix ms
  kind   TEXT    NOT NULL,           -- internal_error, upstream_error, job_error
  count  INTEGER NOT NULL,
  detail TEXT,                       -- the latest message, cut to 300 characters
  PRIMARY KEY (hour, kind)
);

-- Alerts that were emailed and haven't cleared: when they started and when they were last sent.
CREATE TABLE alerts_sent (
  kind     TEXT    PRIMARY KEY,
  first_at INTEGER NOT NULL,
  sent_at  INTEGER NOT NULL
);
