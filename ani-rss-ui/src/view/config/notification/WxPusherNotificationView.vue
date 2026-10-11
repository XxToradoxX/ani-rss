<template>
  <template v-if="notificationConfig['notificationType'] === 'WX_PUSHER'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="推送方式">
      <el-select v-model="notificationConfig['wxPusherType']">
        <el-option
            v-for="it in [{
              label: '标准推送 appToken',
              name: 'APP_TOKEN'
            }, {
              label: '极简推送 spt',
              name: 'SPT'
            }]"
            :key="it.name"
            :label="it.label"
            :value="it.name"
        />
      </el-select>
    </SettingsItem>
    <SettingsItem label="Api地址">
      <el-input v-model="notificationConfig['wxPusherApiHost']"
                placeholder="https://wxpusher.zjiecode.com"/>
    </SettingsItem>
    <template v-if="notificationConfig['wxPusherType'] !== 'SPT'">
      <SettingsItem label="appToken">
        <el-input v-model="notificationConfig['wxPusherAppToken']" placeholder="AT_xxxxxxxx" show-password
                  type="password"/>
      </SettingsItem>
      <SettingsItem label="UID">
        <el-input-tag v-model="notificationConfig['wxPusherUids']" placeholder="UID_xxxxxxxx"/>
      </SettingsItem>
      <SettingsItem label="主题ID">
        <el-input-tag v-model="notificationConfig['wxPusherTopicIds']" placeholder="1234"/>
      </SettingsItem>
      <SettingsItem label="管理后台">
        <el-link type="primary" href="https://wxpusher.zjiecode.com/admin/" target="_blank">
          WxPusher管理后台
        </el-link>
      </SettingsItem>
    </template>
    <template v-else>
      <SettingsItem label="SPT">
        <el-input-tag v-model="notificationConfig['wxPusherSpts']" placeholder="SPT_xxxxxxxx"/>
      </SettingsItem>
      <SettingsItem label="获取方式">
        <el-link type="primary" href="https://wxpusher.zjiecode.com/docs/" target="_blank">
          SPT获取方式
        </el-link>
      </SettingsItem>
    </template>
    <SettingsItem label="消息设置">
      <div class="flex notification-margin-top-center">
        <el-checkbox v-model="notificationConfig['wxPusherUseMarkdown']">
          使用 markdown
        </el-checkbox>
        <el-checkbox v-model="notificationConfig['wxPusherSummary']">
          发送消息摘要
        </el-checkbox>
      </div>
    </SettingsItem>
  </template>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";

let props = defineProps(['notificationConfig', 'config'])
</script>
