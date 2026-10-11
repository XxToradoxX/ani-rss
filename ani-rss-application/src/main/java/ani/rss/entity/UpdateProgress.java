package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 更新进度
 * <p>
 * 更新在后台线程执行, 前端轮询该对象显示进度条。
 */
@Data
@Accessors(chain = true)
@Schema(description = "更新进度")
public class UpdateProgress implements Serializable {

    /**
     * 阶段: idle 空闲 / downloading 下载中 / verifying 校验中 / replacing 替换中 / failed 失败
     */
    @Schema(description = "阶段")
    private String phase;

    @Schema(description = "状态描述")
    private String message;

    /**
     * 已下载字节
     */
    @Schema(description = "已下载字节")
    private Long current;

    /**
     * 总字节
     */
    @Schema(description = "总字节")
    private Long total;

    /**
     * 百分比 0-100
     */
    @Schema(description = "百分比")
    private Integer percent;

    @Schema(description = "错误信息")
    private String error;

    public static UpdateProgress idle() {
        return new UpdateProgress()
                .setPhase("idle")
                .setMessage("")
                .setCurrent(0L)
                .setTotal(0L)
                .setPercent(0)
                .setError("");
    }
}
