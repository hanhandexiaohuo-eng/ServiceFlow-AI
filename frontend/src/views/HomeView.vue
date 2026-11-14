<script setup lang="ts">
import { ref } from 'vue'
import { checkBackendHealth, type HealthResponse } from '../api/health'

const loading = ref(false)
const result = ref<HealthResponse | null>(null)
const errorMessage = ref('')

const services = [
  { name: 'Vue 3 前端', port: 5173, desc: '当前页面' },
  { name: 'Java 后端', port: 8080, desc: 'Spring Boot 业务入口' },
  { name: 'Python AI 服务', port: 8000, desc: 'FastAPI 能力服务' },
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
  <p class="intro">
    通过 AI 对工单做分类、信息提取与处理建议生成，帮助服务团队更快定位问题、分派任务并闭环处理。
  </p>

  <section class="cards">
    <div v-for="s in services" :key="s.port" class="card">
      <h3>{{ s.name }}</h3>
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
.intro {
  margin-top: 12px;
  color: #7a8a99;
  line-height: 1.6;
}
.cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin: 32px 0;
}
.card {
  background: #fff;
  border: 1px solid #e4e9ef;
  border-radius: 8px;
  padding: 16px;
}
.card h3 {
  font-size: 15px;
}
.port {
  color: #3b82f6;
  font-weight: 600;
  margin-top: 6px;
}
.desc {
  color: #7a8a99;
  font-size: 13px;
  margin-top: 4px;
}
.check button {
  background: #3b82f6;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 10px 24px;
  font-size: 15px;
  cursor: pointer;
}
.check button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.panel {
  margin-top: 20px;
  border-radius: 8px;
  padding: 16px;
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
  color: #5a6b7d;
}
.panel dd {
  font-family: Consolas, monospace;
  word-break: break-all;
}
.hint {
  margin-top: 8px;
  color: #7a8a99;
  font-size: 13px;
}
</style>
