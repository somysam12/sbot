import aiosqlite

DB_PATH = "bot_data.db"

async def init_db():
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executescript("""
        CREATE TABLE IF NOT EXISTS channels (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL
        );

        CREATE TABLE IF NOT EXISTS users (
            user_id INTEGER PRIMARY KEY,
            username TEXT,
            verified INTEGER DEFAULT 0,
            last_key_time TEXT DEFAULT NULL
        );

        CREATE TABLE IF NOT EXISTS keys (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            key_text TEXT NOT NULL,
            duration_days INTEGER NOT NULL,
            meta_name TEXT DEFAULT NULL,
            meta_link TEXT DEFAULT NULL,
            used INTEGER DEFAULT 0,
            added_at TEXT
        );

        CREATE TABLE IF NOT EXISTS sales (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            key_id INTEGER,
            key_text TEXT,
            assigned_at TEXT,
            expires_at TEXT,
            active INTEGER DEFAULT 1,
            message_chat_id INTEGER,
            message_id INTEGER
        );

        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT
        );

        CREATE INDEX IF NOT EXISTS idx_keys_used ON keys(used);
        CREATE INDEX IF NOT EXISTS idx_users_last_key_time ON users(last_key_time);
        CREATE INDEX IF NOT EXISTS idx_sales_user ON sales(user_id);
        """)
        await db.commit()
