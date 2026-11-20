<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword, logout } from '../api/auth'
import { clearAuth, getCurrentUser } from '../api/request'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()
const user = getCurrentUser()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

/** 与后端 PasswordPolicy 保持一致的前置提示，最终以后端校验为准 */
const RULES = [
  '长度至少 12 位',
  '同时包含字母和数字',
  '不能包含用户名',
  '不能使用常见弱口令',
]

async function onSubmit() {
  errorMessage.value = ''
  successMessage.value = ''
  if (!form.currentPassword || !form.newPassword) {
    errorMessage.value = '请填写当前密码和新密码'
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    errorMessage.value = '两次输入的新密码不一致'
    return
  }
  submitting.value = true
  try {
    await changePassword(form.currentPassword, form.newPassword)
    successMessage.value = '密码已更新，正在进入系统…'
    form.currentPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    const redirect = typeof router.currentRoute.value.query.redirect === 'string'
      ? router.currentRoute.value.query.redirect
      : '/'
    setTimeout(() => router.replace(redirect), 600)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '修改密码失败'
  } finally {
    submitting.value = false
  }
}

async function onLogout() {
  try {
    await logout()
  } catch {
    // 会话已失效时仍需清理本地状态
  }
  clearAuth()
  router.push('/login')
}
</script>

<template>
  <div class="page">
    <form class="card" @submit.prevent="onSubmit">
      <div class="head">
        <span class="logo">SF</span>
        <div>
          <h1>修改密码</h1>
          <p class="sub">{{ user?.name ? `${user.name}，` : '' }}首次登录或定期更换密码时需在此完成设置</p>
        </div>
      </div>

      <label class="field">
        <span>当前密码</span>
        <input
          v-model="form.currentPassword"
          type="password"
          autocomplete="current-password"
          maxlength="72"
          placeholder="请输入当前密码"
        />
      </label>

      <label class="field">
        <span>新密码</span>
        <input
          v-model="form.newPassword"
          type="password"
          autocomplete="new-password"
          maxlength="72"
          placeholder="请设置新密码"
        />
      </label>

      <label class="field">
        <span>确认新密码</span>
        <input
          v-model="form.confirmPassword"
          type="password"
          autocomplete="new-password"
          maxlength="72"
          placeholder="请再次输入新密码"
        />
      </label>

      <ul class="rules">
        <li v-for="rule in RULES" :key="rule">{{ rule }}</li>
      </ul>

      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <p v-if="successMessage" class="success">{{ successMessage }}</p>

      <button class="btn btn-primary submit" type="submit" :disabled="submitting">
        {{ submitting ? '提交中…' : '确认修改' }}
      </button>

      <button class="link" type="button" @click="onLogout">
        <AppIcon name="logout" :size="13" />
        退出登录
      </button>
    </form>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 12% -10%, #e0e7ff 0%, transparent 60%),
    radial-gradient(900px 500px at 100% 110%, #e0f2fe 0%, transparent 55%),
    var(--bg);
}
.card {
  width: min(440px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 32px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.12);
}
.head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 4px;
}
.logo {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.head h1 {
  font-size: 18px;
  font-weight: 700;
}
.sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
  line-height: 1.5;
}
.rules {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 11px;
  color: var(--ink-4);
  padding: 0;
  margin: 0;
}
.rules li::before {
  content: '·';
  margin-right: 4px;
  color: var(--brand);
}
.submit {
  height: 40px;
  margin-top: 2px;
  font-size: 14px;
  font-weight: 500;
}
.error {
  font-size: 12px;
  color: var(--rose);
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  padding: 8px 12px;
}
.success {
  font-size: 12px;
  color: #047857;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  border-radius: 8px;
  padding: 8px 12px;
}
.link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  background: transparent;
  color: var(--ink-4);
  font-size: 12px;
  cursor: pointer;
}
.link:hover {
  color: var(--brand);
}
</style>
