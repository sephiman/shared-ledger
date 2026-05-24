CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    password_hash   TEXT NOT NULL,
    locale          VARCHAR(2) NOT NULL DEFAULT 'en',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_users_email_lower ON users (LOWER(email));

CREATE TABLE households (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(120) NOT NULL,
    currency          VARCHAR(3) NOT NULL DEFAULT 'EUR',
    default_locale    VARCHAR(2) NOT NULL DEFAULT 'en',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE household_members (
    household_id  UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role          VARCHAR(16) NOT NULL CHECK (role IN ('owner','member')),
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, user_id)
);
CREATE INDEX idx_household_members_user ON household_members(user_id);

CREATE TABLE household_invitations (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    email                 VARCHAR(255),
    token_hash            TEXT NOT NULL UNIQUE,
    role                  VARCHAR(16) NOT NULL CHECK (role IN ('owner','member')),
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ NOT NULL,
    accepted_at           TIMESTAMPTZ,
    accepted_by_user_id   UUID REFERENCES users(id),
    revoked_at            TIMESTAMPTZ
);
CREATE INDEX idx_invitations_household ON household_invitations(household_id);
CREATE UNIQUE INDEX idx_invitations_active_email
    ON household_invitations(household_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL AND email IS NOT NULL;
