# Protocol fixtures

Golden JSON for the phone-to-browser protocol (ADR-0007). These files are the
**parity contract** between platforms: the Android tests run them today and the
iOS tests must run the same files in Phase 4, whether the iOS domain code is
Kotlin or Swift (ADR-0010, ADR-0013).

A behaviour not covered by a fixture is a behaviour that can drift.

- The message types themselves are the `@Serializable` classes in `:domain`.
  Never hand-write a second copy of a message shape; TypeScript for the web UI
  is generated from the same classes (ADR-0009).
- Adding a field is backward compatible and does not need a new fixture version;
  renaming or removing one bumps the protocol major (ADR-0007).

| Fixture | Covers |
|---|---|
| `hello.json` | The first server message on `/ws`: protocol version, app name, platform discriminator. |
| `state.json` | The snapshot every browser mirrors, mid-recording: the flicker-safe shutter step in use, a locked focus point, a warning, and the client count. |
| `cmd-record-start.json` | A record command: no `expectRev`, no args. |
| `cmd-settings-set.json` | A settings patch with the staleness guard set, and a null field meaning "leave it alone". |
| `cmd-focus-set.json` | Tap to focus: a normalised point in the frame, and no `expectRev` — focus acts on the latest state, like record. |
| `ack.json` | The revision a command produced. |
| `nack-stale.json` | A command refused for a stale `expectRev`. |

Run them with `./gradlew :domain:jvmTest` from `android/`.
