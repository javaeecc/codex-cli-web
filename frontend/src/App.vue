<template>
  <div v-if="!authReady" class="auth-loading"><span class="brand-mark">C</span><span>正在连接 Codex Web...</span></div>
  <div v-else-if="!authenticated" class="login-page">
    <div class="login-panel">
      <div class="login-brand"><span class="brand-mark">C</span><div><strong>Codex Web</strong><small>本地开发工作台</small></div></div>
      <h1>登录</h1>
      <p class="login-subtitle">登录后继续使用你的工作空间和会话。</p>
      <form class="login-form" @submit.prevent="submitLogin">
        <label>用户名<input v-model.trim="loginForm.username" type="email" autocomplete="username" placeholder="请输入用户名" autofocus></label>
        <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" placeholder="请输入密码"></label>
        <p v-if="loginError" class="login-error" role="alert">{{ loginError }}</p>
        <el-button class="login-submit" type="primary" native-type="submit" :loading="loginLoading" :disabled="!loginForm.username || !loginForm.password">登录</el-button>
      </form>
    </div>
  </div>
  <div v-else class="shell" :class="{ 'left-collapsed': leftCollapsed, 'right-collapsed': rightCollapsed }">
    <header class="topbar">
      <div class="brand"><button class="mobile-menu-button" type="button" title="打开会话列表" @click="leftCollapsed = !leftCollapsed"><i class="el-icon-menu"></i></button><span class="brand-mark">C</span><span>Codex Web</span></div>
      <div class="top-context">
        <span class="context-label">工作空间</span>
        <strong>{{ currentProject ? currentProject.name : '未选择' }}</strong>
        <span class="divider">/</span>
        <i class="el-icon-branch" v-if="currentBranch"></i>
        <el-select v-if="currentProject && branches.length" v-model="currentBranch" size="mini" class="branch-select" @change="checkoutBranch"><el-option v-for="branch in branches" :key="branch" :label="branch" :value="branch"></el-option></el-select><span v-else>{{ currentBranch || '无分支' }}</span>
      </div>
      <div class="top-actions">
        <span class="runtime-pill" :class="runtime.running ? 'is-live' : 'is-idle'"><span class="status-dot"></span>{{ runtime.running ? 'Codex 运行中' : 'Codex 未启动' }}</span>
        <button class="mobile-inspector-button" type="button" :title="rightCollapsed ? '查看变更和文件' : '关闭变更和文件'" @click="rightCollapsed = !rightCollapsed"><i :class="rightCollapsed ? 'el-icon-s-operation' : 'el-icon-close'"></i></button>
        <el-button class="icon-button" icon="el-icon-setting" circle title="设置" @click="openSettings"></el-button>
      </div>
    </header>
    <div v-if="!rightCollapsed" class="mobile-inspector-backdrop" @click="rightCollapsed = true"></div>

    <div class="workspace-layout">
      <aside class="left-panel panel-border">
        <div class="panel-heading">
          <span>项目</span>
          <el-button type="text" icon="el-icon-plus" title="添加工作空间" @click="openWorkspacePicker"></el-button>
        </div>
        <div class="project-list" v-if="projects.length">
          <button v-for="project in projects" :key="project.id" class="project-row" :class="{ active: currentProject && currentProject.id === project.id }" @click="selectProject(project)">
            <span class="project-icon" :class="{ git: project.isGitRepository }"><i :class="project.isGitRepository ? 'el-icon-connection' : 'el-icon-folder'"></i></span>
            <span class="project-copy"><strong>{{ project.name }}</strong><small>{{ compactPath(project.path) }}</small></span>
            <i class="el-icon-delete project-delete" title="删除项目" @click.stop="deleteProject(project)"></i>
          </button>
        </div>
        <div class="empty-side" v-else><i class="el-icon-folder-opened"></i><span>还没有工作空间</span><el-button size="mini" @click="openWorkspacePicker">选择目录</el-button></div>

        <div class="session-heading"><span>会话</span><div class="session-heading-actions"><el-button type="text" icon="el-icon-plus" :disabled="!currentProject" title="新建会话" @click="createSession"></el-button></div></div><div class="session-search"><i class="el-icon-search"></i><input v-model="sessionSearch" placeholder="搜索会话"></div>
        <div class="session-list" v-if="sessions.length">
          <button v-for="session in visibleSessions" :key="session.id" class="session-row" :class="{ active: currentSession && currentSession.id === session.id }" @click="selectSession(session)">
            <span class="session-status" :class="statusClass(session.status)"></span>
            <span class="session-copy"><strong>{{ session.title }}</strong><small>创建 {{ formatSessionTime(session.createdAt) }} · 更新 {{ formatSessionTime(session.updatedAt || session.createdAt) }}</small></span>
            <i class="session-archive" :class="session.archived ? 'el-icon-refresh-left' : 'el-icon-box'" :title="session.archived ? '取消归档' : '归档'" @click.stop="toggleSessionArchive(session)"></i>
          </button>
        </div>
        <div class="empty-side compact" v-else><span>{{ currentProject ? '新建一个会话开始工作' : '先选择工作空间' }}</span></div>
        <div class="left-footer"><span class="connection-state"><span class="status-dot" :class="socketOpen ? 'green' : 'gray'"></span>{{ socketOpen ? '实时连接' : '正在重连' }}</span><button class="footer-link" @click="showArchived = !showArchived">{{ showArchived ? '隐藏归档' : '显示归档' }}</button></div>
      </aside>

      <main class="conversation panel-border">
        <div class="conversation-empty" v-if="!currentSession"><div class="empty-glyph">C</div><h2>准备开始</h2><p>选择一个工作空间，创建会话，然后把任务交给 Codex。</p><el-button type="primary" icon="el-icon-folder-opened" @click="openWorkspacePicker">选择工作空间</el-button></div>
        <div class="message-scroll" ref="messageScroll" v-if="currentSession" @scroll="handleMessageScroll">
          <div v-if="!messages.length" class="first-prompt"><span class="prompt-kicker">{{ currentProject ? currentProject.name : 'Codex' }}</span><h2>你想处理什么？</h2><p>可以从查看项目结构、解释代码或修改功能开始。</p></div>
          <div v-for="(message, index) in displayMessages" :key="message.id || index" class="message-block" :class="message.role">
            <details v-if="message.role === 'thinking'" class="thinking-block" :open="message.thinkingOpen" @toggle="setThinkingOpen(message, $event)"><summary><i class="el-icon-caret-right"></i><span>思考过程</span><span v-if="message.streaming" class="thinking-live">正在思考</span></summary><div class="thinking-content markdown-body" v-html="renderMarkdown(message.text)"></div></details>
            <div v-if="message.role === 'user'" class="message-meta"><span class="avatar user">你</span><strong>你</strong><span>{{ formatTime(message.timestamp) }}</span></div>
            <div v-if="message.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(message.text)"></div>
            <div v-else-if="message.role === 'user'" class="user-message">{{ message.text }}</div>
            <span v-if="message.role === 'assistant' && message.streaming" class="typing-cursor"></span>
          </div>
          <div v-if="running && liveStatus" class="assistant-status" role="status" aria-live="polite"><i class="el-icon-loading"></i><span>{{ liveStatus }}</span></div>
          <div v-if="errorMessage" class="error-banner"><i class="el-icon-warning-outline"></i><span>{{ errorMessage }}</span><el-button size="mini" @click="retryLast">重试</el-button></div>
        </div>
        <div class="composer" v-if="currentSession">
        <div v-if="currentSession && currentSession.queuedTurns && currentSession.queuedTurns.length" class="queued-panel"><div class="queued-panel-heading"><span><i class="el-icon-time"></i> 待发送</span><small>按顺序自动执行</small></div><div v-for="item in currentSession.queuedTurns" :key="item.id" class="queued-item"><span class="queued-item-index"></span><span class="queued-item-text">{{ item.text }}</span><el-button v-if="canSteer" type="text" size="mini" icon="el-icon-position" :disabled="sending || deletingQueueId === item.id" @click="steerQueued(item)">引导当前</el-button><el-button class="queue-delete-button" type="text" icon="el-icon-delete" title="删除待发送消息" :loading="deletingQueueId === item.id" :disabled="sending || deletingQueueId !== null" @click.stop="deleteQueued(item)"></el-button></div></div>
        <div class="composer-shell" :class="{ focus: composerFocused }"><textarea v-model="draft" rows="3" placeholder="描述你希望 Codex 完成的任务..." @focus="composerFocused = true" @blur="composerFocused = false" @keydown="handleComposerKeydown"></textarea><div class="composer-actions"><span class="composer-hint">{{ socketOpen ? (running ? '可继续发送，任务将按顺序执行' : 'Enter 发送 · Ctrl / Cmd + Enter 换行') : '正在连接 Codex...' }}<span v-if="currentSession && currentSession.queuedTurns && currentSession.queuedTurns.length"> · 队列 {{ currentSession.queuedTurns.length }} 条</span><span v-if="attachments.length"> · {{ attachments.length }} 个附件</span></span><div><input ref="upload" type="file" hidden multiple @change="uploadFiles"><el-button class="icon-button" icon="el-icon-paperclip" circle title="上传文件" @click="$refs.upload.click()"></el-button><el-button class="stop-button" v-if="running && currentSession && (currentSession.status === 'RUNNING' || currentSession.status === 'WAITING_APPROVAL')" icon="el-icon-video-pause" @click="cancelTurn">停止</el-button><el-button type="primary" icon="el-icon-position" :loading="sending" :disabled="!draft.trim() || sending" @click="sendMessage">发送</el-button></div></div></div>
        </div>
      </main>

      <aside class="right-panel panel-border">
        <div class="inspector-tabs"><button v-for="tab in tabs" :key="tab.id" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}<span v-if="tab.id === 'changes' && gitFiles.length">{{ gitFiles.length }}</span></button></div>
        <div class="inspector-content" v-if="currentProject">
          <div v-if="activeTab === 'changes'" class="change-view"><div class="inspector-toolbar"><strong>工作区变更</strong><el-button type="text" icon="el-icon-refresh" title="刷新 Git 状态" @click="refreshGit"></el-button></div><div v-if="gitFiles.length" class="change-list"><button v-for="file in gitFiles" :key="file.path" class="change-row" @click="openDiff(file.path, true)"><span class="change-kind" :class="file.kind">{{ file.code.trim() || 'M' }}</span><span>{{ file.path }}</span></button></div><div v-else class="inspector-empty"><i class="el-icon-check"></i><span>工作区干净</span></div></div>
          <div v-else class="files-view"><div class="inspector-toolbar"><strong>文件</strong><el-button type="text" icon="el-icon-refresh" title="刷新文件树" @click="loadFiles"></el-button></div><div class="file-tree"><div v-for="item in fileItems" :key="item.path" class="file-row" :class="{ 'file-row-unavailable': !item.directory && !item.viewable }" :style="{ paddingLeft: `${10 + item.depth * 16}px` }" @click="item.directory ? toggleDirectory(item) : (item.viewable ? openFile(item) : notifyFileUnavailable(item))"><i :class="item.directory ? (fileLoadingPaths[item.path] ? 'el-icon-loading' : (item.expanded ? 'el-icon-folder-opened' : 'el-icon-folder')) : (item.viewable ? 'el-icon-document' : 'el-icon-document-delete')"></i><span class="file-name">{{ item.name }}</span><small v-if="!item.directory && item.size != null" class="file-size">{{ formatFileSize(item.size) }}</small></div></div></div>
        </div>
        <div v-else class="inspector-empty full"><i class="el-icon-s-operation"></i><span>选择项目后查看代码状态</span></div>
      </aside>
    </div>

    <el-dialog title="选择工作空间" :visible.sync="workspaceDialog" width="600px" custom-class="workspace-dialog"><div class="picker-path"><el-button icon="el-icon-back" circle :disabled="!workspaceParent" @click="browse(workspaceParent)"></el-button><span>{{ workspacePath }}</span><el-button type="text" icon="el-icon-folder-add" title="新建目录" @click="createFolder"></el-button></div><div class="root-switch"><button v-for="root in workspaceRoots" :key="root.path" :class="{ active: workspacePath === root.path }" @click="browse(root.path)"><i class="el-icon-folder"></i>{{ root.name }}</button></div><div class="browser-list"><button v-for="item in workspaceItems" :key="item.path" class="browser-row" :class="{ selected: selectedWorkspace === item.path }" @dblclick="browse(item.path)" @click="selectedWorkspace = item.path"><i :class="item.isGitRepository ? 'el-icon-connection' : 'el-icon-folder'"></i><span>{{ item.name }}</span><i class="el-icon-arrow-right"></i></button><div v-if="!workspaceItems.length" class="browser-empty">此目录没有子目录</div></div><div class="dialog-footer"><span class="selected-path">{{ selectedWorkspace || '双击进入目录，或选择当前目录' }}</span><el-button @click="workspaceDialog = false">取消</el-button><el-button type="primary" icon="el-icon-check" :disabled="!selectedWorkspace" @click="confirmWorkspace">选择此目录</el-button></div></el-dialog>
    <el-dialog title="设置" :visible.sync="settingsDialog" width="540px" custom-class="settings-dialog"><div class="settings-section"><div class="settings-label"><strong>模型</strong><span>选择模型，新建会话时生效。</span></div><el-select v-model="settings.model" class="settings-select"><el-option label="默认（跟随 Codex 配置）" value=""></el-option><el-option label="GPT-5.6 Sol" value="gpt-5.6-sol"></el-option><el-option label="GPT-5.6 Terra" value="gpt-5.6-terra"></el-option><el-option label="GPT-5.6 Luna" value="gpt-5.6-luna"></el-option><el-option label="GPT-5.5" value="gpt-5.5"></el-option><el-option label="GPT-5.2" value="gpt-5.2"></el-option></el-select></div><div class="settings-section"><div class="settings-label"><strong>推理级别</strong><span>控制任务的思考深度，下一次发送时生效。</span></div><el-select v-model="settings.reasoningEffort" class="settings-select"><el-option label="默认（跟随 Codex 配置）" value=""></el-option><el-option label="低" value="low"></el-option><el-option label="中" value="medium"></el-option><el-option label="高" value="high"></el-option><el-option label="超高" value="xhigh"></el-option></el-select></div><div class="settings-section"><div class="settings-label"><strong>工作权限</strong><span>控制 Codex 执行命令和修改文件时的授权方式。</span></div><el-select v-model="settings.approvalPolicy" class="settings-select"><el-option label="请求批准" value="on-request"></el-option><el-option label="帮我批准" value="on-failure"></el-option><el-option label="完全访问" value="never"></el-option></el-select><p class="settings-warning" v-if="settings.approvalPolicy === 'never'"><i class="el-icon-warning-outline"></i> 完全访问会允许 Codex 在工作空间中直接运行命令并修改文件，请确认你信任当前任务。</p><p class="settings-note">策略对新建会话生效，当前会话不会被中断。</p></div><div class="dialog-footer"><el-button type="danger" plain icon="el-icon-switch-button" @click="logout">退出登录</el-button><span class="dialog-footer-spacer"></span><el-button @click="settingsDialog = false">取消</el-button><el-button type="primary" icon="el-icon-check" :loading="settingsSaving" @click="saveSettings">保存设置</el-button></div></el-dialog>
    <el-dialog title="需要审批" :visible.sync="approvalDialog" width="540px" custom-class="approval-dialog" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false"><div class="approval-intro"><span class="approval-icon"><i class="el-icon-warning-outline"></i></span><div><strong>Codex 请求执行操作</strong><p>请确认是否允许这次操作继续。</p></div></div><div class="approval-command"><code>{{ approvalCommand || '未提供操作详情' }}</code></div><div class="dialog-footer"><el-button @click="respondApproval('decline')">拒绝</el-button><el-button type="primary" @click="respondApproval('accept')">允许一次</el-button><el-button type="success" @click="respondApproval('acceptForSession')">本次会话允许</el-button></div></el-dialog>
    <div v-if="diffDialog" class="diff-modal" @click.self="diffDialog = false"><section class="diff-dialog"><header class="diff-dialog-header"><strong>{{ selectedFile || '文件修改' }}</strong><button class="diff-dialog-close" type="button" title="关闭 Diff" @click="diffDialog = false"><i class="el-icon-close"></i></button></header><div class="diff-dialog-body"><div v-if="diffLines.length" class="diff-code"><div v-for="(line, index) in diffLines" :key="line.key || `dialog-${index}-${line.text}`" class="diff-line" :class="`diff-line-${line.type}`"><button v-if="line.type === 'collapsed'" type="button" class="diff-expand-button" @click="expandDiffSection(line)"><i class="el-icon-more"></i><span>展开隐藏的 {{ line.hiddenCount }} 行</span></button><template v-else><span class="diff-line-marker">{{ line.marker }}</span><span class="diff-line-number">{{ line.oldLine || '' }}</span><span class="diff-line-number">{{ line.newLine || '' }}</span><span class="diff-line-content">{{ line.content }}</span></template></div></div><div v-else-if="diffLoading" class="inspector-empty"><i class="el-icon-loading"></i><span>正在加载修改...</span></div><div v-else class="inspector-empty"><i class="el-icon-document"></i><span>没有可显示的修改</span></div></div></section></div>
    <div v-if="fileDialog" class="diff-modal" @click.self="fileDialog = false"><section class="diff-dialog file-dialog"><header class="diff-dialog-header"><strong>{{ fileContent ? fileContent.path : '文件预览' }}</strong><button class="diff-dialog-close" type="button" title="关闭文件预览" @click="fileDialog = false"><i class="el-icon-close"></i></button></header><div class="diff-dialog-body file-dialog-body"><div v-if="filePreviewLoading" class="inspector-empty"><i class="el-icon-loading"></i><span>正在加载文件...</span></div><pre v-else-if="fileContent">{{ fileContent.content }}</pre><div v-else class="inspector-empty"><i class="el-icon-document"></i><span>没有可显示的内容</span></div></div></section></div>
  </div>
  </div>
</template>

<script>
import api from './api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

export default {
  data () { return { authReady: false, authenticated: false, loginForm: { username: '', password: '' }, loginLoading: false, loginError: '', authSyncInFlight: false, projects: [], sessions: [], currentProject: null, currentSession: null, runtime: { running: false }, runtimeTimer: null, settings: { approvalPolicy: 'on-request', model: '', reasoningEffort: '' }, settingsDialog: false, settingsSaving: false, diffDialog: false, diffLoading: false, fileDialog: false, filePreviewLoading: false, socket: null, socketOpen: false, socketRetryTimer: null, socketHealthTimer: null, lastSocketActivityAt: 0, leftCollapsed: false, rightCollapsed: true, activeTab: 'changes', tabs: [{ id: 'changes', label: 'Changes' }, { id: 'files', label: 'Files' }], messages: [], itemPhases: {}, activities: [], liveStatus: '', draft: '', lastDraft: '', sending: false, deletingQueueId: null, running: false, activeTurnText: '', errorMessage: '', gitFiles: [], currentBranch: '', branches: [], diffText: '', diffExpandedSections: {}, selectedFile: '', fileContent: null, fileItems: [], fileChildrenCache: {}, fileLoadingPaths: {}, fileTreeGeneration: 0, expandedPaths: {}, workspaceDialog: false, workspaceRoots: [], workspaceItems: [], workspacePath: '', workspaceParent: '', selectedWorkspace: '', approvalDialog: false, approvalRequestId: null, approvalCommand: '', composerFocused: false, showArchived: false, sessionSearch: '', attachments: [], statusTimer: null, statusSyncInFlight: false, sessionLoadController: null, sessionLoadGeneration: 0, markdownCache: null, lastEventId: '', seenEventIds: {}, followOutput: true } },
  computed: {
    visibleSessions () {
      const query = this.sessionSearch.trim().toLowerCase()
      return this.sessions
        .filter(s => (this.showArchived || !s.archived) && (!query || `${s.title} ${s.lastUserMessage || ''}`.toLowerCase().includes(query)))
        .slice()
        .sort((a, b) => {
          const aTime = Date.parse(a.updatedAt || a.createdAt || '') || 0
          const bTime = Date.parse(b.updatedAt || b.createdAt || '') || 0
          return bTime - aTime
        })
    },
    canSteer () { return !!(this.currentSession && this.currentSession.status === 'RUNNING' && this.currentSession.currentTurnId && this.currentSession.steeringAvailable !== false) },
    displayMessages () {
      const result = []
      let thinking = null
      this.messages.forEach(message => {
        if (message.role === 'assistant' && message.phase !== 'final_answer') {
          if (!thinking) {
            thinking = { id: `thinking-${message.id}`, role: 'thinking', text: '', streaming: false, thinkingOpen: false, sourceIds: [] }
            result.push(thinking)
          }
          if (thinking.text && message.text) thinking.text += '\n\n'
          thinking.text += message.text || ''
          thinking.streaming = thinking.streaming || !!message.streaming
          thinking.thinkingOpen = thinking.thinkingOpen || !!message.thinkingOpen
          thinking.sourceIds.push(message.id)
        } else {
          thinking = null
          result.push(message)
        }
      })
      return result
    },
    diffLines () {
      const source = String(this.diffText || '')
      if (!source) return []
      const lines = source.split(/\r?\n/)
      if (lines[lines.length - 1] === '') lines.pop()
      const parsed = []
      let oldLine = null
      let newLine = null
      let inHunk = false
      lines.forEach((text, index) => {
        const hunk = text.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/)
        if (hunk) {
          oldLine = Number(hunk[1])
          newLine = Number(hunk[3])
          inHunk = true
          return
        }
        if (!inHunk || text.startsWith('diff ') || text.startsWith('index ') || text.startsWith('--- ') || text.startsWith('+++ ') || text.startsWith('new file ') || text.startsWith('deleted file ') || text.startsWith('similarity ') || text.startsWith('rename ') || text.startsWith('Binary files') || text.startsWith('\\ No newline')) {
          return
        }
        if (text.startsWith('+')) {
          parsed.push({ type: 'added', text, marker: '+', oldLine: null, newLine, content: text.slice(1), index })
          newLine += 1
          return
        }
        if (text.startsWith('-')) {
          parsed.push({ type: 'deleted', text, marker: '-', oldLine, newLine: null, content: text.slice(1), index })
          oldLine += 1
          return
        }
        if (text.startsWith(' ')) {
          parsed.push({ type: 'context', text, marker: ' ', oldLine, newLine, content: text.slice(1), index })
          oldLine += 1
          newLine += 1
        }
      })
      const changedIndexes = parsed.reduce((indexes, line, index) => {
        if (line.type === 'added' || line.type === 'deleted') indexes.push(index)
        return indexes
      }, [])
      if (!changedIndexes.length) return []
      const visible = new Set()
      changedIndexes.forEach(index => {
        const start = Math.max(0, index - 3)
        const end = Math.min(parsed.length - 1, index + 3)
        for (let cursor = start; cursor <= end; cursor += 1) visible.add(cursor)
      })
      const rows = []
      let cursor = 0
      while (cursor < parsed.length) {
        if (visible.has(cursor)) {
          rows.push(parsed[cursor])
          cursor += 1
          continue
        }
        const start = cursor
        while (cursor < parsed.length && !visible.has(cursor)) cursor += 1
        const end = cursor - 1
        const key = `${this.selectedFile}:${start}-${end}`
        if (this.diffExpandedSections[key]) rows.push(...parsed.slice(start, end + 1))
        else rows.push({ type: 'collapsed', key, hiddenCount: end - start + 1, text: '', marker: '', oldLine: null, newLine: null, content: '' })
      }
      return rows
    }
  },
  mounted () { this.checkAuth() },
  beforeDestroy () { this.closeSocket(); if (this.sessionLoadController) this.sessionLoadController.abort(); this.stopStatusPolling(); this.stopRuntimePolling() },
  methods: {
    async checkAuth () {
      try {
        const explicitlyLoggedOut = localStorage.getItem('codex-web-explicit-logout') === 'true'
        if (explicitlyLoggedOut) {
          this.authenticated = false
          const saved = this.loadSavedCredentials()
          if (saved) {
            this.loginForm.username = saved.username
            this.loginForm.password = saved.password
          }
          return
        }
        await api.authMe()
        this.authenticated = true
        await this.refreshAll()
      } catch (e) {
        this.authenticated = false
        const saved = this.loadSavedCredentials()
        if (saved) {
          this.loginForm.username = saved.username
          this.loginForm.password = saved.password
          await this.submitLogin()
        }
      } finally {
        this.authReady = true
      }
    },
    async submitLogin () {
      if (this.loginLoading) return
      this.loginLoading = true
      this.loginError = ''
      try {
        await api.login(this.loginForm)
        try { localStorage.removeItem('codex-web-explicit-logout') } catch (e) {}
        this.saveCredentials()
        this.authenticated = true
        this.loginForm.password = ''
        await this.refreshAll()
      } catch (e) {
        this.authenticated = false
        this.loginError = e && e.response && e.response.data && e.response.data.message ? e.response.data.message : '登录失败，请检查用户名和密码'
      } finally {
        this.loginLoading = false
        this.authReady = true
      }
    },
    loadSavedCredentials () {
      try {
        const value = localStorage.getItem('codex-web-login')
        if (!value) return null
        const saved = JSON.parse(value)
        return saved && saved.username && saved.password ? saved : null
      } catch (e) {
        return null
      }
    },
    saveCredentials () {
      try {
        localStorage.setItem('codex-web-login', JSON.stringify({ username: this.loginForm.username, password: this.loginForm.password }))
      } catch (e) {}
    },
    async logout () {
      try { await api.logout() } catch (e) {}
      try { localStorage.setItem('codex-web-explicit-logout', 'true') } catch (e) {}
      this.settingsDialog = false
      this.closeSocket()
      this.stopStatusPolling()
      this.stopRuntimePolling()
      this.authenticated = false
      const saved = this.loadSavedCredentials()
      if (saved) {
        this.loginForm.username = saved.username
        this.loginForm.password = saved.password
      }
      this.currentProject = null
      this.currentSession = null
      this.projects = []
      this.sessions = []
      this.messages = []
    },
    async refreshAll () { try { const [projects, runtime, settings] = await Promise.all([api.projects(), api.runtime(), api.settings()]); this.projects = projects.data; this.runtime = runtime.data; this.settings = settings.data; this.startRuntimePolling(); if (this.currentProject) { const found = this.projects.find(p => p.id === this.currentProject.id); if (found) await this.selectProject(found) } else if (this.projects.length) await this.selectProject(this.projects[0]) } catch (e) { this.notifyError(e) } },
    async selectProject (project) { this.stopStatusPolling(); this.closeSocket(); this.currentProject = project; this.currentSession = null; this.messages = []; this.sessions = []; this.leftCollapsed = true; try { const result = await api.sessions(project.id); this.sessions = result.data; await Promise.all([this.refreshGit(), this.loadFiles()]); if (this.sessions.length) await this.selectSession(this.visibleSessions[0] || this.sessions[0]) } catch (e) { this.notifyError(e) } },
    async selectSession (session) { this.stopStatusPolling(); if (this.sessionLoadController) this.sessionLoadController.abort(); const generation = this.sessionLoadGeneration + 1; this.sessionLoadGeneration = generation; const controller = new AbortController(); this.sessionLoadController = controller; this.closeSocket(); this.currentSession = session; this.lastDraft = session.lastUserMessage || ''; this.approvalDialog = false; this.approvalRequestId = null; this.approvalCommand = ''; this.followOutput = true; this.errorMessage = ''; this.messages = []; this.activities = []; this.liveStatus = ''; this.activeTurnText = ''; this.diffText = ''; this.selectedFile = ''; this.fileContent = null; this.seenEventIds = {}; this.lastEventRecoveryAt = 0; this.lastEventId = ''; this.leftCollapsed = true; try { const result = await api.history(session.id, { signal: controller.signal }); if (generation !== this.sessionLoadGeneration || !this.currentSession || this.currentSession.id !== session.id) return; const history = result.data || {}; (history.events || []).forEach(event => this.applyEvent(event, true)); this.lastEventId = history.lastEventId || this.lastEventId; this.running = this.sessionHasPendingWork(this.currentSession); if (this.running && !this.liveStatus) this.liveStatus = this.currentSession.status === 'WAITING_APPROVAL' ? '等待审批' : (this.hasQueuedTurns(this.currentSession) ? '队列处理中' : '正在思考'); this.connectSocket(session.id); const recovery = await api.events(session.id, this.lastEventId); if (generation !== this.sessionLoadGeneration || !this.currentSession || this.currentSession.id !== session.id) return; recovery.data.forEach(event => this.applyEvent(event, false)); this.startStatusPolling(session.id); this.scrollToBottomAfterRender() } catch (e) { if (e && e.code === 'ERR_CANCELED') return; if (generation === this.sessionLoadGeneration) this.notifyError(e) } finally { if (this.sessionLoadController === controller) this.sessionLoadController = null } },
    async createSession () { if (!this.currentProject) return; try { const result = await api.createSession(this.currentProject.id, { title: '新建会话' }); this.sessions.unshift(result.data); await this.selectSession(result.data) } catch (e) { this.notifyError(e) } },
    async toggleSessionArchive (session) {
      if (!session) return
      try {
        const result = session.archived ? await api.unarchive(session.id) : await api.archive(session.id)
        const updated = result.data
        const index = this.sessions.findIndex(item => item.id === updated.id)
        if (index >= 0) this.$set(this.sessions, index, updated)
        if (this.currentSession && this.currentSession.id === updated.id) this.currentSession = updated
      } catch (e) { this.notifyError(e) }
    },
    handleComposerKeydown (event) {
      if (event.key === 'Enter' && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
        event.preventDefault()
        this.sendMessage()
      }
    },
    async sendMessage () { return this.submitMessage() },
    async submitMessage () {
      if (!this.currentSession || !this.draft.trim() || this.sending) return
      const wasRunning = this.running
      const text = this.draft.trim()
      const attachmentItems = this.attachments.slice()
      const attachments = attachmentItems.map(item => item.path)
      this.followOutput = true
      this.lastDraft = text
      this.errorMessage = ''
      this.sending = true
      this.running = true
      this.liveStatus = wasRunning ? '已加入待发送' : '正在思考'
      const messageId = `user-${Date.now()}`
      if (!wasRunning) this.messages.push({ id: messageId, role: 'user', text, timestamp: new Date().toISOString() })
      this.$nextTick(this.scrollToBottom)
      try {
        const result = await api.startTurn(this.currentSession.id, { text, attachments })
        this.draft = ''
        this.attachments = []
        if (result && result.data) {
          this.currentSession = result.data
          const index = this.sessions.findIndex(item => item.id === result.data.id)
          if (index >= 0) this.$set(this.sessions, index, result.data)
        }
        this.startStatusPolling(this.currentSession.id)
      } catch (e) {
        this.messages = this.messages.filter(message => message.id !== messageId)
        this.draft = text
        this.attachments = attachmentItems
        this.running = wasRunning
        this.liveStatus = wasRunning ? '正在思考' : ''
        if (!wasRunning) this.stopStatusPolling()
        this.errorMessage = e && e.response && e.response.data ? e.response.data.message : (e.message || '任务发送失败')
        this.notifyError(e)
      } finally {
        this.sending = false
      }
    },
    async steerQueued (item) {
      if (!item || this.sending) return
      this.sending = true
      try {
        const result = await api.steerQueued(this.currentSession.id, item.id)
        if (result && result.data) this.currentSession = result.data
        this.liveStatus = '正在引导当前任务'
        this.startStatusPolling(this.currentSession.id)
      } catch (e) { this.notifyError(e) } finally { this.sending = false }
    },
    async deleteQueued (item) {
      if (!item || this.deletingQueueId) return
      this.deletingQueueId = item.id
      try {
        const result = await api.deleteQueued(this.currentSession.id, item.id)
        if (result && result.data) {
          this.currentSession = result.data
          const index = this.sessions.findIndex(entry => entry.id === result.data.id)
          if (index >= 0) this.$set(this.sessions, index, result.data)
        }
      } catch (e) {
        const code = e && e.response && e.response.data && e.response.data.code
        if (code === 'QUEUE_ITEM_NOT_FOUND') {
          try {
            const result = await api.session(this.currentSession.id)
            if (result && result.data && this.currentSession && this.currentSession.id === result.data.id) {
              this.currentSession = result.data
              const index = this.sessions.findIndex(entry => entry.id === result.data.id)
              if (index >= 0) this.$set(this.sessions, index, result.data)
              if (!this.hasQueuedTurns(result.data)) return
            }
          } catch (refreshError) {}
        }
        this.notifyError(e)
      } finally { this.deletingQueueId = null }
    },
     async cancelTurn () { if (!this.currentSession) return; try { const result = await api.cancelTurn(this.currentSession.id); if (result && result.data) { this.currentSession = result.data; const index = this.sessions.findIndex(item => item.id === result.data.id); if (index >= 0) this.$set(this.sessions, index, result.data); this.running = this.sessionHasPendingWork(result.data); this.liveStatus = this.running ? '队列处理中' : '' } } catch (e) { this.notifyError(e) } },
    retryLast () { const text = (this.lastDraft || (this.currentSession && this.currentSession.lastUserMessage) || '').trim(); const busy = this.currentSession && ['RUNNING', 'WAITING_APPROVAL'].includes(this.currentSession.status); if (!text || this.sending || busy) return; this.lastDraft = text; this.draft = text; this.sendMessage() },
    stopStatusPolling () { if (this.statusTimer) { clearInterval(this.statusTimer); this.statusTimer = null } },
    startRuntimePolling () { this.stopRuntimePolling(); this.runtimeTimer = setInterval(() => this.syncRuntime(), 60000) },
    stopRuntimePolling () { if (this.runtimeTimer) { clearInterval(this.runtimeTimer); this.runtimeTimer = null } },
    async syncRuntime () { try { this.runtime = (await api.runtime()).data } catch (e) {} },
    async recoverSocketAuth () {
      if (this.authSyncInFlight || !this.authenticated) return
      this.authSyncInFlight = true
      try {
        await api.authMe()
      } catch (e) {
        if (e && e.response && e.response.status === 401) await this.checkAuth()
      } finally {
        this.authSyncInFlight = false
      }
    },
    startStatusPolling (sessionId) {
      this.stopStatusPolling()
      this.statusSyncInFlight = false
      // SSE is the primary path; poll only as a reconnect/recovery fallback.
      this.statusTimer = setInterval(() => {
        if (this.socket && this.socket.readyState === 1) this.socketOpen = true
        if (!this.socketOpen) this.syncSession(sessionId)
      }, 5000)
    },
    async syncSession (sessionId) {
      if (this.statusSyncInFlight) return
      this.statusSyncInFlight = true
      try {
        const sessionResult = await api.session(sessionId)
        if (!this.currentSession || this.currentSession.id !== sessionId) return
        const previousPending = this.sessionHasPendingWork(this.currentSession)
        this.currentSession = sessionResult.data
        const index = this.sessions.findIndex(item => item.id === sessionId)
        if (index >= 0) this.$set(this.sessions, index, sessionResult.data)
        const pending = this.sessionHasPendingWork(sessionResult.data)
        this.running = pending
        // SSE is the normal event path. Fetch history only for reconnect recovery or a terminal transition.
        const shouldRecoverEvents = !this.socketOpen || (previousPending && !pending)
        const recoveryDue = Date.now() - this.lastEventRecoveryAt > 10000
        if (shouldRecoverEvents && recoveryDue) {
          this.lastEventRecoveryAt = Date.now()
          const eventsResult = await api.events(sessionId, this.lastEventId)
          if (this.currentSession && this.currentSession.id === sessionId) eventsResult.data.forEach(event => this.applyEvent(event, false))
        }
        if (!pending) this.stopStatusPolling()
      } catch (e) {
        if (window.console) console.warn('[codex-web] 会话状态同步失败', sessionId, e)
      } finally {
        this.statusSyncInFlight = false
      }
    },
    hasQueuedTurns (session) { return !!(session && session.queuedTurns && session.queuedTurns.length) },
    sessionHasPendingWork (session) { return !!(session && (session.status === 'RUNNING' || session.status === 'WAITING_APPROVAL' || this.hasQueuedTurns(session))) },
    legacyApplyEvent (event, replay) { if (!replay && event.id && this.seenEventIds[event.id]) return; if (event.id) this.$set(this.seenEventIds, event.id, true); const type = event.type; const data = event.data || {}; const timestamp = event.timestamp || new Date().toISOString(); if (type === 'agent.message.delta') { const text = data.text || ''; let last = this.messages[this.messages.length - 1]; if (!last || last.role !== 'assistant') { last = { id: `assistant-${Date.now()}`, role: 'assistant', text: '', timestamp, streaming: true }; this.messages.push(last) } last.text += text; last.streaming = true; this.running = true } else if (type === 'turn.completed') { const last = this.messages[this.messages.length - 1]; if (last && last.role === 'assistant') last.streaming = false; this.running = false } else if (type === 'turn.cancelled') { this.running = false } else if (type === 'tool.call.started' || type === 'tool.call.output' || type === 'tool.call.completed') { const detail = data.text || (data.payload && (data.payload.command || data.payload.output)) || ''; const existing = this.activities.find(a => a.rawId === (data.payload && (data.payload.itemId || data.payload.callId))); if (existing) { existing.detail = String(detail); existing.state = type.endsWith('completed') ? '完成' : '运行中' } else this.activities.push({ id: `${type}-${Date.now()}-${Math.random()}`, rawId: data.payload && data.payload.itemId, icon: type.includes('output') ? 'el-icon-loading' : 'el-icon-cpu', title: type.endsWith('started') ? 'Codex 正在执行工具' : '工具输出', detail: String(detail).slice(0, 220), state: type.endsWith('completed') ? '完成' : '运行中' }) } else if (type === 'approval.request') { const wasWaiting = !!(this.currentSession && this.currentSession.status === 'WAITING_APPROVAL'); if (!replay && this.currentSession) this.$set(this.currentSession, 'status', 'WAITING_APPROVAL'); const payload = data.payload || {}; this.approvalRequestId = data.requestId || payload.requestId || null; this.approvalCommand = Array.isArray(payload.command) ? payload.command.join(' ') : (payload.command || payload.reason || '需要你的确认'); this.approvalDialog = !replay || wasWaiting; this.running = true } else if (type === 'approval.responded') { this.approvalDialog = false; this.approvalRequestId = null } else if (type === 'diff.updated') { const payload = data.payload || {}; this.diffText = payload.diff || data.text || this.diffText; } else if (type === 'error') { this.errorMessage = data.text || (data.payload && data.payload.message) || data.message || 'Codex 运行失败'; this.running = false } if (!replay) this.$nextTick(this.scrollToBottom) },
     closeSocket () {
       if (this.socketRetryTimer) { clearTimeout(this.socketRetryTimer); this.socketRetryTimer = null }
       if (this.socketHealthTimer) { clearInterval(this.socketHealthTimer); this.socketHealthTimer = null }
       if (this.socket) {
         this.socket.onopen = null
         this.socket.onerror = null
         this.socket.onmessage = null
         this.socket.close()
       }
       this.socket = null
       this.socketOpen = false
     },
    scheduleSocketReconnect (sessionId, source) { if (this.socketRetryTimer) clearTimeout(this.socketRetryTimer); this.socketRetryTimer = setTimeout(() => { this.socketRetryTimer = null; if (this.socket === source && !this.socketOpen) this.connectSocket(sessionId) }, 5000) },
    connectSocket (sessionId) {
      if (!sessionId) {
        this.closeSocket()
        return
      }
      if (this.socket) this.closeSocket()
      const controller = new AbortController()
       const source = { readyState: 0, closed: false, close: () => { source.closed = true; source.readyState = 2; controller.abort() } }
       this.socket = source
       this.socketOpen = false
       this.lastSocketActivityAt = Date.now()
       const handleEvent = data => {
        try {
          const event = JSON.parse(data)
          if (this.socket === source) this.socketOpen = true
          if (event.type === 'stream.ready') return
          if (event.sessionId === this.currentSession?.id) this.applyEvent(event, false)
        } catch (e) { if (window.console) console.warn('[codex-web] SSE 事件解析失败', e) }
      }
      ;(async () => {
        try {
          const response = await fetch(api.streamUrl(sessionId), { credentials: 'include', headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' }, signal: controller.signal })
          if (this.socket !== source) return
          if (!response.ok) throw new Error(`SSE HTTP ${response.status}`)
           source.readyState = 1
           this.socketOpen = true
           if (this.socketRetryTimer) { clearTimeout(this.socketRetryTimer); this.socketRetryTimer = null }
           this.socketHealthTimer = setInterval(() => {
             if (this.socket !== source || source.closed || source.readyState !== 1) return
             if (Date.now() - this.lastSocketActivityAt <= 45000) return
             source.closed = true
             source.readyState = 2
             controller.abort()
             this.socketOpen = false
             this.syncSession(sessionId)
             this.scheduleSocketReconnect(sessionId, source)
             if (window.console) console.warn('[codex-web] SSE 长时间无事件，主动恢复连接', sessionId)
           }, 15000)
           const reader = response.body.getReader()
          const decoder = new TextDecoder()
          let buffer = ''
          while (this.socket === source) {
            const part = await reader.read()
            if (part.done) break
            buffer += decoder.decode(part.value, { stream: true })
            let boundary
            while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
               const block = buffer.slice(0, boundary)
               buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '')
               if (this.socket === source) this.lastSocketActivityAt = Date.now()
               const data = block.split(/\r?\n/).filter(line => line.indexOf('data:') === 0).map(line => line.slice(5).trim()).join('\n')
              if (data) handleEvent(data)
            }
          }
          if (this.socket === source) throw new Error('SSE stream ended')
        } catch (error) {
         if (controller.signal.aborted || this.socket !== source) return
           if (this.socketHealthTimer) { clearInterval(this.socketHealthTimer); this.socketHealthTimer = null }
           source.readyState = 2
          this.socketOpen = false
          this.recoverSocketAuth()
          this.scheduleSocketReconnect(sessionId, source)
          if (window.console) console.warn('[codex-web] SSE 连接异常，等待自动重连', sessionId, error)
        }
      })()
    },
    async refreshGit () { if (!this.currentProject) return; try { const [status, branches] = await Promise.all([api.gitStatus(this.currentProject.id), api.branches(this.currentProject.id)]); this.gitFiles = status.data.files || []; this.currentBranch = status.data.branch || ''; this.branches = branches.data || [] } catch (e) { this.gitFiles = []; this.currentBranch = ''; this.branches = [] } },
    async checkoutBranch (branch) { if (!this.currentProject || !branch) return; try { const result = await api.checkout(this.currentProject.id, branch); this.currentBranch = result.data.branch || branch; await this.refreshGit(); this.$message.success('已切换分支') } catch (e) { await this.refreshGit(); this.notifyError(e) } },
    async openDiff (file, showDialog = false) { if (!this.currentProject) return; this.selectedFile = file || ''; if (showDialog) { this.diffDialog = true; this.diffLoading = true; this.diffText = ''; this.diffExpandedSections = {} } try { const result = await api.diff(this.currentProject.id, file); this.diffText = result.data.diff } catch (e) { if (showDialog) this.diffDialog = false; this.notifyError(e) } finally { if (showDialog) this.diffLoading = false } },
    expandDiffSection (section) { if (section && section.key) this.$set(this.diffExpandedSections, section.key, true) },
    async loadFiles () {
      if (!this.currentProject) return
      const generation = this.fileTreeGeneration + 1
      this.fileTreeGeneration = generation
      this.fileChildrenCache = {}
      this.fileLoadingPaths = {}
      try {
        const result = await api.files(this.currentProject.id)
        if (generation !== this.fileTreeGeneration) return
        this.fileItems = result.data.map(item => ({ ...item, depth: 0, expanded: false }))
      } catch (e) {
        if (generation === this.fileTreeGeneration) this.fileItems = []
      }
    },
    insertDirectoryChildren (item, rawChildren) {
      const index = this.fileItems.indexOf(item)
      if (index < 0) return false
      const children = rawChildren.map(child => ({ ...child, depth: item.depth + 1, expanded: false }))
      this.fileItems.splice(index + 1, 0, ...children)
      this.$set(item, 'expanded', true)
      return true
    },
    async toggleDirectory (item) {
      if (item.expanded) {
        this.fileItems = this.fileItems.filter(child => !(child.path !== item.path && child.path.startsWith(`${item.path}/`)))
        this.$set(item, 'expanded', false)
        return
      }
      if (this.fileLoadingPaths[item.path]) return
      const generation = this.fileTreeGeneration
      if (Object.prototype.hasOwnProperty.call(this.fileChildrenCache, item.path)) {
        this.insertDirectoryChildren(item, this.fileChildrenCache[item.path])
        return
      }
      this.$set(this.fileLoadingPaths, item.path, true)
      try {
        const result = await api.files(this.currentProject.id, item.path)
        if (generation !== this.fileTreeGeneration) return
        this.$set(this.fileChildrenCache, item.path, result.data)
        this.insertDirectoryChildren(item, result.data)
      } catch (e) {
        if (generation === this.fileTreeGeneration) this.notifyError(e)
      } finally {
        if (generation === this.fileTreeGeneration) this.$delete(this.fileLoadingPaths, item.path)
      }
    },
    async openFile (item) {
      if (!item || !item.viewable) return this.notifyFileUnavailable(item)
      this.fileDialog = true
      this.filePreviewLoading = true
      this.fileContent = null
      try {
        const result = await api.content(this.currentProject.id, item.path)
        this.fileContent = result.data
      } catch (e) {
        this.fileDialog = false
        this.notifyError(e)
      } finally {
        this.filePreviewLoading = false
      }
    },
    notifyFileUnavailable (item) {
      if (item && Number(item.size) > 2 * 1024 * 1024) {
        this.$message.warning('文件超过 2 MB，不能预览')
      } else {
        this.$message.warning('文件暂时无法打开')
      }
    },
    openWorkspacePicker () { this.workspaceDialog = true; this.selectedWorkspace = ''; api.roots().then(result => { this.workspaceRoots = result.data; if (this.workspaceRoots[0]) this.browse(this.workspaceRoots[0].path) }).catch(e => this.notifyError(e)) },
    async browse (path) { try { const result = await api.browse(path); this.workspacePath = path; this.workspaceItems = result.data; const normalized = path.replace(/\\/g, '/').replace(/\/+$/, ''); this.workspaceParent = /^[A-Za-z]:$/.test(normalized) || normalized === '' ? '' : normalized.slice(0, normalized.lastIndexOf('/')) || '/' ; this.selectedWorkspace = path } catch (e) { this.notifyError(e) } },
    async confirmWorkspace () { try { const result = await api.createProject({ path: this.selectedWorkspace }); this.projects = this.projects.filter(p => p.id !== result.data.id); this.projects.unshift(result.data); this.workspaceDialog = false; await this.selectProject(result.data) } catch (e) { this.notifyError(e) } },
    async createFolder () { const name = await this.ask('新建目录', 'new-project'); if (!name) return; try { const result = await api.createFolder({ parent: this.workspacePath, name }); await this.browse(this.workspacePath); this.selectedWorkspace = result.data.path } catch (e) { this.notifyError(e) } },
    async uploadFiles (event) { const files = Array.from(event.target.files || []); for (const file of files) { try { const result = await api.upload(this.currentSession.id, file); this.attachments.push(result.data) } catch (e) { this.notifyError(e) } } event.target.value = '' },
    async editProject (project) { const name = await this.ask('项目名称', project.name); if (name) { const result = await api.updateProject(project.id, { name }); this.projects = this.projects.map(item => item.id === project.id ? result.data : item); if (this.currentProject && this.currentProject.id === project.id) this.currentProject = result.data } },
    async deleteProject (project) {
      try {
        await this.$confirm(`确定删除项目“${project.name}”吗？这会移除项目记录和本地会话记录，但不会删除工作空间文件。`, '删除项目', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
        const selected = this.currentProject && this.currentProject.id === project.id
        if (selected) { this.stopStatusPolling(); this.closeSocket() }
        await api.deleteProject(project.id)
        this.projects = this.projects.filter(item => item.id !== project.id)
        if (selected) {
          this.currentProject = null
          this.currentSession = null
          this.sessions = []
          this.messages = []
          if (this.projects.length) await this.selectProject(this.projects[0])
        }
      } catch (e) {
        if (e !== 'cancel' && e !== 'close') this.notifyError(e)
      }
    },
     openSettings () { this.settingsDialog = true },
     async saveSettings () { this.settingsSaving = true; try { this.settings = (await api.updateSettings(this.settings)).data; this.settingsDialog = false; this.$message.success('设置已保存，新建会话时生效') } catch (e) { this.notifyError(e) } finally { this.settingsSaving = false } },
    async ask (title, value) { return new Promise(resolve => { this.$prompt('', title, { inputValue: value, confirmButtonText: '确定', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '请输入内容' }).then(result => resolve(result.value)).catch(() => resolve('')) }) },
    statusClass (status) { return { running: 'running', waiting: 'waiting', failed: 'failed', completed: 'completed', archived: 'archived' }[(status || '').toLowerCase()] || 'idle' },
    statusText (status) { return { CREATED: '等待发送任务', IDLE: '空闲', RUNNING: '正在运行', WAITING_APPROVAL: '等待审批', COMPLETED: '已完成', FAILED: '运行失败', CANCELLED: '已停止', ARCHIVED: '已归档' }[status] || status },
    compactPath (path) { return path && path.length > 27 ? `...${path.slice(-24)}` : path },
    formatFileSize (size) {
      const value = Number(size)
      if (!Number.isFinite(value) || value < 0) return ''
      if (value < 1024) return `${Math.round(value)} B`
      const units = ['KB', 'MB', 'GB']
      let amount = value
      let index = -1
      while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1 }
      const formatted = amount >= 10 ? Math.round(amount) : Number(amount.toFixed(1))
      return `${formatted} ${units[index]}`
    },
    formatTime (value) { try { return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) } catch (e) { return '' } },
    formatSessionTime (value) {
      try {
        const date = new Date(value)
        if (Number.isNaN(date.getTime())) return ''
        const now = new Date()
        const sameDay = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
        return sameDay ? this.formatTime(value) : `${date.toLocaleDateString([], { month: '2-digit', day: '2-digit' })} ${this.formatTime(value)}`
      } catch (e) { return '' }
    },
    renderMarkdown (text) { const value = text || ''; if (!this.markdownCache) this.markdownCache = new Map(); if (this.markdownCache.has(value)) return this.markdownCache.get(value); const html = DOMPurify.sanitize(marked.parse(value, { headerIds: false, mangle: false })); this.markdownCache.set(value, html); if (this.markdownCache.size > 128) this.markdownCache.delete(this.markdownCache.keys().next().value); return html },
    handleMessageScroll () {
      const box = this.$refs.messageScroll
      if (!box) return
      const distanceFromBottom = box.scrollHeight - box.clientHeight - box.scrollTop
      this.followOutput = distanceFromBottom <= 48
    },
    scrollToBottom () {
      const box = this.$refs.messageScroll
      if (!box || !this.followOutput) return
      box.scrollTo({ top: box.scrollHeight, behavior: 'auto' })
    },
    scrollToBottomAfterRender () { this.$nextTick(() => { this.scrollToBottom(); requestAnimationFrame(() => { this.scrollToBottom(); requestAnimationFrame(() => this.scrollToBottom()); setTimeout(() => this.scrollToBottom(), 100) }) }) },
    async respondApproval (decision) { try { await api.respondApproval(this.currentSession.id, { requestId: this.approvalRequestId, decision }) } catch (e) { this.notifyError(e) } finally { this.approvalDialog = false; this.approvalRequestId = null } },
    setThinkingOpen (group, event) { const open = event.target.open; (group.sourceIds || []).forEach(id => { const message = this.messages.find(item => item.id === id); if (message) this.$set(message, 'thinkingOpen', open) }) },
    notifyError (error) { const message = error && error.response && error.response.data ? error.response.data.message : (error.message || '请求失败'); this.$message.error(message) }
    ,applyEvent (event, replay) {
      if (!event) return
      if (event.id && this.seenEventIds[event.id]) return
      if (event.id) { this.$set(this.seenEventIds, event.id, true); this.lastEventId = event.id }
      const type = event.type
      const data = event.data || {}
      const timestamp = event.timestamp || new Date().toISOString()
      if (type === 'turn.accepted') {
        if (this.currentSession) {
          this.$set(this.currentSession, 'currentTurnId', data.turnId || null)
          this.$set(this.currentSession, 'status', 'RUNNING')
        }
      } else if (type === 'turn.queued') {
        const text = data.text || ''
        this.running = true
        this.liveStatus = ''
      } else if (type === 'turn.steered') {
        const text = data.text || ''
        const last = this.messages[this.messages.length - 1]
        if (text && (!last || last.role !== 'user' || last.text !== text)) this.messages.push({ id: `user-event-${event.id || Date.now()}`, role: 'user', text, timestamp })
        this.running = true
        this.liveStatus = '正在思考'
      } else if (type === 'turn.started') {
        if (this.currentSession) this.$set(this.currentSession, 'status', 'RUNNING')
        const text = data.text || ''
        if (text) this.lastDraft = text
        if (!replay && this.currentSession && Array.isArray(this.currentSession.queuedTurns)) {
          const queuedIndex = data.queueId ? this.currentSession.queuedTurns.findIndex(item => item.id === data.queueId) : -1
          if (queuedIndex >= 0) this.currentSession.queuedTurns.splice(queuedIndex, 1)
        }
        const last = this.messages[this.messages.length - 1]
        if (text && (!last || last.role !== 'user' || last.text !== text)) this.messages.push({ id: `user-event-${event.id || Date.now()}`, role: 'user', text, timestamp })
        this.activeTurnText = text || this.activeTurnText
        this.running = true
        this.liveStatus = '正在思考'
      } else if (type === 'agent.message.delta') {
        const text = data.text || ''
        const payload = data.payload || {}
        const itemId = data.itemId || payload.itemId || (payload.item && payload.item.id) || ''
        const phase = data.phase || (payload.item && payload.item.phase) || this.itemPhases[itemId] || 'commentary'
        let last = this.messages[this.messages.length - 1]
        if (!last || last.role !== 'assistant' || !last.streaming || (itemId && last.itemId && last.itemId !== itemId)) {
          last = { id: `assistant-${itemId || Date.now()}-${Math.random()}`, role: 'assistant', text: '', timestamp, streaming: true, itemId, phase, thinkingOpen: phase !== 'final_answer' }
          this.messages.push(last)
        }
        if (!last.itemId && itemId) this.$set(last, 'itemId', itemId)
        if (!last.phase && phase) this.$set(last, 'phase', phase)
        if (phase !== 'final_answer') this.$set(last, 'thinkingOpen', true)
        if (phase === 'final_answer') this.messages.forEach(message => { if (message.role === 'assistant' && message.phase !== 'final_answer') this.$set(message, 'thinkingOpen', false) })
        last.text += text
        last.streaming = true
        this.running = true
        this.errorMessage = ''
        this.liveStatus = '正在整理回复'
      } else if (type === 'turn.completed') {
        if (this.currentSession) {
          this.$set(this.currentSession, 'currentTurnId', null)
          this.$set(this.currentSession, 'status', 'COMPLETED')
        }
        this.messages.forEach(message => {
          if (message.role === 'assistant' && message.streaming) message.streaming = false
          if (message.role === 'assistant' && message.phase !== 'final_answer') this.$set(message, 'thinkingOpen', false)
        })
        const queuedTurnCount = Number(data.queuedTurnCount || 0)
        this.running = queuedTurnCount > 0 || this.hasQueuedTurns(this.currentSession)
        this.activeTurnText = ''
        this.errorMessage = ''
        if (!replay && queuedTurnCount === 0 && this.currentSession && Array.isArray(this.currentSession.queuedTurns)) this.currentSession.queuedTurns.splice(0, this.currentSession.queuedTurns.length)
        this.liveStatus = this.running ? '队列处理中' : ''
      } else if (type === 'turn.cancelled') {
        if (this.currentSession) this.$set(this.currentSession, 'status', 'CANCELLED')
        this.running = this.hasQueuedTurns(this.currentSession)
        this.activeTurnText = ''
        this.liveStatus = this.running ? '队列处理中' : ''
      } else if (type === 'turn.steer.unavailable') {
        if (this.currentSession) this.$set(this.currentSession, 'steeringAvailable', false)
      } else if (type === 'turn.queue.error') {
        this.errorMessage = '排队任务启动失败，请检查 Codex 运行状态后重试'
        this.liveStatus = this.hasQueuedTurns(this.currentSession) ? '队列处理中' : ''
      } else if (type === 'turn.retrying') {
        if (this.currentSession) this.$set(this.currentSession, 'status', 'RUNNING')
        this.running = true
        this.liveStatus = '正在重新连接 Codex'
      } else if (type === 'tool.call.started' || type === 'tool.call.output' || type === 'tool.call.completed') {
        const detail = data.text || (data.payload && (data.payload.command || data.payload.output)) || ''
        const payload = data.payload || {}
        const item = payload.item || {}
        const rawId = data.itemId || payload.itemId || item.id || payload.callId
        const phase = data.phase || item.phase || ''
        if (item.type === 'agentMessage' && rawId && phase) {
          this.$set(this.itemPhases, rawId, phase)
          const message = this.messages.find(entry => entry.itemId === rawId)
          if (message) {
            this.$set(message, 'phase', phase)
            if (phase !== 'final_answer') this.$set(message, 'thinkingOpen', true)
          }
        }
        const existing = rawId && this.activities.find(activity => activity.rawId === rawId)
        if (existing) {
          existing.detail = String(detail)
          existing.state = type.endsWith('completed') ? '完成' : '运行中'
        } else {
          this.activities.push({ id: `${type}-${Date.now()}-${Math.random()}`, rawId, icon: 'el-icon-cpu', title: '操作', detail: String(detail).slice(0, 220), state: type.endsWith('completed') ? '完成' : '运行中' })
        }
        this.running = true
        this.liveStatus = type.endsWith('completed') ? '正在整理回复' : '正在执行操作'
      } else if (type === 'approval.request') {
        const wasWaiting = !!(this.currentSession && this.currentSession.status === 'WAITING_APPROVAL')
        if (!replay && this.currentSession) this.$set(this.currentSession, 'status', 'WAITING_APPROVAL')
        const payload = data.payload || {}
        this.approvalRequestId = data.requestId !== undefined && data.requestId !== null
          ? data.requestId
          : (payload.requestId !== undefined && payload.requestId !== null ? payload.requestId : null)
        this.approvalCommand = Array.isArray(payload.command) ? payload.command.join(' ') : (payload.command || payload.reason || '需要你的确认')
        this.approvalDialog = !replay || wasWaiting
        this.running = true
        this.liveStatus = '等待审批'
      } else if (type === 'approval.responded') {
        this.approvalDialog = false
        this.approvalRequestId = null
        if (this.currentSession && this.currentSession.status === 'WAITING_APPROVAL') this.$set(this.currentSession, 'status', 'RUNNING')
      } else if (type === 'diff.updated') {
        const payload = data.payload || {}
        this.diffText = payload.diff || data.text || this.diffText
      } else if (type === 'error') {
        if (data.payload && data.payload.willRetry) {
          if (this.currentSession) this.$set(this.currentSession, 'status', 'RUNNING')
          this.running = true
          this.liveStatus = '正在重新连接 Codex'
          return
        }
        if (this.currentSession) this.$set(this.currentSession, 'status', 'FAILED')
        this.errorMessage = data.text || (data.payload && data.payload.message) || data.message || 'Codex 运行失败'
        this.running = this.hasQueuedTurns(this.currentSession)
        this.liveStatus = this.running ? '队列处理中' : ''
      }
      if (!replay) this.$nextTick(this.scrollToBottom)
    }
  }
}
</script>
