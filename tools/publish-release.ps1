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

.PARAMETER Required
    Marks the release required: every older copy must install it and is given no "Skip this version".
    It does so by ending the release's description with <!-- update: required -->, which GitHub's page
    does not show. A notes file that already holds that line makes the release required without this.

.PARAMETER SigningKey
    The publisher's key file (made by tools\signing-key.ps1 -New). With it the installer is signed and
    the signature is published beside it as <installer>.sig. A program that carries the matching public
    key (.signedBy("...")) is offered only releases signed this way, so for such a program every release
    needs it. The DESKTOP_UPDATER_SIGNING_KEY environment variable is used when this is not given.

.PARAMETER AppName
    The program's name exactly as its code gives it to AppUpdater.builder("..."). It is part of what is
    signed, so it is required with -SigningKey.

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
    [string]$SigningKey,
    [string]$AppName,
    [switch]$Required,
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

# Runs the library's ReleaseSignature as the publisher's tool. Java runs a single source file without a
# build, but from inside the source tree it would pick up module-info.java, so a copy is run instead.
function Invoke-SigningTool {
    param([string]$WorkDir, [string[]]$Arguments)
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'java was not found on PATH; signing needs a JDK 17 or newer.' }
    $source = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\main\java\com\hamza\updater\ReleaseSignature.java'
    if (-not (Test-Path -LiteralPath $source)) { throw "The signing tool is not at $source." }
    $folder = Join-Path $WorkDir 'signing-tool\com\hamza\updater'
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $copy = Join-Path $folder 'ReleaseSignature.java'
    Copy-Item -LiteralPath $source -Destination $copy -Force
    return Invoke-Native -Exe 'java' -Arguments (@($copy) + $Arguments)
}

# What a release's description holds to make it required; the same line the library looks for.
$script:RequiredMark = '<!--\s*update\s*:\s*required\s*-->'
$script:RequiredLine = '<!-- update: required -->'

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
    $marked = @()
    foreach ($release in $releases) {
        $parsed = ConvertTo-ReleaseVersion $release.tag_name
        $asset = @($release.assets | Where-Object { $_.name -like $Glob -and $_.state -eq 'uploaded' }) | Select-Object -First 1
        $reason = $null
        if (-not $parsed) { $reason = 'its tag is not a version' }
        elseif ($release.draft) { $reason = 'it is a draft' }
        elseif (($release.prerelease -or $parsed.Suffix) -and -not $WithPreReleases) { $reason = 'it is a pre-release' }
        # As the program counts it: the mark of a release without an installer still counts.
        elseif ("$($release.body)" -match $script:RequiredMark) { $marked += [pscustomobject]@{ Tag = $release.tag_name; Number = $parsed.Number } }
        if (-not $reason -and -not $asset) { $reason = "it has no file matching $Glob" }
        if ($reason) {
            $passedOver += [pscustomobject]@{ Tag = $release.tag_name; Reason = $reason }
            continue
        }
        if (-not $offered -or $parsed.Number -gt $offered.Number) {
            $signature = @($release.assets | Where-Object { $_.name -eq "$($asset.name).sig" -and $_.state -eq 'uploaded' }) | Select-Object -First 1
            $offered = [pscustomobject]@{
                Tag = $release.tag_name; Number = $parsed.Number; Name = $release.name
                Asset = $asset.name; Size = [long]$asset.size; Digest = [string]$asset.digest
                Signature = $(if ($signature) { $signature.name } else { $null })
                PreRelease = [bool]$release.prerelease; Notes = [string]$release.body
            }
        }
    }
    # A required release stays required after a later one replaces it; one newer than what is offered asks nothing yet.
    $minimum = $null
    if ($offered) {
        $minimum = $marked | Where-Object { $_.Number -le $offered.Number } | Sort-Object Number -Descending | Select-Object -First 1
    }
    return [pscustomobject]@{ Offered = $offered; PassedOver = $passedOver; Minimum = $minimum; All = $releases }
}

function Show-OfferedRelease($Found) {
    foreach ($skipped in $Found.PassedOver) { Write-Note "$($skipped.Tag) is passed over: $($skipped.Reason)" }
    if (-not $Found.Offered) {
        Write-Note 'No release would be offered to an installed copy.'
        return
    }
    $o = $Found.Offered
    Write-Ok ("An installed copy older than {0} is offered {1} ({2}, {3:N1} MB)" -f $o.Number, $o.Tag, $o.Asset, ($o.Size / 1MB))
    if ($Found.Minimum) {
        Write-Note "Required: a copy older than $($Found.Minimum.Number) must update and cannot skip it ($($Found.Minimum.Tag) is marked required)."
    } else {
        Write-Ok 'Nothing is required: every copy may skip or postpone it'
    }
    if ($o.Signature) {
        Write-Ok "Signed: $($o.Signature)"
    } else {
        Write-Note 'Not signed: a program that carries its publisher''s key (.signedBy) is not offered this release.'
    }
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
    $notesText = ''
    if ($NotesFile) {
        if (-not (Test-Path -LiteralPath $NotesFile)) { throw "The notes file $NotesFile is not there." }
        $notesText = "$(Get-Content -LiteralPath $NotesFile -Raw -Encoding UTF8)"
        Write-Ok "What's new: $NotesFile"
    } else {
        Write-Note "No notes file (docs\release-notes\$tag.md): customers will read GitHub's list of commits as ""What's new""."
    }
    if (-not $SigningKey) { $SigningKey = $env:DESKTOP_UPDATER_SIGNING_KEY }
    if ($SigningKey) {
        if (-not (Test-Path -LiteralPath $SigningKey)) {
            throw "The signing key $SigningKey is not there. Publishing without it would give programs that expect a signature a release they refuse."
        }
        if (-not $AppName) { throw '-AppName is needed with a signing key: the program''s name as AppUpdater.builder("...") has it.' }
        $SigningKey = (Resolve-Path -LiteralPath $SigningKey).Path
        Write-Ok "Will be signed for '$AppName' with $SigningKey"
    } else {
        Write-Note 'No signing key: the release will not be signed (fine unless the program uses .signedBy).'
    }
    $isRequired = $Required.IsPresent -or ($notesText -match $script:RequiredMark)
    if ($isRequired) {
        Write-Note "This release is REQUIRED: every copy older than $version must install it and gets no ""Skip this version""."
    } else {
        Write-Ok 'Not a required release: copies may skip or postpone it (-Required changes that)'
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
    # jpackage leaves its installer read-only and the copy inherits that; "mvn clean" cannot delete a
    # read-only file on Windows, so the next release's build would stop at this one.
    Set-ItemProperty -LiteralPath $staged -Name IsReadOnly -Value $false
    $sha256 = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Ok ("{0} -> {1} ({2:N1} MB)" -f $installer.Name, $staged, ($installer.Length / 1MB))
    Write-Ok "SHA-256 $sha256"

    $signatureFile = $null
    if ($SigningKey) {
        $signatureFile = "$staged.sig"
        Remove-Item -LiteralPath $signatureFile -Force -ErrorAction SilentlyContinue
        $signed = Invoke-SigningTool -WorkDir $StagingDir -Arguments @('sign', '--key', $SigningKey, '--app', $AppName,
            '--version', $version, '--file', $staged, '--out', $signatureFile)
        if ($signed.Code -ne 0 -or -not (Test-Path -LiteralPath $signatureFile)) { throw 'Signing the installer failed.' }
        # Checked at once with the key's public half: what goes up is what a program will accept.
        $publicKey = (Invoke-SigningTool -WorkDir $StagingDir -Arguments @('public', '--key', $SigningKey)).Output | Select-Object -Last 1
        $checked = Invoke-SigningTool -WorkDir $StagingDir -Arguments @('verify', '--public', "$publicKey", '--app', $AppName,
            '--version', $version, '--file', $staged, '--sig', $signatureFile)
        if ($checked.Code -ne 0) { throw 'The signature just made does not verify; nothing was published.' }
        Write-Ok "Signed: $(Split-Path -Leaf $signatureFile) (verifies with public key $publicKey)"
    }

    if ($DryRun) {
        Write-Step 'Dry run: nothing was published'
        if ($tagToCreate) { Write-Host "   would create and push tag $tag" }
        Write-Host "   would create release '$title' on $Repository with $asset"
        if ($isRequired) { Write-Host "   would mark it required for every copy older than $version" }
        if ($signatureFile) { Write-Host "   would publish the signature $(Split-Path -Leaf $signatureFile) beside it" }
        return
    }

    # ----------------------------------------------------------------------------------- 4. publish
    Write-Step "4. Publishing $tag"
    if ($tagToCreate) {
        Invoke-Checked -Exe 'git' -Arguments @('tag', '-a', $tag, '-m', $title) -What "Creating tag $tag" | Out-Null
        Invoke-Checked -Exe 'git' -Arguments @('push', 'origin', $tag) -What "Pushing tag $tag" | Out-Null
        Write-Ok "Tag $tag created and pushed"
    }
    $create = @('release', 'create', $tag, $staged)
    if ($signatureFile) { $create += $signatureFile }
    $create += @('-R', $Repository, '--title', $title, '--verify-tag')
    if ($isRequired -and $notesText -notmatch $script:RequiredMark) {
        # The mark goes at the end of the description. Written without a byte-order mark, which would
        # otherwise open the release's text.
        $marked = Join-Path $StagingDir "$tag-notes.md"
        $text = ($notesText.TrimEnd() + "`n`n" + $script:RequiredLine + "`n").TrimStart()
        [System.IO.File]::WriteAllText($marked, $text, (New-Object System.Text.UTF8Encoding($false)))
        $create += @('--notes-file', $marked)
        if (-not $NotesFile) { $create += '--generate-notes' }
    } elseif ($NotesFile) {
        $create += @('--notes-file', $NotesFile)
    } else {
        $create += '--generate-notes'
    }
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
        if ($isRequired -and (-not $after.Minimum -or $after.Minimum.Tag -ne $tag)) { $problems += 'it was to be required, and the program does not read it so' }
        if ($signatureFile -and -not $after.Offered.Signature) { $problems += 'its signature is not among its files' }
    }
    if ($problems.Count -gt 0) { throw "The release is published but not right: $($problems -join '; ')." }
    Write-Ok "Release $tag is what installed copies will be offered"
} finally {
    Pop-Location
}
