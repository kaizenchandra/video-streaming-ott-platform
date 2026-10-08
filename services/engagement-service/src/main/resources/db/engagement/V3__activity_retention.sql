CREATE INDEX notice_retention ON notification_intent(created_at,id) WHERE state IN('DELIVERED','FAILED','SKIPPED');
CREATE INDEX notice_source_retention ON notification_source(received_at,event_id);
CREATE INDEX ad_decision_retention ON ad_decision(created_at,id) WHERE state IN('ACTIVE','SKIPPED');
CREATE INDEX ad_receipt_retention ON ad_receipt(received_at,event_id);
