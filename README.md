# desktop-updater

"A new version is available: download and install it", for JavaFX desktop programs packaged with jpackage.

[![CI](https://github.com/MohamedSalah-13/desktop-updater/actions/workflows/ci.yml/badge.svg)](https://github.com/MohamedSalah-13/desktop-updater/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/tag/MohamedSalah-13/desktop-updater)](https://github.com/MohamedSalah-13/desktop-updater/releases)
[![JitPack](https://jitpack.io/v/MohamedSalah-13/desktop-updater.svg)](https://jitpack.io/#MohamedSalah-13/desktop-updater)
![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-21%2B-2E7DAF?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-build-C71A36?logo=apachemaven&logoColor=white)
![License](https://img.shields.io/badge/license-source--available-lightgrey)

**English** | [العربية](README.ar.md)

## About

A small Java library that gives any desktop program built with JavaFX and jpackage an update feature:
**there is a new version → download it and install it**. It is written once here and used by every program,
instead of each project carrying its own update code.

## Features

- Checks the releases published on **GitHub Releases**, or a JSON file on your own server for private
  repositories.
- Shows the customer a window in Arabic or English: the new version, what's new, and
  «Update now / Later / Skip this version».
- Downloads the installer with a progress bar, and resumes an interrupted download where it stopped.
- Checks the size and **SHA-256** before running any file, and optionally an **Ed25519 signature** made with
  your own key.
- **Required updates**: a release older copies may not skip.
- On Windows: closes the program, runs the installer silently, then opens the program again. Or, for a
  program shipped without an installer, puts the new program folder from a zip in place of the old one.
- On Linux and macOS: downloads the installer for the system and processor, checks it, then opens it so the
  user finishes the installation.
- Release and packaging tools: a release script that publishes what installed copies will find, a shared
  PowerShell packaging module, and a reusable GitHub Actions workflow for Linux and macOS installers.
- No third-party dependencies: `java.base` and the JavaFX your program already has. Java 21 or newer.

## What's New

### v0.6.0

- **Announce a release your own way.** `updates.onAvailable(found -> ...)` hands a release the program found
  on its own to the program instead of opening the window, so it can show a card in a side bar or a badge on
  a menu. `found.update()` opens the update window for that release when the person asks, and
  `found.openPage()` opens its page. A required release still opens the window as well.

### v0.5.0

- **Programs shipped as a zip folder.** `InstallerKind.ZIP` updates a program the customer unzips and runs
  in place, with no installer and no WiX. The new folder is unpacked beside the old one while the program
  still runs, the two swap names after it closes, and the old folder comes back if the new version fails
  within 30 seconds.
- **Shared packaging steps.** `tools/Packaging.psm1` holds the steps every jpackage script repeats (finding
  jpackage and WiX, running Maven, reading the version, Windows installer options, naming the Linux and
  macOS files), tested on all three systems.
- **Linux and macOS installers in GitHub Actions.** The reusable workflow `package-unix.yml` builds them on
  GitHub's machines and adds them to the release.
- The release script clears read-only in the build folders before building.

### v0.4.0

- **Linux and macOS.** Each system gets its own file per processor; a release without a file for a copy's
  system is never offered to it. The button becomes «Download installer» and the program does not close
  itself.
- Fixed: a system named «Darwin» was read as Windows because the name contains «win».
- The «OK» button in the window's messages now follows the window's language.

Older releases: [docs/release-notes](docs/release-notes).

## Tech Stack

| | |
| --- | --- |
| Language | Java 21 (`maven.compiler.release` 21) |
| UI | JavaFX 21 (`provided`: the program brings its own) |
| Build | Maven, JUnit Jupiter 5.12 |
| Distribution | JitPack (`com.github.MohamedSalah-13:desktop-updater`) |
| Release tools | PowerShell (Windows PowerShell 5.1 and PowerShell 7), GitHub CLI, jpackage, WiX 3 |
| CI | GitHub Actions on Windows, Linux and macOS |

## Getting Started

These steps are for programs that have permission to use the library: the [license](LICENSE) does not allow
using, copying or distributing it without prior written permission from the copyright holder. To ask for
permission, open an issue in this repository.

### Add the dependency

`pom.xml`:

```xml
<repositories>
    <!-- Always before JitPack: see the note below the snippet -->
    <repository>
        <id>central</id>
        <url>https://repo.maven.apache.org/maven2</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.MohamedSalah-13</groupId>
    <artifactId>desktop-updater</artifactId>
    <version>v0.6.0</version>
</dependency>
```

**Do not remove `central` from the snippet.** A project's repositories are asked before the inherited Maven
Central, so with `jitpack.io` alone Maven asks it first for every library in the project. JitPack answers
some things it does not have with an empty file instead of a 404 (for example `xml-apis:xml-apis-ext`), so
an empty jar is saved and the build fails with `zip file is empty` on any machine whose local repository is
empty, starting with the CI server. It works on your machine only because the good jar is already saved
there.

With `module-info.java` add: `requires com.hamza.updater;`

### Wire it in

In `start(Stage)`:

```java
AppUpdater updater = AppUpdater.builder("File Transfer")
        .source(ReleaseSource.github("MohamedSalah-13/file-transfer").asset("*-setup.exe"))
        .build();

UpdatePrompt updates = UpdatePrompt.create(updater, stage)
        .language("ar")      // "ar" for Arabic, any other value for English
        .start();            // check 8 seconds after start, then every 6 hours

checkButton.setOnAction(e -> updates.checkNow());   // a «Check for updates» button
```

That is all. The program's version is not written by hand: the launcher that `jpackage` makes gives it to
the program (`jpackage.app-version`), and a copy run from the IDE has no version, so it is never offered an
update.

### Publishing requirements

1. The installer is made with `jpackage` and a fixed `--win-upgrade-uuid` (`--win-per-user-install` is
   recommended).
2. The tag on GitHub has the form `vX.Y.Z` and matches the version in `pom.xml`.
3. The release is not a Draft or a Pre-release (or use `.includePreReleases(true)`).
4. The installer file is uploaded to the release under a name that matches `.asset("...")`.

The release description on GitHub is what the customer sees under «What's new».

### Trying the window during development

Run the program from the IDE with `-Djpackage.app-version=0.0.1`: it then thinks it is an old installed
copy and shows the latest real release. Press «Later»: «Update now» will download the real installer and
run it.

### Building the library

```bash
mvn verify
```

## Configuration

| Need | How |
| --- | --- |
| A private repository or your own server | `ReleaseSource.manifest("https://example.com/app/latest.json")` |
| An Inno Setup installer | `.installer(InstallerKind.INNO_SETUP)` |
| Show the installer's normal pages | `.installer(InstallerKind.WIZARD)` |
| A program shipped as a zip folder (from `jpackage --type app-image`), not an installer | `.installer(InstallerKind.ZIP)` |
| A program with a tray icon or its own way of closing | `UpdatePrompt.onExit(() -> ...)` |
| Change the language while running | `updates.language("en")` |
| Change how often it checks (at least 5 minutes) | `updates.checkEvery(Duration.ofHours(12))` |
| Refuse to run an old copy when an update is required | `updates.enforceRequired(true)` |
| An immediate silent check (for example when the server rejects the version) | `updates.checkQuietly()` |
| Download the release in the background, then show «ready to install» | `updates.downloadInBackground(true)` |
| Announce a release in the program's own way (a card, a badge) instead of the window | `updates.onAvailable(found -> ...)` |
| Accept only releases signed with your key | `AppUpdater.builder(...).signedBy("...")` |
| No JavaFX window | `updater.check()`, then `download(...)`, then `install(...)` from a background thread |

The manifest file:

```json
{
  "version": "2.5.0",
  "url": "https://example.com/downloads/Program-2.5.0-setup.exe",
  "sha256": "9e08356040c651fbcf3134a702854cadfc4eaae15390dd159cb4e4f32dcb2d0e",
  "size": 34853376,
  "notes": "What's new in this release"
}
```

### Required updates

A release that breaks compatibility (a new protocol, a new database, a security hole) is marked «required»
with one line in its description on GitHub, an HTML comment that does not show on the page:

```html
<!-- update: required -->
```

Or from the release script: `.\packaging\release.ps1 -Required`.

- Every copy **older** than that release is shown a «Required update» window with no «Skip this version»
  button, and it comes back on every check even if the user skipped it before.
- The release stays required after newer ones are published: someone on 2.6, with 2.7 required and 2.9 the
  newest, is asked to update; someone on 2.7 or newer sees an ordinary update.
- By default the «Later» button stays. If the program must not run as an old copy, add
  `enforceRequired(true)`: the choice becomes «Update now» or «Close the program». This is the program's
  decision, not the library's, because only the program knows whether the old copy still works. If the
  check fails (no connection), the program runs as usual.
- In the manifest: `"minimum": "2.7.0"` means every copy older than 2.7.0 must update.

### «What's new» and downloading in the background

The release description is shown as it reads, not as it is written: headings are headings, lists are lists,
bold, italic, code and links look like themselves, and Arabic notes read right to left even in an English
window, and the other way round. Tables stay text, and images show only their description.

With `updates.downloadInBackground(true)` the release the program finds is downloaded before the user is
asked, so they see «Version 2.5 is ready to install» and pressing it installs at once with no wait. A
release the user skipped is not downloaded, and one whose download did not finish is shown the usual way.
The option is off by default because it uses the user's connection (tens of megabytes) without asking.

### Announcing a release your own way

A window over the work is not the only way to say a release is out. With `onAvailable` a release the
program finds on its own goes to the program, which shows it where it likes, and the window opens when the
person asks for it:

```java
updates = UpdatePrompt.create(updater, stage)
        .language("ar")
        .onAvailable(found -> {
            card.setText("Version " + found.version() + " is available");
            updateButton.setOnAction(e -> found.update());   // the usual window, for this release
            whatsNew.setOnAction(e -> found.openPage());     // the release's page in the browser
            card.setVisible(true);
        })
        .start();
```

- It is called on the JavaFX thread at every look that finds a release the user did not skip or put off this
  run. `found` also carries `current()`, `required()`, `ready()` (downloaded ahead), `notes()` and `page()`.
- A required release is announced and opens the window as well, so nobody misses it.
- «Check for updates» (`checkNow()`) still answers with the window.

### Signing releases

The SHA-256 check guarantees the file is the release's file, but it does not help if **the release itself**
is forged, for example if someone takes over your GitHub account and publishes a malicious installer. With
signing, the program carries your public key, and it is only offered a release you signed with your private
key, which is never on GitHub at all.

1. Create the key once (saved by default in `%USERPROFILE%\.desktop-updater\release-signing.key`):

   ```powershell
   .\tools\signing-key.ps1 -New
   ```

2. Put the public key the script prints into the program's code:

   ```java
   AppUpdater.builder("File Transfer").source(...).signedBy("MCowBQYDK2VwAyEA...").build();
   ```

3. In the project's `packaging/release.ps1`, add the program's name (exactly as in `builder("...")`) and
   where the key is:

   ```powershell
   $release.AppName    = 'File Transfer'
   $release.SigningKey = Join-Path $env:USERPROFILE '.desktop-updater\release-signing.key'
   ```

   From then on the script signs the installer and publishes the signature next to it as
   `<installer>.sig`, and checks it before and after publishing.

Before turning it on:

- **The key file is a secret and the only copy.** It never goes into any repository; keep a backup somewhere
  safe. If it is lost you cannot sign any release, and installed copies that carry its public half will not
  accept any other.
- **From the first release with `signedBy`, every release must be signed**, or installed copies are offered
  no update at all (the reason shows in «Check now»). `release.ps1 -VerifyOnly` says whether a release is
  signed.
- **Older copies do not check.** The protection starts with whoever installed a release that carries the
  key; copies before that accept any release, as before.
- What is signed: the program's name, the version number, and the installer's SHA-256 and size. So one
  program's signature does not work for another program signed with the same key, and an old signed
  installer does not pass as a new release.
- The algorithm is Ed25519. A runtime image built with JDK 21 or older needs the `jdk.crypto.ec` module.
- In the manifest, the signature goes in the `"signature"` field.

### Linux and macOS

A program published for more than one system names each system's file, and `{arch}` is replaced by the
machine's processor (`x64` or `arm64`):

```java
ReleaseSource.github("owner/app")
        .asset("*-setup.exe")                          // Windows, and any system not named
        .assetFor(Os.LINUX, "*-linux-{arch}.deb")
        .assetFor(Os.MAC, "*-macos-{arch}.dmg")
```

- **Each copy is offered only its own system's file.** A release with no file for a system (or for its
  processor) is never offered to copies on that system, so nobody sees an update they cannot install. This
  matters when the Windows installer is uploaded first and the other files are added minutes later.
- **On Windows** nothing changed: a silent install, then the program opens again.
- **On Linux and macOS** the button becomes «Download installer»: the file is downloaded, its size and
  SHA-256 are checked (and its signature, if the program uses `signedBy`), then it is opened with whatever
  the system opens that type with (`xdg-open` / `open`). The user finishes the installation and restarts the
  program; the program does not close itself. If nothing can open the file, a message shows where it is.
- Downloading in the background works on Windows only.
- In the release script, `$release.OtherAssets = @('*-linux-x64.deb', '*-macos-arm64.dmg')` makes
  `-VerifyOnly` say whether the release carries the other systems' files. The script itself publishes only
  the Windows installer.

This path has not been tried on a real Linux or macOS machine yet; its logic is tested by the tests and by
simulating the system on Windows.

#### Building the Linux and macOS files in GitHub Actions

jpackage builds only for its own system, so the Linux and macOS files are built on GitHub's machines. The
shared workflow `.github/workflows/package-unix.yml` runs the project's own packaging script on three
machines: Linux, macOS on Apple silicon, and macOS on Intel. It keeps the files with the run, and adds them
to the release if it is given a tag. The project calls it from a workflow of its own that decides when it
runs:

```yaml
on:
  release:
    types: [published]   # publishing the Windows installer with the release script starts this
  pull_request:
    paths: [packaging/**, pom.xml, .github/workflows/packages.yml]
  workflow_dispatch:
    inputs:
      tag:
        required: false
        default: ''

jobs:
  packages:
    name: Packages
    uses: MohamedSalah-13/desktop-updater/.github/workflows/package-unix.yml@<commit>
    permissions:
      contents: write
    with:
      script: packaging/build-installer.ps1
      files: target/installer/MyProgram-*
      tag: ${{ github.event.release.tag_name || inputs.tag }}
```

- **`script` and `files` are required.** The script runs with `pwsh` and names its own files, since the
  program looks for them by name.
- **An empty `tag`** in pull requests: the files are built and kept with the run only.
- **Optional:** `java-version` (default `25`), `apt-packages` (default `fakeroot`), `virtual-screen` for
  JavaFX tests on Linux together with `xvfb libgtk-3-0` in `apt-packages`, `test-reports` to keep the reports
  folder on failure, `artifact-name`, and `desktop-updater-ref` for a script that imports
  `tools/Packaging.psm1` (the same commit as in `uses:`).
- **Pin the call to a commit, not to `main`**, so no project's build changes without a pull request in it.

### Programs shipped as a zip

A program the customer unzips and runs in place, with no installer, publishes each release as a zip of its
folder as made by `jpackage --type app-image`, and says `.installer(InstallerKind.ZIP)` with
`.asset("*-windows-x64.zip")`. It does not need WiX.

- **The folder that is replaced** is the program folder that opens after the update (`relaunch`), by
  default the folder of the launcher that started this copy. The zip carries that launcher at its top, or
  inside a single folder at its top, as `Compress-Archive` makes it; otherwise it is refused.
- **`install` unpacks while the program is still running**, into `<folder>.update` beside it on the same
  disk. So a folder that cannot be written beside (inside Program Files, for example), or a file that is not
  this program's, is an error the customer sees while the program is open, and nothing is touched.
- **After it closes**, the old folder is renamed to `<folder>.previous` and the new one to its name, then the
  program opens. Renaming, not copying, so the folder is never half one version and half another, and if the
  second rename fails the first is undone. A file that is still open is waited for ten seconds, then the
  program opens as it was.
- **If the new version ends with an error within 30 seconds**, the old folder is put back and opened.
  Otherwise the old folder is deleted.
- Files the customer adds inside the program folder do not move to the new one: the program's data belongs
  outside it.

### How installation works

An installer cannot replace the files of a program that is still running. So the library writes a small
PowerShell script in `%LOCALAPPDATA%\<program name>-updates\` that waits for the program to close (up to two
minutes), runs the installer, then opens the program again. The log is `update.log` in the same folder, and
it is the first thing to read if an update did not happen.

If one installation holds more than one program (a client and a server, for example), or another window of
the same program is open, the library asks the user to close them first, because the installer does not
replace files that are still open.

## Release and packaging tools

### The release script

Publishing by hand fails silently: a file name that does not match, a Draft release, or a number that is not
newer, and the update simply does not appear, with no error anywhere. `tools/publish-release.ps1` does all
the steps and stops before publishing if a condition fails:

1. Checks the project: no uncommitted changes, a version of the form `X.Y.Z`, the tag at `HEAD`, no release
   with the same number, and the number newer than the last published release.
2. Runs the project's own packaging script.
3. Finds the resulting installer and copies it under the published name.
4. Creates the release on GitHub and uploads the installer.
5. Reads the release the way the program reads it, and says which release installed copies will be offered.

In each project, copy `tools/release.example.ps1` to `packaging/release.ps1` and fill in the four values at
its top. The shared script is taken from the desktop-updater clone next to the project, or from the path in
`DESKTOP_UPDATER_HOME`.

```powershell
.\packaging\release.ps1 -DryRun       # everything except publishing
.\packaging\release.ps1               # the tag v<version> must exist and be at HEAD
.\packaging\release.ps1 -CreateTag    # creates the tag at HEAD first
.\packaging\release.ps1 -Required     # a required release: no «Skip» for older copies
.\packaging\release.ps1 -VerifyOnly   # which release installed copies are offered now, and why
```

«What's new» is taken from `docs/release-notes/v<version>.md` if it exists, otherwise from the list of
commits GitHub generates. It needs `git` and `gh`, and runs on Windows PowerShell 5.1.

### Shared packaging steps

Every program's packaging script repeats the same steps, and each program wrote them its own way. The module
`tools/Packaging.psm1` writes them once, and the program's script keeps only what is its own: its name,
modules, main class, launchers, Java options and upgrade code. It is found the same way as the release
script: a desktop-updater clone next to the repository, or the path in `DESKTOP_UPDATER_HOME`.

```powershell
$updaterHome = $env:DESKTOP_UPDATER_HOME
if (-not $updaterHome) {
    $repository = & git -C $PSScriptRoot rev-parse --show-toplevel 2>$null
    $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
}
Import-Module (Join-Path $updaterHome 'tools/Packaging.psm1') -Force
```

| Function | What it does |
| --- | --- |
| `Find-JPackage -MinimumVersion 25` | Finds a JDK with jpackage in `JAVA_HOME`, on PATH or in `~/.jdks`, and refuses older ones |
| `Invoke-MavenBuild -Pom … -Arguments …` | Runs Maven (the wrapper if there is one), after clearing read-only in `target` |
| `Get-PomVersion -Pom …` | The version without `-SNAPSHOT`, and the version given to jpackage (macOS refuses 0.x, so it becomes 1.x) |
| `New-JPackageInput -Path … -Item …` | A fresh `--input` folder holding only what is named |
| `Resolve-PackageType` and `Get-IconExtension` | The installer type and icon file for each system |
| `Find-WiX` | Finds WiX 3 for exe and msi and puts it on PATH |
| `Get-WinInstallerArgument` | The Windows installer options in one order, with the upgrade code checked |
| `Rename-PlatformPackage` | The published name on Linux and macOS: `<name>-<version>-<system>-<arch>.<type>` |
| `Invoke-Native` | Runs a program and stops if it exits with anything but 0 |

It runs on Windows PowerShell 5.1 and on PowerShell 7 on all three systems, and its test
`tools/tests/Packaging.Tests.ps1` runs in CI on all three. The shared Linux and macOS workflow (above) fetches
desktop-updater next to the build when given `desktop-updater-ref`: the same commit written in `uses:`,
because a called workflow does not know the commit it was called from.

### With Claude Code

The folder `claude-skill/desktop-auto-update` holds a skill that wires the library into any existing or new
project. Copy it to `~/.claude/skills/`, then tell Claude: «Add automatic updates to this program».

## Roadmap

- Try the Linux and macOS update path on a real machine; so far it is covered by tests and by simulating the
  system on Windows.

Progress is tracked in [docs/STATUS.md](docs/STATUS.md).

## License

Copyright (c) 2026 Mohamed Salah. All rights reserved. The source code is published to be read; it is not
open-source software, and using, copying, modifying or distributing it needs prior written permission. See
[LICENSE](LICENSE). For licensing enquiries, open an issue in this repository.
