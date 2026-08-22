<template>
  <div v-if="!authReady" class="auth-loading"><span class="brand-mark">C</span><span>正在连接 Codex Web...</span></div>
  <div v-else class="login-page">
    <div class="login-panel">
      <div class="login-brand"><span class="brand-mark">C</span><div><strong>Codex Web</strong><small>本地开发工作台</small></div></div>
      <h1>登录</h1>
      <p class="login-subtitle">登录后继续使用你的工作空间和会话。</p>
      <form class="login-form" @submit.prevent="$emit('submit')">
        <label>用户名<input v-model.trim="form.username" type="email" autocomplete="username" placeholder="请输入用户名" autofocus></label>
        <label>密码<input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码"></label>
        <p v-if="error" class="login-error" role="alert">{{ error }}</p>
        <el-button class="login-submit" type="primary" native-type="submit" :loading="loading" :disabled="!form.username || !form.password">登录</el-button>
      </form>
    </div>
  </div>
</template>

<script>
export default {
  name: 'LoginPage',
  props: { authReady: Boolean, form: Object, loading: Boolean, error: String }
}
</script>
