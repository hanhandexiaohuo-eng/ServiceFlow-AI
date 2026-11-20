<script setup lang="ts">
/** 负载条：颜色与文案由后端 loadLevel 驱动，前端只做展示 */
import { computed } from 'vue'
import { LOAD_LEVEL_COLOR } from '../constants/dispatch'

const props = withDefaults(
  defineProps<{ rate: number; level: string; showText?: boolean }>(),
  { showText: true }
)

const color = computed(() => LOAD_LEVEL_COLOR[props.level] ?? '#94a3b8')
const width = computed(() => `${Math.max(0, Math.min(100, props.rate))}%`)
</script>

<template>
  <div class="load-bar">
    <div class="track">
      <div class="fill" :style="{ width, background: color }" />
    </div>
    <span v-if="showText" class="txt">{{ rate.toFixed(1) }}%</span>
  </div>
</template>

<style scoped>
.load-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.track {
  flex: 1;
  min-width: 40px;
  height: 6px;
  border-radius: 999px;
  background: var(--line-soft);
  overflow: hidden;
}
.fill {
  height: 100%;
  border-radius: inherit;
  transition: width 0.25s ease;
}
.txt {
  flex: none;
  font-size: 11px;
  line-height: 1;
  color: var(--ink-4);
  font-variant-numeric: tabular-nums;
}
</style>
