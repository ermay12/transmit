-- A plan change Google has scheduled for the next renewal (ReplacementMode.DEFERRED,
-- e.g. Transmit Plus -> Transmit). It takes effect at expiry_time; until then the
-- current product and its allowance apply. NULL = nothing pending (or renewal is off,
-- so the change will never happen).
ALTER TABLE subscriptions ADD COLUMN pending_product_id TEXT;
ALTER TABLE subscriptions ADD COLUMN pending_base_plan_id TEXT;

-- state 'replaced' with replaced_by NULL: Google says a newer purchase replaced this one
-- (an upgrade, a DEFERRED switch, a resubscribe), but the app hasn't sent the newer token
-- yet. The key stays on; next_check_at is the end of the wait (see subscription.ts).
