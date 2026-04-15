$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    git submodule update --init --depth 1 third_party/whisper.cpp
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to initialize third_party/whisper.cpp"
    }
} finally {
    Pop-Location
}

Write-Host "whisper.cpp submodule is ready."
