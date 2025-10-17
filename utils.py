import re
from datetime import datetime

def escape_markdown(text: str) -> str:
    escape_chars = r'\_*[]()~`>#+-=|{}.!'
    return re.sub(f'([{re.escape(escape_chars)}])', r'\\\1', text)

def iso_now():
    return datetime.utcnow().isoformat()

def parse_iso(dt_str):
    try:
        return datetime.fromisoformat(dt_str)
    except Exception:
        return datetime.utcnow()
