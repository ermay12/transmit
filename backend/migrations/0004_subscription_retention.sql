-- Retention: the scheduled job deletes a subscription row, and its OpenRouter key, 90 days after
-- the purchase stopped giving AI for good or was refunded (privacy policy, section 6). ended_at is
-- when the row first reached one of those states (expired, canceled_pending, refunded, replaced);
-- putSubscription() keeps it and clears it if the purchase recovers (a refunded order, then a renewal).
-- Trials are not affected: their rows keep each device to one free trial.
ALTER TABLE subscriptions ADD COLUMN ended_at INTEGER;
UPDATE subscriptions SET ended_at = updated_at WHERE state IN ('expired', 'canceled_pending', 'refunded', 'replaced');
CREATE INDEX subscriptions_ended ON subscriptions (ended_at) WHERE ended_at IS NOT NULL;
