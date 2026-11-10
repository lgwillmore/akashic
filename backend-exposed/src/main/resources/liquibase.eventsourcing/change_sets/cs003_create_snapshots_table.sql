--liquibase formatted sql

--changeset eventsourcing:3
-- Preconditions: schema event_sourcing should already exist from earlier change sets
CREATE TABLE event_sourcing.snapshots
(
    aggregate_type  VARCHAR(200) NOT NULL,
    aggregate_id    VARCHAR(300) NOT NULL,
    aggregate_state_schema_version VARCHAR(200) NOT NULL,
    version                     BIGINT                   NOT NULL,
    source_event_occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    source_event_prepared_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    generated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    snapshot_data               BYTEA                    NOT NULL,
    CONSTRAINT snapshots_pk PRIMARY KEY
        (aggregate_type, aggregate_id, aggregate_state_schema_version, version)
);

CREATE INDEX snapshots_type_state_schema_idx
    ON event_sourcing.snapshots (aggregate_type, aggregate_state_schema_version);

--rollback DROP INDEX IF EXISTS event_sourcing.snapshots_type_state_schema_idx;
--rollback DROP TABLE IF EXISTS event_sourcing.snapshots;
