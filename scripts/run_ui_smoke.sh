#!/usr/bin/env bash
set -euo pipefail
if ! python3 scripts/smoke_android.py > "$RUNNER_TEMP/ui-smoke.log" 2>&1; then
  cat "$RUNNER_TEMP/ui-smoke.log"
  python3 -c 'from pathlib import Path; import os; s=Path(os.environ["RUNNER_TEMP"],"ui-smoke.log").read_text(errors="replace")[-12000:]; print("::error::" + s.replace("%","%25").replace("\r","%0D").replace("\n","%0A"))'
  exit 1
fi
cat "$RUNNER_TEMP/ui-smoke.log"
