-- Lavis Context Engineering: Turn Context Support
-- Add turn_id, image_id, and compression tracking to session_messages

-- Add turn_id column for grouping messages by turn
ALTER TABLE session_messages ADD COLUMN turn_id TEXT;

-- Add image_id for referencing images in placeholders
ALTER TABLE session_messages ADD COLUMN image_id TEXT;

-- Add compression flag
ALTER TABLE session_messages ADD COLUMN is_compressed INTEGER DEFAULT 0;

-- Add position tracking within turn (for first/last identification)
ALTER TABLE session_messages ADD COLUMN turn_position INTEGER;

-- Create indexes for efficient querying
CREATE INDEX idx_messages_turn_id ON session_messages(turn_id);
CREATE INDEX idx_messages_image_id ON session_messages(image_id);
CREATE INDEX idx_messages_turn_position ON session_messages(session_id, turn_id, turn_position);
