<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { checkBackendHealth, checkReadiness } from '../api/health'

/**
 * 服务健康状态：Java / MySQL 使用真实健康检查接口；
 * Python AI 服务前端不直连（跨域且不硬编码域名），明确标记待接入。
 */
type HealthStatus = 'loading' | 'up' | 'down' | 'pending'

interface HealthItem {
  key: string
  name: string
  icon: string
  status: HealthStatus
  detail: string
}

const STATUS_TEXT: Record<HealthStatus, string> = {
  loading: '检测中',
  up: '正常',
  down: '异常',
  pending: '待接入',
}

const health = ref<HealthItem[]>([
  { key: 'java', name: 'Java 后端', icon: 'server', status: 'loading', detail: '检测中…' },
  { key: 'mysql', name: 'MySQL 数据库', icon: 'database', status: 'loading', detail: '检测中…' },
  {
    key: 'python',
    name: 'Python AI 服务',
    icon: 'cpu',
    status: 'pending',
    detail: '前端不直连 AI 服务，需后端聚合健康检查（待接入）',
  },
])

const checkedAt = ref('')
const loading = ref(false)

async function check() {
  loading.value = true
  const started = performance.now()
  const [java, ready] = await Promise.allSettled([checkBackendHealth(), checkReadiness()])
  const cost = Math.round(performance.now() - started)

  const javaItem = health.value.find((i) => i.key === 'java')
  if (javaItem) {
    if (java.status === 'fulfilled') {
      javaItem.status = 'up'
      javaItem.detail = `${java.value.service} · v${java.value.version} · 响应 ${cost}ms`
    } else {
      javaItem.status = 'down'
      javaItem.detail = java.reason instanceof Error ? java.reason.message : '连接失败'
    }
  }

  const mysqlItem = health.value.find((i) => i.key === 'mysql')
  if (mysqlItem) {
    if (ready.status === 'fulfilled') {
      const checkResult = ready.value.checks.find((c) => c.name === 'mysql')
      mysqlItem.status = checkResult?.status === 'UP' ? 'up' : 'down'
      mysqlItem.detail = checkResult?.message ?? '后端未返回数据库检查结果'
    } else {
      mysqlItem.status = 'down'
      mysqlItem.detail = ready.reason instanceof Error ? ready.reason.message : '就绪检查失败'
    }
  }

  checkedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  loading.value = false
}

onMounted(check)
</script>

<template>
  <section class="health-card">
    <header class="head">
      <div>
        <h3>服务健康状态</h3>
        <p class="sub">{{ checkedAt ? `最近检测 ${checkedAt}` : '正在检测…' }}</p>
      </div>
      <button class="btn btn-sm" :disabled="loading" @click="check">
        <AppIcon name="refresh" :size="13" />重新检测
      </button>
    </header>

    <div class="grid-health">
      <div v-for="item in health" :key="item.key" class="item" :class="item.status">
        <span class="icon"><AppIcon :name="item.icon" :size="18" /></span>
        <div class="body">
          <div class="top">
            <strong>{{ item.name }}</strong>
            <span class="pill" :class="item.status">{{ STATUS_TEXT[item.status] }}</span>
          </div>
          <p class="detail">{{ item.detail }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.health-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 0;
}
.head h3 {
  font-size: 15px;
  font-weight: 600;
}
.sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.grid-health {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 14px 18px 18px;
}
.item {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fcfdfe;
  min-width: 0;
}
.icon {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f1f5f9;
  color: var(--ink-3);
}
.item.up .icon {
  background: #ecfdf5;
  color: var(--emerald);
}
.item.down .icon {
  background: #fef2f2;
  color: var(--rose);
}
.body {
  min-width: 0;
  flex: 1;
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.top strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.detail {
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.5;
  word-break: break-all;
}
.pill {
  flex: none;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  color: var(--ink-3);
}
.pill.up {
  background: #ecfdf5;
  color: #047857;
}
.pill.down {
  background: #fef2f2;
  color: #b91c1c;
}
.pill.loading {
  background: #eff6ff;
  color: #1d4ed8;
}

@media (max-width: 1280px) {
  .grid-health {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
