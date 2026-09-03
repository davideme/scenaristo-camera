# scenaristo-camera

Talking-head recording app for phones: correct capture defaults plus a browser remote with live
preview on the local network. Android first, iOS second. No backend.

- Product requirements: [docs/PRD-talking-head-camera.md](docs/PRD-talking-head-camera.md)
- Architecture decisions: [docs/adr/README.md](docs/adr/README.md)
- What to work on: [docs/ROADMAP.md](docs/ROADMAP.md)
- How to contribute: [CONTRIBUTING.md](CONTRIBUTING.md)
- Agent guidance: [CLAUDE.md](CLAUDE.md)

## Quick start

```bash
android sdk install platform-tools platforms/android-37.0 build-tools/37.0.0
cd android && ./gradlew build
```

There is no `sdkmanager` or Android Studio requirement — see
[CONTRIBUTING.md](CONTRIBUTING.md#32-android-tooling-macos--the-android-cli).

## Layout

| Path | What |
|---|---|
| `android/domain` | Platform-free logic and the protocol message types (ADR-0015) |
| `android/capture` | CameraX capture engine; all `Camera2Interop` in `ManualControls` (ADR-0002) |
| `android/server` | Ktor CIO server, LAN-bound (ADR-0006, ADR-0007) |
| `android/app` | Compose phone UI (PRD 6.9) |
| `web/` | The browser remote, one static bundle (ADR-0009) |
| `docs/protocol/fixtures/` | Golden fixtures: the cross-platform parity contract (ADR-0013) |
| `tools/` | Repository invariant checks, run by CI |

MIT licensed.
