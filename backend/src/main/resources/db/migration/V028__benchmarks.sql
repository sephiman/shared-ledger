-- Market benchmarks: a data-driven registry of reference indices/assets plus their
-- stored daily closes, used to overlay a normalized time-weighted-return (TWR) line on
-- the portfolio ROI chart. Kept independent of price_history (holdings) on purpose:
-- benchmark series are their own concern and shared across every household. Closes are
-- stored in the benchmark's own quote currency and converted to the base currency at
-- read time via fx_rates, so the overlay never silently embeds EUR/USD drift.
CREATE TABLE benchmark (
    key             VARCHAR(32) PRIMARY KEY,
    -- Keyless source the background job fetches closes from ('yahoo' for indices/ETFs/
    -- commodities, 'binance' for crypto). Decoupled from the household equity provider.
    source_provider VARCHAR(24) NOT NULL,
    source_symbol   VARCHAR(120) NOT NULL,
    -- Currency the source quotes the benchmark in; converted to base (EUR) at read time.
    currency        VARCHAR(3) NOT NULL,
    -- How to fetch: 'equity' (Yahoo chart) or 'crypto' (Binance USDT klines).
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('equity','crypto')),
    -- Off benchmarks are hidden from the selector and skipped by the refresh job.
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One close per (benchmark, date), in the benchmark's own currency. Non-trading days are
-- never stored; reads forward-fill from the last row <= date. The background job upserts
-- by the unique key below, so overlapping/repeat runs self-heal.
CREATE TABLE benchmark_price (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benchmark_key   VARCHAR(32) NOT NULL REFERENCES benchmark(key) ON DELETE CASCADE,
    price_date      DATE NOT NULL,
    close           NUMERIC(28,12) NOT NULL CHECK (close >= 0),
    as_of           TIMESTAMPTZ NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_benchmark_price_unique
    ON benchmark_price(benchmark_key, price_date);

-- Initial, extensible set. All quoted in USD and converted to base via fx_rates: the S&P
-- 500 (^GSPC) and Gold futures (GC=F) come from Yahoo, MSCI World via the iShares URTH
-- ETF, and Bitcoin as Binance BTCUSDT (USDT treated as USD). Adding a benchmark is one row
-- here plus one i18n label — no chart code changes.
INSERT INTO benchmark (key, source_provider, source_symbol, currency, kind, sort_order) VALUES
    ('sp500',      'yahoo',   '^GSPC',   'USD', 'equity', 10),
    ('msci_world', 'yahoo',   'URTH',    'USD', 'equity', 20),
    ('gold',       'yahoo',   'GC=F',    'USD', 'equity', 30),
    ('bitcoin',    'binance', 'BTCUSDT', 'USD', 'crypto', 40);
