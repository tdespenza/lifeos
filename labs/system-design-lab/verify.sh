#!/usr/bin/env bash
# Deterministic, dependency-free structural validation for the System Design Lab.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  printf 'Usage: bash labs/system-design-lab/verify.sh\n' >&2
  exit 64
fi

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
required_roots=(README.md INDEX.md template.md)
systems=(
  url-shortener
  notification-system
  search-engine
  distributed-scheduler
  recommendation-engine
  rate-limiter
  chat-messaging
  video-session
  document-storage
  event-analytics
)
headings=(
  Requirements
  'API shape'
  'Data model'
  'Scaling and partitioning'
  'Bottlenecks and tradeoffs'
  'Failure and recovery'
  Observability
  'Security and privacy'
)

for required in "${required_roots[@]}"; do
  if [[ ! -s "$lab_root/$required" ]]; then
    printf 'Missing or empty required lab file: %s\n' "$required" >&2
    exit 1
  fi
done

system_document_count="$(find "$lab_root/systems" -maxdepth 1 -type f -name '*.md' | wc -l | tr -d '[:space:]')"
if [[ "$system_document_count" -ne "${#systems[@]}" ]]; then
  printf 'Expected %d mini-system documents but found %s.\n' "${#systems[@]}" "$system_document_count" >&2
  exit 1
fi

for heading in "${headings[@]}"; do
  if ! grep -Fqx "## $heading" "$lab_root/template.md"; then
    printf 'Reusable template is missing heading %q.\n' "$heading" >&2
    exit 1
  fi
done

for system in "${systems[@]}"; do
  document="$lab_root/systems/$system.md"
  if [[ ! -s "$document" ]]; then
    printf 'Missing or empty mini-system document: systems/%s.md\n' "$system" >&2
    exit 1
  fi

  if ! grep -Fqx 'Design exercise only — this is a proposed architecture, not a production deployment.' "$document"; then
    printf 'Missing non-deployment disclaimer: systems/%s.md\n' "$system" >&2
    exit 1
  fi

  for heading in "${headings[@]}"; do
    heading_count="$(grep -Fxc "## $heading" "$document" || true)"
    if [[ "$heading_count" -ne 1 ]]; then
      printf 'Expected exactly one heading %q in systems/%s.md\n' "$heading" "$system" >&2
      exit 1
    fi
  done

  if ! grep -Fq "](systems/$system.md)" "$lab_root/INDEX.md"; then
    printf 'Catalog index does not link systems/%s.md\n' "$system" >&2
    exit 1
  fi
done

while IFS= read -r -d '' markdown; do
  while IFS= read -r target; do
    target="${target%%#*}"
    case "$target" in
      '' | http://* | https://* | mailto:*)
        continue
        ;;
      /* | *://*)
        printf 'Disallowed absolute/non-file Markdown link in %s: %s\n' "$markdown" "$target" >&2
        exit 1
        ;;
    esac

    if [[ ! -f "$(dirname "$markdown")/$target" ]]; then
      printf 'Broken local Markdown link in %s: %s\n' "$markdown" "$target" >&2
      exit 1
    fi
  done < <(grep -Eo '\]\([^ )#]+(#[^)]*)?\)' "$markdown" | sed -E 's/^\]\(([^)#]+).*/\1/' || true)
done < <(find "$lab_root" -type f -name '*.md' -print0)

printf 'System Design Lab verification passed: %d mini-systems and required structure are present.\n' "${#systems[@]}"
