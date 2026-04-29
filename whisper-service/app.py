"""Whisper transcription microservice — OpenAI-compatible API backed by faster-whisper."""

import logging
import os
import tempfile
from typing import Optional

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

WHISPER_MODEL = os.getenv("WHISPER_MODEL", "large-v3")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cuda")
WHISPER_COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "float16")
WHISPER_LANGUAGE = os.getenv("WHISPER_LANGUAGE", "ca")

app = FastAPI(title="Whisper Transcription Service (OpenAI-compatible)")

model: Optional[WhisperModel] = None


class HealthResponse(BaseModel):
    status: str
    model: str
    device: str
    compute_type: str


@app.on_event("startup")
def load_model() -> None:
    global model
    logger.info(
        "Loading Whisper model=%s device=%s compute_type=%s",
        WHISPER_MODEL,
        WHISPER_DEVICE,
        WHISPER_COMPUTE_TYPE,
    )
    model = WhisperModel(
        WHISPER_MODEL,
        device=WHISPER_DEVICE,
        compute_type=WHISPER_COMPUTE_TYPE,
    )
    logger.info("Whisper model loaded successfully")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok" if model is not None else "loading",
        model=WHISPER_MODEL,
        device=WHISPER_DEVICE,
        compute_type=WHISPER_COMPUTE_TYPE,
    )


def _transcribe(audio_bytes: bytes, language: str | None) -> tuple[str, list[dict], str, float]:
    """Run transcription and return (text, segments, language, duration)."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=True) as tmp:
        tmp.write(audio_bytes)
        tmp.flush()

        segments_iter, info = model.transcribe(
            tmp.name,
            language=language if language else None,
            beam_size=5,
            vad_filter=True,
            vad_parameters=dict(
                min_silence_duration_ms=500,
                speech_pad_ms=200,
            ),
        )

        segments = []
        full_text_parts = []
        for seg in segments_iter:
            segments.append(
                {
                    "start": round(seg.start, 3),
                    "end": round(seg.end, 3),
                    "text": seg.text.strip(),
                }
            )
            full_text_parts.append(seg.text.strip())

    return " ".join(full_text_parts), segments, info.language, round(info.duration, 3)


@app.post("/v1/audio/transcriptions")
async def openai_transcribe(
    file: UploadFile = File(...),
    model: str = Form(default="whisper-1"),
    language: str = Form(default=None),
    response_format: str = Form(default="json"),
) -> JSONResponse:
    """OpenAI-compatible transcription endpoint used by Spring AI."""
    audio_bytes = await file.read()
    lang = language if language else WHISPER_LANGUAGE
    text, segments, detected_lang, duration = _transcribe(audio_bytes, lang)

    if response_format == "text":
        return JSONResponse(content=text, media_type="text/plain")

    if response_format == "verbose_json":
        return JSONResponse(
            content={
                "text": text,
                "language": detected_lang,
                "duration": duration,
                "segments": segments,
            }
        )

    return JSONResponse(content={"text": text})
