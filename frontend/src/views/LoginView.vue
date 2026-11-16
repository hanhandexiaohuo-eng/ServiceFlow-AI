<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

const highlights = [
  { icon: 'dashboard', title: '统一工作台', desc: '工单、调度与数据指标集中呈现' },
  { icon: 'activity', title: '全链路可视', desc: '从创建到闭环的状态与效率追踪' },
  { icon: 'sparkles', title: 'AI 辅助处置', desc: '分类、提取与处理建议能力规划中' },
]

async function onSubmit() {
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await login(form.username.trim(), form.password)
    const redirect = typeof router.currentRoute.value.query.redirect === 'string'
      ? router.currentRoute.value.query.redirect
      : '/'
    router.push(redirect)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <!-- 品牌侧 -->
      <aside class="brand">
        <div class="brand-top">
          <span class="logo">SF</span>
          <div>
            <strong>ServiceFlow AI</strong>
            <small>智能工单与服务调度平台</small>
          </div>
        </div>

        <ul class="highlights">
          <li v-for="h in highlights" :key="h.title">
            <span class="h-icon"><AppIcon :name="h.icon" :size="16" /></span>
            <div>
              <p>{{ h.title }}</p>
              <small>{{ h.desc }}</small>
            </div>
          </li>
        </ul>

        <p class="brand-foot">ServiceFlow AI · v0.1.0</p>
      </aside>

      <!-- 表单侧 -->
      <form class="form" @submit.prevent="onSubmit">
        <h1>登录控制台</h1>
        <p class="hint">使用分配给你的账号登录</p>

        <label class="field">
          <span>用户名</span>
          <input v-model="form.username" autocomplete="username" maxlength="32" placeholder="请输入用户名" />
        </label>

        <label class="field">
          <span>密码</span>
          <input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </label>

        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

        <button class="btn btn-primary submit" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>

        <p class="support">
          测试账号：admin / 123456（管理员） · bob / user123（普通用户）
        </p>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
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

.login-card {
  display: flex;
  width: min(960px, 100%);
  min-height: 552px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 18px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.12);
  overflow: hidden;
}

.brand {
  width: 392px;
  flex: none;
  display: flex;
  flex-direction: column;
  padding: 32px 30px;
  background: var(--sidebar-bg);
  color: #e2e8f0;
}
.brand-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-top div {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.brand-top strong {
  font-size: 15px;
  color: #f8fafc;
}
.brand-top small {
  font-size: 11px;
  color: #64748b;
}

.highlights {
  list-style: none;
  margin-top: 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.highlights li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.h-icon {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  background: rgba(99, 102, 241, 0.18);
  color: #a5b4fc;
  display: flex;
  align-items: center;
  justify-content: center;
}
.highlights p {
  font-size: 13px;
  color: #e2e8f0;
  font-weight: 500;
}
.highlights small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #64748b;
  line-height: 1.5;
}
.brand-foot {
  margin-top: auto;
  padding-top: 24px;
  font-size: 11px;
  color: #475569;
}

.form {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 44px 44px 32px;
  justify-content: center;
}
.form h1 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.hint {
  margin-top: -10px;
  font-size: 12px;
  color: var(--ink-4);
}
.submit {
  height: 40px;
  margin-top: 4px;
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
.support {
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.7;
}

@media (max-width: 880px) {
  .brand {
    display: none;
  }
  .login-card {
    min-height: auto;
    width: 420px;
  }
  .form {
    padding: 36px 28px;
  }
}
</style>
