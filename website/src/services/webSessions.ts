import {
  Timestamp,
  collection,
  doc,
  getDoc,
  limit,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
} from "firebase/firestore";
import { db } from "../firebase";

const SESSION_STORAGE_KEY = "traceguard-web-session-id";
const HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000;
const MAX_LISTED_SESSIONS = 50;

export type WebSession = {
  sessionId: string;
  deviceLabel: string;
  createdAt: Timestamp | null;
  lastSeen: Timestamp | null;
  revokedAt: Timestamp | null;
};

function createSessionId() {
  return crypto.randomUUID();
}

export function currentWebSessionId() {
  try {
    const existing = window.localStorage.getItem(SESSION_STORAGE_KEY);
    if (existing) return existing;
    const created = createSessionId();
    window.localStorage.setItem(SESSION_STORAGE_KEY, created);
    return created;
  } catch {
    return createSessionId();
  }
}

function clearCurrentWebSessionId(sessionId: string) {
  try {
    if (window.localStorage.getItem(SESSION_STORAGE_KEY) === sessionId) {
      window.localStorage.removeItem(SESSION_STORAGE_KEY);
    }
  } catch {
    // The browser may block local storage; sign-out still proceeds.
  }
}

function browserLabel() {
  const agent = navigator.userAgent;
  const browser = /Edg\//.test(agent) ? "Microsoft Edge"
    : /OPR\//.test(agent) ? "Opera"
      : /CriOS\//.test(agent) ? "Chrome"
        : /FxiOS\//.test(agent) ? "Firefox"
          : /Chrome\//.test(agent) ? "Chrome"
            : /Firefox\//.test(agent) ? "Firefox"
              : /Safari\//.test(agent) ? "Safari"
                : "Web browser";
  const platform = /Android/.test(agent) ? "Android"
    : /iPhone|iPad|iPod/.test(agent) ? "iOS"
      : /Windows/.test(agent) ? "Windows"
        : /Mac OS X/.test(agent) ? "macOS"
          : /Linux/.test(agent) ? "Linux"
            : "device";
  return `${browser} on ${platform}`;
}

function asTimestamp(value: unknown) {
  return value instanceof Timestamp ? value : null;
}

export function subscribeWebSessions(
  uid: string,
  onSessions: (sessions: WebSession[]) => void,
  onCurrentSessionId: (sessionId: string) => void,
  onCurrentSessionRevoked: () => void,
  onError: (error: Error) => void,
) {
  let sessionId = currentWebSessionId();
  let sessionRef = doc(db, "users", uid, "webSessions", sessionId);
  const sessionsPath = collection(db, "users", uid, "webSessions");
  const recentSessionsQuery = query(sessionsPath, orderBy("lastSeen", "desc"), limit(MAX_LISTED_SESSIONS));
  let disposed = false;
  let revoked = false;
  let initialized = false;
  const label = browserLabel();
  onCurrentSessionId(sessionId);

  const handleRevoked = () => {
    if (disposed || revoked) return;
    revoked = true;
    clearCurrentWebSessionId(sessionId);
    onCurrentSessionRevoked();
  };

  const unsubscribeList = onSnapshot(
    recentSessionsQuery,
    (snapshot) => {
      const sessions: WebSession[] = snapshot.docs.map((item) => {
        const data = item.data();
        return {
          sessionId: item.id,
          deviceLabel: typeof data.deviceLabel === "string" ? data.deviceLabel : "Older browser session",
          createdAt: asTimestamp(data.createdAt),
          lastSeen: asTimestamp(data.lastSeen),
          revokedAt: asTimestamp(data.revokedAt),
        };
      });
      onSessions(sessions);
    },
    onError,
  );

  let unsubscribeCurrent = () => {};
  const watchCurrentSession = () => {
    unsubscribeCurrent();
    unsubscribeCurrent = onSnapshot(
      sessionRef,
      (snapshot) => {
        if (snapshot.exists() && asTimestamp(snapshot.data().revokedAt)) handleRevoked();
      },
      onError,
    );
  };
  watchCurrentSession();

  const heartbeat = () => {
    if (disposed || revoked || !initialized) return;
    void setDoc(sessionRef, { deviceLabel: label, lastSeen: serverTimestamp() }, { merge: true }).catch((error: unknown) => {
      if (!disposed) onError(error instanceof Error ? error : new Error("Could not update this browser session."));
    });
  };

  const initialize = async () => {
    try {
      let existing = await getDoc(sessionRef);
      if (disposed) return;
      if (existing.exists() && asTimestamp(existing.data().revokedAt)) {
        handleRevoked();
        return;
      }
      if (!existing.exists()) {
        await setDoc(sessionRef, { deviceLabel: label, createdAt: serverTimestamp(), lastSeen: serverTimestamp() });
      } else {
        const update: Record<string, unknown> = { deviceLabel: label, lastSeen: serverTimestamp() };
        if (!asTimestamp(existing.data().createdAt)) update.createdAt = serverTimestamp();
        await setDoc(sessionRef, update, { merge: true });
      }
      if (disposed || revoked) return;
      initialized = true;
      existing = await getDoc(sessionRef);
      if (existing.exists() && asTimestamp(existing.data().revokedAt)) handleRevoked();
    } catch (error) {
      if (!disposed) onError(error instanceof Error ? error : new Error("Could not start this browser session."));
    }
  };
  void initialize();

  const heartbeatTimer = window.setInterval(heartbeat, HEARTBEAT_INTERVAL_MS);
  const onVisibilityChange = () => {
    if (document.visibilityState === "visible") heartbeat();
  };
  document.addEventListener("visibilitychange", onVisibilityChange);
  window.addEventListener("focus", heartbeat);

  return () => {
    disposed = true;
    window.clearInterval(heartbeatTimer);
    document.removeEventListener("visibilitychange", onVisibilityChange);
    window.removeEventListener("focus", heartbeat);
    unsubscribeList();
    unsubscribeCurrent();
  };
}

export async function logOutWebSession(uid: string, sessionId: string) {
  await updateDoc(doc(db, "users", uid, "webSessions", sessionId), { revokedAt: serverTimestamp() });
}
