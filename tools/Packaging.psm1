<#
.SYNOPSIS
    The steps every jpackage script of a JavaFX program repeats: finding the JDK, building with Maven,
    reading the version, staging the input, finding WiX, the Windows installer's options and the
    published names on Linux and macOS.

.DESCRIPTION
    What belongs to one program - its name, modules, main class, launchers, java options and the
    upgrade UUID - stays in that program's script, which imports this module:

        $updaterHome = $env:DESKTOP_UPDATER_HOME
        if (-not $updaterHome) {
            $repository = & git -C $PSScriptRoot rev-parse --show-toplevel 2>$null
            $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
        }
        Import-Module (Join-Path $updaterHome 'tools/Packaging.psm1') -Force

    Runs in Windows PowerShell 5.1 and in PowerShell 7 on Windows, Linux and macOS. Every function
    throws on failure, so a script that sets $ErrorActionPreference = 'Stop' stops at the first one.
    tools/tests/Packaging.Tests.ps1 is its test.
#>

Set-StrictMode -Version 2.0

# The installer types jpackage builds on each system; the first is the one a script builds by default.
$script:PackageTypes = @{
    windows = @('exe', 'msi')
    linux   = @('deb', 'rpm')
    macos   = @('dmg', 'pkg')
}

function Get-PackagingPlatform {
    <# 'windows', 'linux' or 'macos'. $IsMacOS does not exist in Windows PowerShell 5.1. #>
    [CmdletBinding()]
    param()
    if ($env:OS -eq 'Windows_NT') { return 'windows' }
    if ((Test-Path variable:IsMacOS) -and $IsMacOS) { return 'macos' }
    return 'linux'
}

function Get-PackagingArch {
    <# This machine's processor as the published names write it: 'x64' or 'arm64'. #>
    [CmdletBinding()]
    param()
    return [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
}

function Get-IconExtension {
    <# The icon file jpackage takes on each system: ico, png or icns. #>
    [CmdletBinding()]
    param([string]$Platform = (Get-PackagingPlatform))
    switch ($Platform) {
        'windows' { return 'ico' }
        'linux' { return 'png' }
        'macos' { return 'icns' }
        default { throw "Unknown platform '$Platform'." }
    }
}

function Resolve-PackageType {
    <#
    .SYNOPSIS
        The installer type to build: the one asked for if this system can build it, or this system's usual
        one (exe, deb or dmg) when none was asked for. app-image builds everywhere.
    #>
    [CmdletBinding()]
    param(
        [AllowEmptyString()][string]$Type = '',
        [string]$Platform = (Get-PackagingPlatform)
    )
    $allowed = $script:PackageTypes[$Platform]
    if (-not $allowed) { throw "Unknown platform '$Platform'." }
    if (-not $Type) { return $allowed[0] }
    if ($Type -ne 'app-image' -and $allowed -notcontains $Type) {
        throw "-Type $Type cannot be built on $Platform. Here: $($allowed -join ', ') or app-image."
    }
    return $Type
}

function Get-JdkVersion {
    <# The JAVA_VERSION a JDK's release file names ('25.0.5'), or $null without one. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$JdkHome)
    $release = Join-Path $JdkHome 'release'
    if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { return $null }
    foreach ($line in Get-Content -LiteralPath $release) {
        if ($line -match '^JAVA_VERSION="?([^"]+)"?\s*$') { return $Matches[1] }
    }
    return $null
}

function Find-JPackage {
    <#
    .SYNOPSIS
        Finds a JDK with jpackage, and returns its jpackage, its folder and its version.

    .DESCRIPTION
        Looks in JAVA_HOME, beside the jpackage and the java on PATH, then in ~/.jdks (newest name first),
        and takes the first JDK that meets -MinimumVersion or -ExactVersion. The runtime an installer
        carries is the JDK its jpackage comes from, so a script names the version it needs rather than
        taking whatever is on PATH.
    #>
    [CmdletBinding()]
    param(
        [int]$MinimumVersion = 0,
        [int]$ExactVersion = 0
    )
    $exe = 'jpackage'
    if ((Get-PackagingPlatform) -eq 'windows') { $exe = 'jpackage.exe' }

    $homes = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $homes.Add($env:JAVA_HOME) }
    foreach ($name in @('jpackage', 'java')) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $homes.Add((Split-Path -Parent (Split-Path -Parent $command.Source))) }
    }
    $jdks = Join-Path $HOME '.jdks'
    if (Test-Path -LiteralPath $jdks) {
        Get-ChildItem -LiteralPath $jdks -Directory | Sort-Object Name -Descending |
            ForEach-Object { $homes.Add($_.FullName) }
    }

    $seen = @{}
    $rejected = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in $homes) {
        $full = [System.IO.Path]::GetFullPath($candidate).TrimEnd('\', '/')
        if ($seen.ContainsKey($full)) { continue }
        $seen[$full] = $true
        $jpackage = Join-Path (Join-Path $full 'bin') $exe
        if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) { continue }

        $version = Get-JdkVersion -JdkHome $full
        $feature = 0
        if ($version) { $feature = [int](($version -split '[.+-]')[0]) }
        $fits = ($ExactVersion -eq 0 -or $feature -eq $ExactVersion) -and ($feature -ge $MinimumVersion)
        if ($fits) {
            return [pscustomobject]@{ Path = $jpackage; JdkHome = $full; Version = $version; Feature = $feature }
        }
        $shown = $version
        if (-not $shown) { $shown = 'unknown version' }
        $rejected.Add("$full ($shown)")
    }

    $wanted = 'a JDK'
    if ($ExactVersion) { $wanted = "JDK $ExactVersion" } elseif ($MinimumVersion) { $wanted = "JDK $MinimumVersion or later" }
    $message = "jpackage from $wanted was not found in JAVA_HOME, on PATH or in ~/.jdks. Set JAVA_HOME to $wanted."
    if ($rejected.Count) { $message += ' Found: ' + ($rejected -join '; ') + '.' }
    throw $message
}

function Invoke-Native {
    <# Runs a program and throws when it exits with anything but 0. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $FilePath) failed with exit code $LASTEXITCODE."
    }
}

function Clear-ReadOnly {
    <#
    .SYNOPSIS
        Clears the read-only flag on every file under a folder, and returns how many had it.

    .DESCRIPTION
        jpackage leaves its launchers and installers read-only, and from JDK 25 on Java no longer deletes a
        read-only file on Windows, so "mvn clean" stops at the previous build's output. A missing folder is
        not an error.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $count = 0
    Get-ChildItem -LiteralPath $Path -Recurse -File -Force | Where-Object { $_.IsReadOnly } | ForEach-Object {
        $_.IsReadOnly = $false
        $count++
    }
    return $count
}

function Find-MavenCommand {
    <#
    .SYNOPSIS
        The Maven to run for a pom: the wrapper in the pom's folder or a folder above it, up to the
        repository's root, else mvn on PATH. Returns the program and the arguments that come before Maven's.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Pom,
        [switch]$NoWrapper
    )
    $windows = (Get-PackagingPlatform) -eq 'windows'
    if (-not $NoWrapper) {
        $folder = Split-Path -Parent ([System.IO.Path]::GetFullPath($Pom))
        while ($folder) {
            if ($windows) {
                $wrapper = Join-Path $folder 'mvnw.cmd'
                if (Test-Path -LiteralPath $wrapper -PathType Leaf) { return [pscustomobject]@{ FilePath = $wrapper; Prefix = @() } }
            } else {
                $wrapper = Join-Path $folder 'mvnw'
                # Through sh: the wrapper may have lost its executable bit in a checkout or a copy.
                if (Test-Path -LiteralPath $wrapper -PathType Leaf) { return [pscustomobject]@{ FilePath = 'sh'; Prefix = @($wrapper) } }
            }
            if (Test-Path -LiteralPath (Join-Path $folder '.git')) { break }
            $parent = Split-Path -Parent $folder
            if ($parent -eq $folder) { break }
            $folder = $parent
        }
    }
    if ($windows) { return [pscustomobject]@{ FilePath = 'mvn.cmd'; Prefix = @() } }
    return [pscustomobject]@{ FilePath = 'mvn'; Prefix = @() }
}

function Invoke-MavenBuild {
    <#
    .SYNOPSIS
        Runs Maven on a pom with the given goals and options, after clearing the read-only flag in the
        folders the build cleans (the pom's own target folder unless -ClearReadOnly names others).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Pom,
        [Parameter(Mandatory)][string[]]$Arguments,
        [string[]]$ClearReadOnly,
        [switch]$NoWrapper
    )
    if (-not $PSBoundParameters.ContainsKey('ClearReadOnly')) {
        $ClearReadOnly = @(Join-Path (Split-Path -Parent ([System.IO.Path]::GetFullPath($Pom))) 'target')
    }
    foreach ($folder in $ClearReadOnly) { [void](Clear-ReadOnly -Path $folder) }
    $maven = Find-MavenCommand -Pom $Pom -NoWrapper:$NoWrapper
    Invoke-Native -FilePath $maven.FilePath -ArgumentList (@($maven.Prefix) + @('-f', $Pom) + $Arguments)
}

function Get-PomVersion {
    <#
    .SYNOPSIS
        A pom's version, as written and as the installer needs it.

    .DESCRIPTION
        Raw is the pom's own <version> (its parent's when it has none). Version drops a qualifier such as
        -SNAPSHOT and must then be one to three numbers, which is all jpackage accepts. PackageVersion is
        what jpackage is given: the same, except on macOS, which refuses a version starting with 0, so 0.x.y
        is packaged there as 1.x.y. A program reads its real version some other way (a system property),
        since that is what its updater compares.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Pom,
        [string]$Platform = (Get-PackagingPlatform)
    )
    [xml]$xml = Get-Content -LiteralPath $Pom -Raw -Encoding UTF8
    $raw = $null
    if ($xml.project.PSObject.Properties['version']) { $raw = $xml.project.version }
    if (-not $raw -and $xml.project.PSObject.Properties['parent']) { $raw = $xml.project.parent.version }
    if (-not $raw) { throw "$Pom has no version." }
    return ConvertTo-PackageVersion -Version $raw -Platform $Platform
}

function ConvertTo-PackageVersion {
    <# Get-PomVersion's rules for a version given as text. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Version,
        [string]$Platform = (Get-PackagingPlatform)
    )
    $plain = $Version -replace '-.*$', ''
    if ($plain -notmatch '^\d+(\.\d+){0,2}$') {
        throw "The version '$Version' cannot be an installer's: jpackage takes one to three numbers, such as 1.2.3."
    }
    $package = $plain
    if ($Platform -eq 'macos' -and $plain -match '^0\.(.+)$') { $package = '1.' + $Matches[1] }
    return [pscustomobject]@{ Raw = $Version; Version = $plain; PackageVersion = $package }
}

function New-JPackageInput {
    <#
    .SYNOPSIS
        Makes jpackage's --input folder from nothing, holding only the files and folders named.

    .DESCRIPTION
        jpackage copies everything in --input into the program, so a target folder handed over whole would
        ship the compiled classes and the test output too. Returns the folder.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$Item
    )
    if (Test-Path -LiteralPath $Path) {
        [void](Clear-ReadOnly -Path $Path)
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    [void](New-Item -ItemType Directory -Path $Path)
    foreach ($entry in $Item) {
        if (-not (Test-Path -LiteralPath $entry)) { throw "Not found, so not put in the input folder: $entry" }
        Copy-Item -LiteralPath $entry -Destination $Path -Recurse
    }
    return $Path
}

function Find-WiX {
    <#
    .SYNOPSIS
        Finds WiX Toolset 3, which jpackage needs for exe and msi, puts it on PATH, and returns its folder.

    .DESCRIPTION
        Looks on PATH, in WIX (the installer sets it), in Program Files (x86)\WiX Toolset v3.* (newest
        first) and in ~/.tools/wix314 (the binaries zip unpacked there).
    #>
    [CmdletBinding()]
    param()
    $onPath = Get-Command candle.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return (Split-Path -Parent $onPath.Source) }

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:WIX) { $candidates.Add((Join-Path $env:WIX 'bin')) }
    $programFiles = ${env:ProgramFiles(x86)}
    if ($programFiles -and (Test-Path -LiteralPath $programFiles)) {
        Get-ChildItem -LiteralPath $programFiles -Directory -Filter 'WiX Toolset v3*' | Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin')) }
    }
    $candidates.Add((Join-Path $HOME '.tools\wix314'))

    foreach ($folder in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $folder 'candle.exe') -PathType Leaf) {
            $env:PATH = $folder + [System.IO.Path]::PathSeparator + $env:PATH
            return $folder
        }
    }
    throw 'WiX Toolset 3.x was not found. Install it from https://wixtoolset.org or use -Type app-image.'
}

function Get-WinInstallerArgument {
    <#
    .SYNOPSIS
        jpackage's options for a Windows installer, in one order whatever the program picks.

    .PARAMETER UpgradeUuid
        Tells Windows Installer that a newer installer replaces an older install. A program picks it once and
        never changes it, or installers stop replacing each other.
    #>
    [CmdletBinding()]
    param(
        [switch]$PerUser,
        [switch]$DirChooser,
        [switch]$Menu,
        [string]$MenuGroup,
        [switch]$Shortcut,
        [string]$UpgradeUuid
    )
    $arguments = New-Object System.Collections.Generic.List[string]
    if ($PerUser) { $arguments.Add('--win-per-user-install') }
    if ($DirChooser) { $arguments.Add('--win-dir-chooser') }
    if ($Menu) { $arguments.Add('--win-menu') }
    if ($MenuGroup) { $arguments.Add('--win-menu-group'); $arguments.Add($MenuGroup) }
    if ($Shortcut) { $arguments.Add('--win-shortcut') }
    if ($UpgradeUuid) {
        $parsed = [guid]::Empty
        if (-not [guid]::TryParse($UpgradeUuid, [ref]$parsed)) { throw "'$UpgradeUuid' is not a UUID." }
        $arguments.Add('--win-upgrade-uuid'); $arguments.Add($UpgradeUuid)
    }
    # Unrolled like any command's output: a caller adds them with $arguments += @(Get-WinInstallerArgument ...).
    return $arguments.ToArray()
}

function Rename-PlatformPackage {
    <#
    .SYNOPSIS
        Gives the one installer of a type in a folder its published name, <Name>-<Version>-<platform>-<arch>.<type>,
        and returns it.

    .DESCRIPTION
        jpackage's own names differ per system and carry the package version, which on macOS is not the real
        one. An updater looks for a release's files by these names (.assetFor(Os.LINUX, "*-linux-{arch}.deb")),
        so the version given is the program's real one.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][string]$Type,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Version,
        [string]$Platform = (Get-PackagingPlatform),
        [string]$Arch = (Get-PackagingArch)
    )
    $found = @(Get-ChildItem -LiteralPath $Directory -File | Where-Object { $_.Extension -eq ".$Type" })
    if ($found.Count -ne 1) { throw "Expected one .$Type file in $Directory, found $($found.Count)." }
    $published = "$Name-$Version-$Platform-$Arch.$Type"
    if ($found[0].Name -eq $published) { return $found[0] }
    return Rename-Item -LiteralPath $found[0].FullName -NewName $published -PassThru
}

Export-ModuleMember -Function Get-PackagingPlatform, Get-PackagingArch, Get-IconExtension, Resolve-PackageType,
    Get-JdkVersion, Find-JPackage, Invoke-Native, Clear-ReadOnly, Find-MavenCommand, Invoke-MavenBuild,
    Get-PomVersion, ConvertTo-PackageVersion, New-JPackageInput, Find-WiX, Get-WinInstallerArgument,
    Rename-PlatformPackage
