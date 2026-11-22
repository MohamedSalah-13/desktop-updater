<#
.SYNOPSIS
    Tests for tools/Packaging.psm1, with no test framework so it runs as it is in Windows PowerShell 5.1
    and in PowerShell 7 on any system:

        powershell -NoProfile -File tools/tests/Packaging.Tests.ps1
        pwsh -NoProfile -File tools/tests/Packaging.Tests.ps1

    Exits 1 when a case fails. Nothing outside a temporary folder is touched; PATH and JAVA_HOME are put
    back after the cases that change them.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path (Split-Path -Parent $PSScriptRoot) 'Packaging.psm1') -Force

$script:failures = 0
$script:passed = 0
$windows = (Get-PackagingPlatform) -eq 'windows'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('packaging-tests-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $root)

function Test-Case {
    param([string]$Name, [scriptblock]$Body)
    try {
        & $Body
        $script:passed++
        Write-Host "ok    $Name"
    } catch {
        $script:failures++
        Write-Host "FAIL  $Name"
        Write-Host "      $($_.Exception.Message)"
    }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$What = 'value')
    if ($Expected -is [array] -or $Actual -is [array]) {
        $e = @($Expected) -join ' | '
        $a = @($Actual) -join ' | '
        if ($e -ne $a) { throw "$What`: expected [$e], got [$a]" }
    } elseif ($Expected -ne $Actual) {
        throw "$What`: expected '$Expected', got '$Actual'"
    }
}

function Assert-Throws {
    param([scriptblock]$Body, [string]$Containing)
    try { & $Body } catch {
        if ($Containing -and $_.Exception.Message -notlike "*$Containing*") {
            throw "threw '$($_.Exception.Message)', which does not contain '$Containing'"
        }
        return
    }
    throw "did not throw"
}

function New-TestFolder {
    param([string]$Name)
    $path = Join-Path $root $Name
    [void](New-Item -ItemType Directory -Path $path -Force)
    return $path
}

function New-FakeJdk {
    # A folder that looks like a JDK to Find-JPackage: bin/jpackage and a release file.
    param([string]$Name, [string]$Version)
    $jdk = New-TestFolder $Name
    $bin = New-TestFolder "$Name/bin"
    $exe = 'jpackage'
    if ($windows) { $exe = 'jpackage.exe' }
    $jpackage = Join-Path $bin $exe
    Set-Content -LiteralPath $jpackage -Value '' -Encoding Ascii
    if (-not $windows) { & chmod +x $jpackage }
    if ($Version) { Set-Content -LiteralPath (Join-Path $jdk 'release') -Value "JAVA_VERSION=`"$Version`"" -Encoding Ascii }
    return $jdk
}

try {
    Test-Case 'each system builds its usual installer when none is asked for' {
        Assert-Equal 'exe' (Resolve-PackageType -Platform windows)
        Assert-Equal 'deb' (Resolve-PackageType -Platform linux)
        Assert-Equal 'dmg' (Resolve-PackageType -Platform macos)
        Assert-Equal 'msi' (Resolve-PackageType -Type msi -Platform windows)
        Assert-Equal 'app-image' (Resolve-PackageType -Type app-image -Platform linux)
    }

    Test-Case 'a type another system builds is refused, naming what this one builds' {
        Assert-Throws { Resolve-PackageType -Type deb -Platform windows } '-Type deb cannot be built on windows. Here: exe, msi or app-image.'
        Assert-Throws { Resolve-PackageType -Type exe -Platform macos } 'Here: dmg, pkg or app-image.'
    }

    Test-Case 'each system takes its own icon file' {
        Assert-Equal 'ico' (Get-IconExtension -Platform windows)
        Assert-Equal 'png' (Get-IconExtension -Platform linux)
        Assert-Equal 'icns' (Get-IconExtension -Platform macos)
    }

    Test-Case 'a version loses its qualifier, and macOS packages 0.x as 1.x' {
        $v = ConvertTo-PackageVersion -Version '0.3.2-SNAPSHOT' -Platform windows
        Assert-Equal '0.3.2-SNAPSHOT' $v.Raw 'Raw'
        Assert-Equal '0.3.2' $v.Version 'Version'
        Assert-Equal '0.3.2' $v.PackageVersion 'PackageVersion on Windows'
        Assert-Equal '1.3.2' (ConvertTo-PackageVersion -Version '0.3.2' -Platform macos).PackageVersion 'PackageVersion on macOS'
        Assert-Equal '0.3.2' (ConvertTo-PackageVersion -Version '0.3.2' -Platform macos).Version 'Version on macOS'
        Assert-Equal '2.0' (ConvertTo-PackageVersion -Version '2.0' -Platform macos).PackageVersion '2.0 on macOS'
    }

    Test-Case 'a version jpackage cannot take is refused' {
        Assert-Throws { ConvertTo-PackageVersion -Version '1.2.3.4' -Platform linux } 'one to three numbers'
        Assert-Throws { ConvertTo-PackageVersion -Version 'v1.2' -Platform linux } 'one to three numbers'
    }

    Test-Case 'the version is read from the pom, or from its parent when it has none' {
        $folder = New-TestFolder 'pom'
        $own = Join-Path $folder 'own.xml'
        Set-Content -LiteralPath $own -Encoding UTF8 -Value @'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <parent><groupId>g</groupId><artifactId>p</artifactId><version>9.9.9</version></parent>
  <artifactId>a</artifactId><version>1.4.0-SNAPSHOT</version></project>
'@
        Assert-Equal '1.4.0' (Get-PomVersion -Pom $own -Platform linux).Version 'own version'
        $inherited = Join-Path $folder 'inherited.xml'
        Set-Content -LiteralPath $inherited -Encoding UTF8 -Value @'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <parent><groupId>g</groupId><artifactId>p</artifactId><version>0.2.0</version></parent>
  <artifactId>a</artifactId></project>
'@
        Assert-Equal '1.2.0' (Get-PomVersion -Pom $inherited -Platform macos).PackageVersion 'parent version on macOS'
    }

    Test-Case 'read-only files are made writable, and a missing folder is no error' {
        $folder = New-TestFolder 'readonly/installer/app'
        $file = Join-Path $folder 'launcher.exe'
        Set-Content -LiteralPath $file -Value 'x'
        (Get-Item -LiteralPath $file).IsReadOnly = $true
        Assert-Equal 1 (Clear-ReadOnly -Path (Join-Path $root 'readonly')) 'files cleared'
        Assert-Equal $false (Get-Item -LiteralPath $file).IsReadOnly 'flag after'
        Remove-Item -LiteralPath (Join-Path $root 'readonly') -Recurse
        Assert-Equal 0 (Clear-ReadOnly -Path (Join-Path $root 'nothing-here')) 'missing folder'
    }

    Test-Case 'the input folder holds only what is named, and an earlier one goes' {
        $target = New-TestFolder 'input/target'
        Set-Content -LiteralPath (Join-Path $target 'app.jar') -Value 'jar'
        $lib = New-TestFolder 'input/target/lib'
        Set-Content -LiteralPath (Join-Path $lib 'dep.jar') -Value 'dep'
        New-TestFolder 'input/target/classes' | Out-Null
        $stage = Join-Path $target 'jpackage-input'
        New-TestFolder 'input/target/jpackage-input/old' | Out-Null
        $stale = Join-Path $stage 'old/stale.exe'
        Set-Content -LiteralPath $stale -Value 'x'
        (Get-Item -LiteralPath $stale).IsReadOnly = $true

        $made = New-JPackageInput -Path $stage -Item (Join-Path $target 'app.jar'), $lib
        Assert-Equal $stage $made 'returned folder'
        $names = Get-ChildItem -LiteralPath $stage -Recurse | ForEach-Object { $_.FullName.Substring($stage.Length + 1).Replace('\', '/') } | Sort-Object
        Assert-Equal @('app.jar', 'lib', 'lib/dep.jar') $names 'contents'
        Assert-Throws { New-JPackageInput -Path $stage -Item (Join-Path $target 'missing.jar') } 'missing.jar'
    }

    Test-Case 'Windows installer options come in one order, and the UUID is checked' {
        $options = @(Get-WinInstallerArgument -UpgradeUuid '6f1d2b0e-7c43-4f0a-9b1e-3a5c8d2e4f71' -Shortcut -MenuGroup 'Git Backup' -Menu -DirChooser -PerUser)
        Assert-Equal @('--win-per-user-install', '--win-dir-chooser', '--win-menu', '--win-menu-group', 'Git Backup',
            '--win-shortcut', '--win-upgrade-uuid', '6f1d2b0e-7c43-4f0a-9b1e-3a5c8d2e4f71') $options 'options'
        Assert-Equal 0 @(Get-WinInstallerArgument).Count 'nothing asked'
        Assert-Throws { Get-WinInstallerArgument -UpgradeUuid 'not-a-uuid' } 'is not a UUID'
    }

    Test-Case 'one installer takes its published name, and two are refused' {
        $folder = New-TestFolder 'rename'
        Set-Content -LiteralPath (Join-Path $folder 'git-backup_1.3.2_amd64.deb') -Value 'deb'
        Set-Content -LiteralPath (Join-Path $folder 'notes.txt') -Value 'x'
        $renamed = Rename-PlatformPackage -Directory $folder -Type deb -Name GitBackup -Version 0.3.2 -Platform linux -Arch x64
        Assert-Equal 'GitBackup-0.3.2-linux-x64.deb' $renamed.Name 'name'
        Assert-Equal 'GitBackup-0.3.2-linux-x64.deb' (Rename-PlatformPackage -Directory $folder -Type deb -Name GitBackup -Version 0.3.2 -Platform linux -Arch x64).Name 'again'
        Set-Content -LiteralPath (Join-Path $folder 'second.deb') -Value 'deb'
        Assert-Throws { Rename-PlatformPackage -Directory $folder -Type deb -Name GitBackup -Version 0.3.2 -Platform linux -Arch x64 } 'found 2'
    }

    Test-Case 'the wrapper is found in the pom folder or above it, up to the repository root' {
        $repo = New-TestFolder 'maven/repo'
        New-TestFolder 'maven/repo/.git' | Out-Null
        $module = New-TestFolder 'maven/repo/desktop'
        $pom = Join-Path $module 'pom.xml'
        Set-Content -LiteralPath $pom -Value '<project/>'
        $plain = 'mvn'
        if ($windows) { $plain = 'mvn.cmd' }
        Assert-Equal $plain (Find-MavenCommand -Pom $pom).FilePath 'no wrapper'
        Set-Content -LiteralPath (Join-Path $root 'maven/mvnw.cmd') -Value '' # above the repository: never used
        Set-Content -LiteralPath (Join-Path $root 'maven/mvnw') -Value ''
        Assert-Equal $plain (Find-MavenCommand -Pom $pom).FilePath 'wrapper outside the repository'
        Set-Content -LiteralPath (Join-Path $repo 'mvnw.cmd') -Value ''
        Set-Content -LiteralPath (Join-Path $repo 'mvnw') -Value ''
        $found = Find-MavenCommand -Pom $pom
        if ($windows) {
            Assert-Equal (Join-Path $repo 'mvnw.cmd') $found.FilePath 'wrapper'
        } else {
            Assert-Equal 'sh' $found.FilePath 'wrapper through sh'
            Assert-Equal (Join-Path $repo 'mvnw') @($found.Prefix)[0] 'wrapper path'
        }
        Assert-Equal $plain (Find-MavenCommand -Pom $pom -NoWrapper).FilePath '-NoWrapper'
    }

    Test-Case 'a program that fails stops the script with its exit code' {
        if ($windows) {
            Assert-Throws { Invoke-Native -FilePath 'cmd.exe' -ArgumentList '/c', 'exit 3' } 'cmd.exe failed with exit code 3.'
            Invoke-Native -FilePath 'cmd.exe' -ArgumentList '/c', 'exit 0' | Out-Null
        } else {
            Assert-Throws { Invoke-Native -FilePath 'sh' -ArgumentList '-c', 'exit 3' } 'sh failed with exit code 3.'
            Invoke-Native -FilePath 'sh' -ArgumentList '-c', 'exit 0' | Out-Null
        }
    }

    Test-Case 'the JDK asked for is found in JAVA_HOME or on PATH, and the others are named when none fits' {
        $savedPath = $env:PATH
        $savedHome = $env:JAVA_HOME
        try {
            $old = New-FakeJdk 'jdks/jdk-21' '21.0.1'
            $new = New-FakeJdk 'jdks/jdk-25' '25.0.5'
            $env:JAVA_HOME = $old
            $env:PATH = (Join-Path $new 'bin') + [System.IO.Path]::PathSeparator + $env:PATH

            $found = Find-JPackage -MinimumVersion 25
            Assert-Equal ([System.IO.Path]::GetFullPath($new)) $found.JdkHome 'JDK 25 or later'
            Assert-Equal 25 $found.Feature 'feature'
            Assert-Equal '25.0.5' $found.Version 'version'
            Assert-Equal ([System.IO.Path]::GetFullPath($old)) (Find-JPackage -ExactVersion 21).JdkHome 'exactly 21, from JAVA_HOME'
            Assert-Equal ([System.IO.Path]::GetFullPath($old)) (Find-JPackage).JdkHome 'any: JAVA_HOME first'
            Assert-Throws { Find-JPackage -MinimumVersion 99 } 'JDK 99 or later was not found'
            Assert-Throws { Find-JPackage -MinimumVersion 99 } '(21.0.1)'
        } finally {
            $env:PATH = $savedPath
            $env:JAVA_HOME = $savedHome
        }
    }
} finally {
    Get-ChildItem -LiteralPath $root -Recurse -File -Force | Where-Object { $_.IsReadOnly } | ForEach-Object { $_.IsReadOnly = $false }
    Remove-Item -LiteralPath $root -Recurse -Force
}

Write-Host ''
Write-Host "$script:passed passed, $script:failures failed (PowerShell $($PSVersionTable.PSVersion), $(Get-PackagingPlatform))"
if ($script:failures) { exit 1 }
