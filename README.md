# DepthMaker

Grayscale depth maps from short video clips, built to the DepthMaker v1.1 spec.

Two pieces, in the order they must be built:

| | What it is | Where |
|---|---|---|
| **Backend** | FastAPI + a separate GPU worker running Video Depth Anything | [`backend/`](backend) |
| **Android app** | Thin client: pick → upload → poll → download → save. Zero inference. | [`android/`](android) |

The app cannot run the model on the phone and does not try to. VDA is a
spatio-temporal model consuming a 32-frame sliding window; per-frame mobile
models (MiDaS Small, DA-V2 Small on TFLite) flicker on static pixels, and the
weights need 6.8–23.6 GB of VRAM. See spec §0.

---

## Cartoon mode — on-device, no server

The brush icon in the top bar opens a second, completely separate path: a
GPU filter chain that runs on the phone. It shares nothing with the depth flow —
no upload, no backend, no GPU bill.

```
MediaExtractor → MediaCodec decoder → SurfaceTexture → GLES filter → encoder Surface → MediaMuxer
```

Surface to surface: no frame is ever copied into CPU memory, which is what makes
**720p at 60 fps** run faster than real time on mid-range hardware. Five styles
(Cartoon, Comic, Sketch, Pop Art, Original) with a strength slider; the audio
track is remuxed untouched.

Two limits are deliberate and surfaced in the UI rather than hidden:

* **Frame rate follows the source.** A 30 fps clip exports at 30. Reaching 60
  from 30 needs frame interpolation, which is a model, not a shader — it would
  put this path back on a server.
* **720p60 is probed, not assumed.** `EncoderCapabilities` asks the platform
  whether the AVC encoder takes 1280×720 @ 60 before the export commits, and
  `ExportPlanner` steps down frame rate first, then resolution, saying which it
  did. Fine on essentially everything since ~2016; "essentially" is not "all".

`ExportPlanner` and the frame-rate gate are covered by JVM unit tests
(`./gradlew testDebugUnitTest`) — the GL and MediaCodec halves need a device.

---

## Getting the APK

Every push to a branch builds it. Download from either:

* **Releases** — each successful build publishes `DepthMaker-debug.apk` and
  `DepthMaker-release.apk` under a tag `apk-build-<n>`.
* **Actions → Build APK → Artifacts → `DepthMaker-apk`.**

Both APKs are installable by sideload. `release` is signed with the debug key
unless a keystore is configured (see below), so install one or the other, not
both — Android refuses an update signed by a different key.

To sign release builds with a real key, set these environment variables in the
workflow (from repository secrets) and the `release` signing config picks them up:
`DEPTHMAKER_KEYSTORE`, `DEPTHMAKER_KEYSTORE_PASSWORD`, `DEPTHMAKER_KEY_ALIAS`,
`DEPTHMAKER_KEY_PASSWORD`.

### You need a backend running

The app does no inference on the phone, so without a server it will say
"Server set nahi hai" and stop. If you don't have a GPU, the
[Colab notebook](backend/colab/DepthMaker_Colab.ipynb) gives you a free one with an
HTTPS URL in about ten minutes —
[open it directly](https://colab.research.google.com/github/foonkoons-cyber/oliv/blob/claude/build-app-apk-cr4a7j/backend/colab/DepthMaker_Colab.ipynb).

### First run

The app ships with no server baked in. Open **Settings** (gear, top right) and set:

* **Server URL** — your backend, `https://…` only. Plain `http://` is rejected at
  save time because Android blocks cleartext by default and it would look like a
  server bug.
* **Bearer token** — the same value as `DEPTHMAKER_TOKEN` on the server.

Defaults are Standard model (`vits`, Apache-2.0, commercial-safe) and MP4 output.

---

## Licensing — read before client work

| Model | License | Commercial use |
|---|---|---|
| Small (`vits`) | Apache-2.0 | **Yes** |
| Base (`vitb`) | CC-BY-NC-4.0 | No |
| Large (`vitl`) | CC-BY-NC-4.0 | No |

The app defaults to `vits` and shows the non-commercial warning inline next to
the High Quality option. For paid client delivery, stay on Standard.

---

## Repository layout

```
android/     Kotlin + Compose client (minSdk 29, targetSdk 35)
             app/src/main/kotlin/.../toon/  on-device GL + MediaCodec cartoon export
backend/     FastAPI API, GPU worker, depth pipeline, tests
.github/     APK build + backend test workflows
```

Backend setup, deployment and the acceptance checklist: [`backend/README.md`](backend/README.md).
