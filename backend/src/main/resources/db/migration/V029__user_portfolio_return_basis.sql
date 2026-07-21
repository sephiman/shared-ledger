-- Per-user visual preference: the base the portfolio Realized % and Total return %
-- are shown over. OPEN_COST (default) = cost of the currently-held (open) lots;
-- TURNOVER = cost of all sold lots / open + sold cost (the pre-existing behavior,
-- which grows with sell-and-rebuy churn). Purely presentational: it changes only the
-- percentage denominators on the Home tile and the Portfolio page, never any euro amount.
ALTER TABLE users
    ADD COLUMN portfolio_return_basis VARCHAR(16) NOT NULL DEFAULT 'OPEN_COST';
