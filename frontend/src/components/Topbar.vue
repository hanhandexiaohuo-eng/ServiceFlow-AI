<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from './AppIcon.vue'
import Breadcrumb from './Breadcrumb.vue'
import type { CrumbItem } from '../types/ui'
import { clearAuth, getCurrentUser } from '../api/request'
import { logout as logoutApi } from '../api/auth'
import { ROLE_LABEL, formatDateTime } from '../constants/ticket'
import {
  buildMyTodos,
  recentTickets,
  recentUpdateCount,
  refreshTickets,
} from '../composables/useTicketStore'

defineProps<{ title: string; crumbs: CrumbItem[]; collapsed: boolean }>()
const emit = defineEmits<{ (e: 'toggle'): void }>()

const router = useRouter()
const user = getCurrentUser()
const keyword = ref('')
const open = ref<'' | 'notif' | 'todo' | 'user'>('')

const isAdmin = user?.role === 'ADMIN'
const todos = computed(() => buildMyTodos(user?.name ?? '', isAdmin))
const pendingTotal = computed(() => todos.value.pending.length + todos.value.assignedToMe.length)
const noticeList = computed(() => recentTickets.value.slice(0, 4))

function toggle(name: 'notif' | 'todo' | 'user') {
  open.value = open.value === name ? '' : name
}

function submitSearch() {
  const q = keyword.value.trim()
  router.push({ path: '/tickets', query: q ? { q } : {} })
  open.value = ''
}

function goDetail(id: number) {
  open.value = ''
  router.push(`/tickets/${id}`)
}

async function logout() {
  try {
    await logoutApi()
  } catch {
    // 会话可能已过期，仍需清理本地状态并回到登录页
  }
  clearAuth()
  router.push('/login')
}

function closeAll() {
  open.value = ''
}

onMounted(() => {
  refreshTickets()
  document.addEventListener('click', closeAll)
})
onBeforeUnmount(() => document.removeEventListener('click', closeAll))
</script>

<template>
  <header class="topbar">
    <button class="icon-btn" :title="collapsed ? '展开侧边栏' : '收起侧边栏'" @click="emit('toggle')">
      <AppIcon name="panel" :size="18" />
    </button>

    <div class="heading">
      <Breadcrumb :items="crumbs" />
      <h2>{{ title }}</h2>
    </div>

    <div class="search">
      <AppIcon name="search" :size="15" class="search-icon" />
      <input
        v-model="keyword"
        placeholder="搜索工单编号 / 标题"
        @keyup.enter="submitSearch"
        @click.stop
      />
      <kbd>Enter</kbd>
    </div>

    <div class="actions">
      <!-- 消息通知：内容来自真实工单的最近更新 -->
      <div class="pop-wrap" @click.stop>
        <button class="icon-btn" title="消息通知" @click="toggle('notif')">
          <AppIcon name="bell" :size="18" />
          <span v-if="recentUpdateCount > 0" class="badge">{{ recentUpdateCount > 9 ? '9+' : recentUpdateCount }}</span>
        </button>
        <div v-if="open === 'notif'" class="pop">
          <div class="pop-head">
            <strong>最近更新</strong>
            <span class="pop-sub">近 24 小时 {{ recentUpdateCount }} 条</span>
          </div>
          <ul v-if="noticeList.length" class="pop-list">
            <li v-for="t in noticeList" :key="t.id" @click="goDetail(t.id)">
              <span class="dot" />
              <div class="pop-item">
                <p class="pop-title">{{ t.title }}</p>
                <p class="pop-meta">{{ t.ticketNo }} · {{ formatDateTime(t.updatedAt) }}</p>
              </div>
            </li>
          </ul>
          <p v-else class="pop-empty">暂无更新</p>
        </div>
      </div>

      <!-- 待办提醒：我相关的待处理工单 -->
      <div class="pop-wrap" @click.stop>
        <button class="icon-btn" title="待办提醒" @click="toggle('todo')">
          <AppIcon name="todo" :size="18" />
          <span v-if="pendingTotal > 0" class="badge amber">{{ pendingTotal > 9 ? '9+' : pendingTotal }}</span>
        </button>
        <div v-if="open === 'todo'" class="pop">
          <div class="pop-head">
            <strong>我的待办</strong>
            <span class="pop-sub">待处理 {{ todos.pending.length }} · 待接单 {{ todos.assignedToMe.length }}</span>
          </div>
          <ul v-if="todos.pending.length" class="pop-list">
            <li v-for="t in todos.pending.slice(0, 4)" :key="t.id" @click="goDetail(t.id)">
              <span class="dot amber" />
              <div class="pop-item">
                <p class="pop-title">{{ t.title }}</p>
                <p class="pop-meta">{{ t.ticketNo }} · 创建于 {{ formatDateTime(t.createdAt) }}</p>
              </div>
            </li>
          </ul>
          <p v-else class="pop-empty">暂无待处理工单</p>
          <router-link to="/tickets/mine" class="pop-foot" @click="open = ''">查看我的工单 →</router-link>
        </div>
      </div>

      <div class="divider" />

      <!-- 用户信息与退出 -->
      <div class="pop-wrap" @click.stop>
        <button class="user" @click="toggle('user')">
          <span class="avatar">{{ user?.name?.slice(0, 1) ?? 'U' }}</span>
          <span class="user-text">
            <strong>{{ user?.name ?? '未登录' }}</strong>
            <small>{{ ROLE_LABEL[user?.role ?? ''] ?? user?.role }}</small>
          </span>
          <AppIcon name="chevronDown" :size="14" class="chev" />
        </button>
        <div v-if="open === 'user'" class="pop pop-sm">
          <div class="pop-head">
            <strong>{{ user?.name }}</strong>
            <span class="pop-sub">{{ user?.username }} · {{ ROLE_LABEL[user?.role ?? ''] ?? user?.role }}</span>
          </div>
          <button class="logout" @click="logout">
            <AppIcon name="logout" :size="15" />
            退出登录
          </button>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 14px;
  height: var(--topbar-h);
  padding: 0 20px 0 16px;
  background: #fff;
  border-bottom: 1px solid var(--line);
}

.icon-btn {
  position: relative;
  width: 34px;
  height: 34px;
  flex: none;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}
.icon-btn:hover {
  background: var(--line-soft);
  color: var(--ink-1);
}
.badge {
  position: absolute;
  top: 3px;
  right: 2px;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  border-radius: 999px;
  background: var(--rose);
  color: #fff;
  font-size: 10px;
  line-height: 15px;
  text-align: center;
  border: 1.5px solid #fff;
}
.badge.amber {
  background: var(--amber);
}

.heading {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.heading h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.search {
  position: relative;
  margin-left: auto;
  width: min(320px, 30vw);
  display: flex;
  align-items: center;
  flex: none;
}
.search-icon {
  position: absolute;
  left: 10px;
  color: var(--ink-4);
  pointer-events: none;
}
.search input {
  width: 100%;
  height: 34px;
  padding: 0 52px 0 32px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fafbfd;
  font-size: 13px;
  transition: all 0.15s ease;
}
.search input::placeholder {
  color: var(--ink-4);
}
.search input:focus {
  outline: none;
  background: #fff;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.search kbd {
  position: absolute;
  right: 8px;
  font-family: inherit;
  font-size: 10px;
  color: var(--ink-4);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 1px 5px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: none;
}
.divider {
  width: 1px;
  height: 22px;
  background: var(--line);
  margin: 0 6px;
}

.pop-wrap {
  position: relative;
}
.pop {
  position: absolute;
  right: 0;
  top: calc(100% + 10px);
  width: 300px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow-pop);
  overflow: hidden;
  z-index: 60;
}
.pop-sm {
  width: 232px;
}
.pop-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line-soft);
}
.pop-head strong {
  font-size: 13px;
  color: var(--ink-1);
}
.pop-sub {
  font-size: 11px;
  color: var(--ink-4);
}
.pop-list {
  list-style: none;
  max-height: 260px;
  overflow-y: auto;
}
.pop-list li {
  display: flex;
  gap: 9px;
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid var(--line-soft);
  transition: background 0.12s ease;
}
.pop-list li:hover {
  background: #fafbfd;
}
.pop-list li:last-child {
  border-bottom: none;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand);
  margin-top: 6px;
  flex: none;
}
.dot.amber {
  background: var(--amber);
}
.pop-item {
  min-width: 0;
}
.pop-title {
  font-size: 13px;
  color: var(--ink-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pop-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.pop-empty {
  padding: 22px 14px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-4);
}
.pop-foot {
  display: block;
  padding: 10px 14px;
  text-align: center;
  font-size: 12px;
  color: var(--brand);
  border-top: 1px solid var(--line-soft);
}
.pop-foot:hover {
  background: #fafbfd;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 8px;
  border: none;
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.user:hover {
  background: var(--line-soft);
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--brand-weak);
  color: var(--brand);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.user-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.25;
}
.user-text strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
  white-space: nowrap;
}
.user-text small {
  font-size: 11px;
  color: var(--ink-4);
}
.chev {
  color: var(--ink-4);
}

.logout {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
}
.logout:hover {
  background: #fef2f2;
  color: var(--rose);
}

@media (max-width: 1280px) {
  .search {
    width: 200px;
  }
  .user-text,
  .chev {
    display: none;
  }
}
@media (max-width: 1024px) {
  .search {
    display: none;
  }
}
</style>
