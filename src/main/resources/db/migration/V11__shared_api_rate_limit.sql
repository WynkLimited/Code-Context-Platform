-- No API-key material is persisted. One current window per authenticated team survives restarts.
CREATE TABLE api_rate_limit_windows (
    team text PRIMARY KEY,
    window_start timestamptz NOT NULL,
    used bigint NOT NULL CHECK (used > 0)
);
