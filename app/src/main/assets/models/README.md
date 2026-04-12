# Whisper Models

The built-in offline voice engine expects a Whisper model under:

`app/src/main/assets/models/`

Recommended first model for Chinese input:

- `ggml-base.bin`

Smaller fallback:

- `ggml-tiny.bin`

You can download a model with:

`powershell -ExecutionPolicy Bypass -File scripts\download_whisper_model.ps1`

Model binaries are intentionally ignored by Git.
