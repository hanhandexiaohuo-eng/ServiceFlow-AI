<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { MENU_GROUPS } from '../constants/menu'

defineProps<{ collapsed: boolean }>()

const route = useRoute()

/** 工单详情页（/tickets/:id）也需要点亮「工单列表」 */
function isActive(to: string): boolean {
  if (to === '/') return route.path === '/'
  if (to === '/tickets') return route.path === '/tickets' || /^\/tickets\/\d+$/.test(route.path)
  return route.path === to
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="brand">
      <span class="logo">SF</span>
      <div v-if="!collapsed" class="brand-text">
        <strong>ServiceFlow AI</strong>
        <small>智能工单与服务调度</small>
      </div>
    </div>

    <nav class="nav">
      <div v-for="group in MENU_GROUPS" :key="group.name" class="group">
        <p v-if="!collapsed" class="group-name">{{ group.name }}</p>
        <div v-else class="group-line" />
        <router-link
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="item"
          :class="{ active: isActive(item.to) }"
          :title="collapsed ? item.label : undefined"
        >
          <span class="item-icon"><AppIcon :name="item.icon" :size="17" /></span>
          <template v-if="!collapsed">
            <span class="item-label">{{ item.label }}</span>
            <span v-if="item.soon" class="soon">待接入</span>
          </template>
        </router-link>
      </div>
    </nav>

    <div class="foot" :class="{ centered: collapsed }">
      <template v-if="!collapsed">
        <span class="version">v0.1.0 · 第一阶段</span>
      </template>
      <span v-else class="version-dot" title="v0.1.0 · 第一阶段" />
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  width: var(--sidebar-w);
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--sidebar-bg);
  color: var(--sidebar-text);
  transition: width 0.22s ease;
  overflow: hidden;
  z-index: 30;
}
.sidebar.collapsed {
  width: var(--sidebar-w-collapsed);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--topbar-h);
  padding: 0 16px;
  border-bottom: 1px solid var(--sidebar-line);
  flex: none;
}
.sidebar.collapsed .brand {
  padding: 0;
  justify-content: center;
}
.logo {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.02em;
}
.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}
.brand-text strong {
  font-size: 14px;
  color: #f1f5f9;
  white-space: nowrap;
}
.brand-text small {
  font-size: 11px;
  color: #64748b;
  white-space: nowrap;
}

.nav {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 10px 10px 16px;
}
.nav::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.3);
}
.sidebar.collapsed .nav {
  padding: 10px 8px 16px;
}

.group + .group {
  margin-top: 6px;
}
.group-name {
  padding: 12px 10px 6px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: #64748b;
  text-transform: uppercase;
  white-space: nowrap;
}
.group-line {
  height: 1px;
  margin: 8px 12px;
  background: var(--sidebar-line);
}
.sidebar.collapsed .group:first-child .group-line {
  display: none;
}

.item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 10px;
  margin: 2px 0;
  border-radius: 8px;
  color: var(--sidebar-text);
  font-size: 13px;
  transition: background 0.15s ease, color 0.15s ease;
  white-space: nowrap;
}
.sidebar.collapsed .item {
  justify-content: center;
  padding: 0;
  gap: 0;
}
.item:hover {
  background: rgba(148, 163, 184, 0.1);
  color: #e2e8f0;
}
.item.active {
  background: rgba(79, 70, 229, 0.22);
  color: var(--sidebar-text-active);
  font-weight: 500;
}
/* 选中态左侧亮条 */
.item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #818cf8;
}
.sidebar.collapsed .item.active::before {
  left: -8px;
}

.item-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 18px;
  color: inherit;
}
.item-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.soon {
  flex: none;
  font-size: 10px;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 4px;
  color: #94a3b8;
  background: rgba(148, 163, 184, 0.14);
}

.foot {
  flex: none;
  padding: 12px 16px;
  border-top: 1px solid var(--sidebar-line);
}
.foot.centered {
  display: flex;
  justify-content: center;
  padding: 14px 0;
}
.version {
  font-size: 11px;
  color: #64748b;
}
.version-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #475569;
}
</style>
