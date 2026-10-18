# Optical Mirrors

A wall-mounted, real-time reflective mirror for **Minecraft Java Edition 1.21.11 + Fabric**.

Each mirror places a virtual camera at the position of its glass face, looking outward along the
normal, and draws the rendered result back onto the mirror surface. Adjacent mirrors join into a
single large mirror with a continuous, seamless image.

Author: Shanque123

Crafting: 5 glass panes of any kind + 4 planks of any kind → 4 mirrors
Drops: Silk Touch required

---

## 1. Features

| Feature | Description |
| --- | --- |
| Six-way wall mounting | `facing` supports up/down/east/west/south/north — mounts onto any block face like an item frame |
| Full-block coverage | Each pane is 16×16; a 3×3 arrangement of adjacent mirrors joins seamlessly into one large mirror |
| Real-time image | The camera sits just outside the glass face, aimed along the normal, showing the actual world as seen from the mirror's position |
| Iris compatible | Optional **vanilla pipeline**, which renders correctly and cuts the cost to 2–5 ms |
| Instant place/break | Client-side block-entity registry with a per-tick sync, latency ≤ 1 tick |
| Tunable | `/omirror config` screen + `/omirror set` commands (Mod Menu compatible) |

---

## 2. Compatibility and Versions

Tested against Iris **1.10.8** and Fabric API **0.141.6**.
A prebuilt jar is included in the `HERE` folder and can be installed directly.

---

## 3. Configuration

Config file: `config/opticalmirrors.json` (generated automatically on first launch).

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `pixelsPerBlock` | `64` | Target pixel density per block (computed against the effective, cropped area) |
| `maxResolution` | `768` | Maximum texture edge length |
| `planesPerTick` | `2` | Maximum number of planes rendered per tick |
| `intervalTicks` | `2` | Base refresh interval for a plane, in ticks |
| `budgetMs` | `3.0` | **Per-tick time budget for off-screen rendering** |
| `maxDistance` | `64` | Maximum render distance |
| `irisMode` | `vanilla` | Shader strategy |
| `irisIntervalTicks` | `20` | Only used in `throttle` mode |

### Commands

```text
/omirror                      Show status
/omirror on | off | toggle    Master switch
/omirror config               Open the settings screen
/omirror list                 List every key and value
/omirror get <key>
/omirror set <key> <value>    e.g. set budgetMs 2.5
/omirror preset low|medium|high
/omirror reset                Restore defaults
/omirror save | reload
```

---

## 4. Code Structure

```
src/main/java/com/gzz/opticalmirrors/
├── OpticalMirrors.java                 Registration entrypoint
├── block/
│   ├── MirrorBlock.java                Six-way wall block, glass-face coordinates
│   └── MirrorBlockEntity.java          Client-side mirror registry
└── client/
    ├── OpticalMirrorsClient.java       Event registration
    ├── MirrorCamera.java               Virtual camera + environment attribute sampling
    ├── MirrorReflection.java           Plane management, scheduling, off-screen render, drawing
    ├── IrisCompat.java                 Iris state detection and strategy
    ├── IrisPipelineOverride.java       Temporarily switch to the vanilla pipeline under shaders
    ├── UnsafeSwap.java                 Replace/restore private final fields
    ├── ReflectionTexture.java          RenderTarget color-texture wrapper
    ├── MirrorConfig.java               Config model
    ├── MirrorConfigScreen.java         Settings screen
    └── MirrorCommand.java              /omirror commands
```

---

## 5. Known Limitations

1. **Updates per tick**, so a lower `intervalTicks` means a smoother image.
2. Under shaders the mirror uses **vanilla rendering** and therefore has no shaderpack post-processing tint.
3. After 5 consecutive failures, real-time reflection switches itself off and falls back to a static glass face.
4. The mirror camera uses a fixed orientation, so head-bob while walking can still slightly affect the mirrored image.

# 光学镜面 · Optical Mirrors

适用于 **Minecraft Java Edition 1.21.11 + Fabric** 的贴墙式实时反射镜。

镜子：在每面镜子在自己的玻璃面位置放一台虚拟相机，沿法线方向看出去，
把渲染结果画回镜面。相邻的镜子会拼成一整面大镜子，画面连续无缝。


作者：Shanque123

合成：5 个任意玻璃板 + 4 个任意木板 → 4 块镜子
掉落：需精准采集

---

## 一、特性

| 特性        | 说明                                                      |
| --------- | ------------------------------------------------------- |
| 六向贴墙      | `facing` 支持上/下/东/西/南/北，像物品展示框一样贴在方块表面                   |
| 铺满整格      | 镜板 16×16，3×3 相邻镜面无缝拼成一面大镜子                              |
| 实时画面      | 相机在玻璃面外侧、朝法线方向，显示"从镜子位置看出去"的真实世界                        |
| Iris 兼容   | 可选**原版管线**，画面正确且开销降到 2–5ms                              |
| 放置/破坏立即生效 | 客户端方块实体注册表 + 每 tick 同步，延迟 ≤1 tick                       |
| 可调参数      | `/omirror config` 面板 + `/omirror set` 指令（本模组兼容Mod Menu） |


---

## 二、兼容性与版本

产品测试时使用的Iris版本号为1.10.8版本
Fabric API 0.141.6
文件内有一份打包好的Jar文件在HERE文件夹内可直接安装


---

## 三、配置

配置文件：`config/opticalmirrors.json`（首次启动自动生成）。

| 键                   | 默认        | 说明                    |
| ------------------- | --------- | --------------------- |
| `enabled`           | `true`    | 总开关                   |
| `pixelsPerBlock`    | `64`      | 每格目标像素密度（按裁剪后的有效区域计算） |
| `maxResolution`     | `768`     | 贴图单边上限                |
| `planesPerTick`     | `2`       | 每 tick 最多渲染平面数        |
| `intervalTicks`     | `2`       | 平面的基础刷新间隔（tick）       |
| `budgetMs`          | `3.0`     | **每 tick 离屏渲染时间预算**   |
| `maxDistance`       | `64`      | 最大渲染距离                |
| `irisMode`          | `vanilla` | 光影策略                  |
| `irisIntervalTicks` | `20`      | 仅 `throttle` 模式使用     |

### 指令

```text
/omirror                      查看状态
/omirror on | off | toggle    总开关
/omirror config               打开设置面板
/omirror list                 列出全部键值
/omirror get <键>
/omirror set <键> <值>         例如 set budgetMs 2.5
/omirror preset low|medium|high
/omirror reset                恢复默认
/omirror save | reload
```

---

## 四、代码结构

```
src/main/java/com/gzz/opticalmirrors/
├── OpticalMirrors.java                 注册入口
├── block/
│   ├── MirrorBlock.java                六向贴墙方块、玻璃面坐标
│   └── MirrorBlockEntity.java          客户端镜面注册表
└── client/
    ├── OpticalMirrorsClient.java       事件注册
    ├── MirrorCamera.java               虚拟相机 + 环境属性采样
    ├── MirrorReflection.java           平面管理、调度、离屏渲染、绘制
    ├── IrisCompat.java                 Iris 状态探测与策略
    ├── IrisPipelineOverride.java       光影下临时切原版管线
    ├── UnsafeSwap.java                 私有 final 字段替换/还原
    ├── ReflectionTexture.java          RenderTarget 颜色贴图包装
    ├── MirrorConfig.java               配置模型
    ├── MirrorConfigScreen.java         设置面板
    └── MirrorCommand.java              /omirror 指令
```

---

## 五、已知限制

1. **按 tick 更新**,`intervalTicks` 越小越流畅。
2. 光影下镜面使用**原版渲染**，没有光影包的后处理色调。
3. 连续失败 5 次会自动关闭实时反射并退回静态玻璃面。
4. 镜面相机是固定视角，玩家走路时的视角摇晃仍会轻微影响镜中画面。
