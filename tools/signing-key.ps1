<#
.SYNOPSIS
    Makes the publisher's release-signing key, or prints the public half of one.

.DESCRIPTION
    A program built with .signedBy("<public key>") is offered only releases signed with the matching
    private key - so a release put up by somebody who took over the GitHub account, but does not have
    the key, is ignored by every installed copy.

      -New      makes a key pair. The private key goes to -KeyFile, which must not exist yet; the public
                key is printed, to be pasted into the program's code.
      -Public   prints the public key of an existing key file.

    The key file is a secret and it is the only copy:
      * keep it out of every repository (the default place is in your user folder, not in a project);
      * keep a backup somewhere safe - without it no release can be signed, and installed copies that
        expect a signature accept nothing else;
      * one key can sign several programs: the program's name is part of what is signed.

    Needs a JDK 17 or newer on PATH.

.EXAMPLE
    .\tools\signing-key.ps1 -New
    .\tools\signing-key.ps1 -Public
#>
[CmdletBinding()]
param(
    [switch]$New,
    [switch]$Public,
    [string]$KeyFile = (Join-Path $env:USERPROFILE '.desktop-updater\release-signing.key')
)

$ErrorActionPreference = 'Stop'
if ($New -eq $Public) { throw 'Say one of -New or -Public.' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'java was not found on PATH; this needs a JDK 17 or newer.' }

# Java runs a single source file without a build, but from inside the source tree it would pick up
# module-info.java, so a copy is run instead.
$source = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\main\java\com\hamza\updater\ReleaseSignature.java'
if (-not (Test-Path -LiteralPath $source)) { throw "The signing tool is not at $source." }
$folder = Join-Path ([System.IO.Path]::GetTempPath()) "desktop-updater-signing-tool\com\hamza\updater"
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$tool = Join-Path $folder 'ReleaseSignature.java'
Copy-Item -LiteralPath $source -Destination $tool -Force

$ErrorActionPreference = 'Continue'
if ($New) {
    if (Test-Path -LiteralPath $KeyFile) { throw "$KeyFile already exists. A key is never replaced: programs that carry its public half would accept no release again." }
    $publicKey = & java $tool keygen --key $KeyFile
    if ($LASTEXITCODE -ne 0) { throw 'The key could not be made.' }
    Write-Host ''
    Write-Host "The key is in $KeyFile" -ForegroundColor Green
    Write-Host 'It is a secret and the only copy: back it up, and never put it in a repository.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'Give the program its public half:'
    Write-Host ''
    Write-Host "    .signedBy(`"$publicKey`")" -ForegroundColor Cyan
    Write-Host ''
} else {
    if (-not (Test-Path -LiteralPath $KeyFile)) { throw "$KeyFile is not there." }
    & java $tool public --key $KeyFile
    if ($LASTEXITCODE -ne 0) { throw 'The key file could not be read.' }
}
