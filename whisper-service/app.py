"""Whisper transcription microservice using faster-whisper."""

import io
import logging
import os
import tempfile
from typing import Optional

from fastapi import FastAPI, File, Form, UploadFile
from faster_whisper import WhisperModel
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

WHISPER_MODEL = os.getenv("WHISPER_MODEL", "large-v3")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cuda")
WHISPER_COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "float16")
WHISPER_LANGUAGE = os.getenv("WHISPER_LANGUAGE", "ca")

app = FastAPI(title="Whisper Transcription Service")

model: Optional[WhisperModel] = None


class TranscriptionResponse(BaseModel):
    text: str
    language: str
    duration: float
    segments: list[dict]


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


@app.post("/transcribe", response_model=TranscriptionResponse)
async def transcribe(
    audio: UploadFile = File(...),
    language: str = Form(default=WHISPER_LANGUAGE),
) -> TranscriptionResponse:
    audio_bytes = await audio.read()

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
        for segment in segments_iter:
            segments.append(
                {
                    "start": round(segment.start, 3),
                    "end": round(segment.end, 3),
                    "text": segment.text.strip(),
                }
            )
            full_text_parts.append(segment.text.strip())

    return TranscriptionResponse(
        text=" ".join(full_text_parts),
        language=info.language,
        duration=round(info.duration, 3),
        segments=segments,
    )
