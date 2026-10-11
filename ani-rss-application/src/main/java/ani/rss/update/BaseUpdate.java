package ani.rss.update;

import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.BiConsumer;

@FunctionalInterface
public interface BaseUpdate {

    /**
     * 下载缓冲区大小
     */
    int BUFFER_SIZE = 64 * 1024;

    static BaseUpdate getInstance() {
        OsInfo osInfo = SystemUtil.getOsInfo();

        if (osInfo.isMac()) {
            return new MacUpdate();
        } else if (osInfo.isLinux()) {
            return new LinuxUpdate();
        } else if (osInfo.isWindows()) {
            return new WindowsUpdate();
        } else {
            String name = osInfo.getName();
            throw new IllegalArgumentException("不支持的系统 " + name);
        }
    }

    void update(File updateFile);

    /**
     * 下载更新文件
     *
     * @param about    关于信息
     * @param progress 进度回调 (已下载字节, 总字节), 可为 null
     * @return 下载后的文件
     */
    default File downloadUpdateFile(About about, BiConsumer<Long, Long> progress) {
        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();
        File file = new File(currentFile.getFile() + ".tmp");

        download(about, file, progress);

        return file;
    }

    /**
     * 分块下载并回调进度
     * <p>
     * 不用 FileUtil.writeFromStream: 那是一次性写入, 无法报告中途进度。
     *
     * @param about    关于信息
     * @param file     目标文件
     * @param progress 进度回调, 可为 null
     */
    default void download(About about, File file, BiConsumer<Long, Long> progress) {
        String downloadUrl = about.getDownloadUrl();
        String sha256 = about.getSha256();
        long size = Objects.isNull(about.getSize()) ? 0L : about.getSize();

        HttpReq.get(downloadUrl)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);

                    long total = size;
                    long contentLength = res.contentLength();
                    if (contentLength > 0) {
                        // 以响应头为准, 更准确
                        total = contentLength;
                    }
                    if (Objects.nonNull(progress)) {
                        progress.accept(0L, total);
                    }

                    long current = 0;

                    // Function 不允许抛受检异常, 这里包一层转为运行时异常
                    try (InputStream in = res.bodyStream();
                         OutputStream out = FileUtil.getOutputStream(file)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                            current += len;
                            if (Objects.nonNull(progress)) {
                                progress.accept(current, total);
                            }
                        }
                        out.flush();
                    } catch (IOException e) {
                        throw new IORuntimeException(e);
                    }

                    if (size > 0) {
                        Assert.isTrue(file.length() == size, "下载出现问题");
                    }
                    if (Objects.nonNull(progress)) {
                        progress.accept(file.length(), file.length());
                    }
                    if (sha256 != null && !sha256.isBlank()) {
                        Assert.isTrue(SecureUtil.sha256(file).equals(sha256), "更新文件的 sha256 不匹配");
                    }
                    return null;
                });
    }
}
