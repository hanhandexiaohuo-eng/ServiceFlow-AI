<script setup lang="ts">
import { STATUS_LABEL } from '../constants/ticket'

defineProps<{ status: string }>()
</script>

<template>
  <span class="tag" :class="'s-' + status">
    <i class="dot" />
    {{ STATUS_LABEL[status] ?? status }}
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  border: 1px solid transparent;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.s-PENDING {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}
.s-ASSIGNED {
  color: #0369a1;
  background: #f0f9ff;
  border-color: #bae6fd;
}
.s-ACCEPTED {
  color: #6d28d9;
  background: #f5f3ff;
  border-color: #ddd6fe;
}
.s-PROCESSING {
  color: #4338ca;
  background: #eef2ff;
  border-color: #c7d2fe;
}
.s-RESOLVED {
  color: #047857;
  background: #ecfdf5;
  border-color: #a7f3d0;
}
.s-CLOSED {
  color: #475569;
  background: #f8fafc;
  border-color: #e2e8f0;
}
.s-CANCELLED {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}
</style>
