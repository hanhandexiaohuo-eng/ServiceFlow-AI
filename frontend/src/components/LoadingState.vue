<script setup lang="ts">
withDefaults(defineProps<{ text?: string; height?: number }>(), { text: '加载中…', height: 120 })
</script>

<template>
  <div class="loading" :style="{ minHeight: height + 'px' }">
    <span class="spinner" />
    <span class="text">{{ text }}</span>
  </div>
</template>

<style scoped>
.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--ink-4);
  font-size: 13px;
}
.spinner {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid #e2e8f0;
  border-top-color: var(--brand);
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
