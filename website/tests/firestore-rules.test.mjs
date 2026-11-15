import assert from "node:assert/strict";
import { after, before, beforeEach, test } from "node:test";
import { readFile } from "node:fs/promises";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  doc,
  getDoc,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";

const projectId = "traceguard-test";
let environment;

before(async () => {
  const rules = await readFile(new URL("../../firebase/firestore.rules", import.meta.url), "utf8");
  environment = await initializeTestEnvironment({
    projectId,
    firestore: { rules, host: "127.0.0.1", port: 8080 },
  });
});

beforeEach(async () => environment.clearFirestore());
after(async () => environment?.cleanup());

function deviceRef(db, ownerId = "owner-a", deviceId = "phone-a") {
  return doc(db, "users", ownerId, "devices", deviceId);
}

function validLocation(locationId = "location-a") {
  return {
    locationId,
    latitude: -26.2041,
    longitude: 28.0473,
    accuracyMeters: 15.5,
    recordedAt: Timestamp.now(),
    receivedAt: serverTimestamp(),
    batteryPercentage: 82,
    charging: false,
    connectionType: "wifi",
    internetValidated: true,
  };
}

async function seedDevice(status = "active") {
  await environment.withSecurityRulesDisabled(async (context) => {
    await setDoc(deviceRef(context.firestore()), {
      deviceId: "phone-a",
      displayName: "Test phone",
      platform: "android",
      registeredAt: Timestamp.now(),
      trackingStatus: status,
    });
  });
}

test("denies unauthenticated and cross-account device reads", async () => {
  await seedDevice();
  await assertFails(getDoc(deviceRef(environment.unauthenticatedContext().firestore())));
  await assertFails(getDoc(deviceRef(environment.authenticatedContext("owner-b").firestore())));
});

test("allows an owner to register a device with a server registration time", async () => {
  const db = environment.authenticatedContext("owner-a").firestore();
  await assertSucceeds(setDoc(deviceRef(db), {
    deviceId: "phone-a",
    displayName: "Test phone",
    platform: "android",
    registeredAt: serverTimestamp(),
    trackingStatus: "paused",
  }));
});

test("allows valid reports only to the device owner while tracking is active", async () => {
  await seedDevice("active");
  const ownerDb = environment.authenticatedContext("owner-a").firestore();
  const otherDb = environment.authenticatedContext("owner-b").firestore();
  const ownerLocation = doc(ownerDb, "users", "owner-a", "devices", "phone-a", "locations", "location-a");
  await assertSucceeds(setDoc(ownerLocation, validLocation()));

  const otherLocation = doc(otherDb, "users", "owner-a", "devices", "phone-a", "locations", "stolen-write");
  await assertFails(setDoc(otherLocation, validLocation("stolen-write")));
});

test("rejects out-of-range coordinates and writes to revoked devices", async () => {
  await seedDevice("active");
  const db = environment.authenticatedContext("owner-a").firestore();
  const invalid = { ...validLocation("invalid"), latitude: 91 };
  await assertFails(setDoc(doc(db, "users", "owner-a", "devices", "phone-a", "locations", "invalid"), invalid));

  await seedDevice("revoked");
  await assertFails(setDoc(doc(db, "users", "owner-a", "devices", "phone-a", "locations", "revoked-write"), validLocation("revoked-write")));
});

