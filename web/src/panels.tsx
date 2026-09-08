import { ABSENT, fileSize, minutesLeft, optics, remotes, timecode } from './format'
import {
  BatteryIcon,
  DownloadIcon,
  LensIcon,
  LightIcon,
  MicIcon,
  MirrorIcon,
  StorageIcon,
  ThermalIcon,
  WaveIcon,
} from './icons'
import { takePath } from './protocol'
import type { GridFrequency, State } from './protocol'
import type { ViewPrefs } from './viewprefs'
import { isRecommended } from './lens'
import { SCENARIOS, approximationNote, presetFor, scenarioOf } from './whitebalance'

/**
 * The control column (UI-9).
 *
 * Order is fixed by the spec: Phone, Light, Mains frequency, Lens, Sound. Every
 * panel that can be changed is **Yours** grammar — framed, amber label, and it
 * carries its own current value, because §5's rule is that a value you chose
 * lives on the control that sets it and nowhere else.
 */

/** A framed, amber-labelled control. Never used for anything reported. */
export function Panel({
  title,
  icon,
  locked,
  children,
}: {
  title: string
  icon?: preact.ComponentChildren
  locked?: boolean
  children: preact.ComponentChildren
}) {
  return (
    <section class={`panel${locked ? ' locked' : ''}`}>
      <h2>
        {icon}
        {title}
      </h2>
      {children}
      {/* UI-6: a control locked for the take says so. Not the padlock glyph,
          which means "held still by the app" and must not gain a second
          meaning. */}
      {locked ? <p class="lock-note">Locked while recording</p> : null}
    </section>
  )
}

/**
 * UI-9's first panel: what the phone is doing, which its own screen cannot say
 * because it is across the room facing you.
 *
 * This is the corollary in §5 about the OS boundary — battery, charging and
 * thermal are Android status-bar items and the phone HUD deliberately does not
 * redraw them, so the remote is the only place they appear.
 */
export function PhonePanel({ state }: { state: State }) {
  const { device } = state
  return (
    <section class="panel reported-panel">
      <h2>Phone</h2>
      <dl class="stack">
        <div>
          <dt>
            <BatteryIcon /> Battery
          </dt>
          <dd class="mono">
            {device.batteryPercent}%{device.charging ? ' · charging' : ''}
          </dd>
        </div>
        {/* UI-5, decision 2026-09-05: thermal says nothing until it costs
            something. NOMINAL and FAIR draw nothing at all — not a green dot,
            not a grey one. #23 measured a 10:42 4K30 take reaching MODERATE
            after eight minutes while holding 29.990 fps with no dropped frames;
            if the throttling neither impacts the experience nor costs frames,
            there is nothing to report. */}
        {device.thermal === 'SERIOUS' || device.thermal === 'CRITICAL' ? (
          <div class="hot">
            <dt>
              <ThermalIcon /> Temperature
            </dt>
            <dd class="mono">{device.thermal.toLowerCase()}</dd>
          </div>
        ) : null}
        <div>
          <dt>
            <StorageIcon /> Space left
          </dt>
          <dd class="mono">{minutesLeft(device.storageMinutesRemaining)}</dd>
        </div>
        <div>
          <dt>Remotes</dt>
          <dd class="mono">{remotes(state.clients ?? 0)}</dd>
        </div>
      </dl>
    </section>
  )
}

/**
 * PRD 6.4's two scenarios and their presets (UI-12).
 *
 * The user does not meet the word Kelvin before the preset value: the scenario
 * is chosen in plain language — "Daylight in the room", "Lamps only" — and the
 * temperature is what the chosen preset happens to be.
 */
export function LightPanel({
  state,
  locked,
  onSet,
}: {
  state: State
  locked: boolean
  onSet: (kelvin: number) => void
}) {
  const kelvin = state.settings.whiteBalanceKelvin
  const scenario = scenarioOf(kelvin)

  return (
    <Panel title="Light" icon={<LightIcon />} locked={locked}>
      {SCENARIOS.map((s) => (
        <div key={s.id} class="scenario">
          <p class="scenario-name">{s.name}</p>
          <div class="choices">
            {s.presets.map((preset) => (
              <button
                key={preset.kelvin}
                type="button"
                class={kelvin === preset.kelvin ? 'choice selected' : 'choice'}
                aria-pressed={kelvin === preset.kelvin}
                disabled={locked}
                onClick={() => onSet(preset.kelvin)}
              >
                <span class="choice-name">{preset.name}</span>
                <span class="choice-value mono">{preset.kelvin} K</span>
              </button>
            ))}
          </div>
        </div>
      ))}
      {/* The current value lives here and nowhere else (§5). When the phone is
          on a temperature no preset names — set from the phone, or a preset
          that moved — say so rather than leaving every button unlit. */}
      {/* PRD 6.4 requires the app to admit an approximation rather than present
          it as the real thing, and UI-8's rule is that it names what it is
          approximated *with*. On the reference device every lens takes this
          path today, because the Kelvin-to-gains curve is #24 in Phase 3 — so
          this is not an edge case, it is the current case. */}
      {approximationNote(state.settings.whiteBalanceApproximatedBy) ? (
        <p class="lock-note">{approximationNote(state.settings.whiteBalanceApproximatedBy)}</p>
      ) : null}
      {presetFor(kelvin) == null ? (
        <p class="lock-note">
          Currently {kelvin} K, which is not one of these presets
        </p>
      ) : scenario == null ? null : null}
    </Panel>
  )
}

/**
 * ADR-0023's choice: does exposure track the light during a take, or hold?
 *
 * Carried over from the control the old page had, into the grammar this one
 * uses. It is **Yours** (§5) — a thing the user decides — so it is framed, amber
 * and carries its own value, and there is deliberately no second readout of it
 * anywhere.
 *
 * Worded as what happens to the take rather than as what the metering loop does:
 * "locked" is a promise about the file, and nobody framing themselves from a
 * laptop should have to know there is a control loop to turn off.
 *
 * Not locked during a take, unlike the other settings: `Session` refuses every
 * `settings.set` while recording, so this is already unreachable mid-take for
 * the same reason as the rest — the dimming just says so first.
 */
export function ExposurePanel({
  state,
  locked,
  onSet,
}: {
  state: State
  locked: boolean
  onSet: (lock: boolean) => void
}) {
  const holds = state.settings.lockExposureWhileRecording === true
  return (
    <Panel title="Exposure during the take" locked={locked}>
      <div class="choices">
        <button
          type="button"
          class={holds ? 'choice' : 'choice selected'}
          aria-pressed={!holds}
          disabled={locked}
          onClick={() => onSet(false)}
        >
          <span class="choice-name">Track the light</span>
        </button>
        <button
          type="button"
          class={holds ? 'choice selected' : 'choice'}
          aria-pressed={holds}
          disabled={locked}
          onClick={() => onSet(true)}
        >
          <span class="choice-name">Lock at record start</span>
        </button>
      </div>
      {/* The exposure aids stop reading during a locked take, by ADR-0023's own
          design: the loop meters nothing, so there is nothing to draw. Said
          here, where the choice is made, rather than left to be discovered when
          the histogram goes blank mid-shot. */}
      {holds ? (
        <p class="lock-note">The exposure aids stop reading while a locked take runs</p>
      ) : null}
    </Panel>
  )
}

/**
 * PRD 6.2's override (UI-4).
 *
 * On the phone this lives in the settings sheet because it is a set-once-per-
 * region choice. On the remote it is a panel of its own, because UI-9 lists it
 * as one and because a producer setting up in a mixed-grid country — Japan,
 * parts of Brazil and Saudi Arabia — is exactly who is sitting at the laptop.
 */
export function MainsPanel({
  state,
  locked,
  onSet,
}: {
  state: State
  locked: boolean
  onSet: (grid: GridFrequency) => void
}) {
  return (
    <Panel title="Mains frequency" locked={locked}>
      <div class="choices">
        {(['HZ_50', 'HZ_60'] as GridFrequency[]).map((grid) => (
          <button
            key={grid}
            type="button"
            class={state.settings.grid === grid ? 'choice selected' : 'choice'}
            aria-pressed={state.settings.grid === grid}
            disabled={locked}
            onClick={() => onSet(grid)}
          >
            <span class="choice-name">{grid === 'HZ_50' ? '50 Hz' : '60 Hz'}</span>
          </button>
        ))}
      </div>
    </Panel>
  )
}

/**
 * The lens, as far as the protocol can currently describe it (UI-18).
 *
 * **Reported, not a control.** UI-9 wants a selectable list of every lens with
 * its capabilities, and `State` carries one `lensId` and no list — so drawing a
 * chooser here would be a control that cannot be honoured, which goal 5 forbids
 * outright. What can be shown honestly is the lens in use: its 35 mm-equivalent
 * focal length, its f/-number, and the T-stop derived from that.
 */
export function LensPanel({
  state,
  locked,
  onSet,
}: {
  state: State
  locked: boolean
  onSet: (zoomRatio: number) => void
}) {
  const glass = optics(state.optics?.apertureFNumber)
  const lenses = state.lenses ?? []
  const active = state.settings.zoomRatio ?? 1
  // The aperture was probed once, at the base lens. See the note beside the
  // readout for why that means it can only be shown there.
  const atBaseFraming = active === 1

  // A control with nothing to choose between is not a control. Before the camera
  // has bound, or on a device with no zoom at all, this reports and does not
  // offer — goal 5: nothing in the interface claims a capability the protocol
  // cannot carry.
  if (lenses.length < 2) {
    const mm = state.optics?.equivalentFocalLengthMm
    return (
      <section class="panel reported-panel">
        <h2>
          <LensIcon /> Lens
        </h2>
        <dl class="stack">
          <div>
            <dt>Focal length</dt>
            <dd class="mono">{mm ? `${mm} mm equivalent` : ABSENT}</dd>
          </div>
          <div>
            <dt>Aperture</dt>
            <dd class="mono">{glass ?? ABSENT}</dd>
          </div>
        </dl>
        {glass ? <p class="lock-note">T assumes 92% transmission · informational</p> : null}
      </section>
    )
  }

  return (
    <Panel title="Lens" icon={<LensIcon />} locked={locked}>
      <div class="choices lens-choices">
        {lenses.map((lens) => {
          const selected = lens.zoomRatio === active
          return (
            <button
              key={lens.zoomRatio}
              type="button"
              class={selected ? 'choice selected' : 'choice'}
              aria-pressed={selected}
              disabled={locked}
              onClick={() => onSet(lens.zoomRatio)}
            >
              <span class="choice-name">{lens.equivalentFocalLengthMm} mm</span>
              <span class="choice-value mono">
                {formatRatio(lens.zoomRatio)}
                {/* PRD 6.5: "If the device has a longer lens (48 mm+
                    telephoto)", say so — the list exists to move people off the
                    wide one. */}
                {isRecommended(lens.equivalentFocalLengthMm) ? ' · recommended' : ''}
              </span>
            </button>
          )
        })}
      </div>
      <dl class="stack">
        <div>
          <dt>Aperture</dt>
          {/*
            Only at the framing the aperture was actually probed at.

            `LENS_INFO_AVAILABLE_APERTURES` describes the logical camera, and it
            is `float[1]` on the reference device -- one number, whichever sensor
            the HAL happens to be using. Once the framing is a zoom ratio (#77),
            a longer framing is served by different glass with a different and
            unreported aperture, so drawing f/1.7 at 5x would be drawing a number
            about the wrong lens. The app has no way to know the right one, so it
            says nothing, the way it says nothing about a thermal state that
            costs nothing and a meter that is not running.
          */}
          <dd class="mono">{atBaseFraming ? (glass ?? ABSENT) : 'not reported at this framing'}</dd>
        </div>
      </dl>
      {/* UI-18: the T-stop assumes a transmission no phone reports. Saying so is
          what makes drawing it defensible, and it is why the f/-number is never
          shown without it or it without the f/-number. */}
      {atBaseFraming && glass ? (
        <p class="lock-note">T assumes 92% transmission · informational</p>
      ) : null}
    </Panel>
  )
}

/** `0.5x`, `1x`, `5x` — a trailing `.0` on a zoom ratio reads as false precision. */
function formatRatio(ratio: number): string {
  return `${Number.isInteger(ratio) ? ratio : ratio.toFixed(1)}×`
}

/**
 * How this browser draws the preview — not what the camera does (spec §8).
 *
 * A control, so it is framed and amber-labelled like any other. What makes it
 * unlike the others is that it sends nothing: the phone never learns this
 * happened, and a second remote is unaffected.
 */
export function ViewPanel({
  view,
  onChange,
}: {
  view: ViewPrefs
  onChange: (view: ViewPrefs) => void
}) {
  const toggle = (key: keyof ViewPrefs, label: string) => (
    <button
      type="button"
      class={view[key] ? 'choice selected' : 'choice'}
      aria-pressed={view[key]}
      onClick={() => onChange({ ...view, [key]: !view[key] })}
    >
      <span class="choice-name">{label}</span>
      <span class="choice-value mono">{view[key] ? 'on' : 'off'}</span>
    </button>
  )

  return (
    <Panel title="View" icon={<MirrorIcon />}>
      <div class="choices">
        {toggle('mirror', 'Mirror preview')}
        {/* PRD 6.8: "Preview shows framing overlays (rule-of-thirds, eye-line
            guide) toggleable from the web UI." Two switches and not one,
            because they are used at different moments -- thirds while placing
            the shot, the eye line while the speaker settles, when the other
            five lines are clutter over their face. */}
        {toggle('thirds', 'Rule of thirds')}
        {toggle('eyeLine', 'Eye line')}
        {/* PRD 6.11: a level aid for the mount PRD 6.1 assumes is there. It
            reads the phone's own accelerometer, which only runs while a take is
            not -- so the overlay says so rather than freezing (ADR-0023). */}
        {toggle('level', 'Level')}
      </div>
      {/* Said plainly, and next to the switch. A mirror control that turned out
          to have flipped the take would be discovered in an edit, which is far
          too late — so the interface states the boundary rather than leaving
          the user to assume it. */}
      <p class="lock-note">Preview only — the recording is never mirrored</p>
    </Panel>
  )
}

/**
 * PRD 6.10's capability report (UI-8, #14).
 *
 * **Reported, never a control**, and per *camera* rather than per framing: the
 * framings of the Lens panel are zoom ratios on one logical camera (UI-21), so
 * they share its characteristics. Four lines repeated four times would imply a
 * per-lens gate this device does not have.
 *
 * UI-6: an unavailable capability is drawn in **neutral grey, not red**. Red
 * means recording and nothing else, and a capability the phone simply does not
 * have is a fact rather than an error — the user did nothing wrong and there is
 * nothing to fix.
 */
export function CapabilityPanel({ state }: { state: State }) {
  const caps = state.capabilities
  if (!caps?.probed) {
    return (
      <section class="panel reported-panel">
        <h2>This camera</h2>
        <p class="lock-note">Not probed yet — waiting for the camera</p>
      </section>
    )
  }

  const rows: Array<[string, boolean, string | null]> = [
    ['4K · 30', caps.uhd30 === true, caps.uhd30 ? null : 'Records at 1080p instead'],
    // ADR-0011 gates recording on MANUAL_SENSOR and does not degrade: without
    // it the shutter drifts and the picture bands, which is the one thing the
    // product exists to prevent.
    ['Manual shutter', caps.manualShutter === true, caps.manualShutter ? null : 'Cannot record on this lens'],
    [
      'Manual white balance',
      caps.manualWhiteBalance === true,
      caps.manualWhiteBalance ? null : 'Presets are approximated',
    ],
    ['Hardware HEVC', caps.hardwareHevc === true, null],
  ]

  return (
    <section class="panel reported-panel">
      <h2>This camera</h2>
      <dl class="stack">
        {rows.map(([label, ok]) => (
          <div key={label} class={ok ? undefined : 'unavailable'}>
            <dt>{label}</dt>
            <dd class="mono">{ok ? 'yes' : 'no'}</dd>
          </div>
        ))}
      </dl>
      {/* PRD 6.10: a missing capability names the consequence, not just the
          absence. "Cannot record on this lens" is what the user needs; "no
          MANUAL_SENSOR" is what the log needs. */}
      {rows
        .filter(([, ok, note]) => !ok && note)
        .map(([label, , note]) => (
          <p key={label} class="lock-note">
            {label}: {note}
          </p>
        ))}
      {/* The gap PRD 6.4 leaves open, and the one that is live on this device:
          the lens may offer gains while the app is still applying presets. */}
      {caps.hardwareHevc && state.encoding?.codec === 'H264' ? (
        <p class="lock-note">
          Hardware HEVC exists, but this device&rsquo;s profile records H.264
        </p>
      ) : null}
    </section>
  )
}


/**
 * PRD 6.11 / UI-23: the takes on the phone, and a way to get one off it.
 *
 * The only panel whose reason for existing is that the phone is *not* reachable
 * any other way. ADR-0020 writes takes into the app's own folder by default,
 * which the gallery does not index and USB does not show, so before this the
 * answer to "how do I get that file" was `adb pull`.
 *
 * §5 grammar: this is **Reported** content carrying a touchable action, which
 * the spec's table does not have a row for -- see UI-23, where it is written
 * down as an exception rather than settled here by whichever style got typed
 * first.
 *
 * Duration leads each row because it is what a creator picks a take by: two
 * takes from the same minute are told apart by one running 4 seconds and the
 * other 40, not by one being 20 MB. Size follows because it is what tells you
 * whether this is a quick copy or a coffee.
 */
export function TakesPanel({ state, recording }: { state: State; recording: boolean }) {
  // Defaulted in the protocol, so an older phone sends no field at all.
  const takes = state.takes ?? []
  const toGallery = state.settings?.saveToGallery === true
  // Every take from one session shares a date, so showing it on each row would
  // spend the column's width on the one part that never distinguishes anything.
  // It earns its place only when the list actually spans more than one day.
  const spansDays = new Set(takes.map((take) => takeDate(take.name))).size > 1

  return (
    <Panel title="Takes" icon={<DownloadIcon />} locked={recording}>
      {takes.length === 0 ? (
        <p class="lock-note">
          {toGallery
            ? 'Saving to the gallery — these takes are in Photos on the phone, or over USB'
            : 'No takes on the phone yet'}
        </p>
      ) : (
        <ul class="takes">
          {takes.map((take) => {
            const when = spansDays
              ? `${takeDate(take.name)} ${takeTime(take.name)}`
              : takeTime(take.name)
            const duration = timecode(take.durationMs / 1000)
            const size = fileSize(take.sizeBytes)
            const row = (
              <>
                <span class="take-when mono">{when}</span>
                <span class="take-length mono">{duration}</span>
                <span class="take-size">{size}</span>
              </>
            )
            // The filename is not drawn: every row would repeat
            // `Scenaristo_2026-09-07_` and only the time would differ, which is
            // the column's whole width spent on the part that distinguishes
            // nothing. It stays reachable as the title and the accessible name,
            // and the browser's own download shelf shows it on the way past.
            const label = `${take.name}, ${duration}, ${size}`
            return (
              <li key={take.name}>
                {/* While recording the row is text, not a link. The server
                    answers 409 anyway (ADR-0028), but a link that is refused
                    when clicked is a worse answer than one visibly not
                    offered -- and there is no such thing as a disabled anchor. */}
                {recording ? (
                  <span class="take-row" title={label}>
                    {row}
                  </span>
                ) : (
                  <a
                    class="take-row"
                    href={takePath(take.name)}
                    download={`${take.name}.mp4`}
                    title={label}
                    aria-label={`Download ${label}`}
                  >
                    {row}
                  </a>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {/* Said once under the list rather than per row. Someone who turned the
          setting on is looking for takes that are not here. */}
      {toGallery && takes.length > 0 ? (
        <p class="lock-note">Newer takes are going to the gallery, not this list</p>
      ) : null}
    </Panel>
  )
}

/**
 * The clock time out of a take name (PRD 6.7 `Scenaristo_YYYY-MM-DD_HH-MM-SS`).
 *
 * Sliced from the name rather than formatted from `recordedAtMs`, and that is
 * deliberate: the name is the phone's **local** time by construction, which is
 * the time the person in front of the camera experienced. Formatting the
 * timestamp instead would render it in the *browser's* zone, so a laptop an hour
 * off the phone would label every take an hour wrong -- and the label would then
 * disagree with the filename it downloads.
 *
 * Slicing a fixed shape, not parsing a date: no zone is involved, and a name
 * that somehow does not match falls back to itself rather than to a wrong time.
 */
function takeTime(name: string): string {
  const match = /_(\d{2})-(\d{2})-(\d{2})$/.exec(name)
  return match ? `${match[1]}:${match[2]}:${match[3]}` : name
}

function takeDate(name: string): string {
  const match = /_(\d{4}-\d{2}-\d{2})_/.exec(name)
  return match ? match[1] : name
}

/**
 * PRD 6.6's meter (UI-4's bottom-left, mirrored here).
 *
 * `metering` is the field that matters: a bar at zero says the room is quiet,
 * and a meter that is not running says nothing at all. The MVP only meters
 * while the recorder is running (ADR-0002's 5 Hz), so "not metering" is the
 * normal state before a take rather than a fault.
 */
export function SoundPanel({ state }: { state: State }) {
  const audio = state.audio
  const level = Math.max(0, Math.min(1, audio?.level ?? 0))
  const input = (audio?.input ?? 'UNKNOWN').toLowerCase().replace('_', '-')

  return (
    <section class="panel reported-panel">
      <h2>
        <MicIcon /> Sound
      </h2>
      {audio?.metering ? (
        <div class="meter" role="img" aria-label={`Input level ${Math.round(level * 100)}%`}>
          <div class="meter-fill" style={{ width: `${level * 100}%` }} />
        </div>
      ) : (
        <p class="lock-note">
          <WaveIcon /> Not metering — the level meter runs while recording
        </p>
      )}
      <dl class="stack">
        <div>
          <dt>Input</dt>
          <dd class="mono">{input === 'unknown' ? ABSENT : input}</dd>
        </div>
      </dl>
      {/* PRD 6.6: hands-free Bluetooth is an 8–16 kHz voice codec, audibly worse
          than the built-in microphone it usually replaces. Worth saying, since
          nobody discovers it until they listen back. */}
      {audio?.input === 'BLUETOOTH' ? (
        <p class="lock-note">Bluetooth is a voice codec — the built-in mic sounds better</p>
      ) : null}
      {audio?.clipping ? <p class="clipping">Clipping — move back from the mic</p> : null}
    </section>
  )
}
