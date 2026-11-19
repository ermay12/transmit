-- Calls to /v1/trial per client network and UTC day, so one address can't use
-- up Google's Play Integrity quota (10,000 checks a day). The client is a keyed
-- hash of its IPv4 address or IPv6 /64, never the address itself. The scheduled
-- job deletes rows older than yesterday.
CREATE TABLE trial_calls (
  day     TEXT    NOT NULL,          -- YYYY-MM-DD (UTC)
  ip_hash TEXT    NOT NULL,          -- HMAC of the IPv4 address or IPv6 /64
  calls   INTEGER NOT NULL,
  PRIMARY KEY (day, ip_hash)
);
