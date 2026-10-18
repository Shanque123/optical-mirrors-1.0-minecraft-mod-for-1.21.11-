package com.gzz.opticalmirrors.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在镜面离屏渲染期间，把 Iris 的 {@code PipelineManager} 临时换成原版管线。
 *
 * <h2>为什么必须这么做</h2>
 * Iris 1.10 的 {@code MixinLevelRenderer} 在 {@code LevelRenderer.renderLevel}
 * 的 HEAD 里调用 {@code preparePipeline(...).beginLevelRendering()}，
 * 在 TAIL 里调用 {@code finalizeLevelRendering()}。也就是说，我们哪怕只是
 * "再渲染一遍世界"，也会被 Iris 当成完整的一帧：
 * <ul>
 *     <li>gbuffer / 阴影 / composite 目标按 {@code getMainRenderTarget()} 的尺寸重建。
 *         我们把主目标换成了镜面贴图，于是 Iris 会把整条管线缩到 64×36 再缩回去；</li>
 *     <li>最终合成写回的是它自己缓存的目标，镜面贴图拿不到正确画面；</li>
 *     <li>每次重入都有 shadow + composite 的固定成本（实测 ~20ms）。</li>
 * </ul>
 *
 * <h2>换成原版管线为什么就对了</h2>
 * Iris 的所有 shader 相关 mixin 都用
 * {@code getPipeline() instanceof IrisRenderingPipeline} 判断是否接管；
 * 换成 {@code VanillaRenderingPipeline} 后它们全部走"没装光影"的分支。
 * 这正是 Iris 未启用光影时的正常运行状态，所以不会崩、也不需要 Mixin。
 *
 * <h2>只改两个字段</h2>
 * {@code PipelineManager} 有两个相关字段：
 * <ul>
 *     <li>{@code pipelinesPerDimension}：{@code preparePipeline} 会用它覆盖 {@code pipeline}；</li>
 *     <li>{@code pipeline}：mixin 直接读取的那份引用。</li>
 * </ul>
 * 两个都要换：只换 map 会被 {@code preparePipeline} 里的赋值覆盖，
 * 只换 pipeline 会被 map 里的旧值覆盖。渲染完按 slot 精确还原。
 *
 * <p>全部走反射 + {@link UnsafeSwap}，编译期不依赖 Iris；
 * 任何一步失败都返回空句柄，绝不影响主流程。
 */
final class IrisPipelineOverride {

	/** 一次替换。{@link #close()} 之后必须丢弃。 */
	static final class Handle implements AutoCloseable {
		private final List<UnsafeSwap.Slot> slots;

		private Handle(List<UnsafeSwap.Slot> slots) {
			this.slots = slots;
		}

		boolean active() {
			return !this.slots.isEmpty();
		}

		@Override
		public void close() {
			UnsafeSwap.restore(this.slots);
		}
	}

	private static final Handle NONE = new Handle(new ArrayList<>());

	private static boolean initialized;
	private static Method getPipelineManager;
	private static Method getCurrentDimension;
	private static Class<?> irisPipelineClass;
	private static Object vanillaPipeline;

	private IrisPipelineOverride() {
	}

	private static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			getPipelineManager = iris.getMethod("getPipelineManager");
			try {
				getCurrentDimension = iris.getMethod("getCurrentDimension");
			} catch (Throwable ignored) {
				getCurrentDimension = null;
			}

			irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");

			Class<?> vanilla = Class.forName("net.irisshaders.iris.pipeline.VanillaRenderingPipeline");
			var ctor = vanilla.getDeclaredConstructor();
			ctor.setAccessible(true);
			vanillaPipeline = ctor.newInstance();
		} catch (Throwable t) {
			getPipelineManager = null;
			getCurrentDimension = null;
			irisPipelineClass = null;
			vanillaPipeline = null;
		}
	}

	static boolean available() {
		init();
		return getPipelineManager != null && vanillaPipeline != null;
	}

	/**
	 * 开始替换。调用方必须在 finally 里 {@link Handle#close()}。
	 *
	 * <p>返回 {@link #NONE} 表示：没装 Iris、当前不是光影管线、或替换失败 ——
	 * 三种情况都不影响继续渲染。
	 */
	static Handle begin() {
		if (!IrisCompat.useVanillaPipeline()) {
			return NONE;
		}
		if (!available()) {
			warnOnce("Cannot load Iris pipeline classes; mirrors will keep using the shader pipeline "
					+ "(visual glitches / stutter possible)");
			return NONE;
		}
		try {
			Object manager = getPipelineManager.invoke(null);
			if (manager == null) {
				return NONE;
			}
			Object current = readPipeline(manager);
			if (current == null) {
				return NONE;
			}
			// 只有真正的光影管线才需要换；已经是原版管线就什么都不用做。
			if (irisPipelineClass == null || !irisPipelineClass.isInstance(current)) {
				return NONE;
			}

			Map<?, ?> oldMap = readPipelineMap(manager);
			if (oldMap == null) {
				return NONE;
			}
			Map<Object, Object> replacement = new HashMap<>();
			for (Object key : oldMap.keySet()) {
				replacement.put(key, vanillaPipeline);
			}
			// 刚切维度时当前维度可能还不在 map 里；补一个，避免
			// preparePipeline 重新 new 一个 IrisRenderingPipeline（那会很贵）。
			if (getCurrentDimension != null) {
				try {
					replacement.put(getCurrentDimension.invoke(null), vanillaPipeline);
				} catch (Throwable ignored) {
					// 拿不到就算了，正常帧里维度一定已在 map 中
				}
			}

			List<UnsafeSwap.Slot> slots = new ArrayList<>();
			try {
				slots.addAll(UnsafeSwap.replaceAll(List.of(manager), oldMap, replacement));
				slots.addAll(UnsafeSwap.replaceAll(List.of(manager), current, vanillaPipeline));
			} catch (Throwable t) {
				UnsafeSwap.restore(slots);
				return NONE;
			}
			return slots.isEmpty() ? NONE : new Handle(slots);
		} catch (Throwable t) {
			return NONE;
		}
	}

	private static boolean warned;

	private static void warnOnce(String message) {
		if (warned) {
			return;
		}
		warned = true;
		com.gzz.opticalmirrors.OpticalMirrors.LOGGER.warn("[OpticalMirrors] {}", message);
	}

	private static Object readPipeline(Object manager) {
		try {
			Method m = manager.getClass().getMethod("getPipelineNullable");
			return m.invoke(manager);
		} catch (Throwable t) {
			return null;
		}
	}

	private static Map<?, ?> readPipelineMap(Object manager) {
		Object value = UnsafeSwap.readByAssignableType(manager, Map.class);
		return value instanceof Map<?, ?> map ? map : null;
	}
}
