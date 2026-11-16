<script setup lang="ts">
/**
 * 统一图标组件：内置线性 SVG 图标，避免引入图标库、保证风格一致。
 * 全部为 24×24 视图、1.8 描边、currentColor 取色。
 */
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })

const PATHS: Record<string, string> = {
  dashboard: 'M3 3h7v7H3zM14 3h7v4h-7zM14 11h7v10h-7zM3 14h7v7H3z',
  tickets: 'M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM9 8h6M9 12h6M9 16h4',
  board: 'M4 4h5v16H4zM15 4h5v10h-5z',
  myTicket: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1',
  workflow: 'M6 4h4v4H6zM14 4h4v4h-4zM6 16h4v4H6zM14 16h4v4h-4zM9 6h5M11 9v6M16 9v6',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
  route: 'M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM18 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM16 7H9a4 4 0 0 0 0 8h6a4 4 0 0 1 0 8H9',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75',
  sparkles: 'm12 3 1.9 4.6L18.5 9.5 13.9 11.4 12 16l-1.9-4.6L5.5 9.5l4.6-1.9L12 3zM19 15l.9 2.1 2.1.9-2.1.9L19 21l-.9-2.1-2.1-.9 2.1-.9L19 15z',
  tag: 'M20 12 12 20l-8-8V4h8l8 8zM7.5 7.5h.01',
  bulb: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5.9 1.2.9 2V17h5.2v-1.1c0-.8.3-1.5.9-2A6 6 0 0 0 12 3z',
  chart: 'M4 20V10M10 20V4M16 20v-6M22 20H2',
  gauge: 'M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18zM12 12l4-4',
  award: 'M12 15a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM8.5 14 7 22l5-3 5 3-1.5-8',
  book: 'M4 5a2 2 0 0 1 2-2h13v18H6a2 2 0 0 1-2-2V5zM8 3v18',
  monitor: 'M3 4h18v12H3zM9 20h6M12 16v4',
  logs: 'M6 3h9l4 4v14H6zM14 3v4h4M9 12h6M9 16h6',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3',
  bell: 'M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0',
  todo: 'M9 11l3 3 8-8M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h9',
  chevronDown: 'm6 9 6 6 6-6',
  chevronLeft: 'm15 18-6-6 6-6',
  chevronRight: 'm9 18 6-6-6-6',
  plus: 'M12 5v14M5 12h14',
  refresh: 'M21 12a9 9 0 1 1-3-6.7M21 3v6h-6',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
  panel: 'M3 4h18v16H3zM9 4v16',
  server: 'M3 4h18v6H3zM3 14h18v6H3zM7 7h.01M7 17h.01',
  database: 'M12 8c4.4 0 8-1.3 8-3s-3.6-3-8-3-8 1.3-8 3 3.6 3 8 3zM4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  cpu: 'M6 6h12v12H6zM9 9h6v6H9zM2 9h4M2 15h4M18 9h4M18 15h4M9 2v4M15 2v4M9 18v4M15 18v4',
  alert: 'M12 3 2 21h20L12 3zM12 9v5M12 18h.01',
  close: 'M6 6l12 12M18 6L6 18',
  filter: 'M3 5h18l-7 8v6l-4 2v-8L3 5z',
  inbox: 'M4 13h4l2 3h4l2-3h4M4 13 6 5h12l2 8v6H4v-6z',
  arrowRight: 'M5 12h14M13 6l6 6-6 6',
  check: 'm5 13 4 4L19 7',
  activity: 'M3 12h4l3 8 4-16 3 8h4',
  trendUp: 'M3 17l6-6 4 4 8-8M15 7h6v6',
  layers: 'm12 3 9 5-9 5-9-5 9-5zM3 13l9 5 9-5',
}
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="PATHS[name] ?? PATHS.dashboard" />
  </svg>
</template>
