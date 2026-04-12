param(
    [string]$Model = "base"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$submoduleRoot = Join-Path $repoRoot "third_party\whisper.cpp"
$downloadScript = Join-Path $submoduleRoot "models\download-ggml-model.cmd"
$modelsDir = Join-Path $repoRoot "app\src\main\assets\models"

if (-not (Test-Path $downloadScript)) {
    throw "whisper.cpp submodule is missing. Run: git submodule update --init --depth 1 third_party/whisper.cpp"
}

New-Item -ItemType Directory -Force $modelsDir | Out-Null

Push-Location $modelsDir
try {
    & $downloadScript $Model $modelsDir
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download whisper model '$Model'"
    }
} finally {
    Pop-Location
}

Write-Host "Model ready under $modelsDir"
