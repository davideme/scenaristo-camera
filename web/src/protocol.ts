// Generated from the :domain @Serializable classes. Do not edit (ADR-0009).
// Regenerate with: cd android && ./gradlew :domain:generateProtocolTypes

export const PROTOCOL_VERSION = 2;

export const WHITE_BALANCE_PRESETS = {
  NATURAL_LIGHT: [4500, 5600, 6500],
  ARTIFICIAL_LIGHT: [3200, 4500, 5600],
} as const;

export const DEFAULT_KELVIN = 5600;

export const LENS_WIDE_BAND = { min: 23, max: 25 } as const;
export const LENS_RECOMMENDED_FROM = 48;

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
  settingsRev?: number;
}

export interface State {
  settings: CaptureSettings;
  recording: RecordingState;
  device: DeviceStatus;
  warnings?: Warning[];
  clients?: number;
  audio?: AudioState;
  encoding?: Encoding;
  exposure?: ExposureReadout;
  optics?: Optics;
  lenses?: LensChoice[];
  serverTimeMs: number;
}

export interface CaptureSettings {
  grid: GridFrequency;
  shutterHz: number;
  iso: number;
  shutterLock?: number | null;
  whiteBalanceKelvin: number;
  whiteBalanceApproximatedBy?: AwbApproximation | null;
  lensId: string;
  saveToGallery?: boolean;
  lockExposureWhileRecording?: boolean;
  focus?: Focus;
  zoomRatio?: number;
}

export type GridFrequency = "HZ_50" | "HZ_60";

export type AwbApproximation = "INCANDESCENT" | "FLUORESCENT" | "DAYLIGHT" | "CLOUDY";

export interface Focus {
  mode?: FocusMode;
  x?: number | null;
  y?: number | null;
}

export type FocusMode = "continuous" | "locked";

export interface RecordingState {
  recording: boolean;
  startedAtMs?: number | null;
  fileName?: string | null;
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

export interface Encoding {
  codec?: VideoCodec;
  widthPx?: number;
  heightPx?: number;
  frameRate?: number;
  bitrate?: number;
}

export type VideoCodec = "HEVC" | "H264" | "UNKNOWN";

export interface ExposureReadout {
  stopsFromTarget?: number;
  histogram?: number[];
  metering?: boolean;
}

export interface Optics {
  equivalentFocalLengthMm?: number | null;
  apertureFNumber?: number | null;
}

export interface LensChoice {
  zoomRatio: number;
  equivalentFocalLengthMm: number;
}

export interface CmdMessage {
  type: "cmd";
  id: string;
  name: CommandName;
  expectRev?: number | null;
  args?: SettingsPatch | null;
  expectSettingsRev?: number | null;
  focus?: Focus | null;
}

export type CommandName = "record.start" | "record.stop" | "settings.set" | "focus.set";

export interface SettingsPatch {
  grid?: GridFrequency | null;
  whiteBalanceKelvin?: number | null;
  lensId?: string | null;
  saveToGallery?: boolean | null;
  lockExposureWhileRecording?: boolean | null;
  shutterLock?: number | null;
  zoomRatio?: number | null;
}

export type ServerMessage = AckMessage | HelloMessage | NackMessage | StateMessage;

export type ClientMessage = CmdMessage;

