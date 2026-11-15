# Android permissions and owner disclosure

This is the planned permission policy. The implementation phase must verify each permission against the selected Android target SDK and current platform rules before adding it to the manifest.

## Consent flow

1. Explain what TraceGuard collects, why, when it may report, where it is stored, and who can view it.
2. Ask the owner to enable tracking for their own device; do not enable collection before consent.
3. Request foreground location at the point it is needed and explain the dashboard feature it enables.
4. Request background location separately only if periodic reporting while the app is not visible is implemented and allowed for the selected Android version. Explain the system settings step if Android requires it.
5. Request notification permission when required to deliver a foreground service's required notification. The app can avoid optional sounds and vibration but cannot suppress Android-required notification disclosure or privacy indicators.
6. Explain that Wi-Fi SSID may be unavailable and may require location-related permissions or other Android conditions.
7. Treat notification forwarding as a separate, optional capability. Explain that it sends notification app names, titles, text, and times to the owner's Firebase account; previews can contain private messages or verification codes. The owner must manually grant Android Notification Access and can pause forwarding in TraceGuard or revoke access in Android settings.

## Planned permission categories

| Capability | Planned permission category | Notes |
| --- | --- | --- |
| Location while app is visible | Coarse and/or fine location | Ask for the least precision that meets the owner's selected use. Handle approximate location. |
| Periodic background location | Background location, where required | Separate, clearly explained request; availability depends on Android version and policy. |
| Active tracking session | Location foreground service and location permissions | Owner-initiated, persistent required notification, subject to current Android rules. |
| Network state | Network state access | Used to report connection type and validation state. |
| Mobile operator/network type | Telephony state access as applicable | Collect only what is available without restricted identifiers. Never request IMEI. |
| Wi-Fi SSID | Wi-Fi/location-related access as applicable | Return unavailable when permission or platform conditions are not met. |
| Notification forwarding | Android Notification Access special access | Optional and off by default. Forward app name, title, text, and timestamps only after separate in-app disclosure/consent and the owner grants access in Android settings. Do not forward attachments or actions. |
| Reboot recovery | Boot completed receiver, if allowed | Schedule eligible work only through documented mechanisms; no restart loop. |

## Owner controls

- A visible setup and settings screen explains tracking state.
- The owner can pause/stop tracking and sign out.
- Revoking a device disables further authorized uploads and supports deleting its records.
- Notification forwarding is separately opt-in and can be paused in the app; the owner can clear notification history from the dashboard or revoke Android Notification Access.
- Permission denial or revocation stops the affected collection and is surfaced clearly when the app next runs.
- Android Settings remains the authoritative place to force-stop or uninstall the app.
