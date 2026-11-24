-- Customer emails (src/customers.ts): the hourly alert job tells the developer about free weeks and
-- subscriptions. Nothing here identifies a person.

-- What the last email said about this purchase: customers.ts FINGERPRINT (product, state, order,
-- pending switch, refunded orders). NULL: a purchase no email has mentioned yet. '': a purchase from
-- before these emails, recorded quietly at the first run rather than reported.
ALTER TABLE subscriptions ADD COLUMN notified TEXT;
UPDATE subscriptions SET notified = '';

-- Google's testPurchase (a license tester's purchase), so the emails can mark tests. Rows from before
-- this migration get it at their next sync or check.
ALTER TABLE subscriptions ADD COLUMN test_purchase INTEGER NOT NULL DEFAULT 0;
