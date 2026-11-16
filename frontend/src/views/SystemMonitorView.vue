<script setup lang="ts">
import ServiceHealth from '../components/ServiceHealth.vue'
import AppIcon from '../components/AppIcon.vue'

/** 系统监控：展示真实存在的健康检查端点与依赖状态 */
const endpoints = [
  {
    name: '存活检查',
    method: 'GET',
    path: '/api/health',
    desc: '返回服务名、状态、版本与服务器时间',
    auth: '无需鉴权',
  },
  {
    name: '就绪检查',
    method: 'GET',
    path: '/api/health/ready',
    desc: '检查数据库等依赖组件，不健康时返回 503',
    auth: '无需鉴权',
  },
  {
    name: '工单分页查询',
    method: 'GET',
    path: '/api/tickets?page=&size=',
    desc: '驾驶舱与列表页的数据来源，单页最多 100 条',
    auth: 'Bearer Token',
  },
  {
    name: '登录',
    method: 'POST',
    path: '/api/auth/login',
    desc: '签发 JWT，前端存储于 localStorage',
    auth: '无需鉴权',
  },
]
</script>

<template>
  <div class="page">
    <ServiceHealth />

    <section class="card">
      <header class="head">
        <h3>监控端点</h3>
        <p>以下为当前后端真实提供的接口，前端均按此调用</p>
      </header>
      <div class="list">
        <div v-for="e in endpoints" :key="e.path" class="row">
          <span class="method" :class="e.method.toLowerCase()">{{ e.method }}</span>
          <div class="info">
            <code>{{ e.path }}</code>
            <p>{{ e.name }} · {{ e.desc }}</p>
          </div>
          <span class="auth">{{ e.auth }}</span>
        </div>
      </div>
    </section>

    <section class="card note-card">
      <span class="note-icon"><AppIcon name="alert" :size="16" /></span>
      <div>
        <h4>尚未接入的监控项</h4>
        <p>
          Python AI 服务（端口 8000）健康检查、接口耗时分布、JVM / 连接池指标均需后端新增聚合接口后才能展示，
          当前不做任何模拟数据。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.head {
  padding: 16px 18px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.head h3 {
  font-size: 15px;
  font-weight: 600;
}
.head p {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.list {
  padding: 6px 0;
}
.row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--line-soft);
}
.row:last-child {
  border-bottom: none;
}
.method {
  flex: none;
  width: 48px;
  text-align: center;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 0;
  border-radius: 5px;
  background: #f1f5f9;
  color: var(--ink-3);
}
.method.get {
  background: #eff6ff;
  color: #1d4ed8;
}
.method.post {
  background: #ecfdf5;
  color: #047857;
}
.info {
  flex: 1;
  min-width: 0;
}
.info code {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: var(--ink-1);
}
.info p {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.auth {
  flex: none;
  font-size: 11px;
  color: var(--ink-4);
}

.note-card {
  display: flex;
  gap: 12px;
  padding: 16px 18px;
  background: #fffbeb;
  border-color: #fde68a;
}
.note-icon {
  color: #b45309;
  flex: none;
  margin-top: 1px;
}
.note-card h4 {
  font-size: 13px;
  font-weight: 600;
  color: #92400e;
}
.note-card p {
  margin-top: 4px;
  font-size: 12px;
  color: #a16207;
  line-height: 1.7;
}
</style>
