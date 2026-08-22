<template>
  <main class="conversation panel-border">
    <div class="conversation-empty" v-if="!currentSession"><div class="empty-glyph">C</div><h2>准备开始</h2><p>选择一个工作空间，创建会话，然后把任务交给 Codex。</p><el-button type="primary" icon="el-icon-folder-opened" @click="$emit('open-workspace')">选择工作空间</el-button></div>
    <div class="message-scroll" ref="messageScroll" v-if="currentSession" @scroll="$emit('message-scroll', $event)">
      <div v-if="!messages.length" class="first-prompt"><span class="prompt-kicker">{{ project ? project.name : 'Codex' }}</span><h2>你想处理什么？</h2><p>可以从查看项目结构、解释代码或修改功能开始。</p></div>
      <div v-if="historyHasMore" class="history-more"><el-button size="mini" :loading="historyLoading" @click="$emit('load-older')">加载更早内容</el-button></div>
      <div v-for="(message, index) in displayMessages" :key="message.id || index" class="message-block" :class="message.role">
        <details v-if="message.role === 'overall-group'" class="overall-thought activity-block" :open="overallOpen(message)" @toggle="$emit('overall-toggle', message, $event)">
          <summary><i :class="isOverallGroupActive(message) ? 'el-icon-loading' : 'el-icon-cpu'"></i><span class="activity-title">总体思考</span><span class="activity-state">{{ overallGroupStatus(message) }}</span></summary>
          <div class="overall-thought-content">
            <template v-for="item in message.items">
              <details v-if="item.role === 'thinking'" :key="item.id" class="thinking-block" :open="item.thinkingOpen" @toggle="$emit('thinking-toggle', item, $event)"><summary><i class="el-icon-caret-right"></i><span>思考过程</span><span class="activity-state">{{ thinkingStatus(item) }}</span></summary><div class="thinking-content markdown-body" v-html="renderMarkdown(item.text)"></div></details>
              <template v-else-if="item.role === 'tool-group'">
                <details :key="item.id" class="work-process-group"><summary><i :class="isToolGroupActive(item) ? 'el-icon-loading' : 'el-icon-cpu'"></i><span class="activity-title">工作过程</span><span class="activity-count">{{ item.activities.length }} 项操作</span><span class="activity-state">{{ toolGroupStatus(item) }}</span></summary>
                  <div class="activity-group-list"><details v-for="activity in item.activities" :key="activity.id" class="activity-entry"><summary class="activity-entry-heading"><i :class="activity.state === '运行中' ? 'el-icon-loading' : 'el-icon-cpu'"></i><span class="activity-title">{{ activity.title }}</span><span class="activity-state">{{ activityStatus(activity) }}</span></summary><div v-if="activity.command" class="activity-section"><span class="activity-label">命令</span><pre class="activity-code">{{ activity.command }}</pre></div><div v-if="activity.output" class="activity-section"><span class="activity-label">输出</span><pre class="activity-output">{{ activity.output }}</pre></div><div v-if="activity.exitCode !== null && activity.exitCode !== undefined" class="activity-exit-code">退出码 {{ activity.exitCode }}</div></details></div>
                </details>
              </template>
            </template>
          </div>
        </details>
        <div v-if="message.role === 'user'" class="message-meta"><span class="avatar user">你</span><strong>你</strong><span>{{ formatTime(message.timestamp) }}</span></div>
        <div v-if="message.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(message.text)"></div>
        <div v-else-if="message.role === 'user'" class="user-message" @dblclick.stop="prepareResend(message)">{{ message.text }}<div v-if="resendMessageId === message.id" class="resend-action"><el-button type="primary" size="mini" icon="el-icon-position" :loading="sending" :disabled="sending" @click.stop="submitResend(message.text)">发送</el-button></div></div>
        <span v-if="message.role === 'assistant' && message.streaming" class="typing-cursor"></span>
      </div>
      <div v-if="running && liveStatus" class="assistant-status" role="status" aria-live="polite"><i class="el-icon-loading"></i><span>{{ liveStatus }}</span></div>
      <div v-if="errorMessage" class="error-banner"><i class="el-icon-warning-outline"></i><span>{{ errorMessage }}</span><el-button size="mini" @click="$emit('retry')">重试</el-button></div>
    </div>

    <div class="composer" v-if="currentSession">
      <div v-if="currentSession.queuedTurns && currentSession.queuedTurns.length" class="queued-panel"><div class="queued-panel-heading"><span><i class="el-icon-time"></i> 待发送</span><small>按顺序自动执行</small></div><div v-for="item in currentSession.queuedTurns" :key="item.id" class="queued-item"><span class="queued-item-index"></span><span class="queued-item-text">{{ item.text }}</span><el-button v-if="canSteer" type="text" size="mini" icon="el-icon-position" :disabled="sending || deletingQueueId === item.id" @click="$emit('steer', item)">引导当前</el-button><el-button class="queue-delete-button" type="text" icon="el-icon-delete" title="删除待发送消息" :loading="deletingQueueId === item.id" :disabled="sending || deletingQueueId !== null" @click.stop="$emit('delete-queued', item)"></el-button></div></div>
      <div v-if="attachments.length" class="queued-panel attachment-panel"><div class="queued-panel-heading"><span><i class="el-icon-paperclip"></i> 待发送附件</span><small>{{ attachments.length }} 个文件</small></div><div v-for="(attachment, index) in attachments" :key="attachment.uploadId || attachment.path || index" class="queued-item attachment-item"><span class="queued-item-index attachment-item-icon" :class="attachment.status"><i :class="attachment.status === 'uploading' ? 'el-icon-loading' : (attachment.status === 'failed' ? 'el-icon-warning-outline' : 'el-icon-document-checked')"></i></span><span class="queued-item-text" :title="attachment.name">{{ attachment.name }}</span><small class="attachment-uploaded" :class="attachment.status">{{ attachment.status === 'uploading' ? '上传中...' : (attachment.status === 'failed' ? '上传失败' : '已上传') }}</small><el-button class="queue-delete-button" type="text" icon="el-icon-delete" title="删除附件" @click.stop="$emit('remove-attachment', index)"></el-button></div></div>
      <div class="composer-shell" :class="{ focus: composerFocused }"><textarea :value="draft" rows="3" placeholder="描述你希望 Codex 完成的任务..." @input="$emit('update:draft', $event.target.value)" @focus="$emit('composer-focus', true)" @blur="$emit('composer-focus', false)" @keydown="$emit('composer-keydown', $event)"></textarea><div class="composer-actions"><span class="composer-hint">{{ socketOpen ? (running ? '可继续发送，任务将按顺序执行' : 'Enter 发送 · Ctrl / Cmd + Enter 换行') : '正在连接 Codex...' }}<span v-if="currentSession.queuedTurns && currentSession.queuedTurns.length"> · 队列 {{ currentSession.queuedTurns.length }} 条</span><span v-if="attachments.length"> · {{ attachments.length }} 个附件</span></span><div><input ref="upload" type="file" hidden multiple @change="$emit('upload', $event)"><el-button class="icon-button" icon="el-icon-paperclip" circle title="上传文件" @click="$refs.upload.click()"></el-button><el-button class="stop-button" v-if="running && (currentSession.status === 'RUNNING' || currentSession.status === 'WAITING_APPROVAL')" icon="el-icon-video-pause" @click="$emit('cancel')">停止</el-button><el-button type="primary" icon="el-icon-position" :loading="sending" :disabled="!draft.trim() || sending || attachments.some(item => item.status !== 'uploaded')" @click="$emit('send')">发送</el-button></div></div></div>
    </div>
  </main>
</template>

<script>
export default {
  name: 'ConversationPanel',
  props: { project: Object, currentSession: Object, messages: Array, displayMessages: Array, running: Boolean, liveStatus: String, errorMessage: String, canSteer: Boolean, sending: Boolean, deletingQueueId: [String, Number], draft: String, attachments: Array, socketOpen: Boolean, composerFocused: Boolean, historyHasMore: Boolean, historyLoading: Boolean, overallOpen: Function, isOverallGroupActive: Function, overallGroupStatus: Function, thinkingStatus: Function, isToolGroupActive: Function, toolGroupStatus: Function, activityStatus: Function, renderMarkdown: Function, formatTime: Function },
  data () { return { resendMessageId: null } },
  methods: {
    prepareResend (message) { if (message && message.role === 'user' && !this.sending) this.resendMessageId = message.id },
    submitResend (text) { if (!text || this.sending) return; this.resendMessageId = null; this.$emit('resend-message', text) },
    clearResend () { this.resendMessageId = null }
  }
}
</script>
