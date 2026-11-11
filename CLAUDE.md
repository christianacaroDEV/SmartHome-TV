# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

SmartTube is an open-source YouTube client for Android TV (Java, Leanback UI). Any `AGENTS.md` found in a parent folder that talks about NestJS microservices does **not** apply to this repo.

## Setup and build

Two git submodules must be present or Gradle fails at configuration time (`settings.gradle` includes them):

```bash
git submodule update --init --recursive   # MediaServiceCore, SharedModules
```

- Requires an Android SDK (`local.properties` with `sdk.dir`, git-ignored), platform 34, build-tools 30.0.3 and **NDK 21.0.6113669**.
- Build with **JDK 17** (what CI uses). Gradle 7.5 / AGP 7.4.2 fail with JDK 21 (`Unable to establish loopback connection`), so do not rely on a `JAVA_HOME` pointing to 21. The README warns that a JDK newer than 14 can make the *app* misbehave at runtime; none of that has been seen in a JDK 17 build.
- This is the **SmartHome TV** fork (remote `origin` = `christianacaroDEV/SmartHome-TV`, branch `main`; the original project is the read-only `upstream` remote, push disabled).
- Flavors: `stbeta` (`com.smarthome.tv.beta`), `ststable` (`com.smarthome.tv.stable`), `stfdroid` (`com.smarthome.tv.fdroid`, minSdk 21). Task names use the capitalised flavor, e.g. `Stbeta`.

```bash
./gradlew assembleStbetaDebug      # APKs in smarttubetv/build/outputs/apk/stbeta/debug/ (per-ABI + universal)
./gradlew installStbetaDebug       # build + install on the connected device/emulator
./gradlew lintStbetaRelease        # what CI runs; lint has abortOnError=true
```

Unit tests (JUnit + Robolectric) exist only in `common` and `slidableactivity`, and are very sparse:

```bash
./gradlew :common:testStbetaDebugUnitTest --tests "*SuggestionsControllerTest"
```

`versionName`/`versionCode` live in `smarttubetv/build.gradle`. Shared SDK/dependency versions live in `SharedModules/constants.gradle` (submodule, not this repo). Firebase/Crashlytics only applies if `smarttubetv/google-services.json` exists; release signing only if `keystore.properties` exists.

## Module layout

- `smarttubetv` – the Android app: Leanback screens, fragments and activities (`tv/ui/...`), launchers, flavor resources (`src/stbeta|ststable|stfdroid`).
- `common` – almost all logic, UI-framework-agnostic: presenters, views (interfaces), playback controllers, preferences, background services.
- `MediaServiceCore` (submodule, **separate upstream repo**): `mediaserviceinterfaces` (contracts) and `youtubeapi` (the actual YouTube client: requests, parsing, SABR/DASH formats, sign-in). Changes to anything that talks to YouTube belong here, not in `common`.
- `SharedModules` (submodule): `sharedutils` plus the shared Gradle constants/settings.
- Vendored/forked libs built as local modules: `exoplayer-amzn-2.10.6` (Amazon ExoPlayer port, modules prefixed `exoplayer-`), `leanback-1.0.0`, `fragment-1.1.0`, `chatkit`, `doubletapplayerview`, `slidableactivity`, `filepicker-lib`, `leanbackassistant`. The root `build.gradle` **excludes** the Maven `androidx.leanback` and `androidx.fragment` artifacts so the modded local copies are used; do not add those dependencies back.

## Architecture (MVP split across `common` and `smarttubetv`)

- **Views** are plain interfaces in `common/.../app/views/` (`BrowseView`, `PlaybackView`, `SearchView`, ...). The concrete Leanback fragments/activities in `smarttubetv/.../tv/ui/` implement them.
- **Presenters** (`common/.../app/presenters/`, extend `BasePresenter<T>`) hold the screen logic and are singletons holding only `WeakReference`s to the view/activity. `ViewManager` starts/stops views, so presenters navigate by calling other presenters rather than starting activities directly.
- **Settings dialogs** are presenters in `presenters/settings/` that build option lists with `AppDialogPresenter` (`appendCheckedCategory` / `appendRadioCategory`, items from `UiOptionItem.from(label, onChange, initialState)`). Context menus are in `presenters/dialogs/menu/`.
- **Playback** is composed from many controllers in `app/models/playback/controllers/` (`VideoLoaderController`, `PlayerUIController`, `SponsorBlockController`, `ErrorFixerController`, `AutoFrameRateController`, `SuggestionsController`, ...) coordinated by `PlaybackPresenter`/`BasePlayerController`. New playback features usually become a new controller. The ExoPlayer glue (track selection, renderers) is in `common/.../exoplayer` and `smarttubetv/.../ui/playback`.
- **Preferences** are `*Data` classes in `common/.../prefs/` (`GeneralData`, `PlayerData`, `PlayerTweaksData`, `MainUIData`, `SearchData`, `SponsorBlockData`, ...) backed by `AppPrefs`/`SharedPreferencesBase`. Adding a setting = a getter/setter in the right `*Data` class, a row in the matching settings presenter, and a string in `common/src/main/res/values/strings.xml`. Backup/restore (`misc/BackupAndRestore*`, GDrive) works on these prefs.
- **YouTube access** goes through `MediaServiceManager` (in `common/.../misc`) which wraps the `mediaserviceinterfaces` services (`ContentService`, `MediaItemService`, `SignInService`, ...) and is the only place presenters should reach YouTube from. Content items are `Video`/`VideoGroup` models in `app/models/data/`; post-processing of lists happens in `misc/*Processor` classes (e.g. DeArrow, unlocalized titles).
- **Remote control / casting** runs as background services/receivers in `common/.../misc` (`RemoteControlService`, `DialServer`).

## Conventions and gotchas

- The code targets Android 4.x+ with `JavaVersion.VERSION_1_8`; keep to Java 8 syntax and APIs available on minSdk 17 (21 for `stfdroid`). Kotlin is used in only a handful of files.
- Release builds are **not minified** (`minifyEnabled`/`shrinkResources` commented out).
- The dev emulator used here is an Android TV image (API 28, x86); install the `x86` APK or use `installStbetaDebug`. The installed package for the beta flavor is `com.smarthome.tv.beta`.
- Update checks read `releases/download/latest/smarthome_{beta,stable}.json` from the SmartHome-TV repo (`common/src/<flavor>/res/values/update_urls.xml`); Firebase/Crashlytics is applied only if `smarttubetv/google-services.json` lists a `com.smarthome.tv*` package (the bundled file is the original project's and is ignored). APK-downloading "bridge" features of the original were removed.
- Cold start shows a branded splash (`SplashActivity` + `activity_splash.xml`, ~2.4 s, once per process) before `SplashPresenter` starts the app.
- Beware of line endings: many files are committed with CRLF; `sed -i` on them can rewrite whole files. Re-add such files with `git -c core.autocrlf=false add` after restoring CRLF so diffs stay minimal.
- Large "god classes" exist (`Utils`, `BrowsePresenter`, `PlayerUIController`, each >1000 lines); prefer adding a focused class over growing them further.
- Translations are managed through Crowdin (`crowdin.yml`); only edit the default `values/strings.xml`.
