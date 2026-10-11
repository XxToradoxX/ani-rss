package ani.rss.update;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;

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

        String template = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$log = Join-Path $env:TEMP 'ani-rss-portable-update.log'",
                "function Write-Log($m) { \"$(Get-Date -Format 'HH:mm:ss') $m\" | Out-File -FilePath $log -Append -Encoding utf8 }",
                "try {",
                "    Write-Log 'wait for pid __PID__ to exit'",
                "    for ($i = 0; $i -lt 240; $i++) {",
                "        if (-not (Get-Process -Id __PID__ -ErrorAction SilentlyContinue)) { break }",
                "        Start-Sleep -Milliseconds 500",
                "    }",
                "    Start-Sleep -Seconds 2",
                "",
                "    $tmp = Join-Path $env:TEMP ('ani-rss-unpack-' + [guid]::NewGuid().ToString('N'))",
                "    New-Item -ItemType Directory -Force -Path $tmp | Out-Null",
                "    Write-Log \"expand to $tmp\"",
                "    Expand-Archive -Path '__ZIP__' -DestinationPath $tmp -Force",
                "",
                "    $src = $tmp",
                "    if (-not (Test-Path (Join-Path $tmp 'ani-rss.exe'))) {",
                "        $sub = Get-ChildItem $tmp -Directory | Select-Object -First 1",
                "        if ($sub) { $src = $sub.FullName }",
                "    }",
                "    Write-Log \"source = $src\"",
                "",
                "    if (Test-Path (Join-Path $src 'ani-rss.exe')) {",
                "        Write-Log 'replace exe'",
                "        Copy-Item (Join-Path $src 'ani-rss.exe') (Join-Path '__DIR__' 'ani-rss.exe') -Force",
                "    }",
                "    if (Test-Path (Join-Path $src 'ani-rss.jar')) {",
                "        Write-Log 'replace jar'",
                "        Copy-Item (Join-Path $src 'ani-rss.jar') (Join-Path '__DIR__' 'ani-rss.jar') -Force",
                "    }",
                "",
                "    $newJre = Join-Path $src 'jre'",
                "    if (Test-Path $newJre) {",
                "        Write-Log 'replace jre'",
                "        $oldJre = Join-Path '__DIR__' 'jre.old'",
                "        Remove-Item $oldJre -Recurse -Force -ErrorAction SilentlyContinue",
                "        $curJre = Join-Path '__DIR__' 'jre'",
                "        if (Test-Path $curJre) { Move-Item $curJre $oldJre -Force }",
                "        Copy-Item $newJre (Join-Path '__DIR__' 'jre') -Recurse -Force",
                "        Remove-Item $oldJre -Recurse -Force -ErrorAction SilentlyContinue",
                "    }",
                "",
                "    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue",
                "    Remove-Item '__ZIP__' -Force -ErrorAction SilentlyContinue",
                "",
                "    Write-Log 'restart'",
                "    Start-Process -FilePath (Join-Path '__DIR__' 'ani-rss.exe') -WorkingDirectory '__DIR__'",
                "    Write-Log 'done'",
                "} catch {",
                "    Write-Log ('FAILED: ' + $_.Exception.Message)",
                "}");

        return template
                .replace("__ZIP__", zipPath)
                .replace("__DIR__", dir)
                .replace("__PID__", String.valueOf(pid));
    }

    /**
     * 便携版需要下载 zip 包
     */
    @Override
    public File downloadUpdateFile(About about) {
        File file = new File(FileUtil.getTmpDir(), "ani-rss-portable.zip");

        String downloadUrl = about.getDownloadUrl();
        String sha256 = about.getSha256();
        long size = about.getSize();

        HttpReq.get(downloadUrl)
                .then(res -> {
                    HttpReq.assertStatus(res);
                    FileUtil.writeFromStream(res.bodyStream(), file, true);
                    Assert.isTrue(file.length() == size, "下载出现问题");
                    Assert.isTrue(SecureUtil.sha256(file).equals(sha256), "更新文件的 sha256 不匹配");
                });

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
