// Generated from the :domain @Serializable classes. Do not edit (ADR-0009).
// Regenerate with: cd android && ./gradlew :domain:generateProtocolTypes

export interface AckMessage {
  type: "ack";
  id: string;
  rev: number;
}

export interface HelloMessage {
  type: "hello";
  protocol?: number;
  app: string;
  platform: Platform;
}

export type Platform = "android" | "ios";

export interface NackMessage {
  type: "nack";
  id: string;
  reason: NackReason;
}

export type NackReason = "stale" | "not_capable" | "invalid";

export interface StateMessage {
  type: "state";
  rev: number;
  state: State;
}

export interface State {
  settings: CaptureSettings;
  recording: RecordingState;
  device: DeviceStatus;
  warnings?: Warning[];
  clients?: number;
  audio?: AudioState;
  serverTimeMs: number;
}

export interface CaptureSettings {
  grid: GridFrequency;
  shutterHz: number;
  iso: number;
  whiteBalanceKelvin: number;
  lensId: string;
  focus?: Focus;
}

export type GridFrequency = "HZ_50" | "HZ_60";

export interface Focus {
  mode?: FocusMode;
  x?: number | null;
  y?: number | null;
}

export type FocusMode = "continuous" | "locked";

export interface RecordingState {
  recording: boolean;
  startedAtMs?: number | null;
}

export interface DeviceStatus {
  batteryPercent: number;
  charging: boolean;
  thermal: ThermalState;
  storageMinutesRemaining: number;
}

export type ThermalState = "NOMINAL" | "FAIR" | "SERIOUS" | "CRITICAL";

export type Warning = "TOO_DARK" | "TOO_CLOSE_TO_LENS" | "OVEREXPOSED_AT_BASE_ISO";

export interface AudioState {
  level?: number;
  clipping?: boolean;
  input?: AudioInput;
  metering?: boolean;
}

export type AudioInput = "BUILT_IN" | "WIRED" | "USB" | "BLUETOOTH" | "UNKNOWN";

export interface CmdMessage {
  type: "cmd";
  id: string;
  name: CommandName;
  expectRev?: number | null;
  args?: SettingsPatch | null;
  focus?: Focus | null;
}

export type CommandName = "record.start" | "record.stop" | "settings.set" | "focus.set";

export interface SettingsPatch {
  grid?: GridFrequency | null;
  whiteBalanceKelvin?: number | null;
  lensId?: string | null;
}

export type ServerMessage = AckMessage | HelloMessage | NackMessage | StateMessage;

export type ClientMessage = CmdMessage;

