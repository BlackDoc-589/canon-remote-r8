# Canon Remote — EOS R8 Wi-Fi remote shooting without a Canon ID

An Android app (Kotlin + Jetpack Compose) that talks directly to your EOS R8
over Wi-Fi using Canon's own **CCAPI (Camera Control API)** — an HTTP
interface the camera serves itself. No Canon account, Camera Connect app,
or internet connection is involved anywhere in this flow.

What it does:
- Connects to the camera over your phone's Wi-Fi (local network or the
  camera's own access point)
- Live view streaming (MJPEG) shown in the app
- Shutter release — one-shot "shoot" button, and separate half-press /
  full-press / release for manual control
- Basic exposure settings: ISO, aperture (Av), shutter speed (Tv),
  exposure compensation
- A "Debug" tab to send raw `GET` requests against the camera's CCAPI, so
  you can see exactly which endpoints/values your firmware exposes

## 1. Enable CCAPI on the EOS R8

On the camera: **Menu → Communication functions → Camera Control API**

1. Set **Camera Control API** to **On**.
2. Set **HTTP/HTTPS** — pick HTTP for simplicity, or HTTPS (the app trusts
   the camera's self-signed certificate automatically). Note the port
   number shown.
3. Under **User authentic.**, choose **Edit account** and set a local
   username and password (this is *not* a Canon account — it's a
   credential pair the camera itself checks). You can disable
   authentication, but the camera will warn you that this lets anyone on
   the network control it.
4. Under **Connect**, use **Add with wizard**:
   - **Camera access point mode** — the camera creates its own Wi-Fi
     network; join it from your phone's Wi-Fi settings.
   - **Existing access point** — both camera and phone join your normal
     Wi-Fi/router.
5. Once connected, the camera's screen shows its **IP address** and the
   **port** — that's what you type into the app.

(Source: Canon's own camera manual pages for CCAPI setup —
https://cam.start.canon/en/C014/manual/html/UG-04_Network_0060.html)

## 2. Build and run the app

1. Install **Android Studio** (free, from Google).
2. Open this folder (`CanonRemoteApp`) as a project — Android Studio will
   offer to generate the Gradle wrapper; accept it, or point it at a local
   Gradle 8.x install.
3. Let it sync, then press **Run** with your phone connected over USB
   (enable Developer options → USB debugging), or build **Build → Build
   Bundle(s)/APK(s) → Build APK(s)** and install the APK manually.
4. On your phone, connect to the camera's Wi-Fi network (see step 1
   above) before opening the app.

## 3. Using the app

- **Connect screen**: enter the camera's IP and port, HTTP/HTTPS, and the
  username/password you set in the camera menu (leave blank if you
  disabled authentication). Tap **Connect**.
- **Shoot tab**: "Start live view" begins the MJPEG preview; "Shoot" fires
  AF + capture in one tap; the three small buttons give manual
  half-press/full-press/release control, useful for bulb exposures or
  precise timing.
- **Settings tab**: shows/edits ISO, Av, Tv and exposure compensation as
  raw CCAPI value strings (e.g. `"400"`, `"5.6"`, `"1/125"`).
- **Debug tab**: fire a raw `GET` at any CCAPI path (starts at `/ccapi/`,
  which lists everything the camera supports) to see exact values/paths
  if something doesn't match your firmware.

## About CCAPI endpoint versions

Canon has revised CCAPI across firmware updates (`ver100` through at least
`ver140` at the time of writing), occasionally renaming or adding
endpoints. This app doesn't hard-code a version: on connect, it calls
`GET /ccapi/` and uses whichever version the camera actually advertises.
The endpoint *names* used here (`shooting/control/shutterbutton`,
`shooting/liveview/flip`, `shooting/settings/iso`, etc.) match Canon's
published CCAPI reference and are stable across versions in practice, but
if your exact firmware differs, use the Debug tab to check
`/ccapi/<version>/shooting` and adjust the path constants in
`CameraRepository.kt` (`apiPaths` object) accordingly.

## Why this is the legitimate approach

Canon Camera Connect's Canon-ID sign-in is only required for its cloud
features (image Cloud sync, remote firmware notices, etc.) — the EOS R8's
own Wi-Fi remote-control interface, CCAPI, was built by Canon specifically
so third-party software can do exactly this without any of that. This
project is a from-scratch client against that public API; it contains none
of Canon's app code or assets.
