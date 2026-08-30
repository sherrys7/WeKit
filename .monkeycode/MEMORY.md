# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-08-24 (updated 2026-08-30)
- Context: During CI commit workflow on the WeKit repo
- Instructions:
  - Commit messages must NOT include `[skip ci]` unless the user explicitly asks for it. Only add `[skip ci]` when the user tells you to skip the CI run.
  - Keep committing AGENTS.md progress updates (do not leave them uncommitted). When a batch contains BOTH code changes and AGENTS.md updates, commit them together in ONE commit whose message focuses on the code change only — do not create a separate docs commit for AGENTS.md.
  - The `paths-ignore` list in `.github/workflows/ci.yml` (includes `**/*.md` and `**/*.txt`) means a pure AGENTS.md-only commit is skipped by the workflow automatically and does NOT need `[skip ci]`; a commit containing code changes triggers CI normally.

[User Instruction Summary]
- Date: 2026-08-29
- Context: During WeKit feature porting work in the devbox
- Instructions:
  - Do NOT run local build verification (`./x build`) or `./x dex-test` after code changes in this devbox. These commands keep getting stuck/hang, so skip them entirely. Do not start them even in a background terminal. Rely on static checks (`git diff --check`) and CI instead.

[Project Knowledge Summary]
- Date: 2026-08-18
- Context: Discovered by Agent while performing the first full build of WeKit in the devbox
- Category: Build Methods
- Instructions:
  - The devbox ships no Rust toolchain and no Android SDK. Install them before building: rustup (stable, minimal profile) plus targets `aarch64-linux-android` and `armv7-linux-androideabi`; Android SDK via sdkmanager at `/opt/android-sdk` with `platforms;android-37.0` (note: the repo uses `android-37.0`, the plain `platforms;android-37` package does not exist) and the pinned NDK from `gradle/libs.versions.toml` (`ndk;30.0.14904198`). SDK path goes in `local.properties` (`sdk.dir=/opt/android-sdk`) since `ANDROID_HOME` is not set.
  - Cross-compiling `wekit-native` with bindgen needs `LIBCLANG_PATH` pointing at the NDK's libclang dir (`.../toolchains/llvm/prebuilt/linux-x86_64/lib`), plus `BINDGEN_EXTRA_CLANG_ARGS="-resource-dir=<ndk>/lib/clang/21 --sysroot=<ndk>/sysroot"`. Without `-resource-dir`, libclang cannot find `float.h`; the NDK sysroot has no `float.h` on its own.
  - The devbox has 7.8GiB RAM total. The stock `gradle.properties` (`-Xmx4096m -XX:MaxMetaspaceSize=2048m`, parallel=true) exceeds a 4.67GiB cgroup cap and the Gradle daemon gets OOM-killed. Use `memory_percent: 70` on the build terminal and run Gradle directly with `-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=768m" -Dkotlin.daemon.jvmargs="-Xmx1536m" -Pandroid.r8.maxWorkers=1 --max-workers=2`.
  - `./x build` fails in a shallow clone only in that the "新功能" pseudo-category is empty (warning only, does not break the build).
  - Build verification: `./x build` runs native (Rust) then Gradle; `app/build/outputs/apk/{standard,legacy}/debug/*.apk` are the artifacts and must contain `libwekit_native.so` for both ABIs.

[Project Knowledge Summary]
- Date: 2026-08-18
- Context: Discovered by Agent while building the `dev` branch (486 commits ahead of master, ~88k added lines)
- Category: Build Methods
- Instructions:
  - The `dev` branch is much heavier than `master`: with `-Xmx1536m`/`-Xmx2048m` Kotlin daemons the build is either cgroup-OOM-killed (5.45GiB cap at `memory_percent: 70`) or fails with "Not enough memory to run compilation" from `BuildToolsApiCompilationWork`. Root cause is the devbox memory balloon making system `MemAvailable` tiny.
  - The working recipe for `dev`: run `./x build --native-only` first (needs the LIBCLANG_PATH/BINDGEN_EXTRA_CLANG_ARGS env from the entry above), then run Gradle per flavor with `-Dorg.gradle.jvmargs="-Xmx3584m -XX:MaxMetaspaceSize=1024m" -Dkotlin.compiler.execution.strategy=in-process -Pandroid.r8.maxWorkers=1 --no-parallel --max-workers=1`. `in-process` makes Kotlin compile inside the Gradle daemon and bypasses the BTAPI memory check; the separate `kotlin.daemon.jvmargs` tweak is not enough.
  - Build one flavor at a time (`assembleStandardDebug`, then `assembleLegacyDebug`) instead of `assembleDebug` to keep peak memory below the 70% cap.
  - `dev` uses Gradle 9.7.0 (wrapper auto-downloads it; `master` used 9.6.1). SDK pins are unchanged (compileSdk 37 / NDK 30.0.14904198).
  - `dev` adds a `third_party/cloudflared` submodule. Its `git submodule update` can fail with "transport 'file' not allowed" / "Direct fetching of that commit failed", but it is only needed by the separate `./x cloudflared-build` command (needs Go toolchain); the default `./x build` path does not reference it.
