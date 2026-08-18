<template>
  <div class="shell" :class="{ 'left-collapsed': leftCollapsed, 'right-collapsed': rightCollapsed }">
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
        <el-button class="icon-button" icon="el-icon-refresh" circle title="刷新" @click="refreshAll"></el-button>
        <el-button class="icon-button" icon="el-icon-setting" circle title="运行时设置" @click="runtimeAction"></el-button>
      </div>
    </header>

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
            <i class="el-icon-more project-more" @click.stop="editProject(project)"></i>
          </button>
        </div>
        <div class="empty-side" v-else><i class="el-icon-folder-opened"></i><span>还没有工作空间</span><el-button size="mini" @click="openWorkspacePicker">选择目录</el-button></div>

        <div class="session-heading"><span>会话</span><el-button type="text" icon="el-icon-plus" :disabled="!currentProject" title="新建会话" @click="createSession"></el-button></div><div class="session-search"><i class="el-icon-search"></i><input v-model="sessionSearch" placeholder="搜索会话"></div>
        <div class="session-list" v-if="sessions.length">
          <button v-for="session in visibleSessions" :key="session.id" class="session-row" :class="{ active: currentSession && currentSession.id === session.id }" @click="selectSession(session)">
            <span class="session-status" :class="statusClass(session.status)"></span>
            <span class="session-copy"><strong>{{ session.title }}</strong><small>{{ session.lastUserMessage || statusText(session.status) }}</small></span>
            <i v-if="session.archived" class="el-icon-box"></i>
          </button>
        </div>
        <div class="empty-side compact" v-else><span>{{ currentProject ? '新建一个会话开始工作' : '先选择工作空间' }}</span></div>
        <div class="left-footer"><span class="connection-state"><span class="status-dot" :class="socketOpen ? 'green' : 'gray'"></span>{{ socketOpen ? '实时连接' : '正在重连' }}</span><button class="footer-link" @click="showArchived = !showArchived">{{ showArchived ? '隐藏归档' : '显示归档' }}</button></div>
      </aside>

      <main class="conversation panel-border">
        <div class="conversation-header" v-if="currentSession">
          <div><h1>{{ currentSession.title }}</h1><p>{{ currentProject ? currentProject.path : '' }}</p></div>
          <div class="conversation-tools"><el-button icon="el-icon-edit-outline" circle title="重命名" @click="renameSession"></el-button><el-button icon="el-icon-download" circle title="导出会话" @click="exportSession"></el-button><el-button icon="el-icon-box" circle :title="currentSession.archived ? '取消归档' : '归档'" @click="toggleArchive"></el-button></div>
        </div>
        <div class="conversation-empty" v-else><div class="empty-glyph">C</div><h2>准备开始</h2><p>选择一个工作空间，创建会话，然后把任务交给 Codex。</p><el-button type="primary" icon="el-icon-folder-opened" @click="openWorkspacePicker">选择工作空间</el-button></div>
        <div class="message-scroll" ref="messageScroll" v-if="currentSession">
          <div v-if="!messages.length" class="first-prompt"><span class="prompt-kicker">{{ currentProject ? currentProject.name : 'Codex' }}</span><h2>你想处理什么？</h2><p>可以从查看项目结构、解释代码或修改功能开始。</p></div>
          <div v-for="(message, index) in messages" :key="message.id || index" class="message-block" :class="message.role">
            <div class="message-meta"><span class="avatar" :class="message.role">{{ message.role === 'user' ? '你' : 'C' }}</span><strong>{{ message.role === 'user' ? '你' : 'Codex' }}</strong><span>{{ formatTime(message.timestamp) }}</span></div>
            <div v-if="message.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(message.text)"></div>
            <div v-else class="user-message">{{ message.text }}</div>
            <span v-if="message.streaming" class="typing-cursor"></span>
          </div>
          <div v-if="running && liveStatus" class="assistant-status" role="status" aria-live="polite"><i class="el-icon-loading"></i><span>{{ liveStatus }}</span></div>
          <div v-if="errorMessage" class="error-banner"><i class="el-icon-warning-outline"></i><span>{{ errorMessage }}</span><el-button size="mini" @click="retryLast">重试</el-button></div>
        </div>
        <div class="composer" v-if="currentSession">
        <div class="composer-shell" :class="{ focus: composerFocused }"><textarea v-model="draft" rows="3" placeholder="描述你希望 Codex 完成的任务..." @focus="composerFocused = true" @blur="composerFocused = false" @keydown.ctrl.enter.prevent="sendMessage" @keydown.meta.enter.prevent="sendMessage"></textarea><div class="composer-actions"><span class="composer-hint">{{ socketOpen ? 'Ctrl / Cmd + Enter 发送' : '正在连接 Codex...' }}<span v-if="attachments.length"> · {{ attachments.length }} 个附件</span></span><div><input ref="upload" type="file" hidden multiple @change="uploadFiles"><el-button class="icon-button" icon="el-icon-paperclip" circle title="上传文件" @click="$refs.upload.click()"></el-button><el-button class="stop-button" v-if="running" icon="el-icon-video-pause" @click="cancelTurn">停止</el-button><el-button type="primary" icon="el-icon-position" :loading="sending" :disabled="!draft.trim() || running" @click="sendMessage">发送</el-button></div></div></div>
        </div>
      </main>

      <aside class="right-panel panel-border">
        <div class="inspector-tabs"><button v-for="tab in tabs" :key="tab.id" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}<span v-if="tab.id === 'changes' && gitFiles.length">{{ gitFiles.length }}</span></button></div>
        <div class="inspector-content" v-if="currentProject">
          <div v-if="activeTab === 'changes'" class="change-view"><div class="inspector-toolbar"><strong>工作区变更</strong><el-button type="text" icon="el-icon-refresh" title="刷新 Git 状态" @click="refreshGit"></el-button></div><div v-if="gitFiles.length" class="change-list"><button v-for="file in gitFiles" :key="file.path" class="change-row" @click="openDiff(file.path)"><span class="change-kind" :class="file.kind">{{ file.code.trim() || 'M' }}</span><span>{{ file.path }}</span></button></div><div v-else class="inspector-empty"><i class="el-icon-check"></i><span>工作区干净</span></div></div>
          <div v-else-if="activeTab === 'diff'" class="diff-view"><div class="inspector-toolbar"><strong>{{ selectedFile || '全部 Diff' }}</strong><el-button type="text" icon="el-icon-refresh" title="刷新 Diff" @click="openDiff(selectedFile)"></el-button></div><pre v-if="diffText" class="diff-code">{{ diffText }}</pre><div v-else class="inspector-empty"><i class="el-icon-document"></i><span>没有可显示的 Diff</span></div></div>
          <div v-else class="files-view"><div class="inspector-toolbar"><strong>文件</strong><el-button type="text" icon="el-icon-refresh" title="刷新文件树" @click="loadFiles"></el-button></div><div class="file-tree"><div v-for="item in fileItems" :key="item.path" class="file-row" :style="{ paddingLeft: `${10 + item.depth * 16}px` }" @click="item.directory ? toggleDirectory(item) : openFile(item.path)"><i :class="item.directory ? (item.expanded ? 'el-icon-folder-opened' : 'el-icon-folder') : 'el-icon-document'"></i><span>{{ item.name }}</span></div></div><div v-if="fileContent" class="file-preview"><div class="preview-title">{{ fileContent.path }}</div><pre>{{ fileContent.binary ? '[二进制文件不可预览]' : fileContent.content }}</pre></div></div>
        </div>
        <div v-else class="inspector-empty full"><i class="el-icon-s-operation"></i><span>选择项目后查看代码状态</span></div>
      </aside>
    </div>

    <el-dialog title="选择工作空间" :visible.sync="workspaceDialog" width="600px" custom-class="workspace-dialog"><div class="picker-path"><el-button icon="el-icon-back" circle :disabled="!workspaceParent" @click="browse(workspaceParent)"></el-button><span>{{ workspacePath }}</span><el-button type="text" icon="el-icon-folder-add" title="新建目录" @click="createFolder"></el-button></div><div class="root-switch"><button v-for="root in workspaceRoots" :key="root.path" :class="{ active: workspacePath === root.path }" @click="browse(root.path)"><i class="el-icon-folder"></i>{{ root.name }}</button></div><div class="browser-list"><button v-for="item in workspaceItems" :key="item.path" class="browser-row" :class="{ selected: selectedWorkspace === item.path }" @dblclick="browse(item.path)" @click="selectedWorkspace = item.path"><i :class="item.isGitRepository ? 'el-icon-connection' : 'el-icon-folder'"></i><span>{{ item.name }}</span><i class="el-icon-arrow-right"></i></button><div v-if="!workspaceItems.length" class="browser-empty">此目录没有子目录</div></div><div class="dialog-footer"><span class="selected-path">{{ selectedWorkspace || '双击进入目录，或选择当前目录' }}</span><el-button @click="workspaceDialog = false">取消</el-button><el-button type="primary" icon="el-icon-check" :disabled="!selectedWorkspace" @click="confirmWorkspace">选择此目录</el-button></div></el-dialog>
    <el-dialog title="审批请求" :visible.sync="approvalDialog" width="540px" custom-class="approval-dialog" :close-on-click-modal="false"><div class="approval-intro"><span class="approval-icon"><i class="el-icon-lock"></i></span><div><strong>Codex 请求执行操作</strong><p>请确认这项操作是否可以继续。</p></div></div><div class="approval-command"><code>{{ approvalCommand }}</code></div><div class="dialog-footer"><el-button @click="respondApproval('decline')">拒绝</el-button><el-button type="primary" @click="respondApproval('accept')">允许一次</el-button><el-button type="success" @click="respondApproval('acceptForSession')">本会话允许</el-button></div></el-dialog>
  </div>
</template>

<script>
import api from './api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

export default {
  data () { return { projects: [], sessions: [], currentProject: null, currentSession: null, runtime: { running: false }, socket: null, socketOpen: false, leftCollapsed: false, rightCollapsed: false, activeTab: 'changes', tabs: [{ id: 'changes', label: 'Changes' }, { id: 'diff', label: 'Diff' }, { id: 'files', label: 'Files' }], messages: [], activities: [], liveStatus: '', draft: '', lastDraft: '', sending: false, running: false, errorMessage: '', gitFiles: [], currentBranch: '', branches: [], diffText: '', selectedFile: '', fileItems: [], fileContent: null, expandedPaths: {}, workspaceDialog: false, workspaceRoots: [], workspaceItems: [], workspacePath: '', workspaceParent: '', selectedWorkspace: '', approvalDialog: false, approvalRequestId: null, approvalCommand: '', composerFocused: false, showArchived: false, sessionSearch: '', attachments: [], statusTimer: null, seenEventIds: {} } },
  computed: { visibleSessions () { const query = this.sessionSearch.trim().toLowerCase(); return this.sessions.filter(s => (this.showArchived || !s.archived) && (!query || `${s.title} ${s.lastUserMessage || ''}`.toLowerCase().includes(query))) } },
  mounted () { this.connectSocket(); this.refreshAll() },
  beforeDestroy () { if (this.socket) this.socket.close(); this.stopStatusPolling() },
  methods: {
    async refreshAll () { try { const [projects, runtime] = await Promise.all([api.projects(), api.runtime()]); this.projects = projects.data; this.runtime = runtime.data; if (this.currentProject) { const found = this.projects.find(p => p.id === this.currentProject.id); if (found) await this.selectProject(found) } else if (this.projects.length) await this.selectProject(this.projects[0]) } catch (e) { this.notifyError(e) } },
    async selectProject (project) { this.stopStatusPolling(); this.closeSocket(); this.currentProject = project; this.currentSession = null; this.messages = []; this.sessions = []; this.leftCollapsed = true; try { const result = await api.sessions(project.id); this.sessions = result.data; await Promise.all([this.refreshGit(), this.loadFiles()]); if (this.sessions.length) await this.selectSession(this.visibleSessions[0] || this.sessions[0]) } catch (e) { this.notifyError(e) } },
    async selectSession (session) { this.stopStatusPolling(); this.currentSession = session; this.errorMessage = ''; this.messages = []; this.activities = []; this.liveStatus = ''; this.diffText = ''; this.selectedFile = ''; this.fileContent = null; this.seenEventIds = {}; this.leftCollapsed = true; try { const result = await api.events(session.id); result.data.forEach(event => this.applyEvent(event, true)); this.running = session.status === 'RUNNING' || session.status === 'WAITING_APPROVAL'; if (this.running && !this.liveStatus) this.liveStatus = session.status === 'WAITING_APPROVAL' ? '等待审批' : '正在思考'; this.connectSocket(session.id); this.startStatusPolling(session.id); this.$nextTick(this.scrollToBottom) } catch (e) { this.notifyError(e) } },
    async createSession () { if (!this.currentProject) return; try { const result = await api.createSession(this.currentProject.id, { title: '新建会话' }); this.sessions.unshift(result.data); await this.selectSession(result.data) } catch (e) { this.notifyError(e) } },
    async renameSession () { const title = await this.ask('会话名称', this.currentSession.title); if (title) { const result = await api.updateSession(this.currentSession.id, { title }); this.currentSession = result.data; const index = this.sessions.findIndex(s => s.id === result.data.id); if (index >= 0) this.$set(this.sessions, index, result.data) } },
    async toggleArchive () { try { const result = this.currentSession.archived ? await api.unarchive(this.currentSession.id) : await api.archive(this.currentSession.id); this.currentSession = result.data; const index = this.sessions.findIndex(s => s.id === result.data.id); if (index >= 0) this.$set(this.sessions, index, result.data) } catch (e) { this.notifyError(e) } },
    async exportSession () { try { const result = await api.exportSession(this.currentSession.id); const blob = new Blob([JSON.stringify(result.data, null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `${this.currentSession.title || 'session'}.json`; link.click(); URL.revokeObjectURL(url) } catch (e) { this.notifyError(e) } },
    async sendMessage () {
      if (!this.currentSession || !this.draft.trim() || this.running) return
      const text = this.draft.trim()
      const attachments = this.attachments.map(item => item.path)
      this.draft = ''
      this.attachments = []
      this.lastDraft = text
      this.errorMessage = ''
      this.sending = true
      this.running = true
      this.liveStatus = '正在思考'
      this.messages.push({ id: `user-${Date.now()}`, role: 'user', text, timestamp: new Date().toISOString() })
      this.$nextTick(this.scrollToBottom)
      try {
        await api.startTurn(this.currentSession.id, { text, attachments })
        this.startStatusPolling(this.currentSession.id)
      } catch (e) {
        this.running = false
        this.liveStatus = ''
        this.stopStatusPolling()
        this.errorMessage = e && e.response && e.response.data ? e.response.data.message : (e.message || '任务发送失败')
        this.notifyError(e)
      } finally {
        this.sending = false
      }
    },
    async cancelTurn () { if (!this.currentSession) return; try { await api.cancelTurn(this.currentSession.id) } catch (e) { this.notifyError(e) } },
    retryLast () { if (this.lastDraft && !this.running) { this.draft = this.lastDraft; this.sendMessage() } },
    stopStatusPolling () { if (this.statusTimer) { clearInterval(this.statusTimer); this.statusTimer = null } },
    startStatusPolling (sessionId) { this.stopStatusPolling(); this.statusTimer = setInterval(() => this.syncSession(sessionId), 1000) },
    async syncSession (sessionId) { try { const [sessionResult, eventsResult] = await Promise.all([api.session(sessionId), api.events(sessionId)]); if (!this.currentSession || this.currentSession.id !== sessionId) return; this.currentSession = sessionResult.data; const index = this.sessions.findIndex(item => item.id === sessionId); if (index >= 0) this.$set(this.sessions, index, sessionResult.data); eventsResult.data.forEach(event => this.applyEvent(event, false)); const active = sessionResult.data.status === 'RUNNING' || sessionResult.data.status === 'WAITING_APPROVAL'; this.running = active; if (!active) this.stopStatusPolling() } catch (e) {} },
    legacyApplyEvent (event, replay) { if (!replay && event.id && this.seenEventIds[event.id]) return; if (event.id) this.$set(this.seenEventIds, event.id, true); const type = event.type; const data = event.data || {}; const timestamp = event.timestamp || new Date().toISOString(); if (type === 'agent.message.delta') { const text = data.text || ''; let last = this.messages[this.messages.length - 1]; if (!last || last.role !== 'assistant') { last = { id: `assistant-${Date.now()}`, role: 'assistant', text: '', timestamp, streaming: true }; this.messages.push(last) } last.text += text; last.streaming = true; this.running = true } else if (type === 'turn.completed') { const last = this.messages[this.messages.length - 1]; if (last && last.role === 'assistant') last.streaming = false; this.running = false } else if (type === 'turn.cancelled') { this.running = false } else if (type === 'tool.call.started' || type === 'tool.call.output' || type === 'tool.call.completed') { const detail = data.text || (data.payload && (data.payload.command || data.payload.output)) || ''; const existing = this.activities.find(a => a.rawId === (data.payload && (data.payload.itemId || data.payload.callId))); if (existing) { existing.detail = String(detail); existing.state = type.endsWith('completed') ? '完成' : '运行中' } else this.activities.push({ id: `${type}-${Date.now()}-${Math.random()}`, rawId: data.payload && data.payload.itemId, icon: type.includes('output') ? 'el-icon-loading' : 'el-icon-cpu', title: type.endsWith('started') ? 'Codex 正在执行工具' : '工具输出', detail: String(detail).slice(0, 220), state: type.endsWith('completed') ? '完成' : '运行中' }) } else if (type === 'approval.request') { const payload = data.payload || {}; this.approvalRequestId = data.requestId; this.approvalCommand = Array.isArray(payload.command) ? payload.command.join(' ') : (payload.command || payload.reason || '需要你的确认'); this.approvalDialog = true; this.running = true } else if (type === 'diff.updated') { const payload = data.payload || {}; this.diffText = payload.diff || data.text || this.diffText; } else if (type === 'error') { this.errorMessage = data.text || (data.payload && data.payload.message) || data.message || 'Codex 运行失败'; this.running = false } if (!replay) this.$nextTick(this.scrollToBottom) },
    closeSocket () { if (this.socket) { this.socket.onopen = null; this.socket.onerror = null; this.socket.onmessage = null; this.socket.close() } this.socket = null; this.socketOpen = false },
    connectSocket (sessionId) {
      if (!sessionId) {
        this.closeSocket()
        return
      }
      if (this.socket) this.closeSocket()
      const source = new EventSource(api.streamUrl(sessionId))
      this.socket = source
      this.socketOpen = false
      source.onopen = () => { if (this.socket === source) this.socketOpen = true }
      source.onerror = () => { if (this.socket === source) this.socketOpen = false }
      source.onmessage = message => {
        try {
          const event = JSON.parse(message.data)
          if (event.type === 'stream.ready') { if (event.sessionId === this.currentSession?.id) this.socketOpen = true; return }
          if (event.sessionId === this.currentSession?.id) this.applyEvent(event, false)
        } catch (e) {}
      }
    },
    async refreshGit () { if (!this.currentProject) return; try { const [status, branches] = await Promise.all([api.gitStatus(this.currentProject.id), api.branches(this.currentProject.id)]); this.gitFiles = status.data.files || []; this.currentBranch = status.data.branch || ''; this.branches = branches.data || [] } catch (e) { this.gitFiles = []; this.currentBranch = ''; this.branches = [] } },
    async checkoutBranch (branch) { if (!this.currentProject || !branch) return; try { const result = await api.checkout(this.currentProject.id, branch); this.currentBranch = result.data.branch || branch; await this.refreshGit(); this.$message.success('已切换分支') } catch (e) { await this.refreshGit(); this.notifyError(e) } },
    async openDiff (file) { if (!this.currentProject) return; this.selectedFile = file || ''; this.activeTab = 'diff'; try { const result = await api.diff(this.currentProject.id, file); this.diffText = result.data.diff } catch (e) { this.notifyError(e) } },
    async loadFiles () { if (!this.currentProject) return; try { const result = await api.files(this.currentProject.id); this.fileItems = result.data.map(item => ({ ...item, depth: 0, expanded: false })) } catch (e) { this.fileItems = [] } },
    async toggleDirectory (item) { if (item.expanded) { this.fileItems = this.fileItems.filter(child => !(child.path !== item.path && child.path.startsWith(`${item.path}/`))); item.expanded = false; return } try { const result = await api.files(this.currentProject.id, item.path); const children = result.data.map(child => ({ ...child, depth: item.depth + 1, expanded: false })); const index = this.fileItems.indexOf(item); this.fileItems.splice(index + 1, 0, ...children); item.expanded = true } catch (e) { this.notifyError(e) } },
    async openFile (path) { try { const result = await api.content(this.currentProject.id, path); this.fileContent = result.data; this.activeTab = 'files' } catch (e) { this.notifyError(e) } },
    openWorkspacePicker () { this.workspaceDialog = true; this.selectedWorkspace = ''; api.roots().then(result => { this.workspaceRoots = result.data; if (this.workspaceRoots[0]) this.browse(this.workspaceRoots[0].path) }).catch(e => this.notifyError(e)) },
    async browse (path) { try { const result = await api.browse(path); this.workspacePath = path; this.workspaceItems = result.data; const parts = path.replace(/\\/g, '/').split('/'); this.workspaceParent = parts.length > 1 ? parts.slice(0, -1).join('/') || `${parts[0]}/` : '' ; this.selectedWorkspace = path } catch (e) { this.notifyError(e) } },
    async confirmWorkspace () { try { const result = await api.createProject({ path: this.selectedWorkspace }); this.projects = this.projects.filter(p => p.id !== result.data.id); this.projects.unshift(result.data); this.workspaceDialog = false; await this.selectProject(result.data) } catch (e) { this.notifyError(e) } },
    async createFolder () { const name = await this.ask('新建目录', 'new-project'); if (!name) return; try { const result = await api.createFolder({ parent: this.workspacePath, name }); await this.browse(this.workspacePath); this.selectedWorkspace = result.data.path } catch (e) { this.notifyError(e) } },
    async uploadFiles (event) { const files = Array.from(event.target.files || []); for (const file of files) { try { const result = await api.upload(this.currentSession.id, file); this.attachments.push(result.data) } catch (e) { this.notifyError(e) } } event.target.value = '' },
    async editProject (project) { const name = await this.ask('项目名称', project.name); if (name) { const result = await api.updateProject(project.id, { name }); this.projects = this.projects.map(item => item.id === project.id ? result.data : item); if (this.currentProject && this.currentProject.id === project.id) this.currentProject = result.data } },
    async runtimeAction () { try { if (this.runtime.running) this.runtime = (await api.runtimeStop()).data; else this.runtime = (await api.runtimeStart()).data } catch (e) { this.notifyError(e) } },
    async ask (title, value) { return new Promise(resolve => { this.$prompt('', title, { inputValue: value, confirmButtonText: '确定', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '请输入内容' }).then(result => resolve(result.value)).catch(() => resolve('')) }) },
    statusClass (status) { return { running: 'running', waiting: 'waiting', failed: 'failed', completed: 'completed', archived: 'archived' }[(status || '').toLowerCase()] || 'idle' },
    statusText (status) { return { CREATED: '等待发送任务', IDLE: '空闲', RUNNING: '正在运行', WAITING_APPROVAL: '等待审批', COMPLETED: '已完成', FAILED: '运行失败', CANCELLED: '已停止', ARCHIVED: '已归档' }[status] || status },
    compactPath (path) { return path && path.length > 27 ? `...${path.slice(-24)}` : path },
    formatTime (value) { try { return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) } catch (e) { return '' } },
    renderMarkdown (text) { return DOMPurify.sanitize(marked.parse(text || '', { headerIds: false, mangle: false })) },
    scrollToBottom () { const box = this.$refs.messageScroll; if (box) box.scrollTop = box.scrollHeight },
    async respondApproval (decision) { try { await api.respondApproval(this.currentSession.id, { requestId: this.approvalRequestId, decision }) } catch (e) { this.notifyError(e) } finally { this.approvalDialog = false; this.approvalRequestId = null } },
    notifyError (error) { const message = error && error.response && error.response.data ? error.response.data.message : (error.message || '请求失败'); this.$message.error(message) }
    ,applyEvent (event, replay) {
      if (!event) return
      if (event.id && this.seenEventIds[event.id]) return
      if (event.id) this.$set(this.seenEventIds, event.id, true)
      const type = event.type
      const data = event.data || {}
      const timestamp = event.timestamp || new Date().toISOString()
      if (type === 'turn.started') {
        const text = data.text || ''
        const duplicate = this.messages.find(message => message.role === 'user' && message.text === text && message.timestamp === timestamp)
        const last = this.messages[this.messages.length - 1]
        if (text && !duplicate && (!last || last.role !== 'user' || last.text !== text)) this.messages.push({ id: `user-event-${event.id || Date.now()}`, role: 'user', text, timestamp })
        this.running = true
        this.liveStatus = '正在思考'
      } else if (type === 'agent.message.delta') {
        const text = data.text || ''
        let last = this.messages[this.messages.length - 1]
        if (!last || last.role !== 'assistant' || !last.streaming) {
          last = { id: `assistant-${Date.now()}-${Math.random()}`, role: 'assistant', text: '', timestamp, streaming: true }
          this.messages.push(last)
        }
        last.text += text
        last.streaming = true
        this.running = true
        this.errorMessage = ''
        this.liveStatus = '正在整理回复'
      } else if (type === 'turn.completed') {
        const last = this.messages[this.messages.length - 1]
        if (last && last.role === 'assistant') last.streaming = false
        this.running = false
        this.errorMessage = ''
        this.liveStatus = ''
      } else if (type === 'turn.cancelled') {
        this.running = false
        this.liveStatus = ''
      } else if (type === 'turn.retrying') {
        this.running = true
        this.liveStatus = '正在重新连接 Codex'
      } else if (type === 'tool.call.started' || type === 'tool.call.output' || type === 'tool.call.completed') {
        const detail = data.text || (data.payload && (data.payload.command || data.payload.output)) || ''
        const rawId = data.payload && (data.payload.itemId || data.payload.callId)
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
        const payload = data.payload || {}
        this.approvalRequestId = data.requestId
        this.approvalCommand = Array.isArray(payload.command) ? payload.command.join(' ') : (payload.command || payload.reason || '需要你的确认')
        this.approvalDialog = true
        this.running = true
        this.liveStatus = '等待审批'
      } else if (type === 'diff.updated') {
        const payload = data.payload || {}
        this.diffText = payload.diff || data.text || this.diffText
      } else if (type === 'error') {
        if (data.payload && data.payload.willRetry) {
          this.running = true
          this.liveStatus = '正在重新连接 Codex'
          return
        }
        this.errorMessage = data.text || (data.payload && data.payload.message) || data.message || 'Codex 运行失败'
        this.running = false
        this.liveStatus = ''
      }
      if (!replay) this.$nextTick(this.scrollToBottom)
    }
  }
}
</script>
