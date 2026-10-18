"""Control page: http://127.0.0.1:8000  (run with: .venv/bin/python app.py)"""
import warnings
from pathlib import Path
from typing import Optional

# macOS's system Python uses LibreSSL; urllib3 warns about it on import. Harmless here.
warnings.filterwarnings("ignore", message="urllib3 v2 only supports OpenSSL")

import uvicorn  # noqa: E402
from fastapi import FastAPI, HTTPException  # noqa: E402
from fastapi.responses import FileResponse  # noqa: E402
from pydantic import BaseModel  # noqa: E402

from hinge.bot import Bot  # noqa: E402

ROOT = Path(__file__).resolve().parent
bot = Bot(ROOT)
app = FastAPI()


class Options(BaseModel):
    dry_run: Optional[bool] = None
    mode: Optional[str] = None
    daily_cap: Optional[int] = None
    ai_comments: Optional[bool] = None


class Decision(BaseModel):
    id: str
    action: str
    comment: Optional[str] = None


@app.get("/")
def index():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/api/status")
def status():
    return bot.status()


@app.post("/api/start")
def start():
    return {"started": bot.start()}


@app.post("/api/stop")
def stop():
    bot.stop()
    return {"ok": True}


@app.post("/api/options")
def options(opts: Options):
    if opts.mode is not None and opts.mode not in ("approve", "auto"):
        raise HTTPException(400, "mode must be 'approve' or 'auto'")
    if opts.daily_cap is not None and opts.daily_cap < 0:
        raise HTTPException(400, "daily_cap must be 0 (no limit) or more")
    bot.update_options(
        dry_run=opts.dry_run, mode=opts.mode, daily_cap=opts.daily_cap, ai_comments=opts.ai_comments
    )
    return bot.status()


@app.post("/api/decide")
def decide(decision: Decision):
    if not bot.decide(decision.id, decision.action, decision.comment):
        raise HTTPException(409, "That profile is no longer waiting for a decision")
    return {"ok": True}


if __name__ == "__main__":
    # access_log off: the page polls /api/status every 1.5s, which would flood the terminal.
    uvicorn.run(app, host="127.0.0.1", port=8000, access_log=False)
