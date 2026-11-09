CREATE INDEX IF NOT EXISTS event_store_request_id
    ON event_store (request_id, breadcrumbs);

CREATE INDEX IF NOT EXISTS command_store_request_id
    ON command_store (request_id, breadcrumbs);
