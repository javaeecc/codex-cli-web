<template>
  <aside class="left-panel panel-border">
    <div class="panel-heading"><span>项目</span><el-button type="text" icon="el-icon-plus" title="添加工作空间" @click="$emit('open-workspace')"></el-button></div>
    <div class="project-list" v-if="projects.length">
      <button v-for="project in projects" :key="project.id" class="project-row" :class="{ active: currentProject && currentProject.id === project.id }" @click="$emit('select-project', project)">
        <span class="project-icon" :class="{ git: project.isGitRepository }"><i :class="project.isGitRepository ? 'el-icon-connection' : 'el-icon-folder'"></i></span>
        <span class="project-copy"><strong>{{ project.name }}</strong><small>{{ compactPath(project.path) }}</small></span>
        <i class="el-icon-delete project-delete" title="删除项目" @click.stop="$emit('delete-project', project)"></i>
      </button>
    </div>
    <div class="empty-side" v-else><i class="el-icon-folder-opened"></i><span>还没有工作空间</span><el-button size="mini" @click="$emit('open-workspace')">选择目录</el-button></div>

    <div class="session-heading"><span>会话</span><div class="session-heading-actions"><el-button type="text" icon="el-icon-plus" :disabled="!currentProject" title="新建会话" @click="$emit('create-session')"></el-button></div></div>
    <div class="session-search"><i class="el-icon-search"></i><input :value="sessionSearch" placeholder="搜索会话" @input="$emit('update:session-search', $event.target.value)"></div>
    <div class="session-list" v-if="sessions.length">
      <button v-for="session in visibleSessions" :key="session.id" class="session-row" :class="{ active: currentSession && currentSession.id === session.id }" @click="$emit('select-session', session)">
        <span class="session-status" :class="statusClass(session.status)"></span>
        <span class="session-copy"><strong>{{ session.title }}</strong><small>创建 {{ formatSessionTime(session.createdAt) }} · 更新 {{ formatSessionTime(session.updatedAt || session.createdAt) }}</small></span>
        <i class="session-archive" :class="session.archived ? 'el-icon-refresh-left' : 'el-icon-box'" :title="session.archived ? '取消归档' : '归档'" @click.stop="$emit('toggle-archive', session)"></i>
      </button>
    </div>
    <div class="empty-side compact" v-else><span>{{ currentProject ? '新建一个会话开始工作' : '先选择工作空间' }}</span></div>
    <div class="left-footer"><span class="connection-state"><span class="status-dot" :class="socketOpen ? 'green' : 'gray'"></span>{{ socketOpen ? '实时连接' : '正在重连' }}</span><button class="footer-link" @click="$emit('toggle-archived')">{{ showArchived ? '隐藏归档' : '显示归档' }}</button></div>
  </aside>
</template>

<script>
export default {
  name: 'ProjectSidebar',
  props: { projects: Array, currentProject: Object, sessions: Array, visibleSessions: Array, currentSession: Object, socketOpen: Boolean, showArchived: Boolean, sessionSearch: String, compactPath: Function, statusClass: Function, formatSessionTime: Function }
}
</script>
