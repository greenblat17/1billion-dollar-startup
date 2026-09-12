from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse, Response
import threading
import time
import uuid

app = FastAPI()
jobs: dict[str, dict] = {}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/clips", status_code=202)
async def create_clip(
    sessionId: str = Form(),
    audio: UploadFile = File(),
) -> dict[str, str]:
    job_id = str(uuid.uuid4())
    payload = await audio.read()
    jobs[job_id] = {"status": "pending", "audio": payload, "sessionId": sessionId}

    def finish() -> None:
        time.sleep(0.8)
        job = jobs.get(job_id)
        if job is not None:
            job["status"] = "ok"

    threading.Thread(target=finish, daemon=True).start()
    return {"jobId": job_id}


@app.get("/v1/clips/{job_id}")
def get_clip(job_id: str) -> JSONResponse | dict[str, str]:
    job = jobs.get(job_id)
    if job is None:
        return JSONResponse(
            status_code=404,
            content={
                "jobId": job_id,
                "status": "error",
                "error": {"code": "not_found", "message": "unknown job"},
            },
        )
    return {"jobId": job_id, "status": job["status"]}


@app.get("/v1/clips/{job_id}/audio")
def get_audio(job_id: str) -> Response:
    job = jobs.get(job_id)
    if job is None or job["status"] != "ok":
        return Response(status_code=404)
    return Response(content=job["audio"], media_type="audio/ogg")
