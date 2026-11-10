--liquibase formatted sql

--changeset system:2
CREATE TABLE event_sourcing.subscription_checkpoints
(
    subscription_id VARCHAR(200) NOT NULL,
    checkpoint_type VARCHAR(200) NOT NULL,
    checkpoint_data BYTEA NOT NULL,
    PRIMARY KEY (subscription_id, checkpoint_type)
);

--rollback DROP TABLE IF EXISTS event_sourcing.subscription_checkpoints;