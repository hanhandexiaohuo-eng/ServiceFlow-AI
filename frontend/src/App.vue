<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearAuth, getCurrentUser } from './api/request'

const route = useRoute()
const router = useRouter()

const isLoginPage = computed(() => route.path === '/login')
const currentUser = computed(() => getCurrentUser())

function logout() {
  clearAuth()
  router.push('/login')
}
</script>

<template>
  <header v-if="!isLoginPage" class="topbar">
    <div class="brand">
      <span class="logo">SF</span>
      <div class="brand-text">
        <h1>ServiceFlow AI</h1>
        <p class="subtitle">AI 智能工单与服务调度平台</p>
      </div>
    </div>

    <nav class="tabs">
      <router-link to="/" class="tab" :class="{ active: route.path === '/' }">首页</router-link>
      <router-link to="/tickets" class="tab" :class="{ active: route.path === '/tickets' }">工单管理</router-link>
    </nav>

    <div class="user-area">
      <span v-if="currentUser" class="user-chip">
        <span class="avatar">{{ currentUser.name.slice(0, 1) }}</span>
        {{ currentUser.name }}
        <em class="role" :class="{ admin: currentUser.role === 'ADMIN' }">
          {{ currentUser.role === 'ADMIN' ? '管理员' : '普通用户' }}
        </em>
      </span>
      <button class="logout" @click="logout">退出登录</button>
    </div>
  </header>

  <main :class="{ 'login-main': isLoginPage }">
    <router-view />
  </main>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 28px;
  margin: 0 -24px 28px;
  padding: 12px 24px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}
.logo {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  background: linear-gradient(135deg, var(--primary), #818cf8);
  color: #fff;
  font-weight: 700;
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-text h1 {
  font-size: 17px;
  line-height: 1.2;
}
.subtitle {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

.tabs {
  display: flex;
  gap: 4px;
  background: var(--bg-page);
  padding: 4px;
  border-radius: 999px;
}
.tab {
  padding: 6px 18px;
  border-radius: 999px;
  font-size: 14px;
  text-decoration: none;
  color: var(--text-sub);
  transition: all 0.15s ease;
}
.tab:hover {
  color: var(--text-main);
}
.tab.active {
  background: var(--bg-card);
  color: var(--primary);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.user-area {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-main);
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--primary-light);
  color: var(--primary);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.role {
  font-style: normal;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  color: var(--text-sub);
}
.role.admin {
  background: #fef3c7;
  color: #b45309;
}
.logout {
  padding: 6px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  cursor: pointer;
  font-size: 13px;
  color: var(--text-sub);
  transition: all 0.15s ease;
}
.logout:hover {
  color: var(--danger);
  border-color: #fecaca;
  background: #fef2f2;
}

main {
  min-height: 60vh;
}
</style>
