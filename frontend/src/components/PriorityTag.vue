<script setup lang="ts">
import { PRIORITY_LABEL } from '../constants/ticket'

defineProps<{ priority: string }>()
</script>

<template>
  <span class="tag" :class="'p-' + priority">{{ PRIORITY_LABEL[priority] ?? priority }}</span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1;
  font-weight: 500;
  white-space: nowrap;
  border: 1px solid transparent;
}
.p-LOW {
  color: #64748b;
  background: #f8fafc;
  border-color: #e2e8f0;
}
.p-MEDIUM {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}
.p-HIGH {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}
.p-URGENT {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}
</style>
