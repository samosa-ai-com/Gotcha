# Running Gotcha

![CI](https://github.com/<org>/Gotcha/actions/workflows/ci.yml/badge.svg?branch=master)

A step-by-step guide to build, run, and configure the Gotcha Android app.

## 1. Prerequisites

| Requirement | Version / Notes |
|-------------|-----------------|
| Android Studio | Hedgehog (2023.1) or newer — this project uses AGP 8.2.2 / Gradle 8.4 |
| JDK | 17 (bundled with recent Android Studio; the project targets Java 17) |
| Android SDK | Platform 34 (Android 14) installed via SDK Manager |
| A device | A physical phone **or** an emulator running **API 26 (Android 8.0) or higher** |

The project already has a `local.properties` pointing at an SDK:
```
sdk.dir=/home/developer/Android/Sdk
```
If you open on another machine, delete that line and let Android Studio regenerate it, or point it at your own SDK.

## 2. Open and build the project

1. Launch Android Studio → **File → Open** → select the project root
   (`.../Gotcha`). Open the folder itself, not a subfolder.
2. Wait for **Gradle sync** to finish (bottom status bar). First sync downloads
   dependencies and may take a few minutes.
3. If prompted to install SDK Platform 34 or build tools, accept.

If sync succeeds, `app` appears as a run configuration in the toolbar.

## 3. Get a device ready

**Option A — Emulator (easiest):**
1. **Tools → Device Manager → Create Device**.
2. Pick e.g. *Pixel 7*, then a system image with **API 26+** (API 34 recommended).
   Prefer a **Google APIs / Play** image so the dialer, wallpaper, and other apps exist.
3. Start the emulator (▶ in Device Manager).

**Option B — Physical phone:**
1. On the phone: **Settings → About phone**, tap *Build number* 7× to enable
   Developer options.
2. **Settings → System → Developer options → USB debugging: ON**.
3. Plug in via USB and accept the "Allow USB debugging" prompt.

## 4. Run the app

1. Select the `app` configuration and your device/emulator in the toolbar.
2. Click **Run ▶** (or `Shift+F10`).
3. The app installs and launches. **On first launch it opens the Settings screen**
   because no API key is configured yet.

### Command-line alternative
From the project root:
```bash
./gradlew assembleDebug        # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # builds + installs onto the connected device
./gradlew testDebugUnitTest    # runs the JVM unit tests
./gradlew detekt lintDebug     # static analysis (detekt + Android Lint)
```

### Continuous integration

Every pull request and push to `master` or `development` runs the
GitHub Actions workflow (`.github/workflows/ci.yml`): detekt → JVM unit tests →
Android Lint → `assembleDebug`. Test and analysis reports are attached to each
run as artifacts. The workflow can also be triggered manually from the Actions
tab (**workflow_dispatch**) on any branch.
Detekt and lint check only *new* findings — existing ones are grandfathered in
committed baselines (`app/detekt-baseline.xml`, `app/lint-baseline.xml`);
regenerate them with `./gradlew :app:detektBaseline :app:updateLintBaseline`.
The unit tests shell out to a POSIX `sh` (TerminalTool), so they need Linux,
macOS, or Git Bash — on plain Windows they fail; use CI instead.

## 5. Configure the LLM (required before chatting)

The app talks to **any OpenAI-compatible `/chat/completions` endpoint**. On the
Settings screen fill in three fields:

| Field | What to enter |
|-------|---------------|
| **API key** | Your provider's secret key. Stored encrypted on-device (EncryptedSharedPreferences); never logged. |
| **Base URL** | The API root **including `/v1/`**, e.g. `https://api.openai.com/v1/`. A trailing slash is added automatically if missing. |
| **Model name** | The model id the provider expects, e.g. `gpt-4o`. |

The app calls `POST {baseUrl}chat/completions`, so the Base URL must be the part
*before* `chat/completions`.

### Example provider settings

| Provider | Base URL | Example model |
|----------|----------|---------------|
| OpenAI | `https://api.openai.com/v1/` | `gpt-4o` |
| OpenRouter | `https://openrouter.ai/api/v1/` | `openai/gpt-4o` |
| Groq | `https://api.groq.com/openai/v1/` | `llama-3.3-70b-versatile` |
| Local (llama.cpp / LM Studio / Ollama's OpenAI shim) | `http://10.0.2.2:<port>/v1/` (see note) | model as served |

> **Emulator → localhost:** an Android emulator reaches the host machine at
> `10.0.2.2`, **not** `127.0.0.1`. So a server on your PC's port 1234 is
> `http://10.0.2.2:1234/v1/` from inside the emulator.
>
> **Cleartext HTTP:** the app currently allows `https` for sure; if you use a
> plain `http://` local endpoint and requests fail with a cleartext error, you
> need to enable cleartext traffic (see Troubleshooting).

### Verify the connection
1. Tap **Test connection**. It sends a cheap "ping" request and shows
   `✓ Connected: …` on success or the error on failure.
2. Tap **Save**. Then **← Back** to reach the chat screen. Chat input stays
   disabled until a key is saved.

Whichever tool the model chooses, **the model itself must support tool/function
calling** — most modern hosted models do.

## 6. Use it

Type natural-language requests. Examples:

- `What's my battery level?`
- `How much free storage do I have?`
- `Switch to dark mode.`
- `Dial +49 151 23456789` (opens the dialer; you press call)
- `List the files in my downloads folder`
- `Set a random wallpaper`
- `Run: getprop ro.build.version.release`
- `Switch to dark mode and tell me my free storage` (triggers two tool calls, then one summary)

### Permissions & confirmations
- **Sensitive actions** (dial, wallpaper, cache clear, write file, run command,
  set brightness) pop an **Allow / Deny** dialog. You can disable this in
  Settings → *Confirm sensitive actions*.
- **Confirmations while another app is in front** (e.g. the accessibility tools
  after `open_app` hands control to Settings): the Allow / Deny prompt is drawn as a
  **floating overlay on top of that app**, so it stays visible and the agent doesn't
  block behind it. This needs the **"Display over other apps"** permission — grant it
  once for Gotcha, otherwise the confirmation falls back to the in-app dialog and,
  if it can't be shown, times out after 60 s (treated as a decline) rather than hanging.
- **Permissions** are requested on first use. If you deny one, the assistant
  explains what's needed instead of crashing. Two need manual grants:
  - **Storage** (reading Downloads/Pictures/etc.): a standard runtime prompt.
  - **Set brightness** (`WRITE_SETTINGS`): the app deep-links you to
    *Settings → Special app access → Modify system settings*. Enable it for
    Gotcha, then ask again.

### Notes on specific tools
- **Dark mode** changes the *app* theme on Android 12+; on older versions it
  reports that you must toggle it in system Settings.
- **Wi-Fi** opens the system Wi-Fi panel (apps can't toggle Wi-Fi directly on
  modern Android).
- **run_command** runs as the unprivileged app user (no root). Many system paths
  are unreadable; destructive commands (`rm -r`, `dd`, `reboot`, `su`, …) are
  blocked, with a 15 s timeout and 32 KB output cap.

### Monitor vs Operator modes

The app has two agent modes you can switch between at any time (even mid-conversation):

- **Monitor** (read-only) — can inspect, list, and read; cannot create, modify, or delete.
  Allowed to open apps and open the dialer (intent hand-offs). Safe for exploration.
- **Operator** (full permissions) — has access to all 56 tools including write, delete,
  accessibility automation (tap/swipe/type), SMS, calls, alarms, and root commands.

Switch modes using the toggle in the chat screen header. The agent is informed of its
current mode via the system prompt on every API call.

### Speech (TTS/STT)

A microphone button next to the input field lets you speak instead of type (Speech-to-Text).
Each assistant message has a speaker icon to read it aloud (Text-to-Speech). Configure
the provider (Android built-in vs OpenAI-compatible API) in Settings. An auto-read toggle
speaks new replies automatically.

### Image reading

When the Accessibility Service is enabled, Monitor and Operator can capture the current
screen and send it as an image to the LLM for visual understanding. This requires a
vision-capable model (GPT-4o, Claude 3.5+, Gemini, etc.).

Other controls: **Clear** (top bar) wipes chat history; **Clear LLM cache**
(Settings) drops cached deterministic responses.

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Gradle sync fails on SDK location | Fix `sdk.dir` in `local.properties`, or delete the file and re-sync. |
| `401` after sending a message | Wrong API key — re-check it in Settings. |
| `Network problem` / cannot reach API | Check the Base URL (needs `/v1/`), device connectivity; for emulator→PC use `10.0.2.2`. |
| Local `http://` endpoint blocked as "cleartext not permitted" | Add `android:usesCleartextTraffic="true"` to `<application>` in `AndroidManifest.xml` (debug only), or use an `https` endpoint. |
| Model replies but never calls tools | Use a model that supports function calling. |
| Emulator has no Dialer/Wallpaper | Use a *Google APIs/Play* system image. |

## 8. Where things live

- API key & config: encrypted in `SharedPreferences` (`gotcha_settings`).
- Chat history: `filesDir/chat_history.json` (survives restarts).
- Audit log of every tool run: `filesDir/action_log.txt`.
- LLM response cache: `cacheDir/llm_cache/`.
