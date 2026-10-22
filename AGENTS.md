KillergramNeo is an **Xposed module** that enhances Telegram (and its forks) without modifying the original APK. It hooks into Telegram's internal classes at runtime via the LSPosed framework and provides a Jetpack Compose settings UI for toggling features.

## Build Commands

```bash
./gradlew :app:assembleDebug       # Debug build
./gradlew :app:assembleRelease     # Signed release (needs signing config)
```

## Architecture

### Two Runtime Contexts

The module runs in **two separate processes**:

1. **Settings app** (`MainActivity`) — standalone Compose UI where users toggle features. Writes to world-readable `SharedPreferences`.
2. **Xposed hooks** (`MainHook`) — injected into the target Telegram process by LSPosed. Reads preferences via `XSharedPreferences` and applies hooks conditionally.

Preferences bridge the two processes: the UI writes them, the hooks read them at injection time.

### Package Layout (`app/src/main/java/aether/killergram/neo/`)

- **`MainHook.kt`** — Entry point for Xposed. Implements `IXposedHookLoadPackage` and `IXposedHookZygoteInit`. Reads prefs and conditionally invokes each hook.
- **`hooks/`** — Each feature is a separate file defining an **extension function on `Hooks`** (e.g., `fun Hooks.killStories()`). The `Hooks` class wraps `LoadPackageParam` and provides `loadClass()` for safe class resolution.
- **`hooks/Hooks.kt`** — Thin wrapper around `LoadPackageParam` with logged class loading.
- **`ui/`** — Jetpack Compose UI: screens, components, theme, navigation. Uses Material3 with bottom nav (Features / Settings tabs).
- **`ui/model/ToggleCatalog.kt`** — Central registry of all feature toggles grouped into sections (Visuals, Premium, Misc, Testing).
- **`data/`** — `ModulePrefsStore` (SharedPreferences wrapper), `RootActions` (root detection/force-stop), `RestartTargetResolver` (detects installed Telegram variants), `TargetApps` (supported package list).
- **`core/PreferenceKeys.kt`** — All toggle keys as `SCREAMING_SNAKE_CASE` constants.

### Adding a New Hook

1. Create a new file in `hooks/` with an extension function: `fun Hooks.myNewHook() { ... }`
2. Add a preference key constant in `PreferenceKeys.kt`.
3. Register the hook in `MainHook.kt`'s `hooksMap` (maps pref key → hook invocation).
4. Add a `ModuleToggle` entry in `ToggleCatalog.kt` under the appropriate section.

### Telegram 12.10.2+ obfuscation

Official Telegram builds are R8-obfuscated since 12.10.2. `org.telegram.ui.*` classes and their members get new names in every release (`ChatActivity` → `no`, `InstantCameraView` → `o60` → `h70`), so `loadClass("org.telegram.ui.…")` fails. `org.telegram.messenger.*`, `org.telegram.tgnet.*` and `org.telegram.messenger.R` keep their names.

- Prefer anchoring hooks in the kept packages (e.g. `MessagesController`, `TopicsController`, `ApplicationLoader.addItemOptions`).
- When a UI class is unavoidable, resolve it at runtime in `core/TgDeobf.kt` with DexKit (string literals, signatures, callers, reads of `R$drawable` fields) and cache the result. Never hard-code obfuscated names.
- `libdexkit.so` is loaded explicitly from the module APK's `lib/<abi>/` (`MainHook.modulePath`), because `System.loadLibrary` cannot see module libraries inside the Telegram process.
- After a Telegram update, check the `Resolved …` lines in the Vector log (debug logging on) to confirm every fingerprint still resolves.

### Settings entry and launcher icon

`hooks/SettingsEntry.kt` always adds a "Killergram" item to Telegram's chat list "⋮" menu that opens `MainActivity`. `MainActivity` also declares `de.robv.android.xposed.category.MODULE_SETTINGS`, so the module list in Vector can open it. The launcher icon is the `.LauncherAlias` activity-alias, which the "Hide launcher icon" setting disables. Keep the module free of requested permissions (`<queries>` instead of `QUERY_ALL_PACKAGES`; androidx's `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is removed), otherwise Android shows an App info stub in place of the hidden icon.

## Key Technical Details

- Xposed API 82 is a **compileOnly** dependency — it's provided by the framework at runtime.
- ProGuard keeps all classes under `aether.killergram.neo.**` (no obfuscation for the module).
- The module maintains a `packageBlocklist` to avoid hooking into itself or GMS.
- `Utils.kt` provides a `log()` helper that wraps `XposedBridge.log()` with level-based filtering (DEBUG output controlled by the debug logging preference).
- Version is in `app/build.gradle.kts` (`versionName` / `versionCode`).
- This fork's `applicationId` is `aether.killergram.neo.fork` (so it can coexist with upstream KG Neo); the code `namespace` stays `aether.killergram.neo`. Never hard-code the package name — use `BuildConfig.APPLICATION_ID`.

## CI

- Every push/PR triggers `assembleDebug`.
- Pushing a `v*` tag builds a signed release and publishes a GitHub Release with the APK.

## Dev Env
You will usually have official Telegram source tree at ../Telegram for reference.
You will also have several custom Telegram clients to reference how they do things, like ../exteraGram and ../NagramX for reference.
When you're done implementing a feature, check if there's a device connected over ADB. If there is - you can use it to install the module yourself, force-stop org.telegram.messenger web, run the app yourself, check logs and do end-to-end testing that way.
On-screen testing that takes more than a couple of taps/screenshots (navigating several screens, opening chats, long UI flows) is left to the user: install the build, force-stop Telegram, then tell the user exactly what to check on screen and wait for their report. Reading logs over ADB stays with you. Screenshots and UI driving cost far more than the user doing it by hand.
