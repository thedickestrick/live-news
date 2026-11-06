# Live News

Live headlines for Android, in an app and a home-screen widget. Kotlin, Jetpack Compose, Glance.

- One merged, newest-first list from public RSS and Atom feeds (31 built in, any other can be added). No accounts, no API keys.
- Refreshes by itself while open, and in the background for the widget.
- Filter by category, search, save stories, share, read in an in-app browser tab.
- Updates itself: the app checks this repository's newest release and installs it on one tap.

## Install

Download `LiveNews.apk` from the [latest release](https://github.com/thedickestrick/live-news/releases/latest) and open it on an Android 8.0+ phone. After that the app offers new versions itself.

## Build

Needs the Android SDK (path in `local.properties` as `sdk.dir=...`) and a JDK 17 or newer.

```
.\build.ps1          # build the APK
.\build.ps1 -Test    # run the tests first
.\gradlew testDebugUnitTest -Plive=true   # also fetch every built-in source for real
```

## Release

```
.\release.ps1 -VersionName 1.2.0 -Notes "What changed."
```

Raises the version, runs the tests, builds, checks the signature, and publishes a GitHub release with `LiveNews.apk` and `version.json`. Phones pick it up the next time the app is opened.

Releases must be signed with the same key as the installed app, or Android refuses the update. That key is `livenews.keystore` in the project folder. It is not in this repository; keep a backup of it.
