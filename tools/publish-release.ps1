<#
.SYNOPSIS
    Publishes the version in a project's pom.xml as a GitHub release that installed copies will find.

.DESCRIPTION
    A program that uses desktop-updater is only offered a release when four things hold: the tag is
    v<version>, the release is neither a draft nor (unless the program asks for them) a pre-release, it
    carries the installer under the name the program looks for, and its version is newer than every
    release before it. Doing that by hand goes wrong quietly: nothing fails, the update just never shows.

    This script does the steps in one go and stops before publishing if any of them would not hold:

      1. checks the project: clean working tree, a MAJOR.MINOR.PATCH version, the tag, no release of
         that version yet, and a version newer than the newest published one
      2. runs the project's own packaging script
      3. finds the installer it made and copies it under its published name
      4. creates the release and uploads the installer
      5. reads the release back the way the program does and says what an installed copy is offered

    Projects call it through a small packaging\release.ps1 of their own (see release.example.ps1).

    Needs git and the GitHub CLI (gh), signed in. Written for Windows PowerShell 5.1.

.PARAMETER Title
    The release's name. {version} stands for the version: 'File Transfer {version}'.

.PARAMETER AssetName
    The installer's published name: 'FileTransfer-{version}-setup.exe'. It must match -AssetGlob.

.PARAMETER AssetGlob
    What the program's updater looks for - the same text as its .asset("...") call.

.PARAMETER BuildScript
    The project's packaging script, relative to -ProjectDir. It runs in its own PowerShell process.

.PARAMETER NotesFile
    What customers read as "What's new". Without it, docs\release-notes\v<version>.md is used when
    it exists, and otherwise GitHub's generated list of commits.

.PARAMETER PreRelease
    Publishes a pre-release. Say it only for a program whose updater includes pre-releases.

.PARAMETER CreateTag
    Creates the tag v<version> at HEAD and pushes it when it does not exist yet. Without it the tag
    must already be there (for example from the maven-release-tag skill).

.PARAMETER DryRun
    Does everything up to the installer's final name and publishes nothing.

.PARAMETER VerifyOnly
    Publishes nothing and builds nothing: reads the repository's releases the way the program does and
    says which one an installed copy is offered, and why any other was passed over.

.EXAMPLE
    .\packaging\release.ps1 -DryRun

.EXAMPLE
    ..\desktop-updater\tools\publish-release.ps1 -VerifyOnly -Title x -AssetName x -Repository owner/name
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$Title,
    [Parameter(Mandatory = $true)] [string]$AssetName,
    [string]$AssetGlob = '*-setup.exe',
    [string]$ProjectDir = (Get-Location).Path,
    [string]$Pom = 'pom.xml',
    [string]$BuildScript,
    [string[]]$BuildArguments = @(),
    [string]$InstallerDir = 'target\installer',
    [string]$StagingDir,
    [string]$NotesFile,
    [string]$Repository,
    [switch]$PreRelease,
    [switch]$CreateTag,
    [switch]$SkipBuild,
    [switch]$DryRun,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$Text) { Write-Host ''; Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "   ok    $Text" -ForegroundColor Green }
function Write-Note([string]$Text) { Write-Host "   note  $Text" -ForegroundColor Yellow }

# A native program writes warnings to stderr; under 'Stop' Windows PowerShell 5.1 would turn the first
# such line into a failure. So native programs run under 'Continue' and are judged by their exit code.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [switch]$Quiet)
    $before = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) { $output = & $Exe @Arguments 2>$null } else { $output = & $Exe @Arguments }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $before
    }
    return [pscustomobject]@{ Code = $code; Output = $output }
}

function Invoke-Checked {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    $result = Invoke-Native -Exe $Exe -Arguments $Arguments
    if ($result.Code -ne 0) { throw "$What failed ($Exe exited with $($result.Code))." }
    return $result.Output
}

# 'v2.5.0' -> 2.5.0; a tag that is not a version -> $null. A '-beta.1' suffix marks a pre-release.
function ConvertTo-ReleaseVersion([string]$Tag) {
    if ($Tag -notmatch '^[vV]?(\d+(?:\.\d+){0,3})(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$') { return $null }
    $numbers = $Matches[1].Split('.')
    while ($numbers.Count -lt 3) { $numbers += '0' }
    return [pscustomobject]@{ Number = [version]($numbers -join '.'); Suffix = [bool]$Matches[2] }
}

# The repository's releases as the program's updater judges them: which it would offer, and why not the rest.
function Get-OfferedRelease {
    param([string]$Repo, [string]$Glob, [bool]$WithPreReleases)
    $json = Invoke-Checked -Exe 'gh' -Arguments @('api', "repos/$Repo/releases?per_page=30") -What 'Reading the releases'
    # Windows PowerShell 5.1 hands a JSON array on as one object; the assignment is what unrolls it.
    $parsedJson = ($json -join "`n") | ConvertFrom-Json
    $releases = @($parsedJson | ForEach-Object { $_ })
    $offered = $null
    $passedOver = @()
    foreach ($release in $releases) {
        $parsed = ConvertTo-ReleaseVersion $release.tag_name
        $asset = @($release.assets | Where-Object { $_.name -like $Glob -and $_.state -eq 'uploaded' }) | Select-Object -First 1
        $reason = $null
        if (-not $parsed) { $reason = 'its tag is not a version' }
        elseif ($release.draft) { $reason = 'it is a draft' }
        elseif (($release.prerelease -or $parsed.Suffix) -and -not $WithPreReleases) { $reason = 'it is a pre-release' }
        elseif (-not $asset) { $reason = "it has no file matching $Glob" }
        if ($reason) {
            $passedOver += [pscustomobject]@{ Tag = $release.tag_name; Reason = $reason }
            continue
        }
        if (-not $offered -or $parsed.Number -gt $offered.Number) {
            $offered = [pscustomobject]@{
                Tag = $release.tag_name; Number = $parsed.Number; Name = $release.name
                Asset = $asset.name; Size = [long]$asset.size; Digest = [string]$asset.digest
                PreRelease = [bool]$release.prerelease; Notes = [string]$release.body
            }
        }
    }
    return [pscustomobject]@{ Offered = $offered; PassedOver = $passedOver; All = $releases }
}

function Show-OfferedRelease($Found) {
    foreach ($skipped in $Found.PassedOver) { Write-Note "$($skipped.Tag) is passed over: $($skipped.Reason)" }
    if (-not $Found.Offered) {
        Write-Note 'No release would be offered to an installed copy.'
        return
    }
    $o = $Found.Offered
    Write-Ok ("An installed copy older than {0} is offered {1} ({2}, {3:N1} MB)" -f $o.Number, $o.Tag, $o.Asset, ($o.Size / 1MB))
    if (-not $o.Digest) { Write-Note 'GitHub gave no SHA-256 for the file; the program then checks its size only.' }
    if ([string]::IsNullOrWhiteSpace($o.Notes)) { Write-Note 'The release has no description; the "What''s new" box will be empty.' }
}

# ---------------------------------------------------------------------------------------------- start

foreach ($tool in 'git', 'gh') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool was not found on PATH." }
}
$ProjectDir = (Resolve-Path -LiteralPath $ProjectDir).Path
Push-Location -LiteralPath $ProjectDir
try {
    if (-not $Repository) {
        $Repository = (Invoke-Checked -Exe 'gh' -Arguments @('repo', 'view', '--json', 'nameWithOwner', '--jq', '.nameWithOwner') `
                -What 'Finding the GitHub repository (is gh signed in, and is this a clone of it?)' | Select-Object -First 1)
    }
    $Repository = "$Repository".Trim()

    if ($VerifyOnly) {
        Write-Step "What an installed copy of $Repository is offered"
        Show-OfferedRelease (Get-OfferedRelease -Repo $Repository -Glob $AssetGlob -WithPreReleases $PreRelease.IsPresent)
        return
    }

    # ------------------------------------------------------------------------------------ 1. checks
    Write-Step '1. Checking the project'
    $pomPath = Join-Path $ProjectDir $Pom
    if (-not (Test-Path -LiteralPath $pomPath)) { throw "No $Pom in $ProjectDir." }
    [xml]$pomXml = Get-Content -LiteralPath $pomPath -Raw -Encoding UTF8
    $version = "$($pomXml.project.version)".Trim()
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "The version in $Pom is '$version'; a release needs MAJOR.MINOR.PATCH." }
    $tag = "v$version"
    $title = $Title.Replace('{version}', $version)
    $asset = $AssetName.Replace('{version}', $version)
    Write-Ok "Version $version of $Repository -> tag $tag, '$title', $asset"

    if ($asset -notlike $AssetGlob) {
        throw "The installer would be published as '$asset', which does not match '$AssetGlob': the program would not find it."
    }

    $dirty = @((Invoke-Native -Exe 'git' -Arguments @('status', '--porcelain')).Output)
    if ($dirty.Count -gt 0) {
        if (-not $DryRun) { throw "The working tree has uncommitted changes; a release is built from a commit.`n$($dirty -join "`n")" }
        Write-Note "The working tree has $($dirty.Count) uncommitted change(s); a real run stops here."
    } else {
        Write-Ok 'The working tree is clean'
    }

    $head = (Invoke-Checked -Exe 'git' -Arguments @('rev-parse', 'HEAD') -What 'Reading HEAD' | Select-Object -First 1)
    $tagCommit = (Invoke-Native -Exe 'git' -Arguments @('rev-parse', '-q', '--verify', "refs/tags/${tag}^{commit}") -Quiet).Output | Select-Object -First 1
    $tagToCreate = $false
    if ($tagCommit) {
        if ($tagCommit -ne $head) {
            throw "Tag $tag is at $($tagCommit.Substring(0, 7)) but HEAD is $($head.Substring(0, 7)). Check out the tag, or the release would not be the tagged code."
        }
        Write-Ok "Tag $tag is at HEAD"
    } elseif ($CreateTag) {
        $tagToCreate = $true
        Write-Ok "Tag $tag does not exist yet and will be created at HEAD ($($head.Substring(0, 7)))"
    } else {
        throw "Tag $tag does not exist. Create it (the maven-release-tag skill does), or pass -CreateTag to tag HEAD."
    }

    if ((Invoke-Native -Exe 'gh' -Arguments @('release', 'view', $tag, '-R', $Repository, '--json', 'tagName') -Quiet).Code -eq 0) {
        throw "$Repository already has a release $tag. Bump the version; a published release is not replaced."
    }
    Write-Ok "No release $tag yet"

    $before = Get-OfferedRelease -Repo $Repository -Glob $AssetGlob -WithPreReleases $PreRelease.IsPresent
    if ($before.Offered -and $before.Offered.Number -ge [version]$version) {
        throw "The newest published release is $($before.Offered.Tag); $version is not newer, so no installed copy would be offered it."
    }
    if ($before.Offered) { Write-Ok "Newer than the newest published release ($($before.Offered.Tag))" } else { Write-Ok 'The first release an installed copy can be offered' }

    if (-not $NotesFile) {
        $default = Join-Path $ProjectDir "docs\release-notes\$tag.md"
        if (Test-Path -LiteralPath $default) { $NotesFile = $default }
    } elseif (-not [System.IO.Path]::IsPathRooted($NotesFile)) {
        $NotesFile = Join-Path $ProjectDir $NotesFile
    }
    if ($NotesFile) {
        if (-not (Test-Path -LiteralPath $NotesFile)) { throw "The notes file $NotesFile is not there." }
        Write-Ok "What's new: $NotesFile"
    } else {
        Write-Note "No notes file (docs\release-notes\$tag.md): customers will read GitHub's list of commits as ""What's new""."
    }

    # ------------------------------------------------------------------------------------- 2. build
    $started = Get-Date
    if ($SkipBuild) {
        Write-Step '2. Building the installer (skipped)'
    } else {
        if (-not $BuildScript) { throw 'Give -BuildScript (the project''s packaging script), or -SkipBuild to use an installer already built.' }
        $buildPath = Join-Path $ProjectDir $BuildScript
        if (-not (Test-Path -LiteralPath $buildPath)) { throw "The packaging script $buildPath is not there." }
        Write-Step "2. Building the installer ($BuildScript)"
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $buildPath) + $BuildArguments
        $build = Invoke-Native -Exe 'powershell.exe' -Arguments $arguments
        $build.Output | ForEach-Object { Write-Host "   $_" }
        if ($build.Code -ne 0) { throw "The packaging script failed (exit code $($build.Code))." }
    }

    # --------------------------------------------------------------------------------- 3. installer
    Write-Step '3. Finding the installer'
    $installerPath = Join-Path $ProjectDir $InstallerDir
    if (-not (Test-Path -LiteralPath $installerPath)) { throw "No folder $installerPath; did the packaging script run?" }
    $candidates = @(Get-ChildItem -LiteralPath $installerPath -File |
            Where-Object { $_.Extension -in '.exe', '.msi' -and $_.Name -like "*$version*" })
    if ($candidates.Count -ne 1) {
        $names = (Get-ChildItem -LiteralPath $installerPath -File | ForEach-Object { $_.Name }) -join ', '
        throw "Expected one .exe or .msi of version $version in $installerPath, found $($candidates.Count). Files there: $names"
    }
    $installer = $candidates[0]
    if (-not $SkipBuild -and $installer.LastWriteTime -lt $started.AddSeconds(-5)) {
        throw "$($installer.Name) is older than this run ($($installer.LastWriteTime)); the packaging script did not rebuild it."
    }
    if ($installer.Length -lt 1MB) { throw "$($installer.Name) is only $($installer.Length) bytes; that is not an installer." }
    if ([System.IO.Path]::GetExtension($asset) -ne $installer.Extension) {
        throw "The installer is a $($installer.Extension) file but would be published as '$asset'."
    }

    if (-not $StagingDir) { $StagingDir = Join-Path $ProjectDir 'target\release' }
    New-Item -ItemType Directory -Path $StagingDir -Force | Out-Null
    $staged = Join-Path $StagingDir $asset
    Copy-Item -LiteralPath $installer.FullName -Destination $staged -Force
    $sha256 = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Ok ("{0} -> {1} ({2:N1} MB)" -f $installer.Name, $staged, ($installer.Length / 1MB))
    Write-Ok "SHA-256 $sha256"

    if ($DryRun) {
        Write-Step 'Dry run: nothing was published'
        if ($tagToCreate) { Write-Host "   would create and push tag $tag" }
        Write-Host "   would create release '$title' on $Repository with $asset"
        return
    }

    # ----------------------------------------------------------------------------------- 4. publish
    Write-Step "4. Publishing $tag"
    if ($tagToCreate) {
        Invoke-Checked -Exe 'git' -Arguments @('tag', '-a', $tag, '-m', $title) -What "Creating tag $tag" | Out-Null
        Invoke-Checked -Exe 'git' -Arguments @('push', 'origin', $tag) -What "Pushing tag $tag" | Out-Null
        Write-Ok "Tag $tag created and pushed"
    }
    $create = @('release', 'create', $tag, $staged, '-R', $Repository, '--title', $title, '--verify-tag')
    if ($NotesFile) { $create += @('--notes-file', $NotesFile) } else { $create += '--generate-notes' }
    if ($PreRelease) { $create += '--prerelease' }
    $url = Invoke-Checked -Exe 'gh' -Arguments $create -What "Creating the release (is tag $tag pushed?)"
    Write-Ok "Published: $($url | Select-Object -Last 1)"

    # ------------------------------------------------------------------------------------ 5. verify
    Write-Step '5. Reading the release back as the program does'
    $after = Get-OfferedRelease -Repo $Repository -Glob $AssetGlob -WithPreReleases $PreRelease.IsPresent
    Show-OfferedRelease $after
    $problems = @()
    if (-not $after.Offered -or $after.Offered.Tag -ne $tag) {
        $problems += "an installed copy is not offered $tag"
    } else {
        if ($after.Offered.Asset -ne $asset) { $problems += "the file is called $($after.Offered.Asset), not $asset" }
        if ($after.Offered.Size -ne $installer.Length) { $problems += "GitHub holds $($after.Offered.Size) bytes, the installer is $($installer.Length)" }
        if ($after.Offered.Digest -and $after.Offered.Digest -ne "sha256:$sha256") { $problems += 'GitHub''s SHA-256 is not the installer''s' }
    }
    if ($problems.Count -gt 0) { throw "The release is published but not right: $($problems -join '; ')." }
    Write-Ok "Release $tag is what installed copies will be offered"
} finally {
    Pop-Location
}
