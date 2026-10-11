package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.entity.Config;
import ani.rss.entity.UpdateInfo;
import ani.rss.entity.UpdateProgress;
import ani.rss.update.BaseUpdate;
import ani.rss.update.PortableUpdate;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateService {

    private final GithubService githubService;

    /*
     * 更新进度状态。
     * 更新在后台线程执行, 前端通过 /api/updateProgress 轮询这些字段渲染进度条。
     */
    private static final AtomicLong PROGRESS_CURRENT = new AtomicLong();
    private static final AtomicLong PROGRESS_TOTAL = new AtomicLong();
    private static volatile String PROGRESS_PHASE = "idle";
    private static volatile String PROGRESS_MESSAGE = "";
    private static volatile String PROGRESS_ERROR = "";

    /**
     * 当前更新进度
     *
     * @return 进度
     */
    public UpdateProgress getProgress() {
        long current = PROGRESS_CURRENT.get();
        long total = PROGRESS_TOTAL.get();

        int percent = 0;
        if (total > 0) {
            percent = (int) Math.min(100, current * 100 / total);
        }

        return new UpdateProgress()
                .setPhase(PROGRESS_PHASE)
                .setMessage(PROGRESS_MESSAGE)
                .setCurrent(current)
                .setTotal(total)
                .setPercent(percent)
                .setError(PROGRESS_ERROR);
    }

    private static void resetProgress() {
        PROGRESS_CURRENT.set(0);
        PROGRESS_TOTAL.set(0);
        PROGRESS_PHASE = "idle";
        PROGRESS_MESSAGE = "";
        PROGRESS_ERROR = "";
    }

    private static void startProgress(long total, String message) {
        PROGRESS_CURRENT.set(0);
        PROGRESS_TOTAL.set(total);
        PROGRESS_PHASE = "downloading";
        PROGRESS_MESSAGE = message;
        PROGRESS_ERROR = "";
    }

    private static void downloadProgress(long current, long total) {
        PROGRESS_CURRENT.set(current);
        if (total > 0) {
            PROGRESS_TOTAL.set(total);
        }
    }

    private static void setPhase(String phase, String message) {
        PROGRESS_PHASE = phase;
        PROGRESS_MESSAGE = message;
    }

    /**
     * 关于
     *
     * @return 关于信息
     */
    public synchronized About about() {
        String version = MavenUtils.getVersion();

        Config config = ConfigUtil.CONFIG;

        About about = (About) new About()
                .setVersion(version)
                .setUpdate(false)
                .setAutoUpdate(false)
                .setLatest("")
                .setMarkdownBody("");
        try {
            MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

            // jar / exe / 便携版(jre 目录) 三种形态对应 release 中不同的资产
            String filename = PortableUpdate.assetName(currentFile);

            // 从配置的仓库检测更新, 默认是本 fork, 不再固定指向上游
            String owner = StrUtil.blankToDefault(config.getUpdateOwner(), "XxToradoxX");
            String repo = StrUtil.blankToDefault(config.getUpdateRepo(), "ani-rss");

            UpdateInfo updateInfo = githubService.getUpdateInfo(owner, repo, filename, version);

            BeanUtil.copyProperties(updateInfo, about, "version");
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("检测更新失败 {}", message);
            log.error(message, e);
        }
        return about;
    }

    /**
     * 更新程序
     * <p>
     * 立即返回, 下载与替换在后台执行, 进度由 {@link #getProgress()} 暴露。
     *
     * @param about 关于信息
     */
    public void update(About about) {
        Boolean update = about.getUpdate();
        if (!update) {
            return;
        }

        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

        Assert.isTrue(currentFile.isFile(), "不支持更新");

        // 便携版是目录形态, 需要解包并替换 exe/jar/jre, 不能只替换单个 exe
        BaseUpdate baseUpdate = PortableUpdate.isPortable(currentFile)
                ? new PortableUpdate()
                : BaseUpdate.getInstance();

        startProgress(about.getSize(), StrUtil.format("正在下载 v{}", about.getLatest()));

        ThreadUtil.execute(() -> {
            try {
                File updateFile = baseUpdate.downloadUpdateFile(about, UpdateService::downloadProgress);

                setPhase("verifying", "正在校验并替换文件, 即将重启");

                baseUpdate.update(updateFile);
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error("更新时遇到错误: {}", message, e);
                PROGRESS_PHASE = "failed";
                PROGRESS_MESSAGE = "更新失败";
                PROGRESS_ERROR = message;
            }
        });
    }

    /**
     * 清除进度状态, 供下次更新重新计数
     */
    public synchronized void clearProgress() {
        resetProgress();
    }
}
