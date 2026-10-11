# ============================================================
# 构建自带 Java 的 Windows 便携版 ani-rss
#
# 产出: ani-rss-application\target\ani-rss-win\
#         ani-rss.exe   启动器(双击运行, 带系统托盘)
#         ani-rss.jar   主程序
#         jre\          精简后的 Java 25 运行时(约 51 MB)
#
# 用法:
#   pwsh -File .\build-windows-portable.ps1
#
# 可选参数:
#   -SkipPackage   跳过 Maven 打包, 只重新生成 jre 并组装目录
# ============================================================
param(
    [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'

# ---- 需要 JDK 25: 优先 JAVA_HOME, 否则用本机已装的 jdk-25 ----
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\jlink.exe'))) {
    $candidates = @(
        'C:\Program Files\Java\jdk-25.0.2',
        'C:\Program Files\Java\jdk-25'
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\jlink.exe') }

    if (-not $candidates) {
        throw '未找到含 jlink 的 JDK 25, 请设置 JAVA_HOME'
    }
    $javaHome = $candidates[0]
}

$jlink = Join-Path $javaHome 'bin\jlink.exe'
$jpackageJdk = $javaHome

$root = $PSScriptRoot
$module = Join-Path $root 'ani-rss-application'
$target = Join-Path $module 'target'
$jreDir = Join-Path $target 'jre'
$distDir = Join-Path $target 'ani-rss-win'

Write-Host "JDK: $javaHome" -ForegroundColor Cyan

# ---- 1. Maven 打包出 jar 与 exe ----
if (-not $SkipPackage) {
    $mvn = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
    if (-not $mvn) {
        $mvn = 'D:\Software\JetBrain\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'
    }
    if (-not (Test-Path $mvn)) {
        throw '未找到 mvn.cmd, 请把 Maven 的 bin 目录加入 PATH'
    }

    # windows profile 会调用 bash 下载 ani-rss-update.exe, 需要 Git Bash 在 PATH 中
    foreach ($p in 'C:\Program Files\Git\bin', 'C:\Program Files\Git\usr\bin') {
        if ((Test-Path $p) -and ($env:PATH -notlike "*$p*")) {
            $env:PATH = "$p;$env:PATH"
        }
    }

    $env:JAVA_HOME = $javaHome
    Write-Host '==> 打包中 (mvn package -P windows)' -ForegroundColor Green
    & $mvn -B package -DskipTests -P windows,macos --file (Join-Path $root 'pom.xml')
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 打包失败, 退出码 $LASTEXITCODE"
    }
}

$jar = Join-Path $target 'ani-rss.jar'
$exe = Join-Path $target 'ani-rss.exe'
if (-not (Test-Path $jar)) { throw "未找到 $jar" }
if (-not (Test-Path $exe)) { throw "未找到 $exe" }

# ---- 2. 用 jdeps 分析所需模块, 再用 jlink 生成精简运行时 ----
Write-Host '==> 分析所需 JDK 模块' -ForegroundColor Green

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
        throw "jdeps 未能得到模块列表: $modules"
    }
    Write-Host "模块: $modules" -ForegroundColor DarkGray
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '==> 生成精简 Java 运行时 (jlink)' -ForegroundColor Green
if (Test-Path $jreDir) { Remove-Item $jreDir -Recurse -Force }

& $jlink --add-modules $modules --output $jreDir `
    --strip-debug --no-man-pages --no-header-files --compress=zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink 失败, 退出码 $LASTEXITCODE" }

# ---- 3. 组装便携目录 ----
Write-Host '==> 组装便携目录' -ForegroundColor Green
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Copy-Item $exe (Join-Path $distDir 'ani-rss.exe') -Force
Copy-Item $jar (Join-Path $distDir 'ani-rss.jar') -Force
Copy-Item $jreDir (Join-Path $distDir 'jre') -Recurse -Force

$size = (Get-ChildItem $distDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ''
Write-Host '构建完成' -ForegroundColor Cyan
Write-Host "  目录: $distDir"
Write-Host "  体积: $([math]::Round($size / 1MB, 1)) MB"
Write-Host ''
Write-Host '分发时把整个 ani-rss-win 文件夹拷给对方, 双击 ani-rss.exe 即可, 无需安装 Java。'
Write-Host '配置与日志会生成在 %USERPROFILE%\ani-rss\ (托盘右键 -> Config 可直接打开)。'
