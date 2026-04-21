#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -f "$PROJECT_ROOT/build/CMakeCache.txt" ]; then
	CACHE_SOURCE="$(grep '^CMAKE_HOME_DIRECTORY:INTERNAL=' "$PROJECT_ROOT/build/CMakeCache.txt" | cut -d= -f2- || true)"
	if [ -n "$CACHE_SOURCE" ] && [ "$CACHE_SOURCE" != "$PROJECT_ROOT" ]; then
		echo "Cache de CMake invalido detectado ($CACHE_SOURCE). Recreando build..."
		rm -rf "$PROJECT_ROOT/build"
	fi
fi

cmake -S "$PROJECT_ROOT" -B "$PROJECT_ROOT/build"
cmake --build "$PROJECT_ROOT/build" -j
