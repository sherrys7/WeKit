# WeKit — Agent Guide

## Superpowers

- All Superpowers workflow artifacts for WeKit (plans, specs/designs, SDD ledgers and
  reports, brainstorm sessions) are written, edited, and committed **only** in
  `~/coding/wekit_dev/superpowers` (its own git repo; read its `AGENTS.md` for layout and
  rules). Never create, edit, or commit `.superpowers/` or `docs/superpowers/` inside this
  repo — those paths are gitignored here by design.

## Build

```bash
./x build           # debug (uses same signing as release)
./x build --release # release (with optimization on)
./x zygisk build    # standard arm64-v8a APK + arm64 Zygisk module ZIP
# (./x is alias to `cargo xtask` which orchestrates the build process)
```

- **When working in a Git worktree, initialize submodules before starting any work:**
  `git submodule update --init --recursive`. Worktrees do not automatically populate submodule
  contents, and builds will fail when `libs/common/bsh` and `libs/common/reflekt` are empty.
- **When working in a Git worktree, work directly on `dev` unless the user explicitly requests
  another branch or isolated history.** This is because commits made on a detached worktree are not automatically
  transferred by Codex's “local checkout” action and can appear to be lost.
- JDK 21
- **Gradle does NOT build the Rust native lib.** `./gradlew assemble*` only packages whatever
  prebuilt `libwekit_native.so` already sits in `app/src/main/jniLibs/<abi>/`. Compiling
  `app/src/main/rust/wekit-native` and refreshing those `.so` files is xtask's job
  (`task_build_native`), so **always go through `./x`** — running Gradle directly will silently ship
  a stale native lib. Requires a Rust toolchain + the Android NDK and its Rust targets;
  `./x configure` regenerates `wekit-native/.cargo/config.toml` from the local NDK and is invoked
  automatically by the build tasks.
- `./x build --native-only` rebuilds just the native lib into `jniLibs/`
- AGP 9, Gradle version catalog in `gradle/libs.versions.toml`

## Project Structure

- `app/` — main Android module, entrypoints, hooks, UI, native Rust lib
- `libs/common/annotation-scanner/` — KSP processors: source-subtype discovery for
  `BaseFeature`/`ExtensionPack` objects plus the `@AgentTool` scanner
- `libs/common/libxposed-api/` — compileOnly LibXposed API interface stubs (compileOnly since they are provided by user's Xposed framework)
- `libs/common/bsh/` — submodule: forked BeanShell interpreter with snapshot serialization (`BshSnapshot`, `BshSnapshotHelper`); snapshots are encrypted AST byte representations used by the WAuxiliary Xposed module; `app/src/main/java/dev/ujhhgtg/wekit/utils/BshSnapshotDecompiler.kt` — decompiles encrypted BeanShell snapshot files back into Java-like source code; the AES key was recovered from WAuxiliary's decompiled source
- `libs/common/reflekt/` — submodule: reflection utility library (`dev.ujhhgtg.reflekt`)
- `libs/common/stubs/` — compileOnly stubs for WeChat and Android hidden classes
- `buildSrc/` — custom Gradle tasks: `GenerateMethodHashesTask` (`IResolveDex` `resolveDex` method MD5 cache), `GenerateNewFeaturesTask` (Kotlin source files added within 30 days of the HEAD commit → `NewFeatures.ADDED_AT_BY_SOURCE_KEY`; KSP joins source keys to discovered features for the 新功能 pseudo-category)
- `xtask/` — build orchestration behind `./x`: native-lib compilation + NDK linker config, APK
  assembly via Gradle, and Zygisk module packaging/flashing

## Entry Points & Architecture

- Xposed entry: `dev.ujhhgtg.wekit.loader.entry.lsp10x.Lsp10xUnifiedHookEntry` (libxposed 101 & 100) and legacy Xposed API (51+) entry: `dev.ujhhgtg.wekit.loader.entry.xp51.Xp51HookEntry`
- Unified flow: `UnifiedEntryPoint.entry()` → `StartupAgent.startup()` → `WeLauncher.init()`
- Feature objects inherit `BaseFeature`, declare `technicalId`/resource/category metadata as
  override properties, and are auto-discovered by KSP from their source subtype at compile time
- Extension pack objects implement `ExtensionPack`, declare a required `displayOrder`, and are
  auto-discovered by the same KSP processor
- Base classes: `SwitchFeature` (toggle on/off), `ClickableFeature` (toggle on/off with onClick event), `ApiFeature` (always-on), `BaseFeature` (abstract base, do not use directly)
- DEX analysis via DexKit with `IResolveDex` interface; method resolve body MD5-hashed for cache (
  `GenerateMethodHashesTask`)
- DEX-resolved targets DSL: `val methodTarget by dexMethod()` `val classTarget by dexClass()` delegate → `methodTarget.hookBefore { ... }`, `val method: Method = methodTarget.method`, `val clazz = classTarget.clazz`
- UI: Jetpack Compose + Material 3, dialogs written using `showComposeDialog` and
  `AlertDialogContent`; settings screens follow the Material 3 UI Standards section below
  (`ui/content/m3/` widget family, InstallerX-Revived design)
- Config: MMKV via `WePrefs`
- Logging: via `WeLogger`

## Desktop DexKit Validation

- Use `./x dex-test` to run the same `IResolveDex`/DexKit resolution steps used by
  `DexCacheManager.kt` against WeChat APKs on the Linux desktop. Test only the supported host
  range **8.0.65–8.0.77**; APKs outside that range are useful for investigation but must not be
  treated as compatibility gates for the project.
- Test each supported APK version separately, including separate normal and Google Play APKs
  when both are available. Each APK runs in its own JVM worker and must carry its own version code,
  version name, build tag, and Google Play metadata.
- Reports belong under `dex-test-results/<run-id>/` (or an explicitly supplied output directory),
  never under Gradle's `build/reports/`. Preserve the per-APK JSON reports and aggregate summary.
- Resolution classification is strict: an `allowFailure = true` delegate that receives its
  placeholder is `EXPECTED_FAILURE`; an unhandled resolver exception is `UNEXPECTED_FAILURE`;
  delegates that remain pending after that exception are `BLOCKED` and must record the triggering
  delegate; a resolver returning with pending delegates is `INCOMPLETE`.
- A desktop resolution pass does not prove hook-time behavior on a physical device. Initialization,
  worker, native-library, APK metadata, report, unexpected, blocked, or incomplete failures must
  remain visible and make the command fail.
- DexKit desktop testing is intentionally expensive. After a supported-version run has passed,
  do not rerun it for unrelated changes when no Dex declarations or resolution steps changed.
  Rerun the affected supported APK versions after changing `dexMethod`, `dexClass`, `dexField`,
  inline matchers, or the corresponding `resolveDex`/`resolveInlineDex` logic.
- Before reporting a Dex resolver change as complete, run the affected desktop tests plus any
  relevant existing or qualifying Gradle tests (as defined under Testing Strategy), `./x build`,
  and `git diff --check`.

### Desktop-safe Dex resolver rules

- `resolveDex`, `resolveInlineDex`, and inline matcher blocks run in the same
  `DexResolutionContext`. When a matcher needs information from an already-resolved delegate,
  use its DexKit metadata (`delegate.data.name`, `.declaredClassName`, `.returnTypeName`,
  `.paramTypeNames`, `.superClass`, `.interfaces`, etc.), not JVM reflection. In particular, do
  not use another delegate's `.clazz`, `.method`, `.constructor`, `.field`, `asClass`, or
  reflection-derived `Class`/type information to construct a later Dex query: desktop workers
  cannot reliably load WeChat/Android classes.
- Do not hide that reflection behind a `lazy` property or object initialization. A resolver-side
  lazy such as `by lazy { target.method.declaringClass }` is still invalid for desktop testing;
  derive the required descriptor from `target.data` while resolving instead. Reflection properties
  remain valid after resolution for actual hook-time Android behavior; this rule applies only to
  declaration and resolution paths.
- An explicitly user-approved host-version branch, or any build-tag/Google Play branch, inside
  resolution must read `DexResolutionContext.host`, rather than `HostInfo`, so `./x dex-test` uses
  metadata belonging to the APK under test. Android resolution receives equivalent current-host
  metadata through the same context.
- A metadata migration must preserve the intended descriptor/matcher constraints. Do not loosen
  strings, signatures, or structural predicates merely to make a desktop test pass; use stable
  DexKit evidence as normal.
- For an intentional supported-version absence, use `allowFailure = true` only as documented
  below. Its generated generic expected-failure reason is acceptable; provide a more precise reason
  when it materially clarifies a structure-selected compatibility path. Do not convert exceptions
  or uncertain matches into placeholders just to obtain a green report.
- Resolver source is part of the device cache key: even a mechanically equivalent rewrite from
  reflection to `.data` changes the generated `methodHash` and invalidates that feature's old
  cache. Expect one device re-resolution after such a change; never retain or hand-edit an old
  hash to suppress it. Avoid unrelated formatting/refactors in resolver and inline matcher bodies
  when a cache invalidation is not intended.

### Host compatibility path selection

- Prefer structure-based compatibility over host-version checks. In Dex resolution, first probe
  one stable class, method, field, or constructor that exists only on the newer path. If that probe
  produces zero results, record its expected placeholder and fall back to the older path. If it is
  present, keep every other required target on that path strict. Multiple results, matcher errors,
  and failures after the probe must remain visible failures; they are not fallback conditions.
- At hook time or when invoking resolved host members, choose the path from the actual resolved
  structure. Use the new-path probe's `isPlaceholder`, inspect the resolved member's reflection
  signature, or test another directly relevant runtime property. Do not repeat the resolver's
  compatibility decision with a host-version comparison.
- If old and new hosts expose the same semantic member with only a signature difference, accept
  the confirmed signatures structurally (for example, `paramCount(10, 11)`). When invocation
  arguments differ, inspect `Method.parameterCount` or `Constructor.parameterCount` and construct
  the arguments from that actual signature.
- Avoid branches based on the WeChat host's `versionCode`, `versionName`, hard-coded WeChat version
  strings, or equivalent version constants. If a host-version check is genuinely unavoidable, ask
  the user for explicit confirmation before adding or retaining it. Distinguishing a Google Play
  build through `isHostGooglePlay`/`isGooglePlay` is **not** a host-version check and does not require
  that confirmation.

## Testing Strategy

- These repository-specific testing constraints take precedence over the generic Superpowers
  skills' TDD workflow. Do not add tests for host hooks, Compose UI, WeChat runtime behavior, or
  database integration when they fall outside the qualifying conditions below; use the required
  build, static checks, and manual host validation instead.

- TDD and new automated tests are allowed only when all core logic under test lives in WeKit,
  has low coupling to WeChat, and does not depend on WeChat host classes, runtime state, UI, or
  behavior.
- Do not add tests for simple logic that is easy to verify by static review, such as constants,
  direct mappings, boolean expressions, identity functions, or straightforward arithmetic. Do not
  add tests merely to satisfy a workflow or a skill such as Superpowers.
- Do not increase production-code complexity to create a test seam. In particular, do not split a
  simple object singleton into an interface plus implementation, introduce unnecessary wrappers or
  dependency injection, or extract simple one-use logic into a standalone function solely so it can
  be unit-tested.
- Keep simple logic inline when it has only one use and does not form a meaningful reusable domain
  boundary. Extract a helper only when it improves readability, is reused, or isolates genuinely
  complex behavior; testability alone is not sufficient justification.
- If work does not meet all of those conditions, do not use TDD and do not add low-value tests
  merely to satisfy a testing workflow. Host hooks, reflection/DexKit glue, and host UI behavior
  are normally in this category.
- Use `./x dex-test` for automated Dex resolution validation as documented above. Apart from Dex
  resolution, manual testing in the real WeChat host is the primary behavioral test method;
  desktop JVM or Gradle tests do not replace it.

## Key Conventions

- Package namespace: `dev.ujhhgtg.wekit`
- Min SDK 28, target SDK 37, compile SDK 37
- Target: WeChat `com.tencent.mm`, versions 8.0.65–8.0.77. Current host info in `HostInfo`
- Process targeting via `TargetProcesses`: override `startup()` to check
  `TargetProcesses.isInMain` / `TargetProcesses.currentType`. Default: main process only.
- Device behavior still requires manual testing on real WeChat; desktop JVM tests cover Dex
  resolution only and do not replace device validation.
- NEVER wrap `hookBefore` and `hookAfter` in a `try-catch`/`runCatching` block. They should NOT fail. If they fail, then it's the module developer's problem.
- Use `WePrefs.Companion.prefOption` delegates to declare & use preference items easily.
- Teardown/revert on `onDisable` is **best-effort by design**, not a requirement. Many features
  irreversibly modify the host view tree; fully reverting them would need complex state management
  and syncing for little gain, so having the user restart WeChat is the accepted approach. Do NOT
  report "feature does not undo its changes in `onDisable`" as a bug.
- `allowFailure` on `dexMethod`/`dexClass`/`dexField` is ONLY for structures whose existence
  differs across supported WeChat versions (present in old, absent in new, or vice versa). If a
  declared Dex resolution is expected to succeed on every supported version (8.0.65–8.0.77), do
  NOT set `allowFailure`: a resolution failure must fail that feature loudly instead of silently
  degrading to a no-op.
- JVM reflection over host classes should go through `reflekt` (`libs/common/reflekt/`) by
  default, e.g. `thisObject.reflekt().firstField { ... }` or `.getField(name, true)` — not
  hand-rolled `getDeclaredField`/`getMethod` traversal.
- **NEVER use `Path.of` or `Files.writeString`.** These are frequent mistakes and
  are unavailable on older Android API levels supported by WeKit. Convert strings through
  `dev.ujhhgtg.wekit.utils.fs.asPath` from `utils/fs/PathUtils.kt` (for example,
  `pathString.asPath` or `base.asPath.resolve(child)`) and write text through
  `kotlin.io.path.writeText`.
- No excessive defensiveness. When e.g. the hooked method and its argument types are
  known to hold, use direct casts: `thisObject as Activity`, `args[0] as View`, `!!`. Do NOT use `as?`
  safe casts, `args.getOrNull(0)`, `?:`, `?.someFun()` or similar guards for values that should always be present/non-null/etc.
  Code that is correct does not need the defense; code that is wrong must throw loudly and get caught by either `HookUtils`' or code's own exception catcher, and these
  guards only swallow the exception and hide the real error. Defenses and guards that are reasonable should still exist.
- The libraries `DexKit` and `reflekt` are NOT something you are familiar with. Do NOT hallucinate their API surfaces. Read their code before using them.
- In Compose, `LocalContext` always means the platform context and is never localized by WeKit.
  Use standard Compose resource APIs for composable text and `LocalWeKitLocalizedContext` only
  for imperative WeKit resource reads. Mixed platform/resource operations must read both locals.
  Use `LocalActivity.current` for Activity-only APIs, and never add AndroidX owner forwarding to
  `WeKitLocaleProvider`.

## Material 3 UI Standards

Design reference: `~/coding/InstallerX-Revived` — when unsure how a settings page should
look or behave, read its `app/src/main/java/com/rosan/installer/ui/page/main/widget/setting/`.
WeKit's ported widget family lives in `app/src/main/java/dev/ujhhgtg/wekit/ui/content/m3/`.

### Layout

- Settings screens are a `LazyColumn` of `SegmentedColumn` groups (inset rounded cards,
  one group per concern, short `title` above each group). Do not hand-roll card layouts
  or use flat lists with dividers.
- Use the shared scaffolds — `M3ListScaffold` (`activity/settings/SettingsActivity.kt`)
  or `AgentSettingsScaffold` (`ui/agent/settings/AgentSettingsCommon.kt`): collapsing
  `LargeFlexibleTopAppBar` + blur + back button. Do not build per-screen scaffolds.
- Multi-screen settings follow the miuix-nav `NavDisplay` pattern of
  `WeAgentSettingsActivity` / `ReadReceiptsSettingsActivity` (sealed `@Serializable`
  routes, predictive-back drill-down).

### Widget choice

Prefer these over raw Compose controls:

- Plain / status / navigation row → `BaseWidget` (chevron or action in `trailingContent`).
- Boolean setting → `SwitchWidget`.
- Exclusive choice → `RadioButtonWidget`; supports dual click areas like the WeAgent
  "Memory" row: `onClick` (main area, e.g. opens the detail screen) + `onSelect` (the
  radio itself) + `trailingDivider`.
- String or number input → `TextFieldDialogWidget`: a standard clickable row showing the
  current value that edits it in a dialog with cancel/confirm. Or for draft & save semantics, place a bare
  `TextField`/`OutlinedTextField` directly in a `BaseSupportingWidget`.
- Value with a natural range and step (counts, seconds, delays) → `IntNumberPickerWidget`
  (slider row with drag tooltip), wrapped in a `BaseItemContainer` inside the group.
  Ports, hostnames, tokens, URLs and other free-form identifiers have no slider
  semantics — use the dialog row instead.
- Compact choice from a fixed set → `DropDownMenuWidget`.

### Interaction semantics

- Prefer **instant apply**: toggles, radios, sliders and dialog confirmations commit on
  change. Avoid "draft state + Save button" page designs — a text row's dialog
  cancel/confirm is its only draft lifecycle. Genuinely transactional flows (connect /
  verify / disconnect) may keep explicit action buttons; those are actions, not saves.
- Buttons that belong together share ONE row (`Modifier.weight(1f)` each, ~12dp gap) —
  not one button per line. Pair an action with its opposite (connect/disconnect,
  save/delete); destructive actions use the error color and a confirm dialog.
- While an operation is in flight, disable the affected rows and show an inline
  progress/feedback line; never leave conflicting controls tappable.
- Blank values show a hint in the row description; the row itself stays clickable.

## Naming Conventions

- 群聊: WeChat: chatroom; WeKit: group/群组
- 朋友圈: WeChat: sns; WeKit: moment

## Context you need

- WeChat decompiled sources: ~/coding/wechat_80{65,67,69,74,76}
- Decrypted WeChat main database: ./decrypted_wechat.db

## CI

- GitHub Actions: builds on push/PR to `master`/`dev` (skips non-code changes)
- Artifacts automatically published to a release named "CI" + Telegram channel

## Progress
### Done
- 8 个首页三卡文件全部实现并编译通过，CI 已成功
- 侧栏冲突解决：采用 `dev` 拆分架构
- 配置 pre-push hook（自动备份分支）
- `HomePageCards` 改为 `ClickableFeature`，添加设置弹窗（子卡片开关 + 顺序调整 + 字体颜色）
- CI build job 添加 `dev-sherry` 分支支持
- 日历卡/图片卡支持自定义背景颜色（`ColorPickerWidget`）和背景图片（`PickVisualMedia` + Coil 加载）
- 日历卡天气功能已全部删除
- 字体颜色 5 组独立存储并生效
- 修复网易云搜索 bug：`data.list` 是 JSONArray `[{index, name}]`，非 JSONObject
- 修复网易云歌词 bug：歌曲 ID 用 `optLong` 避免 `Int` 溢出
- 修复 Telegram 推送：CI 配置 `dev-sherry` 分支条件
- 网易云音乐 API 更换：`api3.andeer.top` AuroraAPI → `ffapi.cn/int/v1/dg_netease`（单接口统一搜索/歌词/播放）
- QQ 音乐 → 汽水音乐：移除 `api.ygking.top/api` → 改用 `api.cxzja.cn/api/qishuimusi`，平台标识 `"qs"`
- 汽水音乐 Token 设置：`HomePageCards` 新增 `home_qs_token` 偏好，设置弹窗中增加「汽水音乐设置」分栏，`TextFieldDialogWidget(password=true)` 供填写
- 汽水音乐搜索修复：`n=` 空值参数返回歌曲列表（`data` 为 JSONArray），`n=1` 返回单首详情含 `download_url` 和 `lyric`
- 三卡默认关闭：`calendarCardEnabled` / `imageCardEnabled` / `musicCardEnabled` 默认值 `true` → `false`
- 汽水音乐搜索兼容性修复：`httpGet` 添加 `User-Agent` 和 `Accept` 头部；`search`/`getTrackDetail` 的 `catch` 块增加 `toast` 错误提示

### In Progress
- (none)

### Blocked
- 汽水音乐 API 搜索需要用户在设置中手动填入 Token，无 Token 时搜索返回空
- 用户设备上搜索仍显示"未找到结果"，原因待排查（API 测试正常，代码逻辑正确，怀疑设备网络问题）

## Key Decisions
- 设置页使用弹窗（`showComposeDialog`）而非独立 Activity
- 音乐 API 直连第三方，不再经过 Wex 密钥分发层
- 日历卡天气功能完全移除
- 网易云音乐改用 `ffapi.cn` 单接口，搜索 `?msg=&limit=20` → `data[{n, title, singer, pic}]`，选歌 `?msg=&n=index` → `data{id, name, singer, pic, url, lrc}`，歌词 `?act=lrcgc&id=ID` 返回 LRC 纯文本，播放 URL 直接传给 `MediaPlayer.setDataSource()`
- 汽水音乐改用 `api.cxzja.cn`，需要 Token 认证，搜索 `?token=&msg=&n=` → `data[{num, identifier, song_name, singers, album_cover}]`，详情 `?token=&msg=&n=num` → `data{download_url, lyric, ...}`
- 三卡默认关闭，减少初始干扰

## Next Steps
1. 等待用户设备上安装新 APK 后观察 toast 错误提示，定位搜索失败原因

## Critical Context
- 远端 `origin/dev-sherry` 最新 commit：`e5a347b`
- 网易云 API：`FFAPI = "https://ffapi.cn/int/v1/dg_netease"`
  - 搜索 `GET ?msg={keyword}&limit=20&format=json` → `data[{n, title, singer, pic}]`
  - 选歌 `GET ?msg={keyword}&n={index}&format=json` → `data{id, name, singer, pic, url, lrc}`
  - 歌词 `GET ?act=lrcgc&id={id}&format=json` → LRC 纯文本
  - 播放 URL 取 `data.url` 直接传给 `MediaPlayer`
- 汽水音乐 API：`URL_QISHUI = "https://api.cxzja.cn/api/qishuimusi"`
  - 搜索 `GET ?token={token}&msg={keyword}&n=` → `data[{num, identifier, song_name, singers, album_cover}]`
  - 详情 `GET ?token={token}&msg={keyword}&n={num}` → `data{download_url, lyric, song_name, singers, album_cover, identifier}`
  - Token 存于 `home_qs_token` 偏好，设置弹窗中密码模式输入
  - 无 Token 时搜索返回空 `[]`
- 三卡默认全部关闭，需在设置中手动开启
- API 无账号认证，无法播放会员歌曲
- `httpGet` 已添加 `User-Agent` 和 `Accept` 请求头

## Relevant Files
- `.../home_page_cards/HomePageCards.kt`: 主入口，`ClickableFeature`，弹窗设置含子卡片开关/顺序/字体颜色/背景/汽水音乐 Token
- `.../home_page_cards/HpcMusicCard.kt`: 音乐播放器引擎，API 直连 ffapi.cn（网易云）+ cxzja.cn（汽水音乐）
- `.../home_page_cards/HpcMusicPanels.kt`: 搜索/详情/收藏/历史/定时关闭面板 UI，含平台选择（网易云/汽水音乐）
- `.../home_page_cards/HpcCalendarCard.kt`: 日历卡，读取 5 组字体颜色偏好，无天气
- `.../home_page_cards/HpcCardManager.kt`: 按 `home_cards_order` 顺序注入卡片
- `.../home_page_cards/HpcMediaNotification.kt`: 系统通知栏 + MediaSession 控制
- `.../home_page_cards/HpcFloatLyric.kt`: 桌面悬浮歌词
- `.../home_page_cards/HpcImageCard.kt`: 图片卡，支持自定义背景图
- `.github/workflows/ci.yml`: CI 配置，含 `upload-telegram` job
