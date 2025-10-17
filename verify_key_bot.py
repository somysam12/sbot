import os, logging
from aiogram import Bot, Dispatcher, executor, types
from dotenv import load_dotenv
from db import init_db
from handlers import start_handler, verify_callback, claim_callback, admin_panel, admin_callbacks, admin_message_handler
from aiohttp import web
import asyncio

logging.basicConfig(filename="bot.log", level=logging.INFO,
                    format="%(asctime)s %(levelname)s:%(message)s")

load_dotenv()
BOT_TOKEN = os.getenv("BOT_TOKEN")
ADMIN_ID = os.getenv("ADMIN_ID")
ADMIN_USERNAME = os.getenv("ADMIN_USERNAME")
PORT = int(os.getenv("PORT", 5000))

bot = Bot(token=BOT_TOKEN)
dp = Dispatcher(bot)

# --------------------- REGISTER HANDLERS ---------------------
dp.register_message_handler(start_handler, commands=["start"])
dp.register_message_handler(lambda m: admin_panel(m, ADMIN_ID, ADMIN_USERNAME), commands=["admin"])
dp.register_callback_query_handler(verify_callback, lambda c: c.data=="verify")
dp.register_callback_query_handler(claim_callback, lambda c: c.data=="claim")

dp.register_callback_query_handler(lambda c: admin_callbacks(c, ADMIN_ID, ADMIN_USERNAME))
dp.register_message_handler(admin_message_handler)

# --------------------- STARTUP ---------------------
async def on_startup(dp):
    await init_db()
    print("Bot started successfully!")

# --------------------- WEB SERVER FOR KEEP-ALIVE ---------------------
async def handle_root(request):
    return web.Response(text="Bot is running ✅")

async def start_web_app():
    app = web.Application()
    app.router.add_get("/", handle_root)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", PORT)
    await site.start()
    print(f"Web server running on port {PORT}")

# --------------------- MAIN ---------------------
if __name__ == "__main__":
    loop = asyncio.get_event_loop()
    loop.create_task(start_web_app())
    executor.start_polling(dp, skip_updates=True, on_startup=on_startup)
