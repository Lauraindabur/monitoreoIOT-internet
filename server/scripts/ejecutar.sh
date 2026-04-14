#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -ne 2 ]; then
  echo "Uso: ./scripts/ejecutar.sh <puerto> <archivoDeLogs>"
  exit 1
fi

mkdir -p "$(dirname "$2")"
"$PROJECT_ROOT/build/server" "$1" "$2"
