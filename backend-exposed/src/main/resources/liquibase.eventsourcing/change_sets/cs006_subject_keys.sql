--liquibase formatted sql

--changeset eventsourcing:6
-- Per-subject data keys for akashic-crypto, stored wrapped. A shredded key keeps its row as a
-- tombstone with the key material removed, so sealed values under it read as shredded rather than
-- as an unknown key. A subject has at most one active key.
CREATE TABLE event_sourcing.subject_keys
(
    key_id          VARCHAR(100)             NOT NULL PRIMARY KEY,
    subject_id      VARCHAR(300)             NOT NULL,
    wrapping_key_id VARCHAR(200),
    wrapped_key     BYTEA,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    shredded_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT subject_keys_state_check CHECK (
        (shredded_at IS NULL AND wrapping_key_id IS NOT NULL AND wrapped_key IS NOT NULL)
        OR
        (shredded_at IS NOT NULL AND wrapping_key_id IS NULL AND wrapped_key IS NULL)
    )
);

CREATE UNIQUE INDEX subject_keys_active_subject_idx
    ON event_sourcing.subject_keys (subject_id) WHERE shredded_at IS NULL;
CREATE INDEX subject_keys_subject_idx ON event_sourcing.subject_keys (subject_id);

--rollback DROP INDEX IF EXISTS event_sourcing.subject_keys_subject_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.subject_keys_active_subject_idx;
--rollback DROP TABLE IF EXISTS event_sourcing.subject_keys;
