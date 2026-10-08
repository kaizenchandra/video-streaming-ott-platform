ALTER TABLE session_sequence ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX progress_retention ON watch_progress(updated_at,id);
CREATE INDEX history_retention ON watch_history(last_at,session_id);
CREATE INDEX watch_receipt_retention ON progress_inbox(received_at,event_id);
CREATE INDEX watch_sequence_retention ON session_sequence(created_at,session_id);
