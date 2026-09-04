import { useEffect, useState } from 'preact/hooks'
import { Connection, elapsedSeconds, type Snapshot } from './connection'
import type { GridFrequency } from './protocol'

/**
 * The browser remote (PRD 6.8).
 *
 * Preview is a plain `<img>` pointed at the MJPEG route — no client-side decode,
 * no canvas, no player. That is ADR-0008's whole argument: the browser already
 * knows how to render `multipart/x-mixed-replace`, so the alternative was a
 * frame protocol we would have had to write and maintain on two platforms.
 */
export function App() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [connection, setConnection] = useState<Connection | null>(null)
  const [refused, setRefused] = useState<string | null>(null)
  const [, tick] = useState(0)

  useEffect(() => {
    const c = new Connection(setSnapshot)
    c.start()
    setConnection(c)
    // The elapsed counter advances between snapshots; without this it would
    // only move when the phone happened to send one.
    const timer = window.setInterval(() => tick((n) => n + 1), 500)
    return () => {
      window.clearInterval(timer)
      c.stop()
    }
  }, [])

  const state = snapshot?.state
  const recording = state?.recording.recording ?? false
  const elapsed = snapshot ? elapsedSeconds(snapshot) : null

  async function run(name: 'record.start' | 'record.stop') {
    setRefused(await (connection?.send(name) ?? Promise.resolve(null)))
  }

  async function setGrid(grid: GridFrequency) {
    // Guarded: a settings change from a tab with a stale view should be refused,
    // not applied over someone else's (ADR-0007).
    setRefused(await (connection?.send('settings.set', { grid }, true) ?? Promise.resolve(null)))
  }

  return (
    <main>
      <header>
        <h1>Scenaristo Camera</h1>
        <span class={`status ${snapshot?.status ?? 'connecting'}`}>
          {snapshot?.status ?? 'connecting'}
          {state?.clients ? ` · ${state.clients} connected` : ''}
        </span>
      </header>

      {/* Keyed on nothing: the element must persist, because recreating it
          restarts the MJPEG stream and shows a flash of nothing. */}
      <img class="preview" src="/preview.mjpg" alt="Live preview from the phone" />

      <section class="controls">
        <button
          class={recording ? 'recording' : ''}
          onClick={() => run(recording ? 'record.stop' : 'record.start')}
          disabled={!state}
        >
          {recording ? 'Stop' : 'Record'}
        </button>
        <span class="timer">
          {elapsed == null ? '--:--' : `${pad(Math.floor(elapsed / 60))}:${pad(elapsed % 60)}`}
        </span>
      </section>

      {state && (
        <section class="settings">
          <fieldset disabled={recording}>
            {/* PRD 6.1 promises a locked look for the whole take, so the phone
                refuses these while recording; disabling them says so before the
                user finds out from a nack. */}
            <legend>Mains frequency</legend>
            {(['HZ_50', 'HZ_60'] as GridFrequency[]).map((grid) => (
              <button
                key={grid}
                class={state.settings.grid === grid ? 'selected' : ''}
                onClick={() => setGrid(grid)}
              >
                {grid === 'HZ_50' ? '50 Hz' : '60 Hz'}
              </button>
            ))}
          </fieldset>

          <dl class="readout">
            <dt>Shutter</dt>
            <dd>1/{state.settings.shutterHz} s</dd>
            <dt>ISO</dt>
            <dd>{state.settings.iso}</dd>
            <dt>White balance</dt>
            <dd>{state.settings.whiteBalanceKelvin} K</dd>
            <dt>Battery</dt>
            <dd>
              {state.device.batteryPercent}%{state.device.charging ? ' charging' : ''}
            </dd>
            <dt>Thermal</dt>
            <dd>{state.device.thermal.toLowerCase()}</dd>
            <dt>Storage</dt>
            <dd>{state.device.storageMinutesRemaining} min left</dd>
          </dl>

          {state.warnings?.length ? (
            <ul class="warnings">
              {state.warnings.map((w) => (
                <li key={w}>{w.replaceAll('_', ' ').toLowerCase()}</li>
              ))}
            </ul>
          ) : null}
        </section>
      )}

      {refused && <p class="refused">Refused: {refused}</p>}
    </main>
  )
}

const pad = (n: number) => String(n).padStart(2, '0')
