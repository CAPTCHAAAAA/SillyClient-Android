# 前端性能优化方案（保持视觉效果）

## 策略1: GPU加速与合成层优化

### 为关键元素添加GPU加速
- 使用 transform: translateZ(0) 或 will-change 提升到独立合成层
- 只在需要动画的元素上使用 will-change，静止时移除

### 实施位置
- 所有带 backdrop-filter 的元素
- 所有动画元素
- 卡片翻转、抽屉滑动等交互元素

## 策略2: CSS Contain 隔离重绘

### 使用 contain 属性隔离渲染
- contain: layout style paint - 完全隔离
- contain: layout - 布局隔离（适合大多数场景）

### 实施位置
- 每个实例卡片
- 键盘输入区域
- 独立的模态框和抽屉

## 策略3: 动画期间降级非关键效果

### 键盘动画时暂停毛玻璃采样
- 已有 data-sc-ime-anim 机制
- 扩展到所有滚动和拖拽动画

### 实施
\\\css
[data-animating] .glass-element {
  backdrop-filter: none !important;
  background: rgba(var(--bg-rgb), 0.95); /* 用高不透明度纯色替代 */
}
\\\

## 策略4: 减少不必要的重排

### 使用 transform 替代位置属性
- 用 transform: translateY() 替代 top/bottom
- 用 transform: scale() 替代 width/height 动画

### 避免触发重排的属性
- 不在动画中使用 padding/margin/border
- 用 transform 和 opacity 实现所有动画

## 策略5: 优化毛玻璃性能（不降级视觉）

### 技巧
- 确保毛玻璃元素在独立合成层
- 使用 backface-visibility: hidden 强制硬件加速
- 减少毛玻璃元素的嵌套层级

## 实施顺序
1. 添加 CSS contain（快速见效）
2. GPU加速提升（立即改善）
3. 动画期间降级（体验平滑）
4. 优化动画属性（长期优化）
