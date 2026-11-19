<#
.SYNOPSIS
    Publishes the version in pom.xml as a GitHub release that installed copies will be offered.

.DESCRIPTION
    Copy this file to the project as packaging\release.ps1 and fill in the four values below. The work
    is done by desktop-updater's tools\publish-release.ps1, which is expected in a clone of
    https://github.com/MohamedSalah-13/desktop-updater beside this project (or where the
    DESKTOP_UPDATER_HOME environment variable points).

.EXAMPLE
    .\packaging\release.ps1 -DryRun       # builds, names the installer, publishes nothing
    .\packaging\release.ps1               # the tag v<version> must exist and be at HEAD
    .\packaging\release.ps1 -CreateTag    # tags HEAD first
    .\packaging\release.ps1 -Required     # every older copy must install it; no "Skip this version"
    .\packaging\release.ps1 -VerifyOnly   # which release is an installed copy offered right now?
#>
param(
    [switch]$DryRun,
    [switch]$SkipBuild,
    [switch]$CreateTag,
    [switch]$VerifyOnly,
    [switch]$Required,
    [switch]$PreRelease
)

$project = Split-Path -Parent $PSScriptRoot

# --- this project ---------------------------------------------------------------------------------
$release = @{
    Title       = 'My Program {version}'               # the release's name on GitHub
    AssetName   = 'MyProgram-{version}-setup.exe'      # must match the program's .asset("...") glob
    AssetGlob   = '*-setup.exe'                        # the same text as that .asset("...") call
    BuildScript = 'packaging\package.ps1'              # makes the installer in target\installer
}
# Paths are relative to the folder above this script's folder, the one that holds pom.xml.
#
# A program whose code says .signedBy("...") is offered only signed releases. For such a program add its
# name exactly as AppUpdater.builder("...") has it, and where the key file is (never inside a repository):
#   $release.AppName    = 'My Program'
#   $release.SigningKey = Join-Path $env:USERPROFILE '.desktop-updater\release-signing.key'
# --------------------------------------------------------------------------------------------------

# desktop-updater is looked for beside the repository, wherever in it this script sits.
$updaterHome = $env:DESKTOP_UPDATER_HOME
if (-not $updaterHome) {
    $repository = & git -C $project rev-parse --show-toplevel 2>$null
    if (-not $repository) { $repository = $project }
    $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
}
$shared = Join-Path $updaterHome 'tools\publish-release.ps1'
if (-not (Test-Path -LiteralPath $shared)) {
    throw "desktop-updater was not found at $updaterHome. Clone https://github.com/MohamedSalah-13/desktop-updater beside this project, or set DESKTOP_UPDATER_HOME."
}

& $shared -ProjectDir $project @release @PSBoundParameters
