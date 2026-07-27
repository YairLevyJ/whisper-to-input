# QuickDictate

> ### Modified version notice
>
> **This is a modified version of [whisper-to-input](https://github.com/j3soon/whisper-to-input) by Yan-Bin Diau, Johnson Sun and Ying-Chou Sun.**
>
> Modified by [@YairLevyJ](https://github.com/YairLevyJ) in July 2026. The changes are
> summarised in [What is different from upstream](#what-is-different-from-upstream) and are
> visible in full in this repository's commit history.
>
> This fork is **not** affiliated with, endorsed by, or supported by the original authors.
> Please do not report problems with this fork to the upstream project — open an issue
> [here](https://github.com/YairLevyJ/whisper-to-input/issues) instead.
>
> Like the original, this fork is licensed under the **GPLv3**. See [License and credits](#license-and-credits).

QuickDictate is an Android keyboard that transcribes speech to text and types the result into
whatever field you are editing. Recording happens on the device; transcription happens on a
speech-to-text service that you point it at.

It was forked to improve transcription accuracy for Hebrew, but nothing in it is
Hebrew-specific — the audio changes help any language, and the interface ships in both English
and Hebrew.

Supported backends: any **OpenAI-compatible** transcription endpoint (the official
[OpenAI API](https://platform.openai.com/docs/guides/speech-to-text),
[Groq](https://console.groq.com/docs/speech-to-text), or similar),
[Whisper ASR Webservice](https://github.com/ahmetoner/whisper-asr-webservice), or
[NVIDIA NIM](https://build.nvidia.com/openai/whisper-large-v3).

Requires Android 7.0 (API 24) or newer.

## What is different from upstream

### Audio is no longer compressed before it is sent

This is the change that motivated the fork, and the one that matters most.

Upstream recorded through `MediaRecorder`, which encoded to **AMR-NB** in an MP4 container for
the OpenAI-compatible backends. AMR-NB is an 8 kHz narrowband codec running at a few kbit/s,
designed in the 1990s so that speech would survive a weak cellular link for a *human* listener
who fills in the gaps from context. It discards essentially everything above ~3.4 kHz — which is
exactly where the consonants that distinguish similar words live.

A speech recognition model does not fill in those gaps the way a person does; what it never
received, it guesses at. QuickDictate captures raw PCM through `AudioRecord` (**16 kHz, mono,
16-bit**, `VOICE_RECOGNITION` source) and streams it straight into a WAV file, uploaded as
`audio/wav`. 16 kHz mono is what Whisper-family models consume internally, so there is no longer
a lossy encode on the phone followed by a decode on the server.

All backends now receive the same WAV payload, replacing upstream's per-backend m4a/ogg split.

### Interface language

An **Interface Language** setting offers the device language (default), English, or Hebrew, with
a full Hebrew translation. The layout mirrors right-to-left when Hebrew is selected.

### Other changes

- **Prompt setting** — optional free-text context or vocabulary hint, sent as the standard
  `prompt` field to OpenAI-compatible endpoints (and `initial_prompt` to Whisper ASR Webservice).
  Useful for biasing recognition toward names, jargon, or a particular language.
- **Auto stop recording** — a voice-activity-detection state machine that finishes the recording
  after a few seconds of silence, and cancels it if speech never starts. Upstream shipped the
  tuning constants for this but never wired it up. Off by default.
- **Microphone audio effects** — optionally enables the device noise suppressor and automatic
  gain control. Off by default: the `VOICE_RECOGNITION` audio source deliberately leaves audio
  unprocessed because that is what recognition models want, and both effects can *reduce*
  accuracy.
- **Chinese postprocessing removed** — upstream converted transcripts between Traditional and
  Simplified Chinese, defaulting to Traditional, which silently rewrote output for everyone else.
  The feature and its third-party dependency are gone. Chinese *transcription* is unaffected —
  that comes from the model, not from this app.
- **Storage hardening** — recordings moved from the external cache directory to the internal one
  (on Android 9 and below, external storage is readable by any app holding
  `READ_EXTERNAL_STORAGE`), the unused `WRITE_EXTERNAL_STORAGE` permission was dropped, and
  `allowBackup` is now `false` so the API key is no longer swept into cloud backups.
- **Settings are stored by stable value, not display label** — upstream persisted the English
  text shown in each dropdown and compared against it at runtime, which would have broken backend
  selection the moment the interface was translated. Existing settings are migrated automatically.
- **Distinct application ID** (`com.yair.whispergroqinput`) so this fork installs alongside the
  original rather than colliding with it.
- **Releases are proper release builds** — signed with a stable key and not debuggable, with
  build provenance attestation (see [Verifying a download](#verifying-a-download)). Upstream
  ships debug builds.

## Installation

1. Download the `.apk` from [the latest release](https://github.com/YairLevyJ/whisper-to-input/releases/latest).

2. Open the file on your phone and tap `Install`.

   <img src='docs/images/01-apk-file.jpg' width='200'>
   <img src='docs/images/02-installing-apk.jpg' width='200'>

3. An `Unsafe app blocked` warning will appear, because the app is not distributed through the
   Play Store. Tap `More details`, then `Install anyway`.

   <img src='docs/images/03-unsafe-app-blocked.jpg' width='200'>
   <img src='docs/images/04-unsafe-app-install-anyway.jpg' width='200'>

4. Allow the app to record audio and to post notifications. Both are required; see
   [Permissions](#permissions). If you deny them by accident you will have to grant them from the
   system app settings page.

   <img src='docs/images/06-record-audio-permission.jpg' width='200'>

5. Open the app and fill in the settings — see [Settings](#settings) and [Backends](#backends)
   below.

6. Enable the keyboard in the system settings. The exact path varies by Android version and
   vendor; on most devices it is `Settings > System > Languages & input > On-screen keyboard`.

   <img src='docs/images/11-settings-languages-and-input.jpg' width='200'>
   <img src='docs/images/12-settings-on-screen-keyboard.jpg' width='200'>
   <img src='docs/images/15-settings-on-screen-keyboard-on.jpg' width='200'>

7. Open any app with a text field, tap the input box, and switch input method to
   **QuickDictate**.

8. Tap the microphone to start recording, and tap it again when you are done. The transcript is
   typed into the field.

> The screenshots above are inherited from the upstream project and predate this fork. The
> installation flow is unchanged, but the app appears as `Whisper Input` in them, and the
> settings screen has since gained several options.

## Verifying a download

Every release APK carries a **build provenance attestation** issued by GitHub's Sigstore instance
and bound to this repository's release workflow. It lets anyone confirm that a downloaded APK
really was built by this repository, from a specific commit, and has not been modified since:

```sh
gh attestation verify QuickDictate-v1.0.1.apk --repo YairLevyJ/whisper-to-input
```

This is separate from the APK's own signature. The Android signature proves that an update comes
from the same signer as the copy already installed; the attestation proves which source code the
file was built from. Android has no notion of a signature "from GitHub", so the two answer
different questions.

## Keyboard usage

<img src='docs/images/keyboard-layout.jpg' width='200'>

- **Microphone** (centre) — start recording; tap again to stop and insert the transcript.
- **Cancel** (bottom left, while recording) — discard the current recording.
- **Backspace** (upper right) — delete the previous character; press and hold to repeat.
- **Enter** (bottom right) — insert a newline. Pressing it while recording stops the recording
  and inserts the transcript followed by a newline.
- **Space** — insert a space. Pressing it while recording stops the recording and inserts the
  transcript followed by a space.
- **Settings** (upper left) — open the app settings.
- **Switch** (upper left) — switch back to the previous input method.
- **Retry** — rerun transcription on the last recording, so a network failure does not mean
  saying it all again.

## Settings

| Setting | Meaning |
| --- | --- |
| Interface Language | Language of the app and keyboard UI. Follows the device language by default. |
| Speech to Text Backend | Which kind of service the endpoint is. See [Backends](#backends). |
| Endpoint | Full URL that transcription requests are sent to. |
| API Key | Sent as `Authorization: Bearer` for OpenAI-compatible backends. |
| Model | Model name to request, e.g. `whisper-1`. |
| Language Code | Language of the **speech being transcribed** (e.g. `he`, `en`). Not the interface language. Leave empty to let the model detect it. |
| Prompt | Optional context or vocabulary hint to bias recognition. May be left empty. |
| Auto Stop Recording | Finish recording automatically after a few seconds of silence. Off by default. |
| Microphone Audio Effects | Apply the device noise suppressor and automatic gain control. Off by default, since they can reduce accuracy. |
| Auto Recording Start | Start recording as soon as the keyboard opens. |
| Auto Switch Back | Return to the previous keyboard once the transcript is inserted. |
| Add Trailing Space | Append a space after the transcript. |

## Backends

### OpenAI-compatible API

Anything implementing OpenAI's `/v1/audio/transcriptions` endpoint. Two common choices:

```
Speech to Text Backend:  OpenAI API
Endpoint:                https://api.openai.com/v1/audio/transcriptions
API Key:                 sk-...
Model:                   whisper-1
Language Code:           he
```

```
Speech to Text Backend:  OpenAI API
Endpoint:                https://api.groq.com/openai/v1/audio/transcriptions
API Key:                 gsk_...
Model:                   (see Groq's speech-to-text docs)
Language Code:           he
```

See the [OpenAI](https://platform.openai.com/docs/guides/speech-to-text) and
[Groq](https://console.groq.com/docs/speech-to-text) documentation for the models each offers.

### Whisper ASR Webservice

A self-hosted open-source Whisper server —
[whisper-asr-webservice](https://github.com/ahmetoner/whisper-asr-webservice). Setup is described
in [upstream PR #13](https://github.com/j3soon/whisper-to-input/pull/13).

```
Speech to Text Backend:  Whisper ASR Webservice
Endpoint:                http://<SERVER_IP>:9000/asr
API Key:
Model:
Language Code:           he
```

### NVIDIA NIM (self-hosted)

NVIDIA's TensorRT-LLM-optimised Whisper, via
[the whisper-large-v3 NIM](https://build.nvidia.com/openai/whisper-large-v3). Requires a
self-hosted GPU server.

```
Speech to Text Backend:  NVIDIA NIM
Endpoint:                http://<SERVER_IP>:9000/v1/audio/transcriptions
API Key:
Model:
Language Code:           multi
```

> **Untested in this fork.** Upstream sent OGG/Opus to this backend; QuickDictate sends WAV to
> every backend. NVIDIA Riva supports `LINEAR_PCM` natively, so this should work, but it has not
> been verified against a live NIM instance. Please
> [open an issue](https://github.com/YairLevyJ/whisper-to-input/issues) if it does not.

<details>
<summary>Deploying the NIM container</summary>

After generating an NGC API key, follow
[the deployment guide](https://build.nvidia.com/openai/whisper-large-v3/deploy):

```sh
export NGC_API_KEY=<PASTE_API_KEY_HERE>
export LOCAL_NIM_CACHE=~/.cache/nim
mkdir -p "$LOCAL_NIM_CACHE"

docker run -it --rm --name=riva-asr \
   --runtime=nvidia \
   --gpus '"device=0"' \
   --shm-size=8GB \
   -e NGC_API_KEY \
   -e NIM_HTTP_API_PORT=9000 \
   -e NIM_GRPC_API_PORT=50051 \
   -v "$LOCAL_NIM_CACHE:/opt/nim/.cache" \
   -u $(id -u) \
   -p 9000:9000 \
   -p 50051:50051 \
   -e NIM_TAGS_SELECTOR=name=whisper-large-v3 \
   nvcr.io/nim/nvidia/riva-asr:1.3.0
```

Startup takes a while, and ends with:

```
INFO:uvicorn.error:Uvicorn running on http://0.0.0.0:9000 (Press CTRL+C to quit)
```

Check readiness and try a sample:

```sh
curl -X 'GET' 'http://localhost:9000/v1/health/ready'
# {"ready":true}

# MP3 will not work, use wav instead.
wget https://github.com/audio-samples/audio-samples.github.io/raw/refs/heads/master/samples/wav/ted_speakers/BillGates/sample-0.wav

curl --request POST \
  --url http://localhost:9000/v1/audio/transcriptions \
  --header 'Content-Type: multipart/form-data' \
  --form file=@./sample-0.wav \
  --form language=multi \
  --form response_format=text
# "A cramp is no small danger on a swim. "
```

</details>

## Privacy and data handling

- Recordings are written to the app's **internal** cache directory, which other apps cannot read,
  and are deleted after a successful transcription.
- Audio is sent **only** to the endpoint you configure. There is no telemetry, no analytics, and
  no other network destination anywhere in the code.
- As an input method, the app *could* observe everything you type. It does not: it only writes
  text into the field, and reads the current selection solely to decide whether backspace should
  delete one character or the selected range.
- The API key is stored in the app's private storage in plain text — normal for an app of this
  kind, readable only by the app itself or by root. `allowBackup` is disabled so it is not
  included in cloud backups or device transfers.
- Cleartext HTTP is permitted, because the self-hosted backends are commonly reached over plain
  HTTP on a local network. If you configure an `http://` endpoint over an untrusted network your
  API key travels unencrypted — use `https://` for anything hosted remotely.

## Building

```sh
cd android
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 34). The resulting APK is at
`android/app/build/outputs/apk/debug/app-debug.apk`.

Debug builds are debuggable and are signed with a throwaway key, so two of them cannot be
installed over one another. They are for testing a change, not for daily use.

CI builds every push and pull request through the `Build` workflow. Cutting a signed release is
the `Release` workflow — see [docs/RELEASING.md](docs/RELEASING.md) for the keystore and secrets
it needs.

## Debugging

Enable [USB debugging](https://developer.android.com/studio/debug/dev-options), connect the phone
to a computer, and use [`adb logcat`](https://developer.android.com/tools/logcat):

```sh
adb devices
adb logcat *:E     # errors only
adb logcat *:W     # warnings and above
```

The app logs under two tags: `whisper-input` for recording, `WhisperTranscriber` for the
transcription request.

Release builds are **not** debuggable — a debugger cannot attach to them, which is deliberate,
since the process memory holds your API key. Build a debug APK to investigate a problem.

## Permissions

- `RECORD_AUDIO` — required to record speech.
- `POST_NOTIFICATIONS` — required to surface errors as toasts while in the background.
- `INTERNET` — required to reach the transcription endpoint.

## Known issues

- Hebrew right-to-left layout and the voice-activity-detection thresholds have had limited
  real-device testing. Please report anything that looks wrong.
- The keyboard occasionally fails silently; see
  [upstream issue #17](https://github.com/j3soon/whisper-to-input/issues/17).
- Taiwanese (Hokkien) transcription works reasonably well even though it is
  [not officially claimed](https://github.com/openai/whisper) by Whisper — leave `Language Code`
  empty to use it. Note that this fork no longer performs Traditional/Simplified conversion on
  the result.

## License and credits

This repository is licensed under the **GNU General Public License v3.0**, the same licence as
the upstream project. See [LICENSE](LICENSE).

Both the original work and the modifications in this fork are distributed under GPLv3. You may
use, study, modify and redistribute it under those terms; if you distribute it, you must pass on
the same freedoms, provide access to the corresponding source, and state your changes. The
complete corresponding source is this repository.

### Original work

QuickDictate is derived from
**[whisper-to-input](https://github.com/j3soon/whisper-to-input)** (Mandarin name: 輕聲細語輸入法),
copyright © 2023-2025 Yan-Bin Diau, Johnson Sun.

Main contributors to the original project: Yan-Bin Diau
([@tigerpaws01](https://github.com/tigerpaws01)), Johnson Sun
([@j3soon](https://github.com/j3soon)), Ying-Chou Sun ([@ijsun](https://github.com/ijsun)). The
full list is in the
[upstream contributor list](https://github.com/j3soon/whisper-to-input/graphs/contributors).

The original copyright and licence headers are preserved in every source file that carried them.
The screenshots under `docs/images/` are from the original project.

### Modifications

Copyright © 2026 [@YairLevyJ](https://github.com/YairLevyJ), July 2026. See
[What is different from upstream](#what-is-different-from-upstream) for a summary, and the commit
history for the details.

The original authors have no involvement in this fork and bear no responsibility for it.
