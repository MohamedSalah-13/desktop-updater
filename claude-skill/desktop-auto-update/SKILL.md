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
    <!-- Named so that it is asked before JitPack; see the note under the snippet. -->
    <repository>
        <id>central</id>
        <url>https://repo.maven.apache.org/maven2</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
    <!-- Serves desktop-updater straight from its GitHub tags. -->
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.MohamedSalah-13</groupId>
    <artifactId>desktop-updater</artifactId>
    <version>v0.4.0</version>
</dependency>
```

Keep `central` in the snippet, first. A project's own repositories are asked before the inherited Maven Central, so
with `jitpack.io` alone Maven asks JitPack first for every library of the program. JitPack answers some coordinates
it does not have (`xml-apis:xml-apis-ext`, for one) with an empty file instead of 404; Maven keeps the empty jar and
the build fails with `zip file is empty` wherever the local repository is empty - a CI runner first of all. If the
program's `pom.xml` already has a `<repositories>` block, add what is missing rather than a second block.

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

## Required updates

A release that breaks compatibility - a new wire protocol, a database older copies cannot open, a security
fix - can be marked required. Ask the user whether the program has such releases; most do not need more
than knowing the switch exists.

- **Marking one:** `.\packaging\release.ps1 -Required`, or the line `<!-- update: required -->` in the
  release's description (an HTML comment; GitHub's page does not show it). Every copy older than that
  release then gets a "Required update" window with no "Skip this version", again at every look, even if
  the person had skipped it. It stays required after later releases replace it as the newest.
- **"Later" stays by default.** Whether an old copy may keep working is the program's call, not the
  library's: a client that cannot talk to a newer server is useless, a backup tool one version behind is
  fine. For a program that must not run old, add `.enforceRequired(true)` on the `UpdatePrompt`: the choice
  becomes "Update now" or "Close the program". Do not turn it on without the user saying so - it can lock
  people out, and a wrong `-Required` on a release then stops every customer.
- **A program that learns it is out of date** - a server refused its version - calls
  `updates.checkQuietly()` at that moment, so the offer appears then instead of at the next six-hour look.
- **Two programs that must match** (client and server): marking a release required does not order the
  updates. Say in the release notes which side updates first; the offer shows them.

## Downloading ahead

`.downloadInBackground(true)` on the `UpdatePrompt` fetches a release the program found on its own before
asking, so the window says "version 2.5 is ready to install" and the button installs at once. It is off by
default because it spends the customer's connection - tens of megabytes - unasked; turn it on when the user
wants it, not as a matter of course. A release the person skipped is not downloaded.

## Signed releases

The SHA-256 a release publishes ties the file to the release; it does not help when the release itself is
forged by somebody who took over the GitHub account. With signing, the program carries the publisher's
public key and is offered only releases signed with the private key, which never goes near GitHub.

Setting it up is the user's decision and partly the user's own action:

1. **The key is made by the user, once:** `..\desktop-updater\tools\signing-key.ps1 -New`. It writes the
   private key to `%USERPROFILE%\.desktop-updater\release-signing.key` and prints the public key. Ask the
   user to run it and give you the printed public key. Do not run it for them unasked, do not read the key
   file, and never copy it into a repository, a commit, a log or a message.
2. **The program gets the public key:** `.signedBy("MCowBQYDK2VwAyEA...")` on the `AppUpdater` builder.
   The public key is not a secret; it belongs in the code.
3. **The release script signs:** in the project's `packaging/release.ps1` add
   `$release.AppName = '<exactly the builder's name>'` and
   `$release.SigningKey = Join-Path $env:USERPROFILE '.desktop-updater\release-signing.key'`.
   The script then signs the installer, publishes `<installer>.sig` beside it, and stops rather than
   publish unsigned when the key file is missing.

Tell the user what turning it on commits them to, before they do:

- From the first version built with `signedBy`, **every** release must be signed, or installed copies are
  offered nothing. `release.ps1 -VerifyOnly` says whether the newest release is signed.
- The key file is the only copy. Lost, no release can be signed again and installed copies accept nothing
  else; they need a backup of it somewhere safe.
- Copies installed before the key was added do not check anything; protection starts with the first
  signed version a customer installs.
- The program's name is part of what is signed, so `AppName` and `AppUpdater.builder("...")` must be the
  same text. One key can serve several programs.
- A runtime image built with JDK 21 or older needs `jdk.crypto.ec` for the signature check.

## Linux and macOS

A program published for more than Windows names each system's file; `{arch}` stands for the processor the
copy runs on (`x64` or `arm64`):

```java
ReleaseSource.github("owner/app")
        .asset("*-setup.exe")                          // Windows, and any system not named
        .assetFor(Os.LINUX, "*-linux-{arch}.deb")
        .assetFor(Os.MAC, "*-macos-{arch}.dmg")
```

Take the patterns from what the packaging really produces (the packaging script and the workflow that
builds the other systems), not from this example - the name, the processor's spelling and the order differ
from project to project.

- A release without a file for the copy's system and processor is passed over on that system. That is the
  point: the Windows installer usually goes up first and the others are added by a workflow minutes later,
  or not at all when the workflow fails.
- Windows installs and reopens as before. On Linux and macOS the button is "Download the installer": the
  file is downloaded, checked, and opened with the system's own handler (`xdg-open` / `open`); the person
  finishes the installation and starts the program again. The program does not close itself, so `onExit`
  is not called there. Downloading ahead is Windows only.
- In the project's `release.ps1`, `$release.OtherAssets = @('*-linux-x64.deb', '*-macos-arm64.dmg', ...)`
  makes `-VerifyOnly` say whether a release carries them. The script itself publishes the Windows
  installer only.
- A program that uses `signedBy` needs the other systems' installers signed too (`<file>.sig` beside each),
  or copies there are offered nothing.
- This path has not been seen working on a real Linux or macOS machine yet. Say so to the user, and if you
  can run the program there, try an update before calling it done.

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
- **"Could not check" naming a signature:** the program uses `signedBy` and the newest release is unsigned,
  or signed with another key or for another program name. `release.ps1 -VerifyOnly` shows whether it is
  signed; `AppName` must equal the builder's name.
- **"Could not check":** the reason is in the dialog. HTTP 403 from GitHub is its hourly limit of 60
  requests per address; a TLS error in a jlink image is the `jdk.crypto.ec` note in step 6.

## What not to do

- Do not copy the library's classes into the program. A fix then has to be made in every program; that is
  the problem the library exists to remove. If the library lacks something, change the library and tag it.
- Do not write the version into a properties file for the updater. The launcher's version is the
  installer's version; a second copy can only disagree with it.
- Do not call `AppUpdater.check()` or `download()` on the JavaFX thread - they wait on the network.
  `UpdatePrompt` already keeps them off it.
