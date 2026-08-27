#!/usr/bin/env bash
# Blocks `git commit` when staged content contains key-shaped strings.
# devflowai is a public repo; a leaked key is unrecoverable.
set -uo pipefail

payload=$(cat)
command=$(printf '%s' "$payload" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)

case "$command" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

if git diff --cached 2>/dev/null | grep -qiE 'sk-ant-[A-Za-z0-9_-]{16,}|ANTHROPIC_API_KEY[[:space:]]*=[[:space:]]*[A-Za-z0-9]'; then
  echo "BLOCKED: staged content contains a key-shaped string. devflowai is a public repo." >&2
  exit 2
fi
exit 0
