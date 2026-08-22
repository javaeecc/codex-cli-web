<template>
  <header class="topbar">
    <div class="brand"><button class="mobile-menu-button" type="button" title="打开会话列表" @click="$emit('toggle-left')"><i class="el-icon-menu"></i></button><span class="brand-mark">C</span><span>Codex Web</span></div>
    <div class="top-context">
      <span class="context-label">工作空间</span>
      <strong>{{ project ? project.name : '未选择' }}</strong>
      <span class="divider">/</span>
      <i class="el-icon-branch" v-if="branch"></i>
      <el-select v-if="project && branches.length" :value="branch" size="mini" class="branch-select" @change="$emit('checkout-branch', $event)"><el-option v-for="item in branches" :key="item" :label="item" :value="item"></el-option></el-select><span v-else>{{ branch || '无分支' }}</span>
    </div>
    <div class="top-actions">
      <span class="runtime-pill" :class="runtime.running ? 'is-live' : 'is-idle'"><span class="status-dot"></span>{{ runtime.running ? 'Codex 运行中' : 'Codex 未启动' }}</span>
      <button class="mobile-inspector-button" type="button" :title="rightCollapsed ? '查看变更和文件' : '关闭变更和文件'" @click="$emit('toggle-right')"><i :class="rightCollapsed ? 'el-icon-s-operation' : 'el-icon-close'"></i></button>
      <el-button class="icon-button" icon="el-icon-setting" circle title="设置" @click="$emit('settings')"></el-button>
    </div>
  </header>
</template>

<script>
export default {
  name: 'AppTopbar',
  props: { project: Object, branch: String, branches: Array, runtime: Object, rightCollapsed: Boolean }
}
</script>
