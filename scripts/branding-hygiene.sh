#!/usr/bin/env bash
set -euo pipefail

# scripts/branding-hygiene.sh
# Verifies that no inherited template or third-party branding literals exist in app/src/main.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

TARGETS=("app/src/main")
if [ -d "res" ]; then
    TARGETS+=("res")
fi

echo "Running branding hygiene check on ${TARGETS[*]}..."

FORBIDDEN_WORD_PATTERN='AI Assistant|Developer:|GrapheneOS'
FORBIDDEN_PROVIDER_PATTERN='hermes_api_key|openrouter|groq|api\.openai\.com|brave\.com|exa\.ai|langsearch'

if grep -rnE -w "$FORBIDDEN_WORD_PATTERN" "${TARGETS[@]}"; then
    echo "ERROR: Forbidden branding literals detected above!" >&2
    echo "Check failed: 'AI Assistant', 'Developer:', and 'GrapheneOS' are forbidden in ${TARGETS[*]}." >&2
    exit 1
fi

if grep -rnEi "$FORBIDDEN_PROVIDER_PATTERN" "${TARGETS[@]}"; then
    echo "ERROR: Forbidden provider literals detected above!" >&2
    echo "Check failed: forbidden provider literals found in ${TARGETS[*]}." >&2
    exit 1
fi

echo "Branding hygiene check passed: no forbidden literals found."
exit 0
