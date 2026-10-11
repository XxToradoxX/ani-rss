<template>
  <div class="flex-center about-container">
    <div class="flex about-header">
      <img alt="icon.svg" height="80" src="/public/icon.svg" width="80"/>
      <div>
        <h1>ANI-RSS</h1>
        <el-tooltip
            :content="`${props.config.gitInfo.branch} ${props.config.gitInfo.shortCommitId}`"
            placement="right">
          <el-text class="mx-1 cursor-pointer" size="small">
            &nbsp;v{{ props.config.version }}
          </el-text>
        </el-tooltip>
      </div>
    </div>
    <div class="flex button-list">
      <el-button :icon="Github" bg text type="info" @click="openUrl('https://github.com/wushuo894/ani-rss')">
        GitHub
      </el-button>
      <el-button :icon="Book" bg text type="info" @click="openUrl('https://docs.wushuo.top')">
        使用文档
      </el-button>
      <el-button :icon="Telegram" bg text type="info" @click="openUrl('https://t.me/ani_rss')">
        TG群
      </el-button>
    </div>
    <div v-loading.fullscreen.lock="actionLoading" class="button-list">
      <PopconfirmView title="你确定要退出吗?" @confirm="logout">
        <template #reference>
          <el-button type="danger" bg text icon="Back">
            退出
          </el-button>
        </template>
      </PopconfirmView>
      <PopconfirmView title="你确定重启吗?" @confirm="stop(0)">
        <template #reference>
          <el-button bg icon="RefreshRight" text type="warning">重启</el-button>
        </template>
      </PopconfirmView>
      <PopconfirmView title="你确定关闭吗?" @confirm="stop(1)">
        <template #reference>
          <el-button bg icon="SwitchButton" text type="danger">关闭</el-button>
        </template>
      </PopconfirmView>
      <el-badge :hidden="!about.update" class="item" value="new">
        <el-button :loading="about.version.length < 1" bg icon="Top" text type="success" @click="dialogVisible = true">
          更新
        </el-button>
      </el-badge>
    </div>
  </div>
  <el-dialog v-if="dialogVisible" v-model="dialogVisible" align-center center title="版本更新"
             class="about-dialog">
    <div v-if="about.update">
      <div>
        <SettingsItem label="版本号">
          <el-link type="default" :href="`${about.repositoryUrl || 'https://github.com/XxToradoxX/ani-rss'}/releases/tag/v${about.latest}`"
                   target="_blank">
            {{ about.latest }}
          </el-link>
        </SettingsItem>
        <SettingsItem label="发布时间">
          {{ about.date }}
        </SettingsItem>
        <SettingsItem label="大小">
          {{ about['formatSize'] }}
        </SettingsItem>
        <SettingsItem v-if="progressVisible" label="更新进度">
          <div class="full-width">
            <el-progress
                :percentage="progress.percent"
                :status="progress.phase === 'failed' ? 'exception' : (progress.phase === 'verifying' ? 'success' : '')"
                :stroke-width="14"
                striped
                :striped-flow="progress.phase === 'downloading'"
            />
            <div class="margin-top-4">
              <el-text size="small" :type="progress.phase === 'failed' ? 'danger' : 'info'">
                {{ progressText }}
              </el-text>
            </div>
          </div>
        </SettingsItem>
        <SettingsItem label="更新内容">
          <el-scrollbar class="about-scrollbar" :always="true">
            <div class="markdown-body about-markdown" v-html="md.render(about.markdownBody)"></div>
            <el-alert
                show-icon
                :closable="false"
                class="about-alert"
                title="更新依赖于Github, 需要网络环境支持"
                type="info"
            />
          </el-scrollbar>
        </SettingsItem>
      </div>
    </div>
    <div v-else>
      <el-empty description="无更新"></el-empty>
    </div>
    <div class="flex about-dialog-footer">
      <el-button bg text icon="Tickets"
                 @click="openUrl('https://docs.wushuo.top/history')"
                 type="primary">
        更新历史
      </el-button>
      <div>
        <el-button :disabled="!about.update" bg text icon="Check"
                   type="success" @click="update">
          开始更新
        </el-button>
        <el-button bg icon="Close" text @click="dialogVisible = false">取消</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";
import {computed, onMounted, ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import PopconfirmView from "@/view/custom/PopconfirmView.vue";
import {Book, Github, Telegram} from "@vicons/fa";

import markdownit from 'markdown-it'
import MarkdownItGitHubAlerts from 'markdown-it-github-alerts'
import 'markdown-it-github-alerts/styles/github-colors-light.css'
import 'markdown-it-github-alerts/styles/github-colors-dark-media.css'
import 'markdown-it-github-alerts/styles/github-base.css'

import {authorization} from "@/js/global.js";
import * as http from "@/js/http.js";

let md = markdownit({
  html: true,
  linkify: true
})

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  token.attrSet('target', '_blank') // 强制添加属性
  return self.renderToken(tokens, idx, options)
}

md.use(MarkdownItGitHubAlerts)

const actionLoading = ref(false)

const stop = (status) => {
  actionLoading.value = true
  http.stop(status)
      .then(res => {
        ElMessage.success(res.message)
        setTimeout(() => {
          authorization.value = ''
          location.reload()
        }, 5000)
      })
      .finally(() => {
        actionLoading.value = false
      })
}

// 更新进度: 后端在后台线程下载并替换, 这里轮询 /api/updateProgress 渲染进度条
let progress = ref({
  phase: 'idle',
  message: '',
  current: 0,
  total: 0,
  percent: 0,
  error: ''
})
let progressVisible = ref(false)

let progressText = computed(() => {
  let {phase, message, percent, current, total, error} = progress.value
  if (phase === 'failed') {
    return `更新失败: ${error || '未知错误'}`
  }
  if (phase === 'verifying' || phase === 'replacing') {
    return message || '正在替换文件, 即将重启'
  }
  if (phase === 'downloading') {
    let c = formatSize(current)
    if (total > 0) {
      return `${message}  ${c} / ${formatSize(total)}  (${percent}%)`
    }
    return `${message}  ${c}`
  }
  return message || ''
})

let formatSize = (bytes) => {
  if (!bytes || bytes <= 0) return '0 B'
  let units = ['B', 'KB', 'MB', 'GB']
  let i = Math.floor(Math.log(bytes) / Math.log(1024))
  i = Math.min(i, units.length - 1)
  return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

let update = async () => {
  let sleep = ms => {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  progressVisible.value = true
  progress.value.phase = 'starting'
  actionLoading.value = true

  // 立即返回, 实际下载在后台进行
  let updateRes
  try {
    updateRes = await http.update(about.value)
  } catch (e) {
    // api.js 已经弹过错误提示
    progress.value.phase = 'failed'
    progress.value.error = e.message
    actionLoading.value = false
    return
  }
  ElMessage.success(updateRes.message)

  // 阶段一: 跟随下载进度; 若服务端其实没在更新则立刻进入重启等待
  for (let i = 0; i < 180; i++) {
    await sleep(1000)
    let res = null
    try {
      res = await http.updateProgress()
    } catch (e) {
      // 服务端正在重启, 交给下面阶段二
      break
    }
    let d = res.data || {}
    progress.value = {
      phase: d.phase || 'idle',
      message: d.message || '',
      current: d.current || 0,
      total: d.total || 0,
      percent: d.percent || 0,
      error: d.error || ''
    }
    if (d.phase === 'failed') {
      actionLoading.value = false
      return
    }
    // 已进入替换阶段, 或服务端已空闲(非下载中), 说明下载结束
    if (d.phase === 'verifying' || d.phase === 'replacing' || d.phase === 'idle') {
      break
    }
  }

  // 阶段二: 等待程序重启完成
  for (let i = 0; i < 60; i++) {
    await sleep(5000)
    try {
      let pingRes = await http.ping()
      if (pingRes.code === 200) {
        authorization.value = ''
        location.reload()
        return
      }
    } catch (e) {
    }
  }
  ElMessage.error("重启时遇到错误")
  actionLoading.value = false
}

const about = ref({
  'version': '',
  'latest': '',
  'update': false,
  'markdownBody': '',
  'repositoryUrl': ''
})

onMounted(() => {
  http.about()
      .then(res => {
        about.value = res.data
      })
})

let logout = () => {
  authorization.value = ''
  location.reload()
}

let openUrl = (url) => window.open(url)

let dialogVisible = ref(false)
let props = defineProps(['config'])

</script>

<style scoped>
.about-container {
  width: 100%;
  flex-flow: column;
}

.about-header {
  margin-bottom: 12px;
  align-items: end;
}

.cursor-pointer {
  cursor: pointer;
}

.about-dialog {
  max-width: 500px;
}

.about-scrollbar {
  margin-bottom: 16px;
  max-height: 400px;
}

.about-markdown {
  width: 800px;
}

.about-alert {
  margin-top: 8px;
}

.about-dialog-footer {
  width: 100%;
  justify-content: space-between;
}

.button-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  max-width: 100%;
  gap: 12px;
  margin-bottom: 12px;

  .el-button {
    margin: 0;
  }
}

</style>
