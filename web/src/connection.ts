import type {
  ClientMessage,
  CommandName,
  ServerMessage,
  SettingsPatch,
  State,
} from './protocol'

/**
 * The phone connection: one WebSocket, revisioned snapshots, commands with ids
 * (ADR-0007).
 *
 * Kept out of the components because none of this is view logic, and because the
 * reconnect and staleness rules are the parts that decide whether the remote can
 * be trusted mid-take.
 */

export type Status = 'connecting' | 'live' | 'stale' | 'closed'

export interface Snapshot {
  status: Status
  rev: number
  state: State | null
  /** Offset between the phone's clock and ours, so elapsed time survives a reconnect. */
  clockOffsetMs: number
  lastError: string | null
}

/**
 * A snapshot older than this means the phone has gone quiet. ADR-0007 has the
 * server send one at least every 2 s precisely so silence is diagnostic — a
 * browser cannot send WebSocket pings itself, so this is the only way it can
 * tell "nothing changed" from "the phone is gone".
 */
const STALE_AFTER_MS = 5000

export class Connection {
  private socket: WebSocket | null = null
  private timer: number | undefined
  private lastMessageAt = 0
  private pending = new Map<string, (ok: boolean, reason?: string) => void>()

  snapshot: Snapshot = {
    status: 'connecting',
    rev: -1,
    state: null,
    clockOffsetMs: 0,
    lastError: null,
  }

  private readonly onChange: (snapshot: Snapshot) => void

  // Written out rather than a constructor parameter property: the tsconfig sets
  // erasableSyntaxOnly, which forbids syntax that has to be compiled away.
  constructor(onChange: (snapshot: Snapshot) => void) {
    this.onChange = onChange
  }

  start() {
    this.open()
    // Staleness is time passing, not an event, so it needs its own tick.
    this.timer = window.setInterval(() => {
      if (this.snapshot.status === 'live' && Date.now() - this.lastMessageAt > STALE_AFTER_MS) {
        this.update({ status: 'stale' })
      }
    }, 1000)
  }

  stop() {
    window.clearInterval(this.timer)
    this.socket?.close()
    this.socket = null
  }

  private open() {
    // Same origin as the page: the phone serves both, and hard-coding a host
    // would break the moment its address changes (ADR-0006 dropped mDNS, so the
    // address is whatever the user typed).
    const url = `ws://${location.host}/ws`
    const socket = new WebSocket(url)
    this.socket = socket

    socket.onopen = () => this.update({ status: 'connecting', lastError: null })
    socket.onclose = () => {
      this.update({ status: 'closed' })
      // The phone may simply have been backgrounded; keep trying rather than
      // making the user reload during a take.
      window.setTimeout(() => this.open(), 1000)
    }
    socket.onerror = () => this.update({ lastError: 'connection failed' })
    socket.onmessage = (event) => this.receive(event.data as string)
  }

  private receive(raw: string) {
    let message: ServerMessage
    try {
      message = JSON.parse(raw) as ServerMessage
    } catch {
      return
    }
    this.lastMessageAt = Date.now()

    switch (message.type) {
      case 'hello':
        // ADR-0007: refuse an unknown major rather than mis-rendering a protocol
        // we do not understand.
        if ((message.protocol ?? 1) !== 1) {
          this.update({ status: 'closed', lastError: `unsupported protocol ${message.protocol}` })
          this.socket?.close()
        }
        break
      case 'state':
        this.update({
          status: 'live',
          rev: message.rev,
          state: message.state,
          clockOffsetMs: message.state.serverTimeMs - Date.now(),
        })
        break
      case 'ack':
        this.pending.get(message.id)?.(true)
        this.pending.delete(message.id)
        break
      case 'nack':
        this.pending.get(message.id)?.(false, message.reason)
        this.pending.delete(message.id)
        break
    }
  }

  /**
   * Sends a command and resolves when the phone answers.
   *
   * `expectRev` is passed for settings and omitted for record start and stop:
   * ADR-0007's reasoning is that acting on the latest state is always what the
   * user meant when they hit record, while a settings change from a stale tab
   * should be refused rather than silently undo someone else's.
   */
  send(name: CommandName, args?: SettingsPatch, guard = false): Promise<string | null> {
    const id = randomId()
    const message: ClientMessage = {
      type: 'cmd',
      id,
      name,
      expectRev: guard ? this.snapshot.rev : null,
      args: args ?? null,
    }
    return new Promise((resolve) => {
      this.pending.set(id, (ok, reason) => resolve(ok ? null : (reason ?? 'refused')))
      this.socket?.send(JSON.stringify(message))
    })
  }

  private update(patch: Partial<Snapshot>) {
    this.snapshot = { ...this.snapshot, ...patch }
    this.onChange(this.snapshot)
  }
}

/**
 * A command id, without `crypto.randomUUID()`.
 *
 * The page is served over plain HTTP from a LAN IP (ADR-0006), so it is not a
 * secure context and `randomUUID` does not exist — it throws, and the record
 * button silently does nothing. ADR-0008 anticipated the secure-context loss for
 * WebCodecs; it takes `randomUUID` with it.
 *
 * `getRandomValues` is not secure-context-restricted, so it is the first choice;
 * the fallback is only reached on something very old. Either way the id only has
 * to be unique among one client's own commands within the phone's 10-second
 * idempotency window (ADR-0007), which is a far weaker requirement than
 * uniqueness in general.
 */
function randomId(): string {
  const bytes = new Uint8Array(16)
  if (globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  }
  // Version and variant bits, so the value is a well-formed UUIDv4 as ADR-0007
  // describes rather than merely random-looking.
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * Elapsed recording time, corrected for the difference between the phone's clock
 * and this browser's.
 *
 * ADR-0007 sends `startedAtMs` rather than a duration for exactly this: a
 * duration is wrong the moment a snapshot is late, and after a reconnect it
 * would restart from zero.
 */
export function elapsedSeconds(snapshot: Snapshot): number | null {
  const started = snapshot.state?.recording.startedAtMs
  if (started == null) return null
  return Math.max(0, Math.floor((Date.now() + snapshot.clockOffsetMs - started) / 1000))
}
