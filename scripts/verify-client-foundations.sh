#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
required_files=(
  "$repo_root/clients/web-dashboard/package.json"
  "$repo_root/clients/web-dashboard/angular.json"
  "$repo_root/clients/web-dashboard/src/main.ts"
  "$repo_root/clients/desktop-client/build.gradle.kts"
  "$repo_root/clients/desktop-client/src/main/java/com/lifeos/desktop/LifeOsDesktopApplication.java"
  "$repo_root/clients/mobile/pubspec.yaml"
  "$repo_root/clients/mobile/lib/main.dart"
  "$repo_root/clients/mobile/test/widget_test.dart"
)
for file in "${required_files[@]}"; do
  [[ -s "$file" ]] || { printf 'missing client foundation: %s\n' "$file" >&2; exit 65; }
done

rg -q 'bootstrapApplication' "$repo_root/clients/web-dashboard/src/main.ts"
rg -q 'AbortController|timeout' "$repo_root/clients/web-dashboard/src/main.ts"
for destination in Home Plan Calendar Money Vault Assistant Sessions Settings; do
  rg -q "$destination" "$repo_root/clients/web-dashboard/src/main.ts"
done
rg -q 'extends Application' "$repo_root/clients/desktop-client/src/main/java/com/lifeos/desktop/LifeOsDesktopApplication.java"
rg -q 'ListView|Settings' "$repo_root/clients/desktop-client/src/main/java/com/lifeos/desktop/LifeOsDesktopApplication.java"
rg -q 'extractAccessToken|Sign out|Idempotency-Key' "$repo_root/clients/desktop-client/src/main/java/com/lifeos/desktop/LifeOsDesktopApplication.java"
rg -q 'FutureBuilder|Duration\(seconds: 5\)' "$repo_root/clients/mobile/lib/main.dart"
rg -q 'NavigationBar' "$repo_root/clients/mobile/lib/main.dart"
rg -q 'flutter_secure_storage|secureStorage' "$repo_root/clients/mobile/lib/main.dart"
for destination in Home Plan Calendar Money Vault Assistant Sessions Settings; do
  rg -q "label: '$destination'" "$repo_root/clients/mobile/lib/main.dart"
done
printf 'client foundations verified (web Angular shell, JavaFX shell, bounded Flutter shell)\n'
