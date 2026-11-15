import type { Timestamp } from "firebase/firestore";

export type TrackingStatus = "active" | "paused" | "revoked";

export interface Device {
  deviceId: string;
  displayName: string;
  platform: "android";
  registeredAt: Timestamp;
  trackingStatus: TrackingStatus;
}

export interface LocationReport {
  locationId: string;
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  recordedAt: Timestamp;
  receivedAt: Timestamp;
  batteryPercentage?: number | null;
  charging?: boolean | null;
  connectionType?: string | null;
  networkOperator?: string | null;
  wifiSsid?: string | null;
  internetValidated?: boolean | null;
}

export interface NotificationEvent {
  notificationId: string;
  appName: string;
  packageName: string;
  title: string;
  body: string;
  postedAt: Timestamp;
  receivedAt: Timestamp;
}
