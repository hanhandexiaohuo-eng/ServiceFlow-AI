import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { MENU_GROUPS } from '../src/constants/menu.ts'
import { FEATURE_FLAGS, isFeatureEnabled } from '../src/constants/features.ts'

/**
 * 导航一致性测试：保证「菜单项 ↔ 路由 ↔ 功能开关」三者不脱节。
 * 使用 Node 内置测试运行器与 TypeScript 类型擦除（Node 24），无需额外依赖。
 */
const here = dirname(fileURLToPath(import.meta.url))
const routerSource = readFileSync(resolve(here, '../src/router/index.ts'), 'utf8')
const sidebarSource = readFileSync(resolve(here, '../src/components/Sidebar.vue'), 'utf8')

const items = MENU_GROUPS.flatMap((group) => group.items)

test('菜单路径唯一且以 / 开头', () => {
  const paths = items.map((item) => item.to)
  assert.equal(new Set(paths).size, paths.length, '存在重复菜单路径')
  for (const path of paths) {
    assert.ok(path.startsWith('/'), `菜单路径必须以 / 开头：${path}`)
  }
})

test('每个已接入的菜单项都有对应路由', () => {
  for (const item of items) {
    if (item.soon) continue // soon 路由由 MENU_GROUPS 自动生成
    const relative = item.to.replace(/^\//, '')
    assert.ok(
      routerSource.includes(`path: '${relative}'`),
      `菜单「${item.label}」指向 ${item.to}，但路由表缺少 path: '${relative}'`
    )
  }
})

test('未接入功能由功能开关控制，直链会落到「功能未启用」页', () => {
  for (const item of items.filter((i) => i.feature)) {
    assert.ok(
      Object.prototype.hasOwnProperty.call(FEATURE_FLAGS, item.feature),
      `菜单「${item.label}」引用了未定义的开关：${item.feature}`
    )
    if (!isFeatureEnabled(item.feature)) {
      assert.ok(item.soon, `开关关闭的「${item.label}」必须标记 soon，保证直链可解释`)
    }
  }
})

test('侧边栏按功能开关与管理员权限过滤菜单', () => {
  assert.ok(sidebarSource.includes('isFeatureEnabled'), '侧边栏必须使用功能开关')
  assert.ok(sidebarSource.includes('visibleGroups'), '侧边栏必须基于过滤后的菜单渲染')
  assert.ok(sidebarSource.includes('adminOnly'), '侧边栏必须按 adminOnly 过滤管理员入口')
})

test('用户管理、操作日志与 AI 模型管理已接入真实页面，且仅管理员可见', () => {
  assert.equal(FEATURE_FLAGS.systemUsers, true, '用户管理已接入后端，开关应开启')
  assert.equal(FEATURE_FLAGS.systemLogs, true, '操作日志已接入后端，开关应开启')
  assert.equal(FEATURE_FLAGS.systemAi, true, 'AI 模型管理已接入后端，开关应开启')
  for (const path of ['/system/users', '/system/logs', '/system/ai']) {
    const item = items.find((entry) => entry.to === path)
    assert.ok(item, `菜单缺少 ${path}`)
    assert.ok(!item.soon, `${path} 已接入真实页面，不得再标记 soon`)
    assert.ok(item.adminOnly, `${path} 必须限制为仅管理员可见`)
  }
  assert.ok(routerSource.includes("path: 'system/users'"), '路由表缺少用户管理真实页面')
  assert.ok(routerSource.includes("path: 'system/logs'"), '路由表缺少操作日志真实页面')
  assert.ok(routerSource.includes("path: 'system/ai'"), '路由表缺少 AI 模型管理真实页面')
})

test('直链可直接访问：使用 history 路由且 nginx 回退到 index.html', () => {
  const nginxSource = readFileSync(resolve(here, '../nginx.conf'), 'utf8')
  assert.ok(routerSource.includes('createWebHistory()'), '必须使用 history 路由，直链 /system/users 才能命中路由')
  assert.ok(!routerSource.includes('createWebHashHistory()'), '不得使用 hash 路由，否则直链会被当作首页')
  assert.ok(nginxSource.includes('try_files $uri $uri/ /index.html'), 'nginx 必须为 SPA 路由回退到 index.html')
})
