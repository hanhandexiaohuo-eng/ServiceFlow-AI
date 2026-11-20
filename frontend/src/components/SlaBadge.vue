<script setup lang="ts">
/** SLA 徽标：统一列表 / 看板 / 详情三处的展示口径（数据来自后端 SLA 服务） */
import { computed } from 'vue'
import type { Ticket } from '../api/tickets'
import { getSla } from '../constants/ticket'

const props = defineProps<{ ticket: Ticket; showWarnText?: boolean }>()

const sla = computed(() => getSla(props.ticket))
</script>

<template>
  <span class="sla" :class="`is-${sla.state}`" :title="sla.detail">
    <i class="dot" />
    <span class="txt">{{ sla.short }}</span>
    <em v-if="showWarnText && sla.state === 'warn'" class="warn">即将超时</em>
  </span>
</template>

<style scoped>
.sla {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
  border: 1px solid transparent;
  font-variant-numeric: tabular-nums;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.is-ok {
  color: #475569;
  background: #f8fafc;
  border-color: #e2e8f0;
}
.is-warn {
  color: #b45309;
  background: #fffbeb;
  border-color: #fcd34d;
  font-weight: 600;
}
.is-overdue {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
  font-weight: 700;
}
.is-overdue .dot {
  animation: pulse 1.6s ease-in-out infinite;
}
.is-done {
  color: #64748b;
  background: #f1f5f9;
  border-color: #e2e8f0;
}
.warn {
  font-style: normal;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
  background: #f59e0b;
  color: #fff;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
</style>
