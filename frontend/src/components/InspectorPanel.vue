<template>
  <aside class="right-panel panel-border">
    <div class="inspector-tabs"><button v-for="tab in tabs" :key="tab.id" :class="{ active: activeTab === tab.id }" @click="$emit('update:active-tab', tab.id)">{{ tab.label }}<span v-if="tab.id === 'changes' && gitFiles.length">{{ gitFiles.length }}</span></button></div>
    <div class="inspector-content" v-if="project">
      <div v-if="activeTab === 'changes'" class="change-view"><div class="inspector-toolbar"><strong>工作区变更</strong><el-button type="text" icon="el-icon-refresh" title="刷新 Git 状态" @click="$emit('refresh-git')"></el-button></div><div v-if="gitFiles.length" class="change-list"><button v-for="file in gitFiles" :key="file.path" class="change-row" @click="$emit('open-diff', file.path, true)"><span class="change-kind" :class="file.kind">{{ file.code.trim() || 'M' }}</span><span>{{ file.path }}</span></button></div><div v-else class="inspector-empty"><i class="el-icon-check"></i><span>工作区干净</span></div></div>
      <div v-else class="files-view"><div class="inspector-toolbar"><strong>文件</strong><el-button type="text" icon="el-icon-refresh" title="刷新文件树" @click="$emit('load-files')"></el-button></div><div class="file-tree"><div v-for="item in fileItems" :key="item.path" class="file-row" :class="{ 'file-row-unavailable': !item.directory && !item.viewable }" :style="{ paddingLeft: `${10 + item.depth * 16}px` }" @click="item.directory ? $emit('toggle-directory', item) : (item.viewable ? $emit('open-file', item) : $emit('file-unavailable', item))"><i :class="item.directory ? (fileLoadingPaths[item.path] ? 'el-icon-loading' : (item.expanded ? 'el-icon-folder-opened' : 'el-icon-folder')) : (item.viewable ? 'el-icon-document' : 'el-icon-document-delete')"></i><span class="file-name">{{ item.name }}</span><small v-if="!item.directory && item.size != null" class="file-size">{{ formatFileSize(item.size) }}</small></div></div></div>
    </div>
    <div v-else class="inspector-empty full"><i class="el-icon-s-operation"></i><span>选择项目后查看代码状态</span></div>
  </aside>
</template>

<script>
export default {
  name: 'InspectorPanel',
  props: { project: Object, tabs: Array, activeTab: String, gitFiles: Array, fileItems: Array, fileLoadingPaths: Object, formatFileSize: Function }
}
</script>
