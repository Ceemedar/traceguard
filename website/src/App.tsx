import { useCallback, useEffect, useMemo, useState } from "react";
import {
  createUserWithEmailAndPassword,
  onAuthStateChanged,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
  type User,
} from "firebase/auth";
import { auth } from "./firebase";
import MapPanel from "./MapPanel";
import { reverseGeocode } from "./services/address";
import { logOutWebSession, subscribeWebSessions, type WebSession } from "./services/webSessions";
import {
  ageLabel,
  deleteNotificationHistory,
  deleteDeviceAndHistory,
  formatTimestamp,
  renameDevice,
  setTrackingStatus,
  subscribeDevices,
  subscribeHistory,
  subscribeLatestLocation,
  subscribeNotifications,
} from "./services/devices";
import type { Device, LocationReport, NotificationEvent } from "./types";

type Page = "dashboard" | "history" | "notifications" | "settings";
type AuthMode = "signin" | "signup" | "reset";

function errorMessage(error: unknown) {
  if (error instanceof Error) return error.message;
  return "Something went wrong. Please try again.";
}

function connectedNetwork(location: LocationReport) {
  switch (location.connectionType) {
    case "wifi": return location.wifiSsid ? `Wi-Fi · ${location.wifiSsid}` : "Wi-Fi · network name unavailable";
    case "mobile": return location.networkOperator ? `Mobile data · ${location.networkOperator}` : "Mobile data · carrier unavailable";
    case "ethernet": return "Ethernet";
    case "none": return "No network connection";
    case "unknown": return "Network type unavailable";
    default: return "Network details not reported";
  }
}

function internetAvailability(location: LocationReport) {
  if (location.internetValidated === true) return "Internet access available when this point was reported.";
  if (location.internetValidated === false) return "Android could not confirm internet access at this point.";
  return "Internet availability was not reported by the phone.";
}

function AuthScreen() {
  const [mode, setMode] = useState<AuthMode>("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setMessage("");
    try {
      if (mode === "signup") {
        if (password.length < 12) throw new Error("Use a password with at least 12 characters.");
        const credential = await createUserWithEmailAndPassword(auth, email.trim(), password);
        await sendEmailVerification(credential.user);
        await signOut(auth);
        setMode("signin");
        setMessage("Account created. Check your email to verify it, then sign in.");
      } else if (mode === "reset") {
        await sendPasswordResetEmail(auth, email.trim());
        setMessage("If an account exists for that email, a password reset link is on its way.");
      } else {
        await signInWithEmailAndPassword(auth, email.trim(), password);
      }
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-layout">
      <section className="auth-story">
        <div className="brand brand-large"><span className="brand-mark">T</span><span>TraceGuard</span></div>
        <div className="story-copy">
          <div className="eyebrow"><span className="status-dot" /> PRIVATE DEVICE RECOVERY</div>
          <h1>Your phone.<br /><em>Your security.</em><br />Your control.</h1>
          <p>A private place to check your phone’s last report and location history.</p>
        </div>
        <div className="auth-footnote">Reports can be delayed by Android, battery settings, permissions, or connectivity.</div>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <div className="section-kicker">OWNER ACCESS</div>
          <h2>{mode === "signup" ? "Create your account" : mode === "reset" ? "Reset your password" : "Welcome back"}</h2>
          <p className="muted">Sign in to view the devices registered to your account.</p>
          <form onSubmit={submit}>
            <label>Email address<input type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" /></label>
            {mode !== "reset" && <label>Password<input type="password" autoComplete={mode === "signup" ? "new-password" : "current-password"} required minLength={mode === "signup" ? 12 : 1} value={password} onChange={(event) => setPassword(event.target.value)} placeholder={mode === "signup" ? "At least 12 characters" : "Your password"} /></label>}
            {error && <div className="alert alert-error" role="alert">{error}</div>}
            {message && <div className="alert alert-success" role="status">{message}</div>}
            <button className="button button-primary button-wide" disabled={busy}>{busy ? "Please wait…" : mode === "signup" ? "Create account" : mode === "reset" ? "Send reset link" : "Sign in"}<span>↗</span></button>
          </form>
          <div className="auth-links">
            {mode === "signin" ? <><button className="link-button" onClick={() => { setMode("reset"); setError(""); }}>Forgot password?</button><button className="link-button" onClick={() => { setMode("signup"); setError(""); }}>Create an account</button></> : <button className="link-button" onClick={() => { setMode("signin"); setError(""); }}>Back to sign in</button>}
          </div>
          <div className="privacy-note"><span>◈</span> Only the signed-in owner can read their device data.</div>
        </div>
      </section>
    </main>
  );
}

function Sidebar({ page, setPage, user, onSignOut }: { page: Page; setPage: (page: Page) => void; user: User; onSignOut: () => void }) {
  const nav: { key: Page; icon: string; label: string }[] = [
    { key: "dashboard", icon: "⌂", label: "Overview" },
    { key: "history", icon: "◷", label: "Location history" },
    { key: "notifications", icon: "▤", label: "Notifications" },
    { key: "settings", icon: "⚙", label: "Device settings" },
  ];
  return (
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark">T</span><span>TraceGuard</span></div>
      <div className="side-label">YOUR SPACE</div>
      <nav>{nav.map((item) => <button key={item.key} className={`nav-item${page === item.key ? " active" : ""}`} onClick={() => setPage(item.key)}><span className="nav-icon">{item.icon}</span>{item.label}{page === item.key && <span className="nav-active-mark" />}</button>)}</nav>
      <div className="side-bottom">
        <div className="secure-card"><span className="secure-icon">◈</span><strong>Private by design</strong><span>Your locations are protected by your account.</span><span className="account-id">Account ID …{user.uid.slice(-4)}</span></div>
        <div className="profile-row"><span className="avatar">{user.email?.[0]?.toUpperCase() ?? "T"}</span><span className="profile-email" title={user.email ?? ""}>{user.email}</span><button className="icon-button" aria-label="Sign out" title="Sign out" onClick={onSignOut}>↪</button></div>
      </div>
    </aside>
  );
}

function Dashboard({ devices, latest, busy, error, setPage }: { devices: Device[]; latest: Record<string, LocationReport | null>; busy: boolean; error: string; setPage: (page: Page) => void }) {
  const points = devices.flatMap((device) => latest[device.deviceId] ? [{ device, location: latest[device.deviceId]! }] : []);
  const mostRecent = [...points].sort((a, b) => (b.location.receivedAt?.toMillis() ?? 0) - (a.location.receivedAt?.toMillis() ?? 0))[0];
  const freshness = ageLabel(mostRecent?.location.receivedAt);
  const latestLocation = mostRecent?.location;
  const [addressResult, setAddressResult] = useState<{ locationId: string; address: string | null; loading: boolean }>({ locationId: "", address: null, loading: false });
  useEffect(() => {
    if (!latestLocation) {
      setAddressResult({ locationId: "", address: null, loading: false });
      return;
    }
    let current = true;
    setAddressResult({ locationId: latestLocation.locationId, address: null, loading: true });
    void reverseGeocode(latestLocation.latitude, latestLocation.longitude).then((address) => {
      if (current) setAddressResult({ locationId: latestLocation.locationId, address, loading: false });
    });
    return () => { current = false; };
  }, [latestLocation?.locationId, latestLocation?.latitude, latestLocation?.longitude]);
  const addressText = !latestLocation
    ? "Waiting for the phone’s first location report."
    : addressResult.locationId !== latestLocation.locationId || addressResult.loading
      ? "Looking up the street address…"
      : addressResult.address ?? "Street address unavailable for this location.";
  return (
    <>
      <div className="page-heading"><div><div className="section-kicker">DEVICE SECURITY</div><h1>Overview</h1><p>See the latest reports from your registered phones.</p></div><div className="live-badge"><span className="status-dot" /> SECURE ACCOUNT</div></div>
      {error && <div className="alert alert-error page-alert" role="alert">{error}</div>}
      <section className="summary-grid">
        <article className="summary-card"><div className="summary-top"><span className="summary-label">REGISTERED DEVICES</span><span className="metric-icon">▣</span></div><div className="summary-number">{busy ? "—" : devices.length.toString().padStart(2, "0")}</div><div className="summary-foot">Your phones connected to TraceGuard</div></article>
        <article className="summary-card"><div className="summary-top"><span className="summary-label">LATEST REPORT</span><span className="metric-icon">◷</span></div><div className="summary-number summary-time">{mostRecent ? freshness.label : "No reports"}</div><div className="summary-foot">{mostRecent ? formatTimestamp(mostRecent.location.receivedAt) : "Waiting for your Android app"}</div></article>
        <article className="summary-card"><div className="summary-top"><span className="summary-label">ACCOUNT STATUS</span><span className="metric-icon">◈</span></div><div className="summary-number summary-state"><span className="status-dot" /> Protected</div><div className="summary-foot">Your data is private to this account</div></article>
      </section>
      <section className="content-grid">
        <article className="panel map-panel"><div className="panel-heading"><div><div className="section-kicker">LAST KNOWN LOCATION</div><h2>{mostRecent?.device.displayName ?? "Your phone"}</h2></div><button className="text-action" onClick={() => setPage("history")}>View history <span>→</span></button></div>
          <MapPanel locations={mostRecent ? [mostRecent.location] : []} compact />
          <div className="location-detail-grid">
            <div className="location-detail-card address-detail"><span className="meta-label">NEAREST STREET ADDRESS</span><strong>{addressText}</strong><small>{latestLocation ? `Latest phone location · GPS accuracy ±${Math.round(latestLocation.accuracyMeters)} m` : "Address appears after the phone reports its location"} · <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">© OpenStreetMap</a></small></div>
            <div className="location-detail-card network-detail"><span className="meta-label">CONNECTED NETWORK</span><strong>{latestLocation ? connectedNetwork(latestLocation) : "No network report yet"}</strong><small>{latestLocation ? internetAvailability(latestLocation) : "Network information appears with a phone report"}</small></div>
          </div>
          <div className="map-meta"><div><span className="meta-label">LAST CONTACT</span><strong className={freshness.stale ? "stale-text" : ""}>{mostRecent ? formatTimestamp(mostRecent.location.receivedAt) : "Waiting for first report"}</strong></div><div><span className="meta-label">ACCURACY</span><strong>{mostRecent ? `±${Math.round(mostRecent.location.accuracyMeters)} m` : "—"}</strong></div><div><span className="meta-label">FRESHNESS</span><strong className={freshness.stale ? "stale-text" : "fresh-text"}>{freshness.label}</strong></div></div>
        </article>
        <article className="panel device-panel"><div className="panel-heading"><div><div className="section-kicker">CONNECTED DEVICES</div><h2>Your phones</h2></div><span className="count-pill">{devices.length}</span></div>
          {devices.length ? <div className="device-list">{devices.map((device) => {
            const point = latest[device.deviceId];
            const age = ageLabel(point?.receivedAt);
            return <button className="device-row" key={device.deviceId} onClick={() => setPage("history")}><span className="phone-glyph">▯</span><span className="device-copy"><strong>{device.displayName}</strong><small>{point ? age.label : "Waiting for first report"}</small></span><span className={`status-chip ${device.trackingStatus}`}>{device.trackingStatus}</span><span className="row-chevron">›</span></button>;
          })}</div> : <div className="empty-device"><div className="empty-device-icon">▯</div><strong>No phones connected yet</strong><p>Install TraceGuard on your Android phone and sign in with this account to register it.</p><button className="button button-secondary" onClick={() => setPage("settings")}>Device setup steps <span>→</span></button></div>}
        </article>
      </section>
      <div className="freshness-note"><span>ⓘ</span><span>Location reports are periodic and best-effort. A new dashboard update means a report reached Firebase; it does not mean the phone is transmitting continuously.</span></div>
    </>
  );
}

function History({ devices, uid, onError }: { devices: Device[]; uid: string; onError: (message: string) => void }) {
  const [selected, setSelected] = useState("");
  const [points, setPoints] = useState<LocationReport[]>([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!selected && devices[0]) setSelected(devices[0].deviceId);
  }, [devices, selected]);
  useEffect(() => {
    if (!selected) { setPoints([]); return; }
    setLoading(true);
    return subscribeHistory(uid, selected, (items) => { setPoints(items); setLoading(false); }, (error) => { onError(error.message); setLoading(false); });
  }, [uid, selected, onError]);
  return (
    <>
      <div className="page-heading"><div><div className="section-kicker">REPORT ARCHIVE</div><h1>Location history</h1><p>Review the points your phone has successfully reported.</p></div>{devices.length > 0 && <label className="select-wrap"><span>DEVICE</span><select value={selected} onChange={(event) => setSelected(event.target.value)}>{devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.displayName}</option>)}</select></label>}</div>
      {devices.length ? <section className="panel history-panel"><div className="history-map"><MapPanel locations={points} /></div><div className="history-side"><div className="panel-heading"><div><div className="section-kicker">RECENT REPORTS</div><h2>{points.length} points</h2></div><span className="count-pill">LAST 100</span></div>{loading ? <div className="loading-line">Loading reports…</div> : points.length ? <div className="timeline">{points.map((point, index) => <div className="timeline-item" key={point.locationId}><span className={`timeline-dot${index === 0 ? " current" : ""}`} /><div><strong>{formatTimestamp(point.recordedAt)}</strong><small>Received {formatTimestamp(point.receivedAt)}</small><small>{point.latitude.toFixed(5)}, {point.longitude.toFixed(5)} · ±{Math.round(point.accuracyMeters)} m</small></div></div>)}</div> : <div className="empty-history"><span>◷</span><strong>No reports yet</strong><small>Location points appear here after your phone syncs.</small></div>}</div></section> : <section className="panel blank-state"><span className="blank-icon">◷</span><h2>Your history starts with your first phone</h2><p>Register an Android phone with this account to see reported locations here.</p></section>}
    </>
  );
}

function NotificationHistory({ devices, uid, onError }: { devices: Device[]; uid: string; onError: (message: string) => void }) {
  const [selected, setSelected] = useState("");
  const [items, setItems] = useState<NotificationEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (!selected && devices[0]) setSelected(devices[0].deviceId);
    else if (selected && !devices.some((device) => device.deviceId === selected)) setSelected(devices[0]?.deviceId ?? "");
  }, [devices, selected]);

  useEffect(() => {
    if (!selected) { setItems([]); return; }
    setLoading(true);
    return subscribeNotifications(uid, selected, (notifications) => { setItems(notifications); setLoading(false); }, (error) => { onError(error.message); setLoading(false); });
  }, [uid, selected, onError]);

  async function clearHistory() {
    const device = devices.find((item) => item.deviceId === selected);
    if (!device || !window.confirm(`Delete all forwarded notifications for ${device.displayName}? This cannot be undone.`)) return;
    setBusy(true);
    setNotice("");
    try {
      await deleteNotificationHistory(uid, selected);
      setNotice("Notification history deleted.");
    } catch (error) {
      onError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-heading"><div><div className="section-kicker">PHONE NOTIFICATIONS</div><h1>Notifications</h1><p>Recent notification text forwarded from your phone.</p></div>{devices.length > 0 && <label className="select-wrap"><span>DEVICE</span><select value={selected} onChange={(event) => setSelected(event.target.value)}>{devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.displayName}</option>)}</select></label>}</div>
      {notice && <div className="alert alert-success page-alert" role="status">{notice}</div>}
      {devices.length ? <section className="panel notifications-panel"><div className="panel-heading"><div><div className="section-kicker">LATEST 100</div><h2>{items.length} notifications</h2></div><button className="button button-danger" disabled={busy || loading || items.length === 0} onClick={() => void clearHistory()}>{busy ? "Deleting…" : "Clear history"}</button></div>
        {loading ? <div className="loading-line">Loading notifications…</div> : items.length ? <div className="notification-feed">{items.map((item) => <article className="notification-item" key={item.notificationId}><span className="notification-app-mark">{item.appName.slice(0, 1).toUpperCase()}</span><div className="notification-copy"><div className="notification-meta"><strong>{item.appName}</strong><time>{formatTimestamp(item.receivedAt)}</time></div>{item.title && <strong className="notification-title">{item.title}</strong>}{item.body && <p>{item.body}</p>}<small>Posted {formatTimestamp(item.postedAt)}</small></div></article>)}</div> : <div className="empty-notifications"><span>▤</span><strong>No forwarded notifications yet</strong><p>On your phone, open TraceGuard from Android Settings → Apps and enable notification forwarding. Android requires you to grant Notification Access.</p></div>}
      </section> : <section className="panel blank-state"><span className="blank-icon">▤</span><h2>Notifications appear after your phone is registered</h2><p>Install TraceGuard, sign in, and enable notification forwarding in the Android app.</p></section>}
    </>
  );
}

function Settings({ user, devices, webSessions, currentSessionId, webSessionError, onError }: { user: User; devices: Device[]; webSessions: WebSession[] | null; currentSessionId: string; webSessionError: string; onError: (message: string) => void }) {
  const [nameDrafts, setNameDrafts] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState("");
  const [notice, setNotice] = useState("");
  const activeWebSessionCount = webSessions?.filter((session) =>
    !session.revokedAt && session.lastSeen && Date.now() - session.lastSeen.toMillis() <= 15 * 60 * 1000,
  ).length;
  async function logOutSession(session: WebSession) {
    setBusyId(session.sessionId);
    try {
      await logOutWebSession(user.uid, session.sessionId);
      setNotice(`${session.deviceLabel} was logged out. If it is offline, it will sign out when it reconnects.`);
    } catch (error) { onError(errorMessage(error)); }
    finally { setBusyId(""); }
  }
  async function changeName(device: Device) {
    const nextName = (nameDrafts[device.deviceId] ?? device.displayName).trim();
    if (!nextName || nextName.length > 80) { onError("Device name must be between 1 and 80 characters."); return; }
    setBusyId(device.deviceId);
    try { await renameDevice(user.uid, device, nextName); setNotice("Device name saved."); }
    catch (error) { onError(errorMessage(error)); }
    finally { setBusyId(""); }
  }
  async function toggle(device: Device) {
    const next = device.trackingStatus === "active" ? "paused" : "active";
    setBusyId(device.deviceId);
    try {
      await setTrackingStatus(user.uid, device, next);
      setNotice(next === "paused"
        ? "Pause request sent. The phone applies it on its next background check, which Android may delay."
        : "Start request sent. After its next background check, a consented phone with internet and all-time location access can send periodic reports. Continuous live tracking still needs to be started on the phone.");
    }
    catch (error) { onError(errorMessage(error)); }
    finally { setBusyId(""); }
  }
  async function remove(device: Device) {
    if (!window.confirm(`Delete ${device.displayName} and all of its stored location history? This cannot be undone.`)) return;
    setBusyId(device.deviceId);
    try { await deleteDeviceAndHistory(user.uid, device); setNotice("Device and stored location history deleted."); }
    catch (error) { onError(errorMessage(error)); }
    finally { setBusyId(""); }
  }
  return (
    <>
      <div className="page-heading"><div><div className="section-kicker">OWNER CONTROLS</div><h1>Device settings</h1><p>Manage your account and registered TraceGuard phones.</p></div></div>
      {notice && <div className="alert alert-success page-alert" role="status">{notice}</div>}
      <section className="panel settings-section"><div className="panel-heading"><div><div className="section-kicker">ACCOUNT</div><h2>Sign-in details</h2></div><span className="settings-icon">◈</span></div><div className="account-details"><div><span className="meta-label">EMAIL ADDRESS</span><strong>{user.email}</strong></div><div><span className="meta-label">EMAIL VERIFIED</span><strong>{user.emailVerified ? "Yes" : "Not yet"}</strong></div></div>{!user.emailVerified && <p className="settings-hint">Verify your email address through the link Firebase sent when you created your account.</p>}</section>
      <section className="panel settings-section"><div className="panel-heading"><div><div className="section-kicker">WEB ACCESS</div><h2>Signed-in browsers</h2></div><span className="count-pill">{activeWebSessionCount === undefined ? "…" : `${activeWebSessionCount} active`}</span></div><p className="settings-hint">Browsers seen in the last 15 minutes are active. Each browser profile is listed once; separate browsers on the same device can appear separately. Logging out an offline browser takes effect when it reconnects.</p>{webSessionError ? <div className="alert alert-error" role="alert">Session list unavailable: {webSessionError}</div> : webSessions === null ? <div className="loading-line">Loading signed-in browsers…</div> : webSessions.length ? <div className="web-session-list">{webSessions.map((session) => { const current = session.sessionId === currentSessionId; const active = !session.revokedAt && session.lastSeen !== null && Date.now() - session.lastSeen.toMillis() <= 15 * 60 * 1000; return <div className="web-session-row" key={session.sessionId}><div className="web-session-copy"><strong>{session.deviceLabel}{current ? " · This browser" : ""}</strong><small>{session.revokedAt ? `Logged out ${formatTimestamp(session.revokedAt)}` : `Last seen ${formatTimestamp(session.lastSeen)}`}</small></div><div className="web-session-actions"><span className={`status-chip ${session.revokedAt ? "revoked" : active ? "active" : "paused"}`}>{session.revokedAt ? "Logged out" : active ? "Active" : "Not recently seen"}</span>{!session.revokedAt && <button className="button button-danger" disabled={busyId === session.sessionId} onClick={() => void logOutSession(session)}>{busyId === session.sessionId ? "Logging out…" : "Log out"}</button>}</div></div>; })}</div> : <div className="empty-settings">No web sessions have checked in yet.</div>}</section>
      <section className="panel settings-section"><div className="panel-heading"><div><div className="section-kicker">REGISTERED PHONES</div><h2>Manage devices</h2></div><span className="count-pill">{devices.length}</span></div>{devices.length ? <div className="settings-devices">{devices.map((device) => <div className="settings-device" key={device.deviceId}><div className="settings-device-head"><span className="phone-glyph">▯</span><div><strong>{device.displayName}</strong><small>Registered {formatTimestamp(device.registeredAt)}</small></div><span className={`status-chip ${device.trackingStatus}`}>{device.trackingStatus === "active" ? "reports on" : device.trackingStatus}</span></div><div className="name-editor"><input aria-label={`Name for ${device.displayName}`} maxLength={80} value={nameDrafts[device.deviceId] ?? device.displayName} onChange={(event) => setNameDrafts((old) => ({ ...old, [device.deviceId]: event.target.value }))} /><button className="button button-secondary" disabled={busyId === device.deviceId} onClick={() => void changeName(device)}>Save name</button></div><div className="device-actions"><button className="button button-secondary" disabled={busyId === device.deviceId || device.trackingStatus === "revoked"} onClick={() => void toggle(device)}>{device.trackingStatus === "active" ? "Pause reports" : "Start / resume reports"}</button><button className="button button-danger" disabled={busyId === device.deviceId} onClick={() => void remove(device)}>Delete phone data</button></div><p className="settings-hint">Remote start runs as periodic background reports after consent, sign-in, all-time location permission, and internet are available. Android may delay them by 15 minutes or more. Continuous live tracking must be started on the phone and keeps the location notification visible; system permissions and indicators stay under the phone owner’s control.</p></div>)}</div> : <div className="empty-settings">No phones are registered to this account yet.</div>}</section>
      <section className="panel settings-section privacy-settings"><div className="panel-heading"><div><div className="section-kicker">DATA & PRIVACY</div><h2>Your location data</h2></div><span className="settings-icon">⌖</span></div><p>Only this signed-in account can read its device records. You can delete a phone and its location history above. Location data is stored in the Johannesburg Firestore region.</p><div className="retention-callout"><strong>Retention</strong><span>Location history is kept until you delete it. Automatic 30-day cleanup is not enabled yet; the current Firebase setup is on the no-cost plan.</span></div></section>
    </>
  );
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [page, setPage] = useState<Page>("dashboard");
  const [devices, setDevices] = useState<Device[]>([]);
  const [latest, setLatest] = useState<Record<string, LocationReport | null>>({});
  const [webSessions, setWebSessions] = useState<WebSession[] | null>(null);
  const [currentSessionId, setCurrentSessionId] = useState("");
  const [webSessionError, setWebSessionError] = useState("");
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const reportError = useCallback((message: string) => setError(message), []);
  const signOutCurrentSession = useCallback(async () => {
    if (user && currentSessionId) {
      try { await logOutWebSession(user.uid, currentSessionId); } catch { /* Always complete the local sign-out. */ }
    }
    await signOut(auth);
  }, [user?.uid, currentSessionId]);

  useEffect(() => onAuthStateChanged(auth, (next) => { setUser(next); setAuthReady(true); }), []);
  useEffect(() => {
    if (!user) {
      setWebSessions(null);
      setCurrentSessionId("");
      setWebSessionError("");
      return;
    }
    setWebSessions(null);
    setWebSessionError("");
    return subscribeWebSessions(
      user.uid,
      setWebSessions,
      setCurrentSessionId,
      () => { void signOut(auth); },
      (reason) => setWebSessionError(reason.message),
    );
  }, [user?.uid]);
  useEffect(() => {
    if (!user) { setDevices([]); setLatest({}); setBusy(false); return; }
    setBusy(true);
    return subscribeDevices(user.uid, (items) => { setDevices(items); setBusy(false); setError(""); }, (reason) => { setError(reason.message); setBusy(false); });
  }, [user]);
  const deviceKey = useMemo(() => devices.map((device) => device.deviceId).sort().join("|"), [devices]);
  useEffect(() => {
    if (!user || !deviceKey) { setLatest({}); return; }
    const unsubscribers = devices.map((device) => subscribeLatestLocation(user.uid, device.deviceId, (point) => setLatest((old) => ({ ...old, [device.deviceId]: point })), (reason) => setError(reason.message)));
    return () => unsubscribers.forEach((unsubscribe) => unsubscribe());
  }, [user, deviceKey]);

  if (!authReady) return <div className="full-loader"><span className="loader-ring" />Loading your private dashboard…</div>;
  if (!user) return <AuthScreen />;

  return (
    <div className="app-shell">
      <Sidebar page={page} setPage={setPage} user={user} onSignOut={() => void signOutCurrentSession()} />
      <main className="main-area">
        <header className="topbar"><div className="breadcrumbs"><span>TraceGuard</span><span>/</span><strong>{page === "dashboard" ? "Overview" : page === "history" ? "Location history" : page === "notifications" ? "Notifications" : "Device settings"}</strong></div><div className="topbar-right"><span className="secure-mini"><span>◈</span> Owner-only data</span><span className="avatar avatar-small">{user.email?.[0]?.toUpperCase() ?? "T"}</span></div></header>
        <div className="page-content">
          {error && <div className="alert alert-error page-alert" role="alert"><span>{error}</span><button className="icon-button" onClick={() => setError("")} aria-label="Dismiss error">×</button></div>}
          {page === "dashboard" && <Dashboard devices={devices} latest={latest} busy={busy} error="" setPage={setPage} />}
          {page === "history" && <History devices={devices} uid={user.uid} onError={reportError} />}
          {page === "notifications" && <NotificationHistory devices={devices} uid={user.uid} onError={reportError} />}
          {page === "settings" && <Settings user={user} devices={devices} webSessions={webSessions} currentSessionId={currentSessionId} webSessionError={webSessionError} onError={reportError} />}
          <footer className="page-footer"><span>TRACEGUARD <span className="footer-dot">·</span> PRIVATE DEVICE RECOVERY</span><span>Location reports are best-effort and may be delayed.</span></footer>
        </div>
      </main>
    </div>
  );
}
