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
    <form class="login-card" @submit.prevent="onSubmit">
      <h1>ServiceFlow AI</h1>
      <p class="subtitle">AI 智能工单与服务调度平台</p>
      <label>用户名
        <input v-model="form.username" autocomplete="username" maxlength="32" />
      </label>
      <label>密码
        <input v-model="form.password" type="password" autocomplete="current-password" />
      </label>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <button class="primary" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 70vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 360px;
  background: #fff;
  border: 1px solid #e4e9ef;
  border-radius: 12px;
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
h1 {
  font-size: 24px;
  text-align: center;
}
.subtitle {
  text-align: center;
  color: #7a8a99;
  font-size: 13px;
  margin-top: -8px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 14px;
}
input {
  padding: 8px 10px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  font-size: 14px;
}
.primary {
  background: #3b82f6;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 10px;
  font-size: 15px;
  cursor: pointer;
}
.primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.error {
  color: #dc2626;
  font-size: 13px;
  margin: 0;
}
</style>
