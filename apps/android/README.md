# NXFR Android Application

## Background Behavior Contract

NXFR respects user battery and privacy by following an explicit lifecycle contract:

1. **Visibility-Tied Lifetime**:
   - **Visible = ON**: Foreground service remains active with notification `"NXFR visible on LAN — tap to manage"`.
   - **Visible = OFF**: If no active transfer and web server is stopped, the service immediately calls `stopForeground(true)` and `stopSelf()`. The background process terminates cleanly with zero battery drain.
   - **Active Transfer / Web Upload**: Keeps service alive until the active transfer completes, fails, or is cancelled, then re-evaluates the lifecycle rule contract.

2. **App Swipe Removal (`onTaskRemoved`)**:
   - When the user swipes NXFR away from recent apps, the lifecycle contract rule engine re-evaluates immediately. If visibility is OFF and no transfer is active, the foreground service stops.

3. **Battery Optimization**:
   - Access **Settings → Battery & background** to disable OEM battery optimization for seamless background transfers.
