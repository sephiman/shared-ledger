-- Portfolio: individual holdings (crypto / ETF / stock / fund) with purchase lots,
-- provider-driven price history, FX rates, and per-snapshot frozen valuations.
CREATE TABLE holdings (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    asset_class           VARCHAR(16) NOT NULL CHECK (asset_class IN ('crypto','etf','stock','fund')),
    symbol                VARCHAR(32) NOT NULL,
    label                 VARCHAR(120),
    native_currency       VARCHAR(3) NOT NULL,
    isin                  VARCHAR(12),
    provider              VARCHAR(24) CHECK (provider IN ('coingecko','yahoo','eodhd','twelvedata')),
    provider_symbol       VARCHAR(120),
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    -- A holding is either fully linked to a provider or not linked at all.
    CONSTRAINT holdings_link_chk CHECK ((provider IS NULL) = (provider_symbol IS NULL))
);
CREATE UNIQUE INDEX idx_holdings_household_class_symbol
    ON holdings(household_id, asset_class, symbol)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_holdings_household
    ON holdings(household_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_holdings_linked
    ON holdings(provider, provider_symbol)
    WHERE deleted_at IS NULL AND provider IS NOT NULL;

-- Ordered BUY/SELL movement ledger. Net quantity, remaining cost basis and realized
-- P&L are always computed by replaying the ledger (FIFO); nothing is denormalized.
CREATE TABLE holding_lots (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holding_id            UUID NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
    type                  VARCHAR(4) NOT NULL DEFAULT 'BUY' CHECK (type IN ('BUY','SELL')),
    traded_on             DATE NOT NULL,
    quantity              NUMERIC(28,12) NOT NULL CHECK (quantity > 0),
    -- Purchase price for BUY, sale price for SELL.
    unit_price            NUMERIC(28,12) NOT NULL CHECK (unit_price >= 0),
    currency              VARCHAR(3) NOT NULL,
    fee                   NUMERIC(28,12) CHECK (fee >= 0),
    -- Frozen at trade registration; 1 when currency is the household base currency.
    fx_rate_to_base       NUMERIC(18,8) NOT NULL CHECK (fx_rate_to_base > 0),
    note                  VARCHAR(500),
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX idx_holding_lots_holding_date
    ON holding_lots(holding_id, traded_on)
    WHERE deleted_at IS NULL;

-- Daily instrument prices, keyed by provider coordinates (shared across households).
CREATE TABLE price_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider          VARCHAR(24) NOT NULL,
    provider_symbol   VARCHAR(120) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    price             NUMERIC(28,12) NOT NULL CHECK (price >= 0),
    price_date        DATE NOT NULL,
    as_of             TIMESTAMPTZ NOT NULL,
    fetched_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Idempotency: refresh jobs upsert by this key; one row per instrument per day.
CREATE UNIQUE INDEX idx_price_history_unique
    ON price_history(provider, provider_symbol, currency, price_date);

-- Daily FX rates into the household base currency.
CREATE TABLE fx_rates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider        VARCHAR(24) NOT NULL,
    base_currency   VARCHAR(3) NOT NULL,
    quote_currency  VARCHAR(3) NOT NULL,
    rate            NUMERIC(18,8) NOT NULL CHECK (rate > 0),
    rate_date       DATE NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_fx_rates_unique
    ON fx_rates(provider, base_currency, quote_currency, rate_date);

-- Per-holding valuation frozen when a net-worth snapshot is created. Write-once:
-- rows are only replaced wholesale when the snapshot itself is edited.
CREATE TABLE holding_valuations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id     UUID NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
    holding_id      UUID NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
    quantity        NUMERIC(28,12) NOT NULL,
    unit_price      NUMERIC(28,12),
    price_currency  VARCHAR(3),
    price_as_of     DATE,
    fx_rate         NUMERIC(18,8),
    value_base      NUMERIC(15,2) NOT NULL,
    stale           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT idx_holding_valuations_unique UNIQUE (snapshot_id, holding_id)
);
CREATE INDEX idx_holding_valuations_snapshot ON holding_valuations(snapshot_id);

-- Snapshot class values now record whether they came from the portfolio (computed)
-- or were entered by hand (overridden). All pre-existing rows were manual.
ALTER TABLE snapshot_asset_values
    ADD COLUMN value_source VARCHAR(16) NOT NULL DEFAULT 'overridden'
    CHECK (value_source IN ('computed','overridden'));
