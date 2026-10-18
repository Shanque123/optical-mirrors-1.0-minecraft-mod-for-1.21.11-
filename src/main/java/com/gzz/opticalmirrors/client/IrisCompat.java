package com.gzz.opticalmirrors.client;

import java.lang.reflect.Method;

/**
 * Iris 兼容层：用<b>反射</b>检测光影状态，不添加任何编译期依赖。
 *
 * <h2>为什么光影一开就没反射、还特别卡</h2>
 * Iris 接管渲染管线后，每帧的 world 渲染不再是"往一个 RenderTarget 画一遍"，
 * 而是分段（gbuffers / shadow / composite ...）+ 多张目标。我们的离屏渲染是在
 * tick 阶段再跑一遍 {@code GameRenderer.renderLevel}，于是：
 * <ul>
 *     <li><b>结果可能错</b>：这一次重入会被 Iris 当作管线里的一环；</li>
 *     <li><b>极卡</b>：每次重入都会重跑 shadow pass 与全部 composite 阶段，
 *         实测单次 19–20ms。<b>注意这是固定成本</b>，与我们自己贴图的
 *         分辨率几乎无关 —— 所以"降分辨率"救不了，只能降频率。</li>
 * </ul>
 *
 * <h2>缓存必须按 tick 走，不能按调用次数</h2>
 * 上一版用"每次调用减一"的冷却计数器，结果 {@link #shouldPause()} 和日志里的
 * {@link #describe()} 谁先调用谁就把冷却吃掉，导致两处读到不一致的结论 ——
 * 表现就是日志里"已暂停"和"正在渲染（20ms/次）"交替出现，也就是玩家感觉到的
 * "光影下特别卡"。现在改成每 tick 由 {@link #tick(int)} 刷新一次，
 * {@link #isActive()} 是纯读取，任何调用方都不会改变结果。
 *
 * <h2>为什么用反射</h2>
 * 一旦编译期依赖 Iris，没装 Iris 的玩家就会 {@code NoClassDefFoundError}。
 * Iris 官方提供了稳定的 API（{@code IrisApi.getInstance().isShaderPackInUse()}），
 * 反射调用即可，且 {@code Class.forName} 失败时就是"没有 Iris"。
 */
final class IrisCompat {

	private static final int CACHE_TICKS = 10;

	private static boolean initialized;
	private static boolean irisPresent;
	private static Method getInstance;
	private static Method isShaderPackInUse;

	/** 最近一次探测结果。只能由 {@link #tick(int)} 改写。 */
	private static boolean active;
	private static int lastProbe = Integer.MIN_VALUE / 2;

	private IrisCompat() {
	}

	static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			getInstance = api.getMethod("getInstance");
			isShaderPackInUse = api.getMethod("isShaderPackInUse");
			irisPresent = true;
		} catch (Throwable t) {
			// 没有 Iris：不是错误，正常情况
			irisPresent = false;
		}
	}

	static boolean irisPresent() {
		init();
		return irisPresent;
	}

	/** 每 tick 调用一次，刷新缓存。是唯一会改变状态的入口。 */
	static void tick(int ticks) {
		init();
		if (!irisPresent) {
			active = false;
			return;
		}
		if (ticks - lastProbe < CACHE_TICKS) {
			return;
		}
		lastProbe = ticks;
		try {
			Object instance = getInstance.invoke(null);
			Object result = isShaderPackInUse.invoke(instance);
			active = result instanceof Boolean b && b;
		} catch (Throwable t) {
			// API 变动/内部异常：当作没开光影
			active = false;
		}
	}

	/** 光影是否正在生效。纯读取，不影响任何缓存。 */
	static boolean isActive() {
		init();
		return active;
	}

	/**
	 * 当前策略下应该"完全跳过渲染"吗（保留最后一帧）。
	 *
	 * <p>{@code VANILLA} / {@code THROTTLE} 不在这里返回 true —— 它们要渲染，
	 * 前者切原版管线，后者只是降频，见 {@link #irisThrottleDivisor()}。
	 */
	static boolean shouldPause() {
		MirrorConfig.IrisMode mode = MirrorConfig.get().irisMode;
		return switch (mode) {
			case IGNORE, THROTTLE, VANILLA -> false;
			case DISABLE -> irisPresent();
			case AUTO -> isActive();
		};
	}

	/**
	 * 这一趟镜面渲染是否应该临时切回原版管线。
	 *
	 * <p>Iris 的 {@code IrisRenderingPipeline} 把 gbuffer、阴影、合成阶段都缓存成
	 * 自己的 FBO，并且每趟都会按 {@code getMainRenderTarget().width/height} 重建。
	 * 我们在 tick 里重入 {@code renderLevel} 时，它会以为这是一帧新画面，
	 * 把整条 gbuffer 按镜面贴图的尺寸重建、再合成到某个目标上；结果是
	 * 镜面内容不是最终画面，而且每次都要付 shadow + composite 的 ~20ms。
	 *
	 * <p>把 {@code PipelineManager} 临时换成 {@code VanillaRenderingPipeline}
	 * 后，Iris 的所有 mixin 都会走"没开光影"的分支（它们都按
	 * {@code pipeline instanceof IrisRenderingPipeline} 判断），于是这一趟
	 * 就是纯原版渲染：天空盒、天气、实体、雾全部正常，耗时 2–3ms。
	 * 渲染完立刻换回去，主画面仍然走光影。
	 */
	static boolean useVanillaPipeline() {
		return MirrorConfig.get().irisMode == MirrorConfig.IrisMode.VANILLA && isActive();
	}

	/**
	 * 光影生效时，更新频率需要额外除以多少。
	 *
	 * <p>只有 {@code THROTTLE} 会在光影生效时返回 &gt;1 的值。
	 * 一次离屏渲染是固定 ~20ms 的整帧成本，所以唯一的省法就是少做几次。
	 */
	static int irisThrottleDivisor() {
		MirrorConfig cfg = MirrorConfig.get();
		if (!isActive()) {
			return 1;
		}
		if (cfg.irisMode == MirrorConfig.IrisMode.VANILLA) {
			// 正常情况下原版管线替换让镜面这一趟只要 2–5ms，不需要降频；
			// 但万一 Iris 版本变动导致替换不可用，就退回降频，避免 20ms/tick。
			return IrisPipelineOverride.available() ? 1 : Math.max(1, cfg.irisIntervalTicks);
		}
		if (cfg.irisMode != MirrorConfig.IrisMode.THROTTLE) {
			return 1;
		}
		return Math.max(1, cfg.irisIntervalTicks);
	}

	/**
	 * 一句人类可读的状态，仅供<b>控制台日志</b>使用。
	 *
	 * <p>界面上不要用它 —— 界面必须用 {@link #describeKey()} + 语言文件，
	 * 才能跟随客户端语言变化（简体中文 → 中文，其它 → 英文）。
	 */
	static String describe() {
		init();
		if (!irisPresent) {
			return "Iris not installed";
		}
		if (!isActive()) {
			return "Iris installed, no shaderpack";
		}
		return "Iris shaderpack active (" + MirrorConfig.get().irisMode.name().toLowerCase(java.util.Locale.ROOT) + ")";
	}

	/** 状态对应的翻译键，供界面/指令使用。 */
	static String describeKey() {
		init();
		if (!irisPresent) {
			return "opticalmirrors.iris.absent";
		}
		if (!isActive()) {
			return "opticalmirrors.iris.idle";
		}
		return switch (MirrorConfig.get().irisMode) {
			case VANILLA -> "opticalmirrors.iris.vanilla";
			case THROTTLE -> "opticalmirrors.iris.throttle";
			case AUTO -> "opticalmirrors.iris.auto";
			case IGNORE -> "opticalmirrors.iris.ignore";
			case DISABLE -> "opticalmirrors.iris.disable";
		};
	}
}
