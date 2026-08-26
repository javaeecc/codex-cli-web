<template>
  <LoginPage v-if="!authReady || !authenticated" :auth-ready="authReady" :form="loginForm" :loading="loginLoading" :error="loginError" @submit="submitLogin"></LoginPage>
  <div v-else class="shell" :class="{ 'left-collapsed': leftCollapsed, 'right-collapsed': rightCollapsed }">
    <AppTopbar :project="currentProject" :branch="currentBranch" :branches="branches" :runtime="runtime" :right-collapsed="rightCollapsed" @toggle-left="leftCollapsed = !leftCollapsed" @toggle-right="rightCollapsed = !rightCollapsed" @checkout-branch="checkoutBranch" @settings="openSettings"></AppTopbar>
    <div v-if="!rightCollapsed" class="mobile-inspector-backdrop" @click="rightCollapsed = true"></div>
    <div v-if="!leftCollapsed" class="mobile-sidebar-backdrop" @click="leftCollapsed = true"></div>
    <div class="workspace-layout">
      <ProjectSidebar :projects="projects" :current-project="currentProject" :sessions="sessions" :visible-sessions="visibleSessions" :current-session="currentSession" :socket-open="socketOpen" :show-archived="showArchived" :session-search="sessionSearch" :compact-path="compactPath" :status-class="statusClass" :format-session-time="formatSessionTime" @open-workspace="openWorkspacePicker" @select-project="selectProject" @delete-project="deleteProject" @create-session="createSession" @update:session-search="sessionSearch = $event" @select-session="selectSession" @toggle-archive="toggleSessionArchive" @toggle-archived="showArchived = !showArchived"></ProjectSidebar>
      <ConversationPanel ref="conversationPanel" :project="currentProject" :current-session="currentSession" :messages="messages" :display-messages="displayMessages" :running="running" :live-status="liveStatus" :error-message="errorMessage" :can-steer="canSteer" :sending="sending" :deleting-queue-id="deletingQueueId" :draft="draft" :attachments="attachments" :socket-open="socketOpen" :composer-focused="composerFocused" :history-has-more="historyHasMore" :history-loading="historyLoading" :show-scroll-bottom="!followOutput" :overall-open="overallOpen" :is-overall-group-active="isOverallGroupActive" :overall-group-status="overallGroupStatus" :thinking-status="thinkingStatus" :is-tool-group-active="isToolGroupActive" :tool-group-status="toolGroupStatus" :activity-status="activityStatus" :render-markdown="renderMarkdown" :format-time="formatTime" @open-workspace="openWorkspacePicker" @message-scroll="handleMessageScroll" @open-local-file="openLocalFile" @scroll-to-bottom="jumpToBottom" @load-older="loadOlderHistory" @overall-toggle="setOverallOpen" @thinking-toggle="setThinkingOpen" @retry="retryLast" @steer="steerQueued" @delete-queued="deleteQueued" @update:draft="draft = $event" @composer-focus="composerFocused = $event" @composer-keydown="handleComposerKeydown" @upload="uploadFiles" @remove-attachment="attachments.splice($event, 1)" @resend-message="resendMessage" @cancel="cancelTurn" @send="sendMessage"></ConversationPanel>
      <InspectorPanel :project="currentProject" :tabs="tabs" :active-tab="activeTab" :git-files="gitFiles" :file-items="fileItems" :file-loading-paths="fileLoadingPaths" :format-file-size="formatFileSize" @update:active-tab="activeTab = $event" @refresh-git="refreshGit" @open-diff="openDiff" @load-files="loadFiles" @toggle-directory="toggleDirectory" @open-file="openFile" @file-unavailable="notifyFileUnavailable"></InspectorPanel>
    </div>
    <WorkspaceDialogs :workspace-visible="workspaceDialog" :workspace-roots="workspaceRoots" :workspace-items="workspaceItems" :workspace-path="workspacePath" :workspace-parent="workspaceParent" :selected-workspace="selectedWorkspace" :settings-visible="settingsDialog" :settings="settings" :settings-saving="settingsSaving" :approval-visible="approvalDialog" :approval-requests="approvalRequests" :diff-visible="diffDialog" :diff-loading="diffLoading" :diff-lines="diffLines" :selected-file="selectedFile" :file-visible="fileDialog" :file-preview-loading="filePreviewLoading" :file-content="fileContent" :file-preview-url="filePreviewUrl" @browse="browse" @create-folder="createFolder" @select-workspace="selectedWorkspace = $event" @close-workspace="workspaceDialog = false" @confirm-workspace="confirmWorkspace" @close-settings="settingsDialog = false" @logout="logout" @save-settings="saveSettings" @respond-approval="respondApproval" @close-diff="diffDialog = false" @expand-diff="expandDiffSection" @close-file="closeFileDialog"></WorkspaceDialogs>
  </div>
</template>

<script>
import api from './api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import LoginPage from './components/LoginPage.vue'
import AppTopbar from './components/AppTopbar.vue'
import ProjectSidebar from './components/ProjectSidebar.vue'
import ConversationPanel from './components/ConversationPanel.vue'
import InspectorPanel from './components/InspectorPanel.vue'
import WorkspaceDialogs from './components/WorkspaceDialogs.vue'

export default {
  components: { LoginPage, AppTopbar, ProjectSidebar, ConversationPanel, InspectorPanel, WorkspaceDialogs },
  data () { return { authReady: false, authenticated: false, loginForm: { username: '', password: '' }, loginLoading: false, loginError: '', authSyncInFlight: false, authExpiredHandled: false, authExpiredCleanup: null, projects: [], sessions: [], currentProject: null, currentSession: null, runtime: { running: false }, runtimeTimer: null, displayNow: Date.now(), displayClockTimer: null, settings: { approvalPolicy: 'on-request', model: '', reasoningEffort: '' }, settingsDialog: false, settingsSaving: false, diffDialog: false, diffLoading: false, fileDialog: false, filePreviewLoading: false, filePreviewUrl: '', socket: null, socketOpen: false, socketRetryTimer: null, socketHealthTimer: null, lastSocketActivityAt: 0, leftCollapsed: false, rightCollapsed: true, activeTab: 'changes', tabs: [{ id: 'changes', label: 'Changes' }, { id: 'files', label: 'Files' }], messages: [], historyEvents: [], historyHasMore: false, historyBefore: 0, historyLoading: false, itemPhases: {}, activities: [], mediaObjectUrls: [], liveStatus: '', draft: '', lastDraft: '', sending: false, deletingQueueId: null, running: false, activeTurnText: '', errorMessage: '', gitFiles: [], currentBranch: '', branches: [], diffText: '', diffExpandedSections: {}, selectedFile: null, fileContent: null, fileItems: [], fileChildrenCache: {}, fileLoadingPaths: {}, fileTreeGeneration: 0, expandedPaths: {}, workspaceDialog: false, workspaceRoots: [], workspaceItems: [], workspacePath: '', workspaceParent: '', selectedWorkspace: '', approvalDialog: false, approvalRequests: [], approvalRequestId: null, approvalCommand: '', composerFocused: false, showArchived: false, sessionSearch: '', attachments: [], statusTimer: null, statusSyncInFlight: false, sessionLoadController: null, sessionLoadGeneration: 0, markdownCache: null, lastEventId: '', seenEventIds: {}, overallOpenState: {}, followOutput: true, liveEventQueue: [], liveEventFlushTimer: null } },
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
        if (message.role === 'turn-start' || message.role === 'turn-end') {
          thinking = null
          result.push(message)
        } else if (message.role === 'tool') {
          const previous = result[result.length - 1]
          if (previous && previous.role === 'tool-group') {
            previous.activities.push(message.activity)
            if (message.activity.state === '运行中') previous.state = '运行中'
          } else {
            result.push({ id: `tool-group-${message.id}`, role: 'tool-group', activities: [message.activity], state: message.activity.state, startedAt: message.activity.startedAt })
          }
          thinking = null
        } else if (message.role === 'assistant' && message.phase !== 'final_answer') {
          if (!thinking) {
            thinking = { id: `thinking-${message.id}`, role: 'thinking', text: '', streaming: false, thinkingOpen: false, sourceIds: [], startedAt: this.eventTime(message.timestamp), endedAt: null }
            result.push(thinking)
          }
          if (thinking.text && message.text) thinking.text += '\n\n'
          thinking.text += message.text || ''
          thinking.streaming = thinking.streaming || !!message.streaming
          thinking.thinkingOpen = thinking.thinkingOpen || !!message.thinkingOpen
          thinking.endedAt = this.eventTime(message.timestamp)
          thinking.sourceIds.push(message.id)
        } else {
          if (message.role === 'assistant' && message.phase === 'final_answer' && thinking) thinking.streaming = false
          thinking = null
          result.push(message)
        }
      })
      const wrapped = []
      let turnStartAt = null
      let currentGroup = null
      let lastGroup = null
      result.forEach(item => {
        if (item.role === 'turn-start') {
          turnStartAt = this.eventTime(item.timestamp)
          currentGroup = null
          lastGroup = null
        } else if (item.role === 'turn-end') {
          if (lastGroup) lastGroup.endedAt = this.eventTime(item.timestamp)
          turnStartAt = null
          currentGroup = null
          lastGroup = null
        } else if (item.role === 'thinking' || item.role === 'tool-group') {
          if (!currentGroup) {
            currentGroup = { id: `overall-${item.id}`, role: 'overall-group', items: [], startedAt: turnStartAt || item.startedAt || item.timestamp }
            lastGroup = currentGroup
            wrapped.push(currentGroup)
          }
          currentGroup.items.push(item)
        } else {
          wrapped.push(item)
          if (item.role === 'user') {
            currentGroup = null
            lastGroup = null
          } else if (item.role === 'assistant' && item.phase === 'final_answer') {
            if (currentGroup && !this.sessionHasPendingWork(this.currentSession)) currentGroup.endedAt = this.eventTime(item.timestamp)
            currentGroup = null
          }
        }
      })
      return wrapped
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
  mounted () { this.displayClockTimer = setInterval(() => { this.displayNow = Date.now() }, 1000); this.authExpiredCleanup = api.onAuthExpired(() => this.handleAuthExpired()); this.checkAuth() },
  beforeDestroy () { if (this.displayClockTimer) clearInterval(this.displayClockTimer); if (this.liveEventFlushTimer) clearTimeout(this.liveEventFlushTimer); if (this.authExpiredCleanup) this.authExpiredCleanup(); this.closeSocket(); if (this.sessionLoadController) this.sessionLoadController.abort(); this.stopStatusPolling(); this.stopRuntimePolling(); this.releaseMediaObjectUrls(); this.releaseFilePreviewUrl() },
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
        this.authExpiredHandled = false
        this.authenticated = true
        await this.refreshAll()
      } catch (e) {
        this.authenticated = false
        if (this.authExpiredHandled) return
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
        this.authExpiredHandled = false
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
    handleAuthExpired () {
      if (this.authExpiredHandled) return
      this.authExpiredHandled = true
      this.authenticated = false
      this.authReady = true
      this.loginError = '服务器会话已失效，请重新登录'
      try { localStorage.setItem('codex-web-explicit-logout', 'true') } catch (e) {}
      this.settingsDialog = false
      this.closeSocket()
      this.stopStatusPolling()
      this.stopRuntimePolling()
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
    async refreshAll () {
      try {
        const [projects, runtime, settings] = await Promise.all([api.projects(), api.runtime(), api.settings()])
        this.projects = projects.data
        this.runtime = runtime.data
        this.settings = settings.data
        if (!this.runtime.running) {
          try {
            this.runtime = (await api.startRuntime()).data
          } catch (startError) {
            this.notifyError(startError)
          }
        }
        this.startRuntimePolling()
        if (this.currentProject) {
          const found = this.projects.find(p => p.id === this.currentProject.id)
          if (found) await this.selectProject(found)
        } else if (this.projects.length) await this.selectProject(this.projects[0])
      } catch (e) { this.notifyError(e) }
    },
    replayHistoryEvents () {
      this.releaseMediaObjectUrls()
      this.messages = []
      this.activities = []
      this.itemPhases = {}
      this.seenEventIds = {}
      this.clearApprovalState()
      this.historyEvents.forEach(event => this.applyEvent(event, true))
    },
    async loadOlderHistory () {
      if (this.historyLoading || !this.historyHasMore || !this.currentSession) return
      this.historyLoading = true
      try {
        const previousBefore = this.historyBefore
        const [olderResult, latestResult] = await Promise.all([
          api.history(this.currentSession.id, { params: { before: previousBefore, limit: 1000 } }),
          api.history(this.currentSession.id, { params: { before: 0, limit: 1000 } })
        ])
        const page = olderResult.data || {}
        const latest = latestResult.data || {}
        const nextBefore = Number(page.nextBefore)
        const cursorProgressed = Number.isFinite(nextBefore) && nextBefore > previousBefore
        const combined = (page.events || []).concat(this.historyEvents, latest.events || [])
        const seen = {}
        this.historyEvents = combined.filter(event => {
          if (!event || !event.id || seen[event.id]) return false
          seen[event.id] = true
          return true
        })
        // Stop if the server returns a stale/non-decreasing cursor. Otherwise the
        // same page can be requested forever when the history changes mid-load.
        this.historyHasMore = !!page.hasMore && cursorProgressed
        this.historyBefore = cursorProgressed ? nextBefore : previousBefore
        const lastEventId = this.lastEventId
        this.replayHistoryEvents()
        this.lastEventId = lastEventId
      } catch (e) {
        this.notifyError(e)
      } finally {
        this.historyLoading = false
      }
    },
    async selectProject (project) { this.stopStatusPolling(); this.closeSocket(); this.currentProject = project; this.currentSession = null; this.messages = []; this.historyEvents = []; this.sessions = []; this.leftCollapsed = true; try { const result = await api.sessions(project.id); this.sessions = result.data; await Promise.all([this.refreshGit(), this.loadFiles()]); if (this.sessions.length) await this.selectSession(this.visibleSessions[0] || this.sessions[0]) } catch (e) { this.notifyError(e) } },
    async selectSession (session) { this.stopStatusPolling(); if (this.sessionLoadController) this.sessionLoadController.abort(); if (this.liveEventFlushTimer) { clearTimeout(this.liveEventFlushTimer); this.liveEventFlushTimer = null } this.liveEventQueue = []; const generation = this.sessionLoadGeneration + 1; this.sessionLoadGeneration = generation; const controller = new AbortController(); this.sessionLoadController = controller; this.closeSocket(); this.currentSession = session; this.lastDraft = session.lastUserMessage || ''; this.clearApprovalState(); this.followOutput = true; this.errorMessage = ''; this.messages = []; this.historyEvents = []; this.historyHasMore = false; this.historyBefore = 0; this.historyLoading = false; this.activities = []; this.overallOpenState = {}; this.liveStatus = ''; this.activeTurnText = ''; this.diffText = ''; this.selectedFile = ''; this.fileContent = null; this.seenEventIds = {}; this.lastEventRecoveryAt = 0; this.lastEventId = ''; this.leftCollapsed = true; try { const result = await api.history(session.id, { params: { before: 0, limit: 1000 }, signal: controller.signal }); if (generation !== this.sessionLoadGeneration || !this.currentSession || this.currentSession.id !== session.id) return; const history = result.data || {}; this.historyEvents = history.events || []; this.historyHasMore = !!history.hasMore; this.historyBefore = Number(history.nextBefore || 0); this.replayHistoryEvents(); this.lastEventId = history.lastEventId || this.lastEventId; this.running = this.sessionHasPendingWork(this.currentSession); if (this.running && !this.liveStatus) this.liveStatus = this.currentSession.status === 'WAITING_APPROVAL' ? '等待审批' : (this.hasQueuedTurns(this.currentSession) ? '队列处理中' : '正在思考'); this.connectSocket(session.id); const recovery = await api.events(session.id, this.lastEventId); if (generation !== this.sessionLoadGeneration || !this.currentSession || this.currentSession.id !== session.id) return; recovery.data.forEach(event => this.applyEvent(event, false)); this.startStatusPolling(session.id); this.scrollToBottomAfterRender() } catch (e) { if (e && e.code === 'ERR_CANCELED') return; if (generation === this.sessionLoadGeneration) this.notifyError(e) } finally { if (this.sessionLoadController === controller) this.sessionLoadController = null } },
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
    resendMessage (text) { if (!text || this.sending) return; this.draft = text; this.$nextTick(() => this.sendMessage()) },
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
      // SSE is the low-latency path. Polling remains enabled as a cheap
      // terminal-state fallback when a stream appears open but an event was lost.
      this.statusTimer = setInterval(() => {
        if (this.socket && this.socket.readyState === 1) this.socketOpen = true
        this.syncSession(sessionId)
      }, 5000)
    },
    async syncSession (sessionId) {
      if (this.statusSyncInFlight) return
      this.statusSyncInFlight = true
      try {
        const sessionResult = await api.session(sessionId)
        if (!this.currentSession || this.currentSession.id !== sessionId) return
        const previousPending = this.sessionHasPendingWork(this.currentSession)
        const previousStatus = this.currentSession.status
        this.currentSession = sessionResult.data
        const index = this.sessions.findIndex(item => item.id === sessionId)
        if (index >= 0) this.$set(this.sessions, index, sessionResult.data)
        const pending = this.sessionHasPendingWork(sessionResult.data)
        this.running = pending
        // Recover when the stream is down, a running turn became terminal, or
        // the session changed while the stream still looked healthy.
        const statusChanged = previousStatus !== sessionResult.data.status
        const shouldRecoverEvents = !this.socketOpen || (previousPending && !pending) || statusChanged
        const recoveryDue = Date.now() - this.lastEventRecoveryAt > 10000
        if (shouldRecoverEvents && recoveryDue) {
          this.lastEventRecoveryAt = Date.now()
          const eventsResult = await api.events(sessionId, this.lastEventId)
          if (this.currentSession && this.currentSession.id === sessionId) eventsResult.data.forEach(event => this.applyEvent(event, false))
        }
        if (!pending) {
          this.clearApprovalState()
          this.liveStatus = ''
          this.stopStatusPolling()
        }
      } catch (e) {
        if (window.console) console.warn('[codex-web] 会话状态同步失败', sessionId, e)
      } finally {
        this.statusSyncInFlight = false
      }
    },
    hasQueuedTurns (session) { return !!(session && session.queuedTurns && session.queuedTurns.length) },
    sessionHasPendingWork (session) { return !!(session && (session.status === 'RUNNING' || session.status === 'WAITING_APPROVAL' || this.hasQueuedTurns(session))) },
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
      flushLiveEvents () {
        this.liveEventFlushTimer = null
        const events = this.liveEventQueue.splice(0, this.liveEventQueue.length)
        events.forEach(event => this.applyEvent(event, false, false))
        if (events.length) this.$nextTick(this.scrollToBottom)
      },
      enqueueLiveEvent (event) {
        this.liveEventQueue.push(event)
        if (this.liveEventFlushTimer) return
        this.liveEventFlushTimer = setTimeout(() => this.flushLiveEvents(), 40)
      },
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
           if (event.sessionId === this.currentSession?.id) this.enqueueLiveEvent(event)
        } catch (e) { if (window.console) console.warn('[codex-web] SSE 事件解析失败', e) }
      }
      ;(async () => {
        try {
          const response = await fetch(api.streamUrl(sessionId), { headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-cache', ...api.streamHeaders() }, signal: controller.signal })
          if (this.socket !== source) return
           if (response.status === 401) { api.notifyAuthExpired(); return }
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
      this.releaseFilePreviewUrl()
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
    async openLocalFile (rawPath) {
      if (!this.currentProject || !rawPath) return this.notifyFileUnavailable()
      const projectRoot = String(this.currentProject.path || '').replace(/\\/g, '/').replace(/\/+$/, '')
      const normalized = String(rawPath).replace(/\\/g, '/')
      const rootLower = projectRoot.toLowerCase()
      const normalizedLower = normalized.toLowerCase()
      if (!projectRoot || !(normalizedLower === rootLower || normalizedLower.startsWith(`${rootLower}/`))) {
        return this.$message.warning('只能打开当前项目工作空间内的文件')
      }
      const relative = normalized.slice(projectRoot.length).replace(/^\/+/, '')
      if (!relative) return this.$message.warning('请选择一个文件')
      const item = { path: relative, viewable: true, size: 0 }
      if (!/\.(png|jpe?g|gif|webp|bmp)$/i.test(relative)) return this.openFile(item)
      this.releaseFilePreviewUrl()
      this.fileDialog = true
      this.filePreviewLoading = true
      this.fileContent = { path: relative, binary: true }
      try {
        const result = await api.rawFile(this.currentProject.id, relative)
        this.filePreviewUrl = URL.createObjectURL(result.data)
      } catch (e) {
        this.fileDialog = false
        this.fileContent = null
        this.notifyError(e)
      } finally {
        this.filePreviewLoading = false
      }
    },
    releaseFilePreviewUrl () {
      if (this.filePreviewUrl) URL.revokeObjectURL(this.filePreviewUrl)
      this.filePreviewUrl = ''
    },
    closeFileDialog () {
      this.fileDialog = false
      this.fileContent = null
      this.releaseFilePreviewUrl()
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
    async uploadFiles (event) { const files = Array.from(event.target.files || []); for (const file of files) { const uploadId = `upload-${Date.now()}-${Math.random()}`; this.attachments.push({ name: file.name, size: file.size, status: 'uploading', uploadId }); try { const result = await api.upload(this.currentSession.id, file); const index = this.attachments.findIndex(item => item.uploadId === uploadId); if (index >= 0) this.$set(this.attachments, index, { ...result.data, status: 'uploaded' }); this.$message({ type: 'success', message: `附件已上传：${result.data.name}`, duration: 3500, showClose: true }) } catch (e) { const index = this.attachments.findIndex(item => item.uploadId === uploadId); if (index >= 0) this.$set(this.attachments[index], 'status', 'failed'); this.notifyError(e) } } event.target.value = '' },
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
    renderMarkdown (text) {
      const value = text || ''
      if (!this.markdownCache) this.markdownCache = new Map()
      if (this.markdownCache.has(value)) return this.markdownCache.get(value)
      const container = document.createElement('div')
      container.innerHTML = marked.parse(value, { headerIds: false, mangle: false })
      container.querySelectorAll('a, img').forEach(element => {
        const attribute = element.tagName === 'IMG' ? 'src' : 'href'
        const rawPath = element.getAttribute(attribute)
        const localPath = this.localPathFromUrl(rawPath)
        if (!localPath) return
        if (element.tagName === 'IMG') {
          const link = document.createElement('a')
          link.textContent = element.getAttribute('alt') || localPath
          element.replaceWith(link)
          element = link
        }
        element.setAttribute('href', '#')
        element.setAttribute('data-local-path', localPath)
        element.setAttribute('title', '打开本地文件')
      })
      const html = DOMPurify.sanitize(container.innerHTML, { ADD_ATTR: ['data-local-path'] })
      this.markdownCache.set(value, html)
      if (this.markdownCache.size > 128) this.markdownCache.delete(this.markdownCache.keys().next().value)
      return html
    },
    localPathFromUrl (value) {
      if (!value) return ''
      let decoded = String(value)
      try { decoded = decodeURIComponent(decoded) } catch (e) {}
      decoded = decoded.replace(/\\/g, '/')
      if (/^[A-Za-z]:\//.test(decoded) || (decoded.startsWith('/') && !/^\/\//.test(decoded))) return decoded
      if (this.currentProject && !/^[a-z][a-z0-9+.-]*:/i.test(decoded) && /\.(png|jpe?g|gif|webp|bmp)$/i.test(decoded)) {
        const root = String(this.currentProject.path || '').replace(/\\/g, '/').replace(/\/+$/, '')
        if (root && !decoded.includes('..')) return `${root}/${decoded.replace(/^\/+/, '')}`
      }
      return ''
    },
    handleMessageScroll () {
      const box = this.$refs.messageScroll || (this.$refs.conversationPanel && this.$refs.conversationPanel.$refs.messageScroll)
      if (!box) return
      const distanceFromBottom = box.scrollHeight - box.clientHeight - box.scrollTop
      this.followOutput = distanceFromBottom <= 48
    },
    jumpToBottom () {
      const box = this.$refs.messageScroll || (this.$refs.conversationPanel && this.$refs.conversationPanel.$refs.messageScroll)
      if (!box) return
      this.followOutput = true
      box.scrollTo({ top: box.scrollHeight, behavior: 'auto' })
    },
    scrollToBottom () {
      const box = this.$refs.messageScroll || (this.$refs.conversationPanel && this.$refs.conversationPanel.$refs.messageScroll)
      if (!box || !this.followOutput) return
      box.scrollTo({ top: box.scrollHeight, behavior: 'auto' })
    },
    scrollToBottomAfterRender () { this.$nextTick(() => { this.scrollToBottom(); requestAnimationFrame(() => { this.scrollToBottom(); requestAnimationFrame(() => this.scrollToBottom()); setTimeout(() => this.scrollToBottom(), 100) }) }) },
    clearApprovalState () { this.approvalDialog = false; this.approvalRequests = []; this.approvalRequestId = null; this.approvalCommand = '' },
    removeApprovalRequest (requestId) { this.approvalRequests = this.approvalRequests.filter(item => String(item.requestId) !== String(requestId)); this.approvalDialog = this.approvalRequests.length > 0 },
    async respondApproval (payload) {
      const requestId = payload && payload.requestId !== undefined ? payload.requestId : this.approvalRequestId
      const decision = payload && payload.decision ? payload.decision : payload
      if (requestId === null || requestId === undefined) return
      try {
        await api.respondApproval(this.currentSession.id, { requestId, decision })
        this.removeApprovalRequest(requestId)
        if (this.currentSession && this.approvalRequests.length === 0 && this.currentSession.status === 'WAITING_APPROVAL') this.$set(this.currentSession, 'status', 'RUNNING')
      } catch (e) {
        // Approval requests live in the Codex process. A backend/Codex restart
        // invalidates the in-memory request even though the old UI event can
        // still be replayed from session history. Remove it so the modal cannot
        // trap the composer behind an unclosable, expired approval.
        const status = e && e.response && e.response.status
        const code = e && e.response && e.response.data && e.response.data.code
        if (status === 409 && code === 'APPROVAL_NOT_PENDING') {
          this.removeApprovalRequest(requestId)
          if (this.currentSession && this.approvalRequests.length === 0) {
            this.$set(this.currentSession, 'status', 'FAILED')
            const index = this.sessions.findIndex(item => item.id === this.currentSession.id)
            if (index >= 0) this.$set(this.sessions[index], 'status', 'FAILED')
            this.running = false
            this.liveStatus = ''
          }
          this.$message.warning('审批请求已失效，已关闭审批窗口')
          return
        }
        this.notifyError(e)
      }
    },
    overallOpen (group) { return this.isOverallGroupActive(group) || this.overallOpenState[group.id] === true },
    setOverallOpen (group, event) { if (!this.isOverallGroupActive(group)) this.$set(this.overallOpenState, group.id, event.target.open) },
    setThinkingOpen (group, event) { const open = event.target.open; (group.sourceIds || []).forEach(id => { const message = this.messages.find(item => item.id === id); if (message) { this.$set(message, 'thinkingOpen', open); this.$set(message, 'thinkingOpenTouched', true) } }) },
    notifyError (error) { const message = error && error.response && error.response.data ? error.response.data.message : (error.message || '请求失败'); this.$message.error(message) }
    ,applyEvent (event, replay, scroll = true) {
      if (!event) return
      if (event.id && this.seenEventIds[event.id]) return
      if (event.id) { this.$set(this.seenEventIds, event.id, true); this.lastEventId = event.id }
      const type = event.type
      const data = event.data || {}
      const timestamp = event.timestamp || new Date().toISOString()
      if (type === 'turn.accepted') {
        this.clearApprovalState()
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
        this.clearApprovalState()
        if (this.currentSession) this.$set(this.currentSession, 'status', 'RUNNING')
        const text = data.text || ''
        if (text) this.lastDraft = text
        if (!replay && this.currentSession && Array.isArray(this.currentSession.queuedTurns)) {
          const queuedIndex = data.queueId ? this.currentSession.queuedTurns.findIndex(item => item.id === data.queueId) : -1
          if (queuedIndex >= 0) this.currentSession.queuedTurns.splice(queuedIndex, 1)
        }
        const last = this.messages[this.messages.length - 1]
        if (text && (!last || last.role !== 'user' || last.text !== text)) this.messages.push({ id: `user-event-${event.id || Date.now()}`, role: 'user', text, timestamp })
        this.pushTurnMarker('turn-start', event, timestamp)
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
        if (phase !== 'final_answer' && !last.thinkingOpenTouched) this.$set(last, 'thinkingOpen', true)
        if (phase === 'final_answer') this.messages.forEach(message => { if (message.role === 'assistant' && message.phase !== 'final_answer') this.$set(message, 'thinkingOpen', false) })
        last.text += text
        last.streaming = true
        this.running = true
        this.errorMessage = ''
        this.liveStatus = ''
      } else if (type === 'turn.completed') {
        this.clearApprovalState()
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
        this.pushTurnMarker('turn-end', event, timestamp)
      } else if (type === 'turn.cancelled') {
        this.clearApprovalState()
        if (this.currentSession) this.$set(this.currentSession, 'status', 'CANCELLED')
        this.running = this.hasQueuedTurns(this.currentSession)
        this.activeTurnText = ''
        this.liveStatus = this.running ? '队列处理中' : ''
        this.pushTurnMarker('turn-end', event, timestamp)
      } else if (type === 'turn.steer.unavailable') {
        if (this.currentSession) this.$set(this.currentSession, 'steeringAvailable', false)
      } else if (type === 'turn.queue.error') {
        this.errorMessage = '排队任务启动失败，请检查 Codex 运行状态后重试'
        this.liveStatus = this.hasQueuedTurns(this.currentSession) ? '队列处理中' : ''
      } else if (type === 'turn.retrying') {
        if (this.currentSession) this.$set(this.currentSession, 'status', 'RUNNING')
        this.running = true
        this.liveStatus = '正在重新连接 Codex'
      } else if (type === 'tool.call.started' || type === 'tool.call.output' || type === 'tool.call.completed' || type === 'tool.call.failed') {
        const payload = data.payload || {}
        const item = payload.item || {}
        const rawId = data.itemId || payload.itemId || item.id || payload.callId
        const phase = data.phase || item.phase || ''
        const command = this.toolValue(payload.command || item.command || payload.cmd || item.cmd || data.command)
        const detail = this.toolValue(payload.changes || payload.patch || payload.arguments || payload.query || payload.url || payload.prompt || payload.tool || item.changes || item.patch || item.arguments || item.query || item.url || item.prompt || item.tool)
        const output = this.toolValue(payload.output || payload.aggregatedOutput || item.output || item.aggregatedOutput || data.output || data.text || detail)
        const exitCode = payload.exitCode !== undefined ? payload.exitCode : item.exitCode
        const status = payload.status !== undefined ? payload.status : (item.status !== undefined ? item.status : data.status)
        const media = data.media || payload.media || item.media
        const title = this.toolTitle(data.method, payload, item, command)
        if (item.type === 'agentMessage' && rawId && phase) {
          this.$set(this.itemPhases, rawId, phase)
          const message = this.messages.find(entry => entry.itemId === rawId)
          if (message) {
            this.$set(message, 'phase', phase)
            if (phase !== 'final_answer') this.$set(message, 'thinkingOpen', true)
          }
        }
        const eventAt = this.eventTime(timestamp)
        const activityId = rawId || event.id || `${type}-${timestamp}`
        const existing = this.activities.find(activity => activity.rawId === activityId)
        if (existing) {
          if (command) existing.command = command
          if (output) existing.output = output
          if (exitCode !== undefined) existing.exitCode = exitCode
          if (status !== undefined) this.$set(existing, 'status', status)
          if (detail) this.$set(existing, 'detail', detail)
          if (title !== '其他操作') existing.title = title
          if (type.endsWith('completed')) this.$set(existing, 'completedAt', eventAt)
          if (type === 'tool.call.failed' || this.activityFailed({ status, exitCode })) existing.state = 'failed'
          existing.state = type.endsWith('completed') ? '完成' : '运行中'
        } else {
          const failed = type === 'tool.call.failed' || this.activityFailed({ status, exitCode })
          const activity = { id: `${type}-${Date.now()}-${Math.random()}`, rawId: activityId, icon: 'el-icon-cpu', title, command, output, exitCode, state: type.endsWith('completed') ? '完成' : '运行中', startedAt: eventAt, completedAt: type.endsWith('completed') ? eventAt : null }
          this.activities.push(activity)
          if (failed) activity.state = 'failed'
          this.messages.push({ id: `tool-${activity.id}`, role: 'tool', timestamp, activity })
        }
        const updatedActivity = this.activities.find(activity => activity.rawId === activityId)
        if (updatedActivity) {
          if (media) this.$set(updatedActivity, 'media', media)
          if (status !== undefined) this.$set(updatedActivity, 'status', status)
          if (detail) this.$set(updatedActivity, 'detail', detail)
          if (media) this.loadActivityMedia(updatedActivity, media, this.currentSession && this.currentSession.id)
          if (type === 'tool.call.failed' || this.activityFailed({ status, exitCode })) {
            updatedActivity.state = 'failed'
            this.$set(updatedActivity, 'completedAt', eventAt)
          }
        }
        if (!replay) {
          this.running = true
          this.liveStatus = ''
        }
      } else if (type === 'approval.request') {
        // Approval requests are process-local. Do not resurrect a historical
        // request when startup recovery already settled this session, while
        // still allowing a genuinely live WAITING_APPROVAL session to recover
        // its dialog after a normal page refresh.
        if (replay && (!this.currentSession || this.currentSession.status !== 'WAITING_APPROVAL')) return
        if (this.currentSession) this.$set(this.currentSession, 'status', 'WAITING_APPROVAL')
        const payload = data.payload || {}
        const requestId = data.requestId !== undefined && data.requestId !== null ? data.requestId : payload.requestId
        if (requestId !== undefined && requestId !== null && !this.approvalRequests.some(item => String(item.requestId) === String(requestId))) {
          const command = Array.isArray(payload.command) ? payload.command.join(' ') : (payload.command || payload.reason || '需要你的确认')
          this.approvalRequests.push({ requestId, command })
        }
        this.approvalDialog = this.approvalRequests.length > 0
        this.running = true
        this.liveStatus = '等待审批'
      } else if (type === 'approval.responded') {
        const requestId = data.requestId !== undefined && data.requestId !== null ? data.requestId : (data.payload && data.payload.requestId)
        if (requestId !== undefined && requestId !== null) this.removeApprovalRequest(requestId)
        if (this.currentSession && this.approvalRequests.length === 0 && this.currentSession.status === 'WAITING_APPROVAL') this.$set(this.currentSession, 'status', 'RUNNING')
      } else if (type === 'diff.updated') {
        const payload = data.payload || {}
        this.diffText = payload.diff || data.text || this.diffText
      } else if (type === 'error') {
        this.clearApprovalState()
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
        this.pushTurnMarker('turn-end', event, timestamp)
      }
      if (!replay && scroll) this.$nextTick(this.scrollToBottom)
    }
    ,pushTurnMarker (role, event, timestamp) {
      const id = `marker-${role}-${event && event.id ? event.id : timestamp}`
      if (this.messages.some(message => message.id === id)) return
      this.messages.push({ id, role, timestamp })
    }
    ,eventTime (value) {
      if (typeof value === 'number' && Number.isFinite(value)) return value
      const parsed = Date.parse(value || '')
      return Number.isFinite(parsed) ? parsed : Date.now()
    }
    ,formatDuration (milliseconds) {
      const seconds = Math.max(0, Math.round(milliseconds / 1000))
      if (seconds < 1) return '<1 秒'
      if (seconds < 60) return `${seconds} 秒`
      const minutes = Math.floor(seconds / 60)
      const remainder = seconds % 60
      return remainder ? `${minutes} 分 ${remainder} 秒` : `${minutes} 分钟`
    }
    ,activityFailed (activity) {
      if (!activity) return false
      const status = String(activity.status || activity.state || '').toLowerCase()
      return status.includes('fail') || status.includes('error') || status.includes('reject') || (Number.isFinite(Number(activity.exitCode)) && Number(activity.exitCode) !== 0)
    }
    ,activityStatus (activity) {
      if (!activity) return ''
      if (this.activityFailed(activity)) return '失败'
      if (activity.state === '运行中') return `正在工作 · ${this.durationText(activity.startedAt)}`
      if (Number.isFinite(activity.startedAt) && Number.isFinite(activity.completedAt)) return `耗时 ${this.durationText(activity.startedAt, activity.completedAt)}`
      return '已完成'
    }
    ,thinkingStatus (item) {
      if (!item) return ''
      if (item.streaming) return `正在工作 · ${this.durationText(item.startedAt)}`
      if (Number.isFinite(item.startedAt) && Number.isFinite(item.endedAt)) return `耗时 ${this.durationText(item.startedAt, item.endedAt)}`
      return '已完成'
    }
    ,toolGroupActivities (group) {
      if (group && group.role === 'tool-group') return group.activities || []
      const items = (group && group.items) || []
      return items.reduce((all, item) => all.concat(item.role === 'tool-group' ? item.activities : []), [])
    }
    ,toolGroupStatus (group) {
      const activities = this.toolGroupActivities(group)
      if (activities.some(activity => this.activityFailed(activity))) return '存在失败操作'
      const startedAt = activities.map(activity => activity.startedAt).filter(value => Number.isFinite(value))
      const completedAt = activities.map(activity => activity.completedAt).filter(value => Number.isFinite(value))
      const start = startedAt.length ? Math.min(...startedAt) : null
      const end = completedAt.length === activities.length && completedAt.length ? Math.max(...completedAt) : null
      const hasRunning = activities.some(activity => activity.state === '运行中')
      if (this.currentSession && this.currentSession.status === 'WAITING_APPROVAL' && (hasRunning || completedAt.length < activities.length)) return `等待审批 · ${this.durationText(start)}`
      if (hasRunning) return `正在工作 · ${this.durationText(start)}`
      if (Number.isFinite(start) && Number.isFinite(end)) return `耗时 ${this.durationText(start, end)}`
      return '已完成'
    }
    ,isToolGroupActive (group) {
      return this.toolGroupActivities(group).some(activity => activity.state === '运行中')
    }
    ,overallGroupStatus (group) {
      const start = this.eventTime(group && group.startedAt)
      const active = this.isOverallGroupActive(group)
      if (active && this.currentSession && this.currentSession.status === 'WAITING_APPROVAL') return `等待审批 · ${this.durationText(start)}`
      if (active) return `正在工作 · ${this.durationText(start)}`
      const end = Number.isFinite(group && group.endedAt) ? group.endedAt : this.overallGroupEndAt(group)
      return Number.isFinite(end) ? `耗时 ${this.durationText(start, end)}` : '已完成'
    }
    ,isOverallGroupActive (group) {
      if (!group) return false
      if (Number.isFinite(group.endedAt)) return false
      const items = group.items || []
      return !!(this.sessionHasPendingWork(this.currentSession) || items.some(item => item.role === 'thinking' && item.streaming) || this.toolGroupActivities(group).some(activity => activity.state === '运行中'))
    }
    ,overallGroupEndAt (group) {
      const values = (group && group.items ? group.items : []).reduce((all, item) => {
        if (item.role === 'thinking' && Number.isFinite(item.endedAt)) all.push(item.endedAt)
        if (item.role === 'tool-group') item.activities.forEach(activity => { if (Number.isFinite(activity.completedAt)) all.push(activity.completedAt) })
        return all
      }, [])
      return values.length ? Math.max(...values) : null
    }
    ,durationText (startedAt, endedAt) {
      if (!Number.isFinite(startedAt)) return '<1 秒'
      const end = Number.isFinite(endedAt) ? endedAt : this.displayNow
      return this.formatDuration(Math.max(0, end - startedAt))
    }
    ,toolValue (value) {
      if (value === undefined || value === null || value === '') return ''
      if (Array.isArray(value)) return value.join(' ')
      if (typeof value === 'object') return JSON.stringify(value, null, 2)
      return String(value)
    }
    ,releaseMediaObjectUrls () {
      this.mediaObjectUrls.forEach(url => URL.revokeObjectURL(url))
      this.mediaObjectUrls = []
    }
    ,loadActivityMedia (activity, media, sessionId) {
      if (!activity || !media || activity.imageUrl || activity.mediaLoading || !media.id || !sessionId) return
      this.$set(activity, 'mediaLoading', true)
      api.media(sessionId, media.id).then(result => {
        const url = URL.createObjectURL(result.data)
        if (!this.currentSession || this.currentSession.id !== sessionId) {
          URL.revokeObjectURL(url)
          return
        }
        this.mediaObjectUrls.push(url)
        this.$set(activity, 'imageUrl', url)
      }).catch(() => {}).finally(() => {
        this.$set(activity, 'mediaLoading', false)
      })
    }
    ,toolTitle (method, payload, item, command) {
      const type = String((item && item.type) || (payload && payload.type) || '').toLowerCase()
      const eventMethod = String(method || '').toLowerCase()
      const commandText = String(command || '').toLowerCase()
      if (type.includes('computer') || type.includes('desktop')) return '操作电脑'
      if (type.includes('collab') || type.includes('subtask') || type.includes('agent')) return '调用子任务'
      if (type.includes('search') || type.includes('fetch')) return '搜索网页'
      if (type.includes('command') || type.includes('shell') || eventMethod.includes('commandexecution') || eventMethod.includes('shellcommand')) return '执行命令'
      if (type.includes('filechange') || type.includes('file_change') || type.includes('patch') || eventMethod.includes('filechange') || eventMethod.includes('applypatch')) return '修改文件'
      if (type.includes('fileread') || type.includes('file_read') || type.includes('readfile') || eventMethod.includes('fileread') || eventMethod.includes('readfile')) return '查看文件'
      if (type.includes('mcp')) return '调用 MCP 工具'
      if (type.includes('websearch') || type.includes('webfetch') || eventMethod.includes('websearch') || eventMethod.includes('webfetch')) return '搜索网页'
      if (type.includes('browser') || eventMethod.includes('browser')) return '浏览网页'
      if (type.includes('image') || eventMethod.includes('image')) return '查看图片'
      if (type.includes('todo') || type.includes('task')) return '更新任务'
      if (type.includes('file') && (type.includes('read') || type.includes('open'))) return '查看文件'
      if (type.includes('file') || type.includes('write')) return '文件操作'
      if (/\b(rg|grep|findstr|select-string)\b/.test(commandText)) return '搜索代码'
      if (/\b(cat|type|more|head|tail|sed)\b/.test(commandText) || commandText.includes('get-content')) return '查看文件'
      if (commandText.includes('git diff') || commandText.includes('git status')) return '查看修改'
      if (commandText.startsWith('git ')) return 'Git 操作'
      return '其他操作'
    }
    ,notifyError (error) {
      if (error && error.response && error.response.status === 401) return
      const message = error && error.response && error.response.data ? error.response.data.message : (error.message || '请求失败')
      this.$message.error(message)
    }
  }
}
</script>
