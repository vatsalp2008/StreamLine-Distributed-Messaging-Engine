-- Records when a message was last rewritten.
--
-- An edit previously overwrote the text with no trace, so a reader had no way
-- to tell an original message from a corrected one, and the client's "(edited)"
-- marker only survived as long as the page stayed open.
--
-- Nullable on purpose: null means never edited, which is the common case and
-- avoids backfilling every existing row with a misleading timestamp.
ALTER TABLE messages ADD COLUMN edited_at TIMESTAMP(6) WITH TIME ZONE;
