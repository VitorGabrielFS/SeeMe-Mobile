$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root "app\src\main\assets"
New-Item -ItemType Directory -Force -Path $assets | Out-Null

$target = Join-Path $assets "hand_landmarker.task"
$url = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

Write-Host "Baixando modelo MediaPipe Hand Landmarker..."
Invoke-WebRequest -Uri $url -OutFile $target
Write-Host "Modelo salvo em $target"
