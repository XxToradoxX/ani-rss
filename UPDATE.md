本次为本仓库（个人自用 fork）的版本，主要差异：

- feat: 新增微信推送通知（WxPusher），支持标准推送（appToken + UID / 主题群发）与极简推送（SPT）
- fix: 测试通知不再因 TMDB / BGM 不可用而中断
- fix: WxPusher 接收列表为空时不再发送空数组
- feat: 更新检测改为读取本仓库的 Release，并可按运行形态自动选择资产（jar / exe / 便携包）
- feat: 便携版支持自适应更新（下载 ani-rss-portable.zip 后解包替换 exe/jar/jre）
- chore: Windows 便携版打包（自带 jlink 精简的 Java 运行时，目标机器无需安装 Java）

> 本仓库 fork 自 [wushuo894/ani-rss](https://github.com/wushuo894/ani-rss)，改动与原作者无关，请勿向原作者提交问题或 PR。
