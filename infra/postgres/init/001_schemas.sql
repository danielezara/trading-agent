-- Local schema skeleton for the modular monolith.
-- The backend does not connect yet. This database cannot store another
-- operational state: trading dispatch stays impossible until a later phase
-- replaces this constraint on purpose.

CREATE SCHEMA IF NOT EXISTS control;
CREATE SCHEMA IF NOT EXISTS market;
CREATE SCHEMA IF NOT EXISTS corp;
CREATE SCHEMA IF NOT EXISTS decision;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS oms;
CREATE SCHEMA IF NOT EXISTS infra;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS read;

COMMENT ON SCHEMA control IS 'Configuration, evidence gate, operational state';
COMMENT ON SCHEMA market IS 'Raw data, documents, bars, feature snapshots';
COMMENT ON SCHEMA corp IS 'Corporate actions and instrument mapping';
COMMENT ON SCHEMA decision IS 'Decision cycles, signals, risk checks';
COMMENT ON SCHEMA ledger IS 'Journal, reservations, positions';
COMMENT ON SCHEMA oms IS 'Order intents, dispatch attempts, fills';
COMMENT ON SCHEMA infra IS 'Outbox, inbox, scheduler, account lease';
COMMENT ON SCHEMA audit IS 'Tamper-evident audit events';
COMMENT ON SCHEMA read IS 'Rebuildable dashboard projections';

CREATE TABLE control.operational_state (
    singleton boolean PRIMARY KEY DEFAULT TRUE,
    state text NOT NULL,
    reason text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT operational_state_singleton CHECK (singleton),
    CONSTRAINT operational_state_observation_only CHECK (state = 'OBSERVATION'),
    CONSTRAINT operational_state_reason_present CHECK (length(reason) > 0)
);

INSERT INTO control.operational_state (singleton, state, reason)
VALUES (
    TRUE,
    'OBSERVATION',
    'skeleton: broker dispatch disabled, evidence gate unverified'
);
