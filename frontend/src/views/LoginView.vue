<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'

const router = useRouter()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

async function onSubmit() {
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await login(form.username.trim(), form.password)
    router.push('/')
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="glow glow-1" />
    <div class="glow glow-2" />

    <form class="login-card" @submit.prevent="onSubmit">
      <div class="logo">SF</div>
      <h1>ServiceFlow AI</h1>
      <p class="subtitle">AI 智能工单与服务调度平台</p>

      <label>用户名
        <input v-model="form.username" autocomplete="username" maxlength="32" placeholder="请输入用户名" />
      </label>
      <label>密码
        <input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码" />
      </label>

      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

      <button class="primary" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登 录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 50%, #e0e7ff 100%);
  overflow: hidden;
}
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
  pointer-events: none;
}
.glow-1 {
  width: 420px;
  height: 420px;
  background: #c7d2fe;
  top: -120px;
  right: -80px;
}
.glow-2 {
  width: 360px;
  height: 360px;
  background: #bfdbfe;
  bottom: -120px;
  left: -60px;
}

.login-card {
  position: relative;
  width: 380px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.logo {
  width: 48px;
  height: 48px;
  margin: 0 auto;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--primary), #818cf8);
  color: #fff;
  font-weight: 700;
  font-size: 19px;
  display: flex;
  align-items: center;
  justify-content: center;
}
h1 {
  font-size: 22px;
  text-align: center;
}
.subtitle {
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
  margin-top: -10px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: var(--text-sub);
}
input {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
input:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-light);
}
.primary {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 11px;
  font-size: 15px;
  letter-spacing: 4px;
  cursor: pointer;
  margin-top: 4px;
  transition: background 0.15s ease;
}
.primary:hover:not(:disabled) {
  background: var(--primary-hover);
}
.primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.error {
  color: var(--danger);
  font-size: 13px;
  margin: 0;
  text-align: center;
}
</style>
