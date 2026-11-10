--liquibase formatted sql

--changeset system:1
CREATE SCHEMA IF NOT EXISTS event_sourcing;

CREATE TABLE event_sourcing.events
(
    id                       BIGSERIAL PRIMARY KEY,
    aggregate_id             VARCHAR(300)             NOT NULL,
    aggregate_type           VARCHAR(100)             NOT NULL,
    event_id                 UUID                     NOT NULL,
    event_type               VARCHAR(200)             NOT NULL,
    event_data               BYTEA                    NOT NULL,
    version                  BIGINT                   NOT NULL,
    command_id               VARCHAR(300)             NOT NULL,
    correlation_id           VARCHAR(300),
    caused_by_aggregate_type VARCHAR(100),
    caused_by_aggregate_id   VARCHAR(300),
    caused_by_event_id       UUID,
    occurred_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    prepared_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT events_caused_by_check CHECK (
        (caused_by_aggregate_type IS NULL AND caused_by_aggregate_id IS NULL AND caused_by_event_id IS NULL)
        OR
        (caused_by_aggregate_type IS NOT NULL AND caused_by_aggregate_id IS NOT NULL AND caused_by_event_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX events_event_id_idx ON event_sourcing.events (event_id);
CREATE UNIQUE INDEX events_aggregate_version_idx ON event_sourcing.events (aggregate_type, aggregate_id, version);
CREATE INDEX events_aggregate_type_id_idx ON event_sourcing.events (aggregate_type, aggregate_id);
CREATE INDEX events_aggregate_id_idx ON event_sourcing.events (aggregate_id);
CREATE INDEX events_id_idx ON event_sourcing.events (id);
CREATE INDEX events_aggregate_type_idx ON event_sourcing.events (aggregate_type);
CREATE INDEX events_command_id_idx ON event_sourcing.events (command_id);
CREATE INDEX events_correlation_id_idx ON event_sourcing.events (correlation_id);

--rollback DROP INDEX IF EXISTS event_sourcing.events_correlation_id_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_command_id_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_aggregate_type_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_id_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_aggregate_id_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_aggregate_type_id_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_aggregate_version_idx;
--rollback DROP INDEX IF EXISTS event_sourcing.events_event_id_idx;
--rollback DROP TABLE IF EXISTS event_sourcing.events;
--rollback DROP SCHEMA IF EXISTS event_sourcing CASCADE;
