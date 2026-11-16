<script setup lang="ts">
import { computed, ref } from 'vue'
import { checkBackendHealth, type HealthResponse } from '../api/health'
import { getCurrentUser } from '../api/request'

const loading = ref(false)
const result = ref<HealthResponse | null>(null)
const errorMessage = ref('')

const currentUser = computed(() => getCurrentUser())

const services = [
  { name: 'Vue 3 前端', port: 5173, desc: '当前页面，承载交互与展示', icon: 'FE' },
  { name: 'Java 后端', port: 8080, desc: 'Spring Boot 业务入口', icon: 'BE' },
  { name: 'Python AI 服务', port: 8000, desc: 'FastAPI 能力服务', icon: 'AI' },
]

async function onCheck() {
  loading.value = true
  result.value = null
  errorMessage.value = ''
  try {
    result.value = await checkBackendHealth()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '未知错误'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="hero">
    <h2>{{ currentUser ? `${currentUser.name}，欢迎回来` : '欢迎使用 ServiceFlow AI' }}</h2>
    <p class="intro">
      通过 AI 对工单做分类、信息提取与处理建议生成，帮助服务团队更快定位问题、分派任务并闭环处理。
    </p>
  </section>

  <section class="cards">
    <div v-for="s in services" :key="s.port" class="card">
      <div class="card-head">
        <span class="icon">{{ s.icon }}</span>
        <h3>{{ s.name }}</h3>
      </div>
      <p class="port">端口 {{ s.port }}</p>
      <p class="desc">{{ s.desc }}</p>
    </div>
  </section>

  <section class="check">
    <button :disabled="loading" @click="onCheck">
      {{ loading ? '检查中…' : '检查后端连接' }}
    </button>

    <div v-if="result" class="panel success">
      <h4>后端连接正常</h4>
      <dl>
        <div><dt>服务名</dt><dd>{{ result.service }}</dd></div>
        <div><dt>状态</dt><dd>{{ result.status }}</dd></div>
        <div><dt>版本</dt><dd>{{ result.version }}</dd></div>
        <div><dt>时间</dt><dd>{{ result.timestamp }}</dd></div>
      </dl>
    </div>

    <div v-else-if="errorMessage" class="panel failure">
      <h4>连接失败</h4>
      <p>{{ errorMessage }}</p>
      <p class="hint">
        排查建议：确认 Java 后端已启动（端口 8080）；确认 MySQL 环境变量已设置；查看后端日志。
      </p>
    </div>
  </section>
</template>

<style scoped>
.hero {
  padding: 12px 0 4px;
}
.hero h2 {
  font-size: 24px;
}
.intro {
  margin-top: 8px;
  color: var(--text-sub);
  line-height: 1.7;
  max-width: 640px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin: 28px 0;
}
.card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  padding: 18px;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.icon {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: var(--primary-light);
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.card h3 {
  font-size: 15px;
}
.port {
  color: var(--primary);
  font-weight: 600;
  font-size: 13px;
  margin-top: 12px;
}
.desc {
  color: var(--text-muted);
  font-size: 13px;
  margin-top: 4px;
}

.check button {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 10px 24px;
  font-size: 15px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.check button:hover:not(:disabled) {
  background: var(--primary-hover);
}
.check button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.panel {
  margin-top: 20px;
  border-radius: var(--radius);
  padding: 18px;
}
.panel.success {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
}
.panel.failure {
  background: #fef2f2;
  border: 1px solid #fecaca;
}
.panel h4 {
  margin-bottom: 10px;
}
.panel dl div {
  display: flex;
  gap: 12px;
  padding: 3px 0;
}
.panel dt {
  width: 64px;
  color: var(--text-sub);
}
.panel dd {
  font-family: Consolas, monospace;
  word-break: break-all;
}
.hint {
  margin-top: 8px;
  color: var(--text-sub);
  font-size: 13px;
}
</style>
