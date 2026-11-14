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
  <header>
    <h1>ServiceFlow AI</h1>
    <p class="subtitle">AI 智能工单与服务调度平台</p>
  </header>

  <template v-if="!isLoginPage">
    <nav class="tabs">
      <router-link to="/" class="tab" :class="{ active: route.path === '/' }">首页</router-link>
      <router-link to="/tickets" class="tab" :class="{ active: route.path === '/tickets' }">工单管理</router-link>
      <span class="spacer" />
      <span v-if="currentUser" class="user">{{ currentUser.name }}（{{ currentUser.role }}）</span>
      <button class="logout" @click="logout">退出登录</button>
    </nav>
  </template>

  <router-view />
</template>

<style scoped>
header h1 {
  font-size: 32px;
}
.subtitle {
  font-size: 18px;
  color: #5a6b7d;
  margin-top: 4px;
}
.tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 20px 0 24px;
}
.tab {
  padding: 8px 20px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  background: #fff;
  font-size: 14px;
  text-decoration: none;
  color: inherit;
}
.tab.active {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}
.spacer {
  flex: 1;
}
.user {
  color: #5a6b7d;
  font-size: 14px;
  margin-right: 8px;
}
.logout {
  padding: 6px 14px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
}
</style>
