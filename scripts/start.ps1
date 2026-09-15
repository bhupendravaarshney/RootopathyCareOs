$ErrorActionPreference = "Stop"

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
}

docker compose up --build -d
docker compose ps

Write-Host "CareOS: http://localhost:4173/#/M1-05"
Write-Host "Backend liveness: http://localhost:8080/livez"
Write-Host "Backend readiness: http://localhost:8080/readyz"
Write-Host "Mailpit: http://localhost:8025"
Write-Host "MinIO: http://localhost:9001"
