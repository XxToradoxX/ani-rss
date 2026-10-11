package ani.rss.notification;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.web.ContentType;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.WxPusherTypeEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.google.gson.annotations.SerializedName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * WxPusher 微信推送
 * <p>
 * 支持 标准推送(appToken + UID/Topic) 与 极简推送(spt)
 *
 * @see <a href="https://wxpusher.zjiecode.com/docs/">WxPusher 开发文档</a>
 */
@Slf4j
public class WxPusherNotification implements BaseNotification {
    /**
     * 正文最大字符数
     */
    private static final int CONTENT_MAX_LENGTH = 40000;
    /**
     * 摘要最大字符数
     */
    private static final int SUMMARY_MAX_LENGTH = 100;
    /**
     * 原文链接最大字符数
     */
    private static final int URL_MAX_LENGTH = 1000;
    /**
     * 业务成功状态码
     */
    private static final int SUCCESS_CODE = 1000;
    /**
     * 默认接口地址
     */
    private static final String DEFAULT_API_HOST = "https://wxpusher.zjiecode.com";

    /**
     * 测试
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     */
    @Override
    public void test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        send(notificationConfig, ani, text, notificationStatusEnum);
    }

    /**
     * 发送通知
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 是否成功
     */
    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        WxPusherTypeEnum type = getType(notificationConfig);

        WxPusherPushBody body = toWxPusherPushBody(notificationConfig, ani, text, notificationStatusEnum);

        String url = getApiUrl(notificationConfig, type);

        return HttpReq.post(url, GsonStatic.toJson(body))
                .contentType(ContentType.JSON)
                .thenFunction(res -> {
                    if (!res.isOk()) {
                        log.error("wxpusher 请求失败, status: {}, body: {}", res.getStatus(), res.body());
                        return false;
                    }

                    WxPusherResult result = parseResult(res.body());

                    if (Objects.isNull(result)) {
                        // 返回值不是 json, 不进行重试
                        return true;
                    }

                    Boolean success = result.getSuccess();
                    Integer code = result.getCode();

                    if (Objects.equals(SUCCESS_CODE, code) || Boolean.TRUE.equals(success)) {
                        log.debug("wxpusher 发送成功 {}", result.getMsg());
                        return true;
                    }

                    log.error("wxpusher 发送失败, code: {}, msg: {}", code, result.getMsg());
                    return false;
                });
    }

    /**
     * 转换为 WxPusherPushBody
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return WxPusherPushBody
     */
    public WxPusherPushBody toWxPusherPushBody(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        WxPusherTypeEnum type = getType(notificationConfig);

        String content = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);

        WxPusherPushBody body = new WxPusherPushBody();

        body.setContent(StrUtil.maxLength(content, CONTENT_MAX_LENGTH))
                // 消息类型 1文本 3markdown
                .setContentType(Boolean.TRUE.equals(notificationConfig.getWxPusherUseMarkdown()) ? 3 : 1);

        // 点击消息跳转到 tmdb
        String tmdbUrl = getTmdbUrl(ani);
        if (StrUtil.isNotBlank(tmdbUrl)) {
            body.setUrl(StrUtil.maxLength(tmdbUrl, URL_MAX_LENGTH));
        }

        if (Boolean.TRUE.equals(notificationConfig.getWxPusherSummary())) {
            String title = Optional.ofNullable(ani)
                    .map(Ani::getTitle)
                    .filter(StrUtil::isNotBlank)
                    .orElse(text);
            String action = notificationStatusEnum.getAction();
            body.setSummary(StrUtil.maxLength(StrUtil.format("{} {}", action, title), SUMMARY_MAX_LENGTH));
        }

        List<String> uids = toList(notificationConfig.getWxPusherUids());
        List<String> topicIds = toList(notificationConfig.getWxPusherTopicIds());
        List<String> spts = toList(notificationConfig.getWxPusherSpts());

        if (WxPusherTypeEnum.SPT.equals(type)) {
            Assert.notEmpty(spts, "请设置 WxPusher spt");
            return body.setSpts(spts);
        }

        String appToken = notificationConfig.getWxPusherAppToken();

        Assert.isTrue(StrUtil.isNotBlank(appToken), "请设置 WxPusher appToken");
        Assert.isTrue(!uids.isEmpty() || !topicIds.isEmpty(), "请设置 WxPusher UID 或 主题 ID");

        // 空数组没有意义, 置为 null 让 gson 直接省略该字段
        return body.setAppToken(appToken.trim())
                .setUids(uids.isEmpty() ? null : uids)
                .setTopicIds(topicIds.isEmpty() ? null : topicIds);
    }

    /**
     * 获取推送方式
     *
     * @param notificationConfig 通知配置
     * @return 推送方式
     */
    private static WxPusherTypeEnum getType(NotificationConfig notificationConfig) {
        WxPusherTypeEnum type = notificationConfig.getWxPusherType();

        if (Objects.isNull(type)) {
            return WxPusherTypeEnum.APP_TOKEN;
        }

        return type;
    }

    /**
     * 获取 WxPusher 接口地址
     *
     * @param notificationConfig 通知配置
     * @param type               推送方式
     * @return 接口地址
     */
    private static String getApiUrl(NotificationConfig notificationConfig, WxPusherTypeEnum type) {
        String apiHost = StrUtil.blankToDefault(notificationConfig.getWxPusherApiHost(), DEFAULT_API_HOST);
        apiHost = StrUtil.removeSuffix(apiHost.trim(), "/");

        Assert.isTrue(StrUtil.startWith(apiHost, "http://") || StrUtil.startWith(apiHost, "https://"), "WxPusher Api 地址错误");

        return apiHost + type.getUri();
    }

    /**
     * 解析返回值, 返回值可能不是 json
     *
     * @param body 响应内容
     * @return 返回值
     */
    private static WxPusherResult parseResult(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }

        if (!StrUtil.startWith(body.trim(), "{")) {
            log.error("wxpusher 返回值错误: {}", StrUtil.maxLength(body, 500));
            return null;
        }

        try {
            return GsonStatic.fromJson(body, WxPusherResult.class);
        } catch (Exception e) {
            log.error("wxpusher 返回值解析失败: {}", StrUtil.maxLength(body, 500));
            log.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取订阅的 tmdb 链接, 没有则不进行跳转
     *
     * @param ani 订阅
     * @return tmdb 链接
     */
    private static String getTmdbUrl(Ani ani) {
        return Optional.ofNullable(ani)
                .map(Ani::getTmdb)
                .map(Tmdb::getId)
                .filter(StrUtil::isNotBlank)
                .map(id -> {
                    Boolean ova = ani.getOva();
                    String type = Boolean.TRUE.equals(ova) ? "movie" : "tv";
                    return StrUtil.format("https://www.themoviedb.org/{}/{}", type, id);
                })
                .orElse(null);
    }

    /**
     * 过滤空值
     *
     * @param list 列表
     * @return 列表
     */
    private static List<String> toList(List<String> list) {
        if (Objects.isNull(list)) {
            return List.of();
        }

        return list.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "WxPusher 推送消息体")
    public static class WxPusherPushBody {
        @Schema(description = "应用 appToken")
        @SerializedName("appToken")
        private String appToken;

        @Schema(description = "极简推送 spt")
        @SerializedName("spt")
        private List<String> spts;

        @Schema(description = "消息正文")
        @SerializedName("content")
        private String content;

        @Schema(description = "消息类型 1文本 2html 3markdown")
        @SerializedName("contentType")
        private Integer contentType;

        @Schema(description = "接收用户 UID")
        @SerializedName("uids")
        private List<String> uids;

        @Schema(description = "主题 ID")
        @SerializedName("topicIds")
        private List<String> topicIds;

        @Schema(description = "消息摘要")
        @SerializedName("summary")
        private String summary;

        @Schema(description = "原文链接")
        @SerializedName("url")
        private String url;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "WxPusher 返回值")
    public static class WxPusherResult {
        @Schema(description = "业务状态码")
        @SerializedName("code")
        private Integer code;

        @Schema(description = "错误信息")
        @SerializedName("msg")
        private String msg;

        @Schema(description = "是否成功")
        @SerializedName("success")
        private Boolean success;
    }
}
