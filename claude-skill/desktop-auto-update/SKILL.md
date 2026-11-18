---
name: desktop-auto-update
description: Adds "a new version is available - download and install it" to a JavaFX desktop program packaged with jpackage, using the shared desktop-updater library (com.github.MohamedSalah-13:desktop-updater on JitPack) instead of writing update code again. Use this whenever the user wants a desktop program to check for new releases, notify the customer, download the installer, update itself, or add a "Check for updates" button - including phrasings like "auto update", "self update", "update checker", "تحديث تلقائي", "مراقبة الإصدار الجديد", "اشعار بوجود اصدار جديد", "زي نظام التحديث في sunbula / git-backup / file-transfer" - and also when a program that already uses desktop-updater stops announcing or installing its releases.
---

# Desktop auto-update

Wire the `desktop-updater` library into a JavaFX program so that an installed copy notices a new release,
shows it to the person (Arabic or English), downloads the installer with a progress bar, checks its SHA-256,
runs it, and reopens the program. The library does all of that; the program only says *where its releases
are* and *how it closes*. A finished integration is about twenty lines in the program plus one dependency.

Library source and README: `https://github.com/MohamedSalah-13/desktop-updater` (a local clone is usually at
`F:\git\desktop-updater`). Read its README when you need an API detail this file does not cover.

## How it works, in one paragraph

A program installed from a jpackage installer is told its own version by the launcher (system property
`jpackage.app-version`) - so no version file is written by hand, and a copy run from the IDE has no version
and is never offered an update. The library asks GitHub for the repository's releases, picks the newest tag
that carries an installer asset, and compares. To install, it leaves a small PowerShell script in
`%LOCALAPPDATA%\<App name>-updates\` that waits for the program to close, runs the installer silently, and
starts the program again; the program just has to close after handing over.

## Before you touch anything

1. Look at `git status`. If the tree has someone else's uncommitted work, or another session is active in
   the repository, say so and agree with the user where the change goes - the edits below land in the
   program's main class, which is exactly where parallel work collides.
2. Find, and note for the steps below:
   - the `Application` subclass and its `start(Stage)`;
   - how the program holds its language (a config field, an `I18n` class...) and where it changes;
   - how the program closes: plain `Platform.exit()`, or a tray icon / unsaved work with its own exit path;
   - whether there is a `module-info.java`;
   - the packaging script (`jpackage ...`) and what the installer file is called;
   - the GitHub repository (`git remote -v`) and whether it is public (`gh repo view --json visibility`).
3. Check the preconditions. If one fails, fix it or tell the user before going on - the update silently
   does nothing otherwise:
   - **jpackage installer with a fixed `--win-upgrade-uuid`.** Without it a new installer installs beside
     the old version instead of replacing it.
   - **`--win-per-user-install`** (recommended). A per-machine install asks for an administrator on every
     update.
   - **`--app-version` comes from `pom.xml`**, and release tags are `v<that version>`.
   - **The repository is public.** A private one needs the manifest source instead - see "Variants".

## The steps

### 1. Dependency

In `pom.xml` (use the newest tag of the library - `gh api repos/MohamedSalah-13/desktop-updater/tags --jq '.[0].name'`):

```xml
<repositories>
    <!-- Serves desktop-updater straight from its GitHub tags. -->
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.MohamedSalah-13</groupId>
    <artifactId>desktop-updater</artifactId>
    <version>v0.1.1</version>
</dependency>
```

The library has no dependencies of its own and uses the program's JavaFX. With a `module-info.java`, add
`requires com.hamza.updater;` - the library is a real module, so `javafx:jlink` accepts it.

### 2. Start it

In `start(Stage)`, once the stage and the language are set up:

```java
import com.hamza.updater.AppUpdater;
import com.hamza.updater.ReleaseSource;
import com.hamza.updater.fx.UpdatePrompt;

updater = AppUpdater.builder("Git Backup")                       // the name the customer knows
        .source(ReleaseSource.github("MohamedSalah-13/git-backup").asset("*-setup.exe"))
        .build();
updates = UpdatePrompt.create(updater, stage)
        .language(config.language)                               // "ar" or anything else for English
        .onExit(Platform::exit)                                  // see step 4
        .start();                                                // first look after 8 s, then every 6 h
```

Keep `updater` and `updates` as fields; the next steps use them.

`.asset(...)` is a glob over the release's file names and must match exactly one installer - look at what
the releases actually contain (`gh release view --json assets --jq '.assets[].name'`) rather than guessing.

### 3. Follow the language

Where the program switches language, add `updates.language(newLanguage);`. The window reads its words when
it opens, so this one line is enough.

### 4. Close the right way

After the download the library calls the `onExit` action, and the installer waits up to two minutes for the
process to end. So `onExit` must really end the process:

- A plain program: leave `onExit` out. The default calls `Platform.exit()` and ends the process a few
  seconds later whatever is still running.
- A program with a tray icon, `Platform.setImplicitExit(false)`, or cleanup in `stop()`: pass whatever its
  own "Exit" menu item does, minus any "are you sure?" question - the person already chose to update.

An installation with more than one launcher (a client and a server from one installer), or a program that
allows several windows, needs nothing extra: when the person presses "Update now" the library lists the
other programs still running from the same installed folder and asks to close them first, because the
installer cannot replace files they hold. If one of them is a program the user cannot see (a service, a
scheduled run), tell the user - that one has to be stopped by the program itself before `install`.

### 5. A "Check for updates" button

Wherever the program has settings or an About box, add a button and show the installed version:

```java
checkUpdates.setOnAction(event -> updates.checkNow());
String version = updater.currentVersion().map(Object::toString).orElse(t("settings.updates.dev"));
```

`checkNow()` always answers - a new release, "you have the latest", or "could not check" - where the
background look stays silent unless there is something to install. Add the button's label and the
description to the program's own message files, in every language it has.

### 6. Packaging

Nothing to add for a classpath program: the jar is copied with the other libraries. The library needs only
`java.base` in the runtime image. One exception: a runtime image built with **JDK 21 or older** needs
`jdk.crypto.ec` in `--add-modules`, or HTTPS to GitHub fails; JDK 22+ has it in `java.base`.

### 7. Verify

- `mvn verify` (and the packaging script, if the project has a module-info - jlink is where a module
  problem shows).
- See the window without publishing anything: run the program from the IDE with
  `-Djpackage.app-version=0.0.1`. It now believes it is an old installed copy and offers the newest real
  release. Check the Arabic and English wording and the theme. Press "Later" - "Update now" would download
  and run the real installer over nothing.
- If the user wants the whole cycle proven, build two installers with different `--app-version`, install
  the older, publish the newer as a release (or serve a manifest from `http://localhost`, which the library
  accepts for exactly this), and watch it update and reopen.

## Releasing so that programs see it

The update is only as good as the release, and a release done by hand goes wrong quietly: a file name the
glob does not match, a draft, a version that is not newer - nothing fails, the update just never shows. So
give the project a release script as part of the integration:

1. Copy `tools/release.example.ps1` from the library to the project as `packaging/release.ps1` and fill in
   its four values: the release title, the installer's published name (it must match the `.asset(...)`
   glob), the glob itself, and the project's packaging script. A project whose `pom.xml` is not at the root
   also sets `Pom` and `InstallerDir`.
2. The wrapper calls the library's `tools/publish-release.ps1`, found in a clone of desktop-updater beside
   the project or under `DESKTOP_UPDATER_HOME`. That script checks the project (clean tree, version, tag
   at HEAD, no such release yet, newer than the newest), runs the packaging script, publishes the
   installer under its name, and then reads the release back the way the program does.
3. Try it with `.\packaging\release.ps1 -DryRun`: it builds and names the installer and publishes nothing.

Using it:

```powershell
.\packaging\release.ps1 -DryRun       # everything except publishing
.\packaging\release.ps1               # the tag v<version> must exist and be at HEAD
.\packaging\release.ps1 -CreateTag    # tags HEAD first
.\packaging\release.ps1 -VerifyOnly   # which release is an installed copy offered right now, and why
```

Publishing is outward-facing and cannot be taken back: run the real thing only when the user asks for a
release, and show them the dry run first. `-VerifyOnly` is the first thing to run when a customer says the
update did not appear.

What the script cannot decide for the user:

- The release's description is shown to customers as "What's new". Write it for them in
  `docs/release-notes/v<version>.md`; without that file GitHub's list of commits is used.
- A program that follows pre-releases (`.includePreReleases(true)`) publishes with `-PreRelease`; any
  other program's pre-releases are skipped by its updater.

## Variants

- **Private repository / own server:** `ReleaseSource.manifest("https://example.com/app/latest.json")`
  with `{"version": "2.5.0", "url": "https://.../Setup.exe", "sha256": "...", "size": 123, "notes": "..."}`.
  `version`, `url` and `sha256` are required.
- **Inno Setup installer:** `.installer(InstallerKind.INNO_SETUP)` on the builder. The default,
  `InstallerKind.JPACKAGE`, passes `/passive /norestart`, which only a jpackage/MSI installer understands.
- **Any other installer:** `InstallerKind.WIZARD` shows the installer's own pages;
  `InstallerKind.withArguments(...)` passes your own.
- **No JavaFX window wanted:** use `AppUpdater` alone - `check()`, `download(...)`, `install(...)` - from a
  background thread, and close the program after `install`.

## When it does not work

- **Never offers an update:** run `.\packaging\release.ps1 -VerifyOnly` - it names the release an installed
  copy is offered and why each other one is passed over (draft, pre-release, no file matching the glob, a
  tag that is not a version). If that looks right, is the copy installed? Run from the IDE it has no
  version.
- **Downloads but does not install:** read `%LOCALAPPDATA%\<App name>-updates\update.log`. "still running"
  means `onExit` did not end the process (step 4).
- **Installs beside the old version:** the `--win-upgrade-uuid` changed between the two installers.
- **"Could not check":** the reason is in the dialog. HTTP 403 from GitHub is its hourly limit of 60
  requests per address; a TLS error in a jlink image is the `jdk.crypto.ec` note in step 6.

## What not to do

- Do not copy the library's classes into the program. A fix then has to be made in every program; that is
  the problem the library exists to remove. If the library lacks something, change the library and tag it.
- Do not write the version into a properties file for the updater. The launcher's version is the
  installer's version; a second copy can only disagree with it.
- Do not call `AppUpdater.check()` or `download()` on the JavaFX thread - they wait on the network.
  `UpdatePrompt` already keeps them off it.
