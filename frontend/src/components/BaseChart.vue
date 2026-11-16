<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type EChartsCoreOption } from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 按需注册：只打包用到的图表与组件，避免整包引入
use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = withDefaults(defineProps<{ option: EChartsCoreOption; height?: number }>(), { height: 280 })

const el = ref<HTMLDivElement | null>(null)
let chart: ReturnType<typeof init> | null = null
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) return
  chart = init(el.value, undefined, { renderer: 'canvas' })
  chart.setOption(props.option)
  // 容器宽度变化（侧边栏折叠、窗口缩放）时重绘，防止图表变形
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(el.value)
})

watch(
  () => props.option,
  (option) => chart?.setOption(option, { notMerge: true }),
  { deep: true }
)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="chart" :style="{ height: height + 'px' }" />
</template>

<style scoped>
.chart {
  width: 100%;
  min-width: 0;
}
</style>
