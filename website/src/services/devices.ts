import {
  collection,
  deleteDoc,
  doc,
  getDocs,
  limit,
  onSnapshot,
  orderBy,
  query,
  updateDoc,
  writeBatch,
  type DocumentData,
} from "firebase/firestore";
import { db } from "../firebase";
import type { Device, LocationReport, NotificationEvent } from "../types";

const devicesPath = (uid: string) => collection(db, "users", uid, "devices");
const locationsPath = (uid: string, deviceId: string) =>
  collection(db, "users", uid, "devices", deviceId, "locations");
const notificationsPath = (uid: string, deviceId: string) =>
  collection(db, "users", uid, "devices", deviceId, "notifications");

function asDevice(id: string, data: DocumentData): Device {
  return {
    deviceId: id,
    displayName: data.displayName,
    platform: data.platform,
    registeredAt: data.registeredAt,
    trackingStatus: data.trackingStatus,
  };
}

function asLocation(id: string, data: DocumentData): LocationReport {
  return { ...data, locationId: id } as LocationReport;
}

export function subscribeDevices(
  uid: string,
  onChange: (devices: Device[]) => void,
  onError: (error: Error) => void,
) {
  return onSnapshot(
    query(devicesPath(uid), orderBy("registeredAt", "desc")),
    (snapshot) => onChange(snapshot.docs.map((item) => asDevice(item.id, item.data()))),
    (error) => onError(error),
  );
}

export function subscribeLatestLocation(
  uid: string,
  deviceId: string,
  onChange: (location: LocationReport | null) => void,
  onError: (error: Error) => void,
) {
  return onSnapshot(
    query(locationsPath(uid, deviceId), orderBy("receivedAt", "desc"), limit(1)),
    (snapshot) => {
      const latest = snapshot.docs[0];
      onChange(latest ? asLocation(latest.id, latest.data()) : null);
    },
    (error) => onError(error),
  );
}

export function subscribeHistory(
  uid: string,
  deviceId: string,
  onChange: (locations: LocationReport[]) => void,
  onError: (error: Error) => void,
) {
  return onSnapshot(
    query(locationsPath(uid, deviceId), orderBy("receivedAt", "desc"), limit(100)),
    (snapshot) => onChange(snapshot.docs.map((item) => asLocation(item.id, item.data()))),
    onError,
  );
}

export function subscribeNotifications(
  uid: string,
  deviceId: string,
  onChange: (notifications: NotificationEvent[]) => void,
  onError: (error: Error) => void,
) {
  return onSnapshot(
    query(notificationsPath(uid, deviceId), orderBy("receivedAt", "desc"), limit(100)),
    (snapshot) => onChange(snapshot.docs.map((item) => ({ ...item.data(), notificationId: item.id }) as NotificationEvent)),
    onError,
  );
}

export async function deleteNotificationHistory(uid: string, deviceId: string) {
  const path = notificationsPath(uid, deviceId);
  while (true) {
    const page = await getDocs(query(path, orderBy("receivedAt", "asc"), limit(450)));
    if (page.empty) return;
    const batch = writeBatch(db);
    page.docs.forEach((entry) => batch.delete(entry.ref));
    await batch.commit();
  }
}

export async function setTrackingStatus(uid: string, device: Device, status: Device["trackingStatus"]) {
  await updateDoc(doc(db, "users", uid, "devices", device.deviceId), { trackingStatus: status });
}

export async function renameDevice(uid: string, device: Device, displayName: string) {
  await updateDoc(doc(db, "users", uid, "devices", device.deviceId), { displayName });
}

export async function deleteDeviceAndHistory(uid: string, device: Device) {
  const history = await getDocs(locationsPath(uid, device.deviceId));
  const chunks: typeof history.docs[] = [];
  for (let i = 0; i < history.docs.length; i += 450) chunks.push(history.docs.slice(i, i + 450));
  for (const chunk of chunks) {
    const batch = writeBatch(db);
    chunk.forEach((entry) => batch.delete(entry.ref));
    await batch.commit();
  }
  await deleteDoc(doc(db, "users", uid, "devices", device.deviceId));
}

export function formatTimestamp(timestamp?: { toDate: () => Date } | null) {
  if (!timestamp) return "Waiting for first report";
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(timestamp.toDate());
}

export function ageLabel(timestamp?: { toDate: () => Date } | null) {
  if (!timestamp) return { label: "No report yet", stale: true };
  const ageMs = Date.now() - timestamp.toDate().getTime();
  const stale = ageMs > 30 * 60 * 1000;
  if (ageMs < 60_000) return { label: "Just now", stale: false };
  const minutes = Math.floor(ageMs / 60_000);
  if (minutes < 60) return { label: `${minutes} min ago`, stale };
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return { label: `${hours} hr ago`, stale };
  return { label: `${Math.floor(hours / 24)} days ago`, stale };
}
