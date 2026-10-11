package ani.rss.update;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RuntimeUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * 便携版(自带 jre)更新
 * <p>
 * 便携版是一个目录: ani-rss.exe + ani-rss.jar + jre/, 因此不能照搬"替换单个 exe"的做法,
 * 否则会把 jre 或主程序覆盖坏。
 * 这里改为下载 ani-rss-portable.zip, 退出程序后由临时脚本解包、替换目录内容并重启。
 */
@Slf4j
public class PortableUpdate implements BaseUpdate {

    /**
     * 与 exe 同级、存在即视为便携版的目录
     */
    public static final String JRE_DIR = "jre";

    /**
     * 判断当前运行的是否为便携版
     *
     * @param currentFile 当前文件
     * @return 是否便携版
     */
    public static Boolean isPortable(MavenUtils.CurrentFile currentFile) {
        if (currentFile.isDirectory()) {
            return false;
        }
        File dir = currentFile.getFile().getParentFile();
        if (dir == null) {
            return false;
        }
        return new File(dir, JRE_DIR).isDirectory();
    }

    /**
     * 根据当前运行形态推导 release 中对应的资产名
     *
     * @param currentFile 当前文件
     * @return 资产文件名
     */
    public static String assetName(MavenUtils.CurrentFile currentFile) {
        if (currentFile.isJar()) {
            return "ani-rss.jar";
        }
        if (isPortable(currentFile)) {
            return "ani-rss-portable.zip";
        }
        return "ani-rss.exe";
    }

    @Override
    public void update(File updateFile) {
        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

        File appDir = currentFile.getFile().getParentFile();
        Assert.notNull(appDir, "无法获取程序目录");

        File script = new File(FileUtil.getTmpDir(), "ani-rss-portable-update.ps1");
        // 用自身 pid 作为等待目标: Spring Boot 的 exe 会把 jar 解到临时目录再起 JVM,
        // 无法靠进程路径判断"程序是否已退出", 而 pid 是可靠的
        long pid = ProcessHandle.current().pid();
        writeUtf8Bom(script, buildScript(appDir, updateFile, pid));

        // 在程序目录放一份回滚脚本, 万一更新后起不来可以一键还原
        try {
            writeRollbackScript(appDir);
        } catch (Exception e) {
            log.warn("写入回滚脚本失败, 更新将继续: {}", e.getMessage());
        }

        // 最小化启动, 脚本自身会等待本进程退出后再替换文件
        String command = "cmd.exe /c start \"\" /min powershell.exe -NoProfile -ExecutionPolicy Bypass -File \""
                + script.getAbsolutePath() + "\"";

        log.info("便携版更新: 将解包 {} 到 {}", updateFile.getAbsolutePath(), appDir.getAbsolutePath());
        RuntimeUtil.exec(command);

        // 给脚本一点启动时间, 再退出让出文件占用
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }

    /**
     * 生成更新脚本
     * <p>
     * 必须写成 UTF-8 BOM, 否则 Windows PowerShell 5.1 会按 ANSI 解析中文注释而报语法错误
     *
     * @param appDir 程序目录
     * @param zip    已下载的便携包
     * @param pid    当前进程 pid, 脚本必须等它退出后才能替换文件
     */
    private String buildScript(File appDir, File zip, long pid) {
        String dir = appDir.getAbsolutePath();
        String zipPath = zip.getAbsolutePath();
        String pidStr = String.valueOf(pid);

        // 用 StringBuilder 逐行拼接: 一次性 String.join 会生成超出 JVM 常量池上限的字符串常量
        StringBuilder sb = new StringBuilder();

        append(sb, "$ErrorActionPreference = 'Stop'");
        append(sb, "$log = Join-Path $env:TEMP 'ani-rss-portable-update.log'");
        append(sb, "function Write-Log($m) { \"$(Get-Date -Format 'HH:mm:ss') $m\" | Out-File -FilePath $log -Append -Encoding utf8 }");
        append(sb, "try {");
        append(sb, "    Write-Log 'wait for pid " + pidStr + " to exit'");
        append(sb, "    for ($i = 0; $i -lt 240; $i++) {");
        append(sb, "        if (-not (Get-Process -Id " + pidStr + " -ErrorAction SilentlyContinue)) { break }");
        append(sb, "        Start-Sleep -Milliseconds 500");
        append(sb, "    }");
        append(sb, "    Start-Sleep -Seconds 2");
        append(sb, "    $tmp = Join-Path $env:TEMP ('ani-rss-unpack-' + [guid]::NewGuid().ToString('N'))");
        append(sb, "    New-Item -ItemType Directory -Force -Path $tmp | Out-Null");
        append(sb, "    Write-Log \"expand to $tmp\"");
        append(sb, "    Expand-Archive -Path '" + zipPath + "' -DestinationPath $tmp -Force");
        append(sb, "    $src = $tmp");
        append(sb, "    if (-not (Test-Path (Join-Path $tmp 'ani-rss.exe'))) {");
        append(sb, "        $sub = Get-ChildItem $tmp -Directory | Select-Object -First 1");
        append(sb, "        if ($sub) { $src = $sub.FullName }");
        append(sb, "    }");
        append(sb, "    Write-Log \"source = $src\"");
        append(sb, "");
        append(sb, "    # back up the current files so a failed update can be rolled back");
        append(sb, "    $backup = Join-Path '" + dir + "' 'backup'");
        append(sb, "    try {");
        append(sb, "        if (Test-Path $backup) { Remove-Item $backup -Recurse -Force -ErrorAction SilentlyContinue }");
        append(sb, "        New-Item -ItemType Directory -Force -Path $backup | Out-Null");
        append(sb, "        foreach ($n in 'ani-rss.exe','ani-rss.jar') {");
        append(sb, "            $f = Join-Path '" + dir + "' $n");
        append(sb, "            if (Test-Path $f) { Copy-Item $f (Join-Path $backup $n) -Force }");
        append(sb, "        }");
        append(sb, "        $curJreDir = Join-Path '" + dir + "' 'jre'");
        append(sb, "        if (Test-Path $curJreDir) { Copy-Item $curJreDir (Join-Path $backup 'jre') -Recurse -Force }");
        append(sb, "        Write-Log 'backup done'");
        append(sb, "    } catch {");
        append(sb, "        Write-Log ('backup FAILED (continuing): ' + $_.Exception.Message)");
        append(sb, "    }");
        append(sb, "");
        append(sb, "    if (Test-Path (Join-Path $src 'ani-rss.exe')) {");
        append(sb, "        Write-Log 'replace exe'");
        append(sb, "        Copy-Item (Join-Path $src 'ani-rss.exe') (Join-Path '" + dir + "' 'ani-rss.exe') -Force");
        append(sb, "    }");
        append(sb, "    if (Test-Path (Join-Path $src 'ani-rss.jar')) {");
        append(sb, "        Write-Log 'replace jar'");
        append(sb, "        Copy-Item (Join-Path $src 'ani-rss.jar') (Join-Path '" + dir + "' 'ani-rss.jar') -Force");
        append(sb, "    }");
        append(sb, "    $newJre = Join-Path $src 'jre'");
        append(sb, "    if (Test-Path $newJre) {");
        append(sb, "        Write-Log 'replace jre'");
        append(sb, "        $oldJre = Join-Path '" + dir + "' 'jre.old'");
        append(sb, "        Remove-Item $oldJre -Recurse -Force -ErrorAction SilentlyContinue");
        append(sb, "        $curJre = Join-Path '" + dir + "' 'jre'");
        append(sb, "        if (Test-Path $curJre) { Move-Item $curJre $oldJre -Force }");
        append(sb, "        Copy-Item $newJre (Join-Path '" + dir + "' 'jre') -Recurse -Force");
        append(sb, "        Remove-Item $oldJre -Recurse -Force -ErrorAction SilentlyContinue");
        append(sb, "    }");
        append(sb, "    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue");
        append(sb, "    Remove-Item '" + zipPath + "' -Force -ErrorAction SilentlyContinue");
        append(sb, "    Write-Log 'restart'");
        append(sb, "    Start-Process -FilePath (Join-Path '" + dir + "' 'ani-rss.exe') -WorkingDirectory '" + dir + "'");
        append(sb, "    Write-Log 'done'");
        append(sb, "} catch {");
        append(sb, "    Write-Log ('FAILED: ' + $_.Exception.Message)");
        append(sb, "}");

        return sb.toString();
    }

    /**
     * 追加一行并换行
     */
    private static void append(StringBuilder sb, String line) {
        sb.append(line).append('\n');
    }

    /**
     * 在程序目录写入回滚脚本
     * <p>
     * 更新前会把当前 exe/jar/jre 备份到程序目录的 backup 下, 该脚本用于一键还原。
     * 同样以 UTF-8 BOM 写出, 并且内容保持纯 ASCII, 避免 PowerShell 5.1 解析问题。
     *
     * @param appDir 程序目录
     */
    private void writeRollbackScript(File appDir) {
        String dir = appDir.getAbsolutePath();

        String template = String.join("\n",
                "# Roll ani-rss back to the version saved in .\\backup",
                "# Generated by ani-rss before an update. Run it while ani-rss is NOT running.",
                "$ErrorActionPreference = 'Stop'",
                "$dir = '__DIR__'",
                "$backup = Join-Path $dir 'backup'",
                "",
                "if (-not (Test-Path $backup)) {",
                "    Write-Host 'no backup directory found, nothing to roll back'",
                "    exit 1",
                "}",
                "",
                "Get-Process -Name 'ani-rss' -ErrorAction SilentlyContinue | Stop-Process -Force",
                "Start-Sleep -Seconds 2",
                "",
                "foreach ($n in 'ani-rss.exe','ani-rss.jar') {",
                "    $f = Join-Path $backup $n",
                "    if (Test-Path $f) {",
                "        Copy-Item $f (Join-Path $dir $n) -Force",
                "        Write-Host \"restored $n\"",
                "    }",
                "}",
                "",
                "$jre = Join-Path $backup 'jre'",
                "if (Test-Path $jre) {",
                "    $cur = Join-Path $dir 'jre'",
                "    if (Test-Path $cur) { Remove-Item $cur -Recurse -Force -ErrorAction SilentlyContinue }",
                "    Copy-Item $jre $cur -Recurse -Force",
                "    Write-Host 'restored jre'",
                "}",
                "",
                "Write-Host ''",
                "Write-Host 'rollback done, starting ani-rss'",
                "Start-Process -FilePath (Join-Path $dir 'ani-rss.exe') -WorkingDirectory $dir");

        writeUtf8Bom(new File(appDir, "rollback.ps1"), template.replace("__DIR__", dir));
    }

    /**
     * 便携版需要下载 zip 包
     */
    @Override
    public File downloadUpdateFile(About about, BiConsumer<Long, Long> progress) {
        File file = new File(FileUtil.getTmpDir(), "ani-rss-portable.zip");

        // 复用分块下载 + sha256 校验, 以便上报进度
        download(about, file, progress);

        return file;
    }

    /**
     * 以 UTF-8 BOM 写出脚本
     */
    public static void writeUtf8Bom(File file, String content) {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        FileUtil.writeBytes(all, file);
    }
}
