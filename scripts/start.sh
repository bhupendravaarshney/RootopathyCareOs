#!/usr/bin/env sh
set -eu

if [ ! -f .env ]; then
  cp .env.example .env
fi

docker compose up --build -d
docker compose ps

printf '%s\n' \
  'CareOS: http://localhost:4173/#/M1-05' \
  'Backend health: http://localhost:8080/actuator/health' \
  'Mailpit: http://localhost:8025' \
  'MinIO: http://localhost:9001'
