$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root "app\src\main\assets"
New-Item -ItemType Directory -Force -Path $assets | Out-Null

$handTarget = Join-Path $assets "hand_landmarker.task"
$handUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
$faceTarget = Join-Path $assets "face_landmarker.task"
$faceUrl = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

Write-Host "Baixando modelo MediaPipe Hand Landmarker..."
Invoke-WebRequest -Uri $handUrl -OutFile $handTarget
Write-Host "Modelo salvo em $handTarget"

Write-Host "Baixando modelo MediaPipe Face Landmarker..."
Invoke-WebRequest -Uri $faceUrl -OutFile $faceTarget
Write-Host "Modelo salvo em $faceTarget"
