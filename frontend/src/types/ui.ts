/** 通用 UI 类型（放在独立的 .ts 中，避免在 <script setup> 内使用 export） */

export interface CrumbItem {
  label: string
  to?: string
}

export interface TableColumn {
  key: string
  label: string
  width?: string
  align?: 'left' | 'right' | 'center'
  nowrap?: boolean
}
