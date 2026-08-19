# Client foundations

The repository now contains three bounded client shells that use the gateway contract:

- `clients/web-dashboard` is an Angular standalone application with the shared Home/Plan/Calendar/
  Money/Vault/Assistant/Sessions/Settings information architecture, responsive navigation, visible
  focus, reduced-motion and dark-mode behavior, bounded Analytics reads, and explicit loading,
  partial, empty, unavailable, and retry states. It does not store bearer credentials.
- `clients/desktop-client` is a JavaFX application module with the same destination terminology,
  keyboard-selectable native rail, accessible status text, a bounded memory-only password
  registration/login boundary, platform-specific OpenJFX dependencies, and smoke tests. It remains
  a shell for authenticated domain workflows.
- `clients/mobile` is a Flutter shell with shared Home/Plan/Calendar/Money/Vault/Assistant/
  Sessions/Settings navigation, a platform-secure password authentication boundary, native bottom
  navigation, dynamic text-friendly cards, bounded bearer-authenticated HTTP reads, refresh/retry
  states, and widget coverage. Flutter SDK/CI is an external prerequisite on hosts where it is not installed.
  The gateway origin is supplied with `--dart-define=LIFEOS_API_BASE_URL=...` (use the host-loopback
  address appropriate to the target emulator/device); the keystore is cleared on sign-out.

Run `bash scripts/verify-client-foundations.sh` for a deterministic static check. The clients still
do not claim offline caching, push notifications, passkey UI, or complete task/calendar/media
workflows; those require authenticated product wiring and remain explicit follow-up work after the
backend contracts stabilize. Web and desktop tokens remain memory-only until their platform-secure
storage boundaries are selected.
