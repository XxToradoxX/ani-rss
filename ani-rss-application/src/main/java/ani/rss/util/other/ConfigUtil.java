package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.URLUtils;
import ani.rss.config.DefaultConfigFactory;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.handle.JsonReader;
import ani.rss.handle.JsonWriter;
import ani.rss.util.basic.LogUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.DynaBean;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.lang.func.LambdaUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

@Slf4j
public class ConfigUtil {

    public static final Config CONFIG = DefaultConfigFactory.create();
    public static final String FILE_NAME = "config.v2.json";

    /**
     * 获取设置文件夹
     * <p>
     * 优先级: 环境变量 CONFIG &gt; Windows/macOS 用户目录/ani-rss &gt; 工作目录下的 config (Linux/Docker)
     * <p>
     * 不再支持"程序目录下放一个 config 文件夹即使用便携模式", 配置固定落在用户目录,
     * 这样程序目录可以整体替换(升级/更新)而不影响配置。
     *
     * @return 文件夹
     */
    public static File getConfigDir() {
        String configDir = SystemUtil.get("CONFIG");
        if (StrUtil.isNotBlank(configDir)) {
            return new File(configDir);
        }

        OsInfo osInfo = SystemUtil.getOsInfo();
        if (osInfo.isWindows() || osInfo.isMac()) {
            // Windows / macOS 默认为 用户目录/ani-rss
            return new File(FileUtil.getUserHomePath(), "ani-rss");
        }

        // Linux 等系统保持原行为: 工作目录下的 config (Docker 通常挂载到这里)
        return new File("config").getAbsoluteFile();
    }

    /**
     * 获取设置文件
     *
     * @return 设置文件
     */
    public static File getConfigFile() {
        File configDir = getConfigDir();
        return new File(configDir + File.separator + FILE_NAME);
    }

    /**
     * 加载设置
     */
    public static synchronized void load() {
        File configFile = getConfigFile();

        Config config = JsonReader.getInstance(configFile)
                .toObject(Config.class, CONFIG);

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true);
        BeanUtil.copyProperties(config, CONFIG, copyOptions);
        format(CONFIG);
        LogUtil.loadLogback();
        log.debug("加载配置文件 {}", configFile);
        TorrentUtil.loadDownloadTool();
    }

    /**
     * 将设置保存到磁盘
     */
    public static synchronized void sync() {
        File configFile = getConfigFile();
        log.debug("保存配置 {}", configFile);
        try {
            ConfigUtil.format(CONFIG);

            // 写入到硬盘
            JsonWriter.getInstance(configFile)
                    .writer(CONFIG);

            LogUtil.loadLogback();
            log.debug("保存成功 {}", configFile);
        } catch (Exception e) {
            log.error("保存失败 {}", configFile);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 处理设置内的url与文件路径标准
     *
     * @param config 设置
     */
    public static void format(Config config) {
        formatPath(config);
        formatUrl(config);

        String messageTemplate = config.getNotificationTemplate();
        config.setNotificationTemplate(messageTemplate.trim());

        NotificationConfig newNotificationConfig = NotificationConfig.createNotificationConfig();

        List<NotificationConfig> notificationConfigList = config.getNotificationConfigList();

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true)
                // 禁止覆盖模式 仅补全null值
                .setOverride(false);

        for (NotificationConfig notificationConfig : notificationConfigList) {
            BeanUtil.copyProperties(newNotificationConfig, notificationConfig, copyOptions);
        }
    }

    /**
     * 处理url
     *
     * @param config 设置
     */
    public static void formatUrl(Config config) {
        List<Func1<Config, String>> func1List = List.of(
                Config::getDownloadToolHost,
                Config::getMikanHost,
                Config::getTmdbApi
        );

        DynaBean dynaBean = DynaBean.create(config);

        for (Func1<Config, String> func1 : func1List) {
            String fieldName = LambdaUtil.getFieldName(func1);
            String v = func1.callWithRuntimeException(config);
            v = URLUtils.getUrlStr(v);
            dynaBean.set(fieldName, v);
        }
    }

    /**
     * 处理文件路径
     *
     * @param config 设置
     */
    public static void formatPath(Config config) {
        List<Func1<Config, String>> func1List = List.of(
                Config::getDownloadPathTemplate,
                Config::getOvaDownloadPathTemplate,
                Config::getCompletedPathTemplate
        );

        DynaBean dynaBean = DynaBean.create(config);

        for (Func1<Config, String> func1 : func1List) {
            String fieldName = LambdaUtil.getFieldName(func1);
            String v = func1.callWithRuntimeException(config);
            v = FileUtils.getAbsolutePath(v);
            dynaBean.set(fieldName, v);
        }
    }

}
