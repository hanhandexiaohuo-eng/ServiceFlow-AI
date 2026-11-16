<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from './Sidebar.vue'
import Topbar from './Topbar.vue'
import type { CrumbItem } from '../types/ui'

const route = useRoute()
const MANUAL_KEY = 'sf_sidebar_collapsed'

const collapsed = ref(localStorage.getItem(MANUAL_KEY) === '1')

const pageTitle = computed(() => (route.meta.title as string) ?? '数据驾驶舱')
const group = computed(() => (route.meta.group as string) ?? '')
const crumbs = computed<CrumbItem[]>(() => {
  const items: CrumbItem[] = []
  if (group.value) items.push({ label: group.value })
  items.push({ label: pageTitle.value })
  return items
})

function toggle() {
  collapsed.value = !collapsed.value
}

watch(collapsed, (value) => {
  localStorage.setItem(MANUAL_KEY, value ? '1' : '0')
})

// 小屏默认收起，用户仍可手动展开
function handleResize() {
  if (window.innerWidth < 1180) collapsed.value = true
}

onMounted(() => {
  handleResize()
  window.addEventListener('resize', handleResize)
})
onBeforeUnmount(() => window.removeEventListener('resize', handleResize))
</script>

<template>
  <div class="layout">
    <Sidebar :collapsed="collapsed" />
    <div class="main">
      <Topbar :title="pageTitle" :crumbs="crumbs" :collapsed="collapsed" @toggle="toggle" />
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
  background: var(--bg);
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.content {
  flex: 1;
  min-width: 0;
}
</style>
