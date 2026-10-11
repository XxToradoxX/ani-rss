# ============================================================
# Build the self-contained Windows portable package for ani-rss
#
# Output: ani-rss-application\target\ani-rss-win\
#           ani-rss.exe   launcher (double click, system tray)
#           ani-rss.jar   application
#           jre\          jlink-trimmed Java 25 runtime (~51 MB)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build-windows-portable.ps1
#
# Options:
#   -SkipPackage   skip Maven packaging, only regenerate jre and reassemble
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads a script
#       without a BOM as ANSI, which corrupts non-ASCII text and breaks parsing.
# ============================================================
param(
    [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'

# ---- JDK 25 required: prefer JAVA_HOME, else known install locations ----
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\jlink.exe'))) {
    $candidates = @(
        'C:\Program Files\Java\jdk-25.0.2',
        'C:\Program Files\Java\jdk-25'
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\jlink.exe') }

    if (-not $candidates) {
        throw 'JDK 25 with jlink not found, please set JAVA_HOME'
    }
    $javaHome = $candidates[0]
}

$jlink = Join-Path $javaHome 'bin\jlink.exe'

$root = $PSScriptRoot
$module = Join-Path $root 'ani-rss-application'
$target = Join-Path $module 'target'
$jreDir = Join-Path $target 'jre'
$distDir = Join-Path $target 'ani-rss-win'

Write-Host "JDK: $javaHome" -ForegroundColor Cyan

# ---- 1. Maven package: produces the jar and the exe ----
if (-not $SkipPackage) {
    $mvn = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
    if (-not $mvn) {
        $mvn = 'D:\Software\JetBrain\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'
    }
    if (-not (Test-Path $mvn)) {
        throw 'mvn.cmd not found, add the Maven bin directory to PATH'
    }

    # the windows profile runs generate-resources.sh (downloads ani-rss-update.exe), needs Git Bash
    foreach ($p in 'C:\Program Files\Git\bin', 'C:\Program Files\Git\usr\bin') {
        if ((Test-Path $p) -and ($env:PATH -notlike "*$p*")) {
            $env:PATH = "$p;$env:PATH"
        }
    }

    $env:JAVA_HOME = $javaHome
    Write-Host '==> packaging (mvn package -P windows)' -ForegroundColor Green
    & $mvn -B package -DskipTests -P windows,macos --file (Join-Path $root 'pom.xml')
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed, exit code $LASTEXITCODE"
    }
}

$jar = Join-Path $target 'ani-rss.jar'
$exe = Join-Path $target 'ani-rss.exe'
if (-not (Test-Path $jar)) { throw "not found: $jar" }
if (-not (Test-Path $exe)) { throw "not found: $exe" }

# ---- 2. jdeps to find required modules, jlink to build the trimmed runtime ----
Write-Host '==> analysing required JDK modules' -ForegroundColor Green

$tmp = Join-Path $env:TEMP ('ani-rss-jdeps-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($jar, $tmp)

    $classes = Join-Path $tmp 'BOOT-INF\classes'
    $libs = Get-ChildItem (Join-Path $tmp 'BOOT-INF\lib') -Filter *.jar
    $cp = "$classes;" + (($libs | ForEach-Object { $_.FullName }) -join ';')

    $modules = (& (Join-Path $javaHome 'bin\jdeps.exe') `
            --multi-release 25 --ignore-missing-deps --print-module-deps `
            --class-path $cp $classes 2>$null | Select-Object -Last 1)

    if (-not $modules -or $modules -notmatch 'java\.base') {
        throw "jdeps did not return a module list: $modules"
    }
    Write-Host "modules: $modules" -ForegroundColor DarkGray
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '==> generating trimmed runtime (jlink)' -ForegroundColor Green
if (Test-Path $jreDir) { Remove-Item $jreDir -Recurse -Force }

& $jlink --add-modules $modules --output $jreDir `
    --strip-debug --no-man-pages --no-header-files --compress=zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink failed, exit code $LASTEXITCODE" }

# ---- 3. assemble the portable directory ----
Write-Host '==> assembling portable directory' -ForegroundColor Green
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Copy-Item $exe (Join-Path $distDir 'ani-rss.exe') -Force
Copy-Item $jar (Join-Path $distDir 'ani-rss.jar') -Force
Copy-Item $jreDir (Join-Path $distDir 'jre') -Recurse -Force

$size = (Get-ChildItem $distDir -Recurse -File | Measure-Object -Property Length -Sum).Sum

# read the version for tagging / CI
# Use a regex on the raw text: casting the pom to [xml] fails because the pom contains
# non-ASCII text (the <description>) and PowerShell 5.1 reads files as ANSI by default.
# -Encoding UTF8 is still required for the regex to match correctly.
$pomText = Get-Content (Join-Path $root 'pom.xml') -Raw -Encoding UTF8
if ($pomText -notmatch '<version>([^<]+)</version>') {
    throw 'cannot read project version from pom.xml'
}
$pomVersion = $Matches[1]

Write-Host ''
Write-Host 'BUILD OK' -ForegroundColor Cyan
Write-Host "  dir     : $distDir"
Write-Host "  version : $pomVersion"
Write-Host "  size    : $([math]::Round($size / 1MB, 1)) MB"
Write-Host ''
Write-Host 'Copy the whole ani-rss-win folder, double click ani-rss.exe, no Java install needed.'
Write-Host 'Config and logs go to %USERPROFILE%\ani-rss\ (tray menu -> Config).'
Write-Host ''
Write-Host "Publish to this repo's Release: git tag v$pomVersion; git push origin v$pomVersion"
