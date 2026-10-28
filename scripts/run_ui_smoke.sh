#!/usr/bin/env bash
set -euo pipefail
emit_preview() {
  # Contains only sample data; the full screenshot is also saved as an artifact.
  if [ -f ui-smoke-preview.jpg ]; then
    python3 -c 'import base64; s=base64.b64encode(open("ui-smoke-preview.jpg", "rb").read()).decode(); print("::notice title=UI preview::" + s if len(s) < 55000 else "Preview too large for annotation")'
  fi
}
if ! python3 scripts/smoke_android.py > "$RUNNER_TEMP/ui-smoke.log" 2>&1; then
  cat "$RUNNER_TEMP/ui-smoke.log"
  emit_preview
  python3 -c 'from pathlib import Path; import os; s=Path(os.environ["RUNNER_TEMP"],"ui-smoke.log").read_text(errors="replace")[-12000:]; print("::error::" + s.replace("%","%25").replace("\r","%0D").replace("\n","%0A"))'
  exit 1
fi
cat "$RUNNER_TEMP/ui-smoke.log"
emit_preview
