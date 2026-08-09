# Contributing to Gotcha

Thanks for your interest in contributing to Gotcha! We welcome contributions from the community. 🙏

Gotcha is an on-device Android copilot that drives real devices through the accessibility service, so contributions carry a little more weight than usual — please read the [Safety-sensitive changes](#safety-sensitive-changes) section before touching the tool layer.

## Quick Start

1. **Fork & clone**

   ```bash
   git clone git@github.com:samosa-ai-com/Gotcha.git
   cd Gotcha
   ```

2. **Point Gradle at your Android SDK**

   Android Studio writes `local.properties` on first sync. To build from the CLI before that, create it yourself at the repo root — or just set `ANDROID_HOME`. See [`RUNNING.md`](RUNNING.md#1-prerequisites) for the exact format.

3. **Build and install**

   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug        # onto a device or emulator running API 30+
   ```

Full prerequisites (Android Studio 2025.2+, JDK 17+, SDK Platform 34), permission notes, and troubleshooting live in [`RUNNING.md`](RUNNING.md).

## How to Contribute

### 1. Find or open an issue

- **Always start with an issue.** Please don't open a PR without a corresponding issue.
- Check [existing issues](https://github.com/samosa-ai-com/Gotcha/issues) first to see if it's already being worked on.
- For anything non-trivial, describe the approach in the issue and wait for a maintainer to weigh in before writing code.

### 2. Branching strategy

- **`main`** — the **release** branch. Tagged builds are cut from here. **Do NOT submit PRs to `main`.**
- **`development`** — the **active development** branch. All features and fixes merge here first.

```bash
git checkout development
git pull origin development
git checkout -b feat/your-feature-name
```

Branch prefixes in use: `feat/`, `fix/`, `perf/`, `chore/`, `docs/`.

### 3. Make your changes

- Match the style of the surrounding code — naming, comment density, and idiom.
- Add tests for new behaviour. New tools especially: see below.
- Keep user-facing strings in the i18n resources rather than hardcoded.

### 4. Run the checks locally

CI is **disabled by default** to conserve GitHub Actions budget, so these must pass on your machine before you open a PR:

```bash
./gradlew testDebugUnitTest    # JVM unit + Robolectric tests
./gradlew detekt lintDebug     # static analysis
./gradlew assembleDebug        # build
```

The unit tests shell out to a POSIX `sh` (`TerminalToolTest`), so run them from Linux, macOS, or Git Bash on Windows. Full details in [`docs/RUNNING_TESTS.md`](docs/RUNNING_TESTS.md).

> **There are no detekt or lint baselines.** Everything must pass clean — don't add a baseline to silence a new finding.

If you want the full CI matrix on a branch, trigger it manually from **Actions → CI → Run workflow** (opt-in, consumes budget).

### 5. Submit a pull request

- **Target the `development` branch.**
- Link the related issue (e.g. `Closes #123`).
- Describe what changed and how you verified it. Screenshots or a screen recording help a lot for UI and copilot-behaviour changes.

## Adding or changing a tool

Tools are the app's feature surface, and they're tracked by a generated manifest.

1. Implement the tool under [`app/src/main/java/com/gotcha/tools/`](app/src/main/java/com/gotcha/tools/).
2. Register it in the capability catalog with the correct **capability tier** and **copilot mode** — read-only tools must be available to Monitor; anything that writes, sends, deletes, or automates the screen is Operator-only.
3. Regenerate the coverage table:

   ```bash
   ./gradlew :app:testDebugUnitTest -PupdateCoverageDocs=true
   ```

   This rewrites [`docs/FEATURE_TEST_COVERAGE.md`](docs/FEATURE_TEST_COVERAGE.md) from `app/src/test/resources/feature-test-coverage.json`. Never edit that markdown by hand — `FeatureCoverageManifestTest` fails the build on drift.

4. Commit the regenerated doc alongside your change.

## Safety-sensitive changes

Extra scrutiny applies to anything that:

- adds a tool at **Tier 2+** (calls, SMS, calendar, location, camera, screen automation, device admin, root),
- widens what the **Monitor** copilot can do,
- changes the **confirmation gate**, the command **deny-list**, or the **audit log**,
- touches credential storage or how data leaves the device.

These need an explicit design discussion in the issue first. Changes that weaken a safety boundary will be declined without a strong justification. See the [Safety & Permissions guide](https://samosa-ai.com/gotcha/docs/safety-permissions) for the model you're working within.

## Code standards

- **Kotlin**, Jetpack Compose, Material 3, MVVM — compiled to Java 17 bytecode.
- **Static analysis**: detekt + Android Lint, with custom rules in [`detekt-rules/`](detekt-rules/). No baselines.
- **Commits**: clear, descriptive messages. Conventional prefixes (`feat:`, `fix:`, `chore:`) are used throughout the history.
- **Secrets**: never commit API keys, `local.properties`, keystores, or real Samosa endpoint values.

## Project structure

```
Gotcha/
├── app/src/main/java/com/gotcha/
│   ├── agent/          # Copilot engine, chat view model, skills
│   ├── tools/          # The tool catalog — one file per capability area
│   ├── llm/            # OpenAI-compatible client, tool-call plumbing
│   ├── service/        # Accessibility service, assistive ball, foreground services
│   ├── connectors/     # Google, Microsoft Graph, Notion, IMAP
│   ├── audio/          # TTS / STT engines
│   ├── data/           # Persistence, encrypted credential storage
│   └── ui/             # Compose screens
├── app/src/test/       # JVM unit + Robolectric tests
├── detekt-rules/       # Custom static-analysis rules
├── docs/               # Setup guides, model card, coverage manifest
└── scripts/            # Development utilities
```

## Help & documentation

- **Docs**: [samosa-ai.com/gotcha/docs](https://samosa-ai.com/gotcha/docs) — [Architecture](https://samosa-ai.com/gotcha/docs/architecture), [Copilot Modes](https://samosa-ai.com/gotcha/docs/agent-modes), [Tool Catalog](https://samosa-ai.com/gotcha/docs/tool-catalog).
- **Get help**: [open an issue](https://github.com/samosa-ai-com/Gotcha/issues) describing what you're stuck on.
- **Security bugs**: don't open an issue — see [`SECURITY.md`](SECURITY.md).

## Using code from other projects

We value the spirit of Open Source and Free Software, which foster collaboration and code reuse across projects. However, Free Software is not the same as the public domain, and license terms still apply. Not all code can be freely reused in every context.

Before integrating a significant portion of code — or adding new dependencies or libraries — please consult the maintainers to verify license compatibility. AGPL-3.0 compatibility is a hard requirement.

## License

By contributing, you agree that your contributions will be licensed under the [AGPL-3.0 License](LICENSE).

---

Thank you for helping make Gotcha better! 🙏
