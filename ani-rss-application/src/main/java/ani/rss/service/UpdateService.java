package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.entity.Config;
import ani.rss.entity.UpdateInfo;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateService {

    private final GithubService githubService;

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
     *
     * @param about 关于信息
     */
    public synchronized void update(About about) {
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

        File updateFile = baseUpdate.downloadUpdateFile(about);

        ThreadUtil.execute(() -> {
            try {
                baseUpdate.update(updateFile);
            } catch (Exception e) {
                log.error("更新时遇到错误: {}", e.getMessage(), e);
            }
        });
    }
}
