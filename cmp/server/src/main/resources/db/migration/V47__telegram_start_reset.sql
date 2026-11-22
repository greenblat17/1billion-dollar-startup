-- Keep a monotonic command boundary outside erasable student state.
CREATE TABLE telegram_start_reset (
    chat_id BIGINT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    input_digest TEXT NOT NULL
);
INSERT INTO telegram_start_reset(chat_id,message_id,input_digest)
SELECT chat_id,message_id,input_digest FROM telegram_introduction;
