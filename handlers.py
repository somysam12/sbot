from aiogram import types
from aiogram.types import InlineKeyboardMarkup, InlineKeyboardButton
from db import DB_PATH
import aiosqlite, asyncio, logging
from utils import escape_markdown, iso_now, parse_iso
from datetime import datetime, timedelta

# Admin state tracking
awaiting_keys = {}  # admin user_id -> state

# --------------------- USER HANDLERS ---------------------
async def start_handler(message: types.Message):
    keyboard = InlineKeyboardMarkup().add(
        InlineKeyboardButton("Verify ✅", callback_data="verify"),
        InlineKeyboardButton("Claim 🎁", callback_data="claim")
    )
    await message.answer("Welcome! Please verify or claim a key:", reply_markup=keyboard)

async def verify_callback(callback: types.CallbackQuery):
    async with aiosqlite.connect(DB_PATH) as db:
        async with db.execute("SELECT username FROM channels") as cur:
            channels = await cur.fetchall()
    if not channels:
        async with aiosqlite.connect(DB_PATH) as db:
            await db.execute(
                "INSERT OR REPLACE INTO users(user_id, username, verified) VALUES(?,?,?)",
                (callback.from_user.id, callback.from_user.username, 1)
            )
            await db.commit()
        await callback.answer("Verified! You can now claim keys ✅", show_alert=True)
    else:
        await callback.answer("No channels configured, automatically verified ✅", show_alert=True)

async def claim_callback(callback: types.CallbackQuery):
    try:
        async with aiosqlite.connect(DB_PATH) as db:
            async with db.execute("SELECT value FROM settings WHERE key='cooldown_hours'") as cur:
                row = await cur.fetchone()
            cooldown = int(row[0]) if row else 48

            async with db.execute("SELECT last_key_time FROM users WHERE user_id=?", (callback.from_user.id,)) as cur:
                row = await cur.fetchone()
                last_key = parse_iso(row[0]) if row and row[0] else None

            if last_key and datetime.utcnow() - last_key < timedelta(hours=cooldown):
                await callback.answer(f"Please wait {cooldown} hours between claims.", show_alert=True)
                return

            async with db.execute("SELECT id, key_text, duration_days FROM keys WHERE used=0 ORDER BY id LIMIT 1") as cur:
                key_row = await cur.fetchone()
            if not key_row:
                await callback.answer("No keys available.", show_alert=True)
                return

            key_id, key_text, duration = key_row
            expires_at = (datetime.utcnow() + timedelta(days=duration)).isoformat()
            now = iso_now()

            # Assign key
            await db.execute("UPDATE keys SET used=1 WHERE id=?", (key_id,))
            await db.execute("INSERT OR REPLACE INTO users(user_id, username, verified, last_key_time) VALUES(?,?,?,?)",
                             (callback.from_user.id, callback.from_user.username, 1, now))
            await db.execute("INSERT INTO sales(user_id, key_id, key_text, assigned_at, expires_at) VALUES(?,?,?,?,?)",
                             (callback.from_user.id, key_id, key_text, now, expires_at))
            await db.commit()
            await callback.answer(f"Here is your key: {key_text}\nValid for {duration} days ✅", show_alert=True)
    except Exception as e:
        logging.exception("Claim flow error")
        await callback.answer("An error occurred. Try again later.", show_alert=True)

# --------------------- ADMIN PANEL ---------------------
async def admin_panel(message: types.Message, ADMIN_ID, ADMIN_USERNAME):
    if message.from_user.id != int(ADMIN_ID) and message.from_user.username != ADMIN_USERNAME:
        await message.answer("Unauthorized ❌")
        return

    keyboard = InlineKeyboardMarkup(row_width=2)
    keyboard.add(
        InlineKeyboardButton("Add Keys", callback_data="admin_add_keys"),
        InlineKeyboardButton("View Stats", callback_data="admin_stats"),
        InlineKeyboardButton("View Keys", callback_data="view_keys"),
        InlineKeyboardButton("List Users", callback_data="list_users"),
        InlineKeyboardButton("Reset Cooldown", callback_data="reset_cooldown")
    )
    await message.answer("Admin Panel:", reply_markup=keyboard)

async def admin_callbacks(callback: types.CallbackQuery, ADMIN_ID, ADMIN_USERNAME):
    user_id = callback.from_user.id
    data = callback.data

    if user_id != int(ADMIN_ID) and callback.from_user.username != ADMIN_USERNAME:
        await callback.answer("Unauthorized ❌", show_alert=True)
        return

    # -- Implement all callbacks: add keys, view stats, view keys, delete key/all, list users, reset cooldown
    # Refer to the full previous handlers.py for complete callback implementations
    pass

async def admin_message_handler(message: types.Message):
    # Handle awaiting state messages for add keys, reset single user, etc.
    pass
