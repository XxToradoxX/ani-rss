package ani.rss.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * WxPusher 推送方式
 */
@Getter
@AllArgsConstructor
public enum WxPusherTypeEnum {
    /**
     * 标准推送 使用 appToken + UID/Topic
     */
    APP_TOKEN("appToken", "标准推送", "/api/send/message", "AT_"),
    /**
     * 极简推送 使用 spt
     */
    SPT("spt", "极简推送", "/api/send/message/simple-push", "SPT_");

    private final String type;
    private final String name;
    private final String uri;
    private final String prefix;
}
