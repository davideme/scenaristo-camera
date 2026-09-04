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
  serverTimeMs: number;
}

export interface CaptureSettings {
  grid: GridFrequency;
  shutterHz: number;
  iso: number;
  whiteBalanceKelvin: number;
  lensId: string;
}

export type GridFrequency = "HZ_50" | "HZ_60";

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

export type Warning = "TOO_DARK" | "TOO_BRIGHT" | "TOO_CLOSE_TO_LENS" | "OVEREXPOSED_AT_BASE_ISO";

export interface CmdMessage {
  type: "cmd";
  id: string;
  name: CommandName;
  expectRev?: number | null;
  args?: SettingsPatch | null;
}

export type CommandName = "record.start" | "record.stop" | "settings.set";

export interface SettingsPatch {
  grid?: GridFrequency | null;
  whiteBalanceKelvin?: number | null;
  lensId?: string | null;
}

export type ServerMessage = AckMessage | HelloMessage | NackMessage | StateMessage;

export type ClientMessage = CmdMessage;

