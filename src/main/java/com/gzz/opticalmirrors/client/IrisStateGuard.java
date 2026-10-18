package com.gzz.opticalmirrors.client;

import java.lang.reflect.Field;

/**
 * 在镜面离屏渲染前后，快照并还原 Iris 的 {@code CapturedRenderingState}。
 *
 * <h2>为什么必须还原（光影闪烁的根因）</h2>
 * Iris 的 {@code MixinLevelRenderer.iris$setupPipeline} 注入在
 * {@code LevelRenderer.renderLevel} 的 HEAD，而且<b>没有任何条件判断</b>：
 * <pre>
 * CapturedRenderingState.INSTANCE.setGbufferModelView(modelView);
 * CapturedRenderingState.INSTANCE.setGbufferProjection(projection);
 * CapturedRenderingState.INSTANCE.setTickDelta(fakeTickDelta);
 * CapturedRenderingState.INSTANCE.setCloudTime(...);
 * </pre>
 *
 * <p>这些都是<b>帧级全局状态</b>：主画面的 shadow / composite 阶段会读
 * {@code gbufferModelView} + {@code gbufferProjection} 去重建世界坐标。
 * 我们每 tick 额外跑一遍 {@code renderLevel} 后，这些值就变成了"镜子相机"的矩阵，
 * 于是主画面下一帧用错的矩阵做后处理 —— 表现就是整屏随着镜子更新频率
 * 抖动、错位、闪烁，光影包越复杂越明显（它们用这些矩阵采样 shadow / 反射 / SSAO）。
 *
 * <p>{@code fogColor} / {@code fogDensity} 是
 * {@code MixinFogRenderer} 在 {@code setupFog} 里写的，同理会被镜子位置覆盖。
 *
 * <h2>为什么按字段名反射，不用 getter/setter</h2>
 * 这样只要字段名不变就能工作，也不用逐版本维护 8 个 setter；
 * 万一 Iris 改了字段名，{@link #capture()} 返回的空句柄不会影响渲染。
 * Iris 在 Fabric 下是普通 mod jar（未命名模块），{@code setAccessible} 没有限制。
 */
final class IrisStateGuard {

	/** 一次快照。*/
	static final class Handle {
		private final Object instance;
		private final Field[] fields;
		private final Object[] objects;
		private final float[] floats;
		private final int[] ints;

		private Handle(Object instance, Field[] fields, Object[] objects, float[] floats, int[] ints) {
			this.instance = instance;
			this.fields = fields;
			this.objects = objects;
			this.floats = floats;
			this.ints = ints;
		}

		void restore() {
			if (this.instance == null) {
				return;
			}
			for (int i = 0; i < this.fields.length; i++) {
				Field f = this.fields[i];
				try {
					Class<?> t = f.getType();
					if (t == float.class) {
						f.setFloat(this.instance, this.floats[i]);
					} else if (t == int.class) {
						f.setInt(this.instance, this.ints[i]);
					} else {
						f.set(this.instance, this.objects[i]);
					}
				} catch (Throwable ignored) {
					// 单个字段失败不影响其它
				}
			}
		}
	}

	private static final Handle EMPTY = new Handle(null, new Field[0], new Object[0], new float[0], new int[0]);

	private static boolean initialized;
	private static Object instance;
	private static Field[] fields;

	private IrisStateGuard() {
	}

	private static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			Class<?> cls = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
			Field inst = cls.getField("INSTANCE");
			instance = inst.get(null);

			java.util.List<Field> list = new java.util.ArrayList<>();
			for (Field f : cls.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				Class<?> t = f.getType();
				// 只快照能廉价复制的类型：基本类型与对象引用
				if (t.isPrimitive() && t != float.class && t != int.class) {
					continue;
				}
				try {
					f.setAccessible(true);
					list.add(f);
				} catch (Throwable ignored) {
					// 单个字段不可访问就跳过
				}
			}
			fields = list.toArray(new Field[0]);
		} catch (Throwable t) {
			instance = null;
			fields = null;
		}
	}

	/** 快照当前状态。没装 Iris / 结构不匹配时返回 {@link #EMPTY}。*/
	static Handle capture() {
		init();
		if (instance == null || fields == null || fields.length == 0) {
			return EMPTY;
		}
		Object[] objects = new Object[fields.length];
		float[] floats = new float[fields.length];
		int[] ints = new int[fields.length];
		try {
			for (int i = 0; i < fields.length; i++) {
				Field f = fields[i];
				Class<?> t = f.getType();
				if (t == float.class) {
					floats[i] = f.getFloat(instance);
				} else if (t == int.class) {
					ints[i] = f.getInt(instance);
				} else {
					objects[i] = f.get(instance);
				}
			}
			return new Handle(instance, fields, objects, floats, ints);
		} catch (Throwable t) {
			return EMPTY;
		}
	}
}
