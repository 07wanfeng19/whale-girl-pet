/**
 * dsh-pet-inbox 的稳定入口。
 *
 * 宿主（cordis 加载器）按「模块 URL」缓存 ESM，所以入口文件一旦被加载过，
 * 改它的内容不会生效，必须重启宿主。这里把入口做成极薄的 shim：
 * apply() 每次被调用时，带时间戳动态 import 真正的实现 impl.js。
 * 于是「改 impl.js → 重新启用插件」就能加载新代码，不用重启宿主。
 *
 * 注意：入口自身的 URL 带 ?v=1（见 cordis.patch.yml），不要改成不带查询串的
 * 裸文件 URL —— 那个 URL 已经被旧版本占用，会读到缓存里的旧模块。
 */
export const inject = ['sessionController', 'commands']

export async function apply(ctx, config = {}) {
  const url = new URL('./impl.js?v=' + Date.now(), import.meta.url).href
  const mod = await import(url)
  return mod.apply(ctx, config)
}
