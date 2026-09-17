from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pathlib import Path
import random
import threading
import time
import uuid

app = FastAPI()
GREETING_AUDIO = Path(__file__).with_name("greeting.ogg").read_bytes()
GREETING_TEXT = (
    "Hey! 👋\n"
    "I'm Speaky, your English practice buddy. Let's improve your English in real conversations\n"
    "Ready? Send a voice message and tell me a bit about yourself 😊"
)
NOTE_POOL = [
    "You said: I was in Turkey last summer with my friends.",
    "Better: I went to Turkey last summer with my friends.",
    "We usually say 'went to' here.",
    "Try to speak a bit slower.",
    "Good rhythm. Keep going.",
]
sessions: set[str] = set()
jobs: dict[str, dict] = {}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/sessions", status_code=201)
def create_session() -> dict:
    session_id = str(uuid.uuid4())
    sessions.add(session_id)
    return {"sessionId": session_id, "greeting": {"text": GREETING_TEXT}}


@app.get("/v1/sessions/{session_id}/greeting/audio")
def greeting_audio(session_id: str) -> Response:
    if session_id not in sessions:
        raise HTTPException(status_code=404, detail="unknown session")
    return Response(content=GREETING_AUDIO, media_type="audio/ogg")


@app.post("/v1/clips", status_code=202)
async def create_clip(
    sessionId: str = Form(),
    audio: UploadFile = File(),
) -> dict[str, str]:
    if sessionId not in sessions:
        raise HTTPException(status_code=404, detail="unknown session")
    job_id = str(uuid.uuid4())
    payload = await audio.read()
    jobs[job_id] = {"status": "pending", "audio": payload}

    def finish() -> None:
        time.sleep(0.8)
        job = jobs.get(job_id)
        if job is not None:
            count = random.randint(1, 3)
            job["status"] = "ok"
            job["notes"] = random.sample(NOTE_POOL, count)

    threading.Thread(target=finish, daemon=True).start()
    return {"jobId": job_id}


@app.get("/v1/clips/{job_id}")
def get_clip(job_id: str) -> dict:
    job = jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="unknown job")
    body: dict = {"jobId": job_id, "status": job["status"]}
    if job["status"] == "ok":
        body["result"] = {"notes": job["notes"]}
    return body


@app.get("/v1/clips/{job_id}/audio")
def get_audio(job_id: str) -> Response:
    job = jobs.get(job_id)
    if job is None or job["status"] != "ok":
        return Response(status_code=404)
    return Response(content=job["audio"] or GREETING_AUDIO, media_type="audio/ogg")
