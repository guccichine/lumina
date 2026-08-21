#!/bin/sh
set -e
cd "$(dirname "$0")"
export PYTHONUNBUFFERED=1
exec python3 server.py
