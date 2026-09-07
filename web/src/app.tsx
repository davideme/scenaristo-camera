import { useCallback, useEffect, useRef, useState } from 'preact/hooks'
import { Connection, elapsedSeconds, type Snapshot } from './connection'
import { ExposureScale, Histogram } from './exposure'
import { ABSENT, bitrate, codecName, format, minutesLeft, timecode } from './format'
import { LensIcon, WarningIcon } from './icons'
import { FramingGuides } from './guides'
import { LevelOverlay } from './level'
import {
  activeFocalLengthMm,
  dismissDistanceGuidance,
  distanceGuidanceDismissed,
  needsDistanceGuidance,
} from './lens'
import {
  CapabilityPanel,
  ExposurePanel,
  LensPanel,
  LightPanel,
  MainsPanel,
  PhonePanel,
  SoundPanel,
  ViewPanel,
} from './panels'
import type { GridFrequency, State, Warning } from './protocol'
import { loadViewPrefs, saveViewPrefs, type ViewPrefs } from './viewprefs'

/**
 * The remote control (PRD 6.8; spec-phone-and-remote-ui UI-9 and UI-10).
 *
 * The layout is a preview stage with a fixed right-hand control column, and the
 * whole page obeys §5's two grammars: **Reported** values are dimmed, unframed
 * and never touchable; **Yours** controls are framed, amber-labelled, and carry
 * their own current value. A value you chose appears on the control that sets it
 * and nowhere else.
 *
 * Preview is a plain `<img>` pointed at the MJPEG route — no client-side decode,
 * no canvas, no player. That is ADR-0008's whole argument: the browser already
 * knows how to render `multipart/x-mixed-replace`, so the alternative was a
 * frame protocol we would have had to write and maintain on two platforms.
 */
export function App() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const connection = useRef<Connection | null>(null)
  const [refused, setRefused] = useState<string | null>(null)
  const [, tick] = useState(0)
  // Client-local, and never sent (spec §8): whether the preview is flipped
  // depends on who is looking at it, not on what the camera is doing.
  const [view, setView] = useState<ViewPrefs>(loadViewPrefs)
  // PRD 6.5's guidance is dismissible for the session (UI-12), so this is
  // sessionStorage rather than a preference: the next session is a different
  // shot with a different person in front of the camera.
  const [guidanceDismissed, setGuidanceDismissed] = useState(distanceGuidanceDismissed)

  useEffect(() => {
    const c = new Connection(setSnapshot)
    c.start()
    connection.current = c
    // The elapsed counter advances between snapshots; without this it would only
    // move when the phone happened to send one.
    const timer = window.setInterval(() => tick((n) => n + 1), 500)
    return () => {
      window.clearInterval(timer)
      c.stop()
      connection.current = null
    }
  }, [])

  const state = snapshot?.state ?? null
  const recording = state?.recording.recording ?? false
  const elapsed = snapshot ? elapsedSeconds(snapshot) : null

  const toggleRecording = useCallback(async () => {
    const c = connection.current
    if (!c) return
    // Deliberately unguarded by `expectRev` (ADR-0007): acting on the latest
    // state is always what the user meant when they reached for record.
    setRefused(await c.send(recording ? 'record.stop' : 'record.start'))
  }, [recording])

  const patch = useCallback(async (args: {
    grid?: GridFrequency
    whiteBalanceKelvin?: number
    lockExposureWhileRecording?: boolean
    zoomRatio?: number
  }) => {
    const c = connection.current
    if (!c) return
    // Guarded: a settings change from a tab with a stale view should be refused
    // rather than applied over someone else's (ADR-0007).
    setRefused(await c.send('settings.set', args, true))
  }, [])

  // UI-9 names the record control's keyboard shortcut, so there has to be one.
  // Space is the shutter on every camera anyone has held.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.code !== 'Space' && event.key !== ' ') return
      const target = event.target as HTMLElement | null
      // Not while a button has focus: space would then both press the button and
      // fire this, and a double toggle on the record control is the worst
      // possible place for that.
      if (target && ['BUTTON', 'INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      event.preventDefault()
      void toggleRecording()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [toggleRecording])

  return (
    <main class={recording ? 'recording' : ''}>
      <header>
        <h1>Scenaristo Camera</h1>
        <Link snapshot={snapshot} />
      </header>

      <div class="stage">
        {/* UI-9: the row above the preview carries the same reported values, in
            the same order, as the phone's top strip — plus the preview's own
            format, which only this surface has. */}
        <Reported state={state} />

        {/*
          PRD 6.5's distance guidance. Deliberately not a `State.warnings` chip
          (UI-5): a warning is something that just became true, and this is a
          standing fact about the framing in use — true for the whole session,
          which is also why it can be dismissed once read.
        */}
        {state != null &&
        !guidanceDismissed &&
        needsDistanceGuidance(activeFocalLengthMm(state)) ? (
          <p class="guidance">
            <LensIcon />
            Wide lens — sit 1.5–2 m back
            <button
              type="button"
              class="dismiss"
              onClick={() => {
                dismissDistanceGuidance()
                setGuidanceDismissed(true)
              }}
            >
              Dismiss
            </button>
          </p>
        ) : null}

        {state?.warnings?.length ? (
          <ul class="warnings">
            {state.warnings.map((w) => (
              <li key={w}>
                <WarningIcon />
                {warningText(w, state)}
              </li>
            ))}
          </ul>
        ) : null}

        {/* Keyed on nothing, and never conditionally rendered: recreating this
            element restarts the MJPEG stream and shows a flash of nothing. */}
        <div class="preview-frame">
          <img
            class={`preview${view.mirror ? ' mirrored' : ''}`}
            src="/preview.mjpg"
            alt="Live preview from the phone"
          />
          {/* Over the image, not inside it: the preview is an MJPEG <img> the
              browser paints itself (ADR-0008), and drawing into it would mean a
              canvas and a copy of every frame for two straight lines. */}
          <FramingGuides thirds={view.thirds} eyeLine={view.eyeLine} />
          {view.level ? (
            <LevelOverlay
              mount={state?.mount}
              mirrored={view.mirror}
              recording={recording}
              previewProducing={state?.device.previewProducing !== false}
            />
          ) : null}
          {/*
            #116: the phone's screen is off, so CameraX's `Preview` has no
            surface, so there are no frames for the ADR-0018 tap to tap. The
            server, the state document and any recording are all completely
            unaffected — only the picture stops.

            Without this the browser shows a black rectangle indistinguishable
            from a dark room, while everything else looks healthy. Davide's call,
            2026-09-07: the behaviour is accepted, so the interface owes an
            explanation. It says what to do and what is *not* wrong, because the
            second is what stops someone abandoning a take that is running fine.
          */}
          {state != null && state.device.previewProducing === false ? (
            <p class="preview-idle">
              <span>Wake the phone&rsquo;s screen to see the preview</span>
              <span class="preview-idle-note">
                The phone is fine — a take in progress keeps recording
              </span>
            </p>
          ) : null}
        </div>

        <Transport
          state={state}
          elapsed={elapsed}
          recording={recording}
          connected={state != null}
          onToggle={toggleRecording}
        />
      </div>

      <aside class="column">
        {state ? (
          <>
            <PhonePanel state={state} />
            {/* No panel heading of its own: `ExposureScale` draws "Exposure"
                in the same row as its reading, so the word appears once. */}
            <section class="panel reported-panel">
              <ExposureScale readout={state.exposure ?? {}} />
              <Histogram readout={state.exposure ?? {}} />
            </section>
            <LightPanel
              state={state}
              locked={recording}
              onSet={(whiteBalanceKelvin) => void patch({ whiteBalanceKelvin })}
            />
            <MainsPanel state={state} locked={recording} onSet={(grid) => void patch({ grid })} />
            <ExposurePanel
              state={state}
              locked={recording}
              onSet={(lockExposureWhileRecording) =>
                void patch({ lockExposureWhileRecording })
              }
            />
            <LensPanel
              state={state}
              locked={recording}
              onSet={(zoomRatio) => void patch({ zoomRatio })}
            />
            <SoundPanel state={state} />
            <CapabilityPanel state={state} />
            <ViewPanel
              view={view}
              onChange={(next) => {
                setView(next)
                saveViewPrefs(next)
              }}
            />
          </>
        ) : (
          <section class="panel reported-panel">
            <h2>Phone</h2>
            <p class="lock-note">Waiting for the phone…</p>
          </section>
        )}
      </aside>

      {refused ? (
        <p class="refused" role="status">
          <WarningIcon />
          {refusalText(refused)}
          <button type="button" class="dismiss" onClick={() => setRefused(null)}>
            Dismiss
          </button>
        </p>
      ) : null}
    </main>
  )
}

/** UI-9's reported row. Dimmed, unframed, nothing here is a touch target (§5). */
function Reported({ state }: { state: State | null }) {
  const shutter = state ? `1/${state.settings.shutterHz}` : ABSENT
  const grid = state?.settings.grid === 'HZ_60' ? '60 Hz' : '50 Hz'
  // ADR-0005's flicker-safe step. PRD 6.3 says explicitly that no warning is
  // shown when the step succeeds, so it is a marker in the reported style
  // rather than anything louder (UI-5).
  const stepped =
    state != null && state.settings.shutterHz !== (state.settings.grid === 'HZ_60' ? 60 : 50)

  return (
    <dl class="reported">
      <div>
        <dt>Format</dt>
        <dd class="mono">{state ? format(state) : ABSENT}</dd>
      </div>
      <div>
        <dt>Shutter</dt>
        <dd class="mono">
          {shutter}
          {state?.settings.shutterLock != null ? <Padlock /> : null}
          {stepped ? <span class="marker">Stepped</span> : null}
          {/* The mains frequency is the shutter's caption, not a readout of its
              own: 1/50 is derived from it, and the pair is what makes an
              automatic step to 1/100 legible rather than alarming (§5). */}
          <span class="caption">{state ? grid : ''}</span>
        </dd>
      </div>
      <div>
        <dt>ISO</dt>
        <dd class="mono">{state ? state.settings.iso : ABSENT}</dd>
      </div>
      <div>
        <dt>Codec</dt>
        <dd class="mono">{state ? codecName(state.encoding?.codec ?? 'UNKNOWN') : ABSENT}</dd>
      </div>
      <div>
        <dt>Preview</dt>
        {/* ADR-0008 fixes this, and it is the one reported value the phone has
            no reason to draw: the preview only exists on this surface. */}
        <dd class="mono">960 × 540 · 15</dd>
      </div>
    </dl>
  )
}

/** §5: a padlock marks a value the app is deliberately holding still. */
const Padlock = () => (
  <svg
    class="padlock"
    width="11"
    height="11"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2.5"
    aria-label="locked"
  >
    <rect x="4" y="11" width="16" height="10" rx="2" />
    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </svg>
)

/**
 * UI-9's transport row: timecode and file name left, the record control centre
 * with its shortcut named, minutes remaining and the codec right.
 */
function Transport({
  state,
  elapsed,
  recording,
  connected,
  onToggle,
}: {
  state: State | null
  elapsed: number | null
  recording: boolean
  connected: boolean
  onToggle: () => void
}) {
  return (
    <div class="transport">
      <div class="transport-left">
        <p class={`timecode mono${recording ? ' live' : ''}`}>
          {recording ? <span class="dot" /> : null}
          {timecode(elapsed)}
        </p>
        <p class="state-word">{recording ? 'Recording' : connected ? 'Ready' : 'Connecting'}</p>
        <p class="file mono">{state?.recording.fileName ?? ABSENT}</p>
      </div>

      <div class="transport-centre">
        <button
          type="button"
          class={`record${recording ? ' stop' : ''}`}
          disabled={!connected}
          aria-label={recording ? 'Stop recording' : 'Start recording'}
          onClick={onToggle}
        >
          <span class="record-glyph" />
          {recording ? 'Stop' : 'Record'}
        </button>
        <p class="shortcut">Space</p>
      </div>

      <div class="transport-right">
        <p class="mono">{state ? minutesLeft(state.device.storageMinutesRemaining) : ABSENT} left</p>
        <p class="mono dim">
          {state ? codecName(state.encoding?.codec ?? 'UNKNOWN') : ABSENT} ·{' '}
          {state ? bitrate(state.encoding?.bitrate ?? 0) : ABSENT}
        </p>
      </div>
    </div>
  )
}

/** UI-12: the count reads "2 remotes connected". Never *viewer*, never *client*. */
function Link({ snapshot }: { snapshot: Snapshot | null }) {
  const status = snapshot?.status ?? 'connecting'
  const words: Record<string, string> = {
    connecting: 'Connecting to the phone',
    live: 'Connected',
    // ADR-0007 has the phone send a snapshot at least every 2 s precisely so
    // silence is diagnostic: a browser cannot ping, so this is the only way it
    // can tell "nothing changed" from "the phone is gone".
    stale: 'The phone has gone quiet',
    closed: 'Disconnected — retrying',
  }
  return (
    <p class={`link ${status}`} role="status">
      <span class="link-dot" />
      {words[status] ?? status}
      {/* PRD 6.8: recording continues if this browser drops off. Said where the
          user can see it, not only in documentation (UI-7). */}
      {status === 'closed' || status === 'stale' ? (
        <span class="link-note">A take in progress keeps recording</span>
      ) : null}
    </p>
  )
}

/**
 * UI-5: a warning names the fix, not the fault, and never restates a value that
 * is already visible in the reported row.
 */
function warningText(warning: Warning, state: State): string {
  switch (warning) {
    case 'TOO_DARK':
      return `Add light — ISO ${state.settings.iso} will look noisy`
    case 'TOO_CLOSE_TO_LENS': {
      // The framing's focal length, not the base lens's: at 5x the advice to
      // sit back is about a 120 mm field of view, not a 24 mm one.
      const mm = activeFocalLengthMm(state)
      return mm
        ? `Sit further back — 1.5–2 m for the ${mm} mm lens`
        : 'Sit further back — 1.5–2 m from the lens'
    }
    case 'OVEREXPOSED_AT_BASE_ISO':
      return 'Too much light — close the blinds or move the key light back'
    default:
      return warning
  }
}

/** A nack, in words. The reasons are ADR-0007's, and none of them is the user's fault. */
function refusalText(reason: string): string {
  switch (reason) {
    case 'stale':
      return 'The phone had already moved on — try that again'
    case 'not_capable':
      return 'This lens cannot do that'
    case 'invalid':
      return 'The phone would not accept that value'
    default:
      return reason
  }
}
