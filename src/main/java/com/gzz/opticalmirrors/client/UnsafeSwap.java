package com.gzz.opticalmirrors.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 {@code sun.misc.Unsafe} 临时改写私有字段，并记录改动以便精确还原。
 *
 * <h2>为什么不是"改一个字段"就够</h2>
 * 想让世界渲染输出到自己的贴图，只改 {@code Minecraft.mainRenderTarget} 是不够的：
 * 1.21.11 的 {@code LevelRenderer} 内部还<b>缓存了一份同类引用</b>（字节码里成片的
 * {@code getfield A} 与 {@code GameRenderer.getMainRenderTarget} 混用）。
 * 只改一个，画面仍会被写回主画面 —— 表现就是"镜子里什么都没有、也完全没变化"。
 *
 * <p>所以这里改成：<b>扫描整个对象，把所有"当前值等于旧对象"的字段一次性替换</b>，
 * 渲染完再统一还原。这样无论内部缓存了多少份，都会被覆盖到。
 *
 * <p>用反射调用 Unsafe 而不是直接引用 {@code sun.misc.Unsafe}，
 * 是为了在编译器模块检查下也能通过；运行期拿不到时 {@link #AVAILABLE} 为 false，
 * 上层会自动关闭反射功能。
 */
final class UnsafeSwap {

	private static final Object UNSAFE;
	private static final Method OBJECT_FIELD_OFFSET;
	private static final Method GET_OBJECT;
	private static final Method PUT_OBJECT;
	static final boolean AVAILABLE;

	static {
		Object u = null;
		Method offset = null;
		Method get = null;
		Method put = null;
		boolean ok = false;
		try {
			Class<?> cls = Class.forName("sun.misc.Unsafe");
			Field f = cls.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			u = f.get(null);
			offset = cls.getMethod("objectFieldOffset", Field.class);
			get = cls.getMethod("getObject", Object.class, long.class);
			put = cls.getMethod("putObject", Object.class, long.class, Object.class);
			ok = true;
		} catch (Throwable ignored) {
			ok = false;
		}
		UNSAFE = u;
		OBJECT_FIELD_OFFSET = offset;
		GET_OBJECT = get;
		PUT_OBJECT = put;
		AVAILABLE = ok;
	}

	/** 一处改动。 */
	record Slot(Object holder, long offset, Object old) {
	}

	private UnsafeSwap() {
	}

	/**
	 * 把 {@code holders} 里所有"值 == from"的字段换成 {@code to}。
	 *
	 * @return 改动记录，交给 {@link #restore(List)} 还原；不做任何异常抛出
	 */
	static List<Slot> replaceAll(List<Object> holders, Object from, Object to) {
		List<Slot> slots = new ArrayList<>();
		if (!AVAILABLE || from == null || to == null) {
			return slots;
		}
		for (Object holder : holders) {
			if (holder == null) {
				continue;
			}
			for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				Field[] fields;
				try {
					fields = c.getDeclaredFields();
				} catch (Throwable t) {
					continue;
				}
				for (Field f : fields) {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
						continue;
					}
					if (!f.getType().isInstance(from) && !f.getType().isAssignableFrom(from.getClass())) {
						continue;
					}
					try {
						long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, f);
						Object current = GET_OBJECT.invoke(UNSAFE, holder, off);
						if (current == from) {
							PUT_OBJECT.invoke(UNSAFE, holder, off, to);
							slots.add(new Slot(holder, off, from));
						}
					} catch (Throwable ignored) {
						// 单个字段失败不影响其它字段
					}
				}
			}
		}
		return slots;
	}

	/**
	 * 按<b>类型</b>把 {@code declaredIn} 里所有该类型的实例字段写成 {@code value}。
	 *
	 * <p>用于给父类的 protected 字段赋值而不引用其名字 —— 名字在不同映射下不同，
	 * 类型不会。找不到匹配字段就静默跳过。
	 */
	static void setByType(Class<?> declaredIn, Object holder, Class<?> type, Object value) {
		if (!AVAILABLE || holder == null || value == null) {
			return;
		}
		for (Field f : declaredIn.getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			if (!f.getType().equals(type)) {
				continue;
			}
			try {
				long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, f);
				PUT_OBJECT.invoke(UNSAFE, holder, off, value);
			} catch (Throwable ignored) {
				// 单个字段失败不影响其它
			}
		}
	}

	/**
	 * 按<b>可赋值类型</b>读出一个字段的值。
	 *
	 * <p>匹配 {@code type.isAssignableFrom(字段类型)}，用于字段声明成接口/父类的场景
	 * （例如 Iris 的
	 * {@code Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension}）。
	 */
	static Object readByAssignableType(Object holder, Class<?> type) {
		if (!AVAILABLE || holder == null) {
			return null;
		}
		for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				if (!type.isAssignableFrom(f.getType())) {
					continue;
				}
				try {
					long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, f);
					return GET_OBJECT.invoke(UNSAFE, holder, off);
				} catch (Throwable ignored) {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * 按<b>类型</b>把 {@code declaredIn} 里所有 {@code boolean} 字段写成 {@code value}。
	 *
	 * <p>同样是为了不依赖字段名：{@code LightTexture.updateLightTexture} 在
	 * intermediary 里叫 {@code field_xxxxx}，但那个类里<b>只有一个</b> boolean 字段，
	 * 按类型定位没有歧义。
	 *
	 * <p>用途：我们那趟离屏渲染会把全局光照贴图写坏（见 {@code MirrorReflection}），
	 * 事后需要把这个标志重新置位，让下一帧的主画面重新生成正确光照。
	 */
	static void setBooleanByType(Class<?> declaredIn, Object holder, boolean value) {
		if (!AVAILABLE || holder == null) {
			return;
		}
		try {
			Method putBoolean = UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class);
			for (Field f : declaredIn.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				if (f.getType() != boolean.class) {
					continue;
				}
				long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, f);
				putBoolean.invoke(UNSAFE, holder, off, value);
			}
		} catch (Throwable ignored) {
			// 拿不到就放弃，不影响主流程
		}
	}

	/** 还原所有改动。 */
	static void restore(List<Slot> slots) {
		if (!AVAILABLE) {
			return;
		}
		for (Slot s : slots) {
			try {
				PUT_OBJECT.invoke(UNSAFE, s.holder(), s.offset(), s.old());
			} catch (Throwable ignored) {
				// 还原失败也不能抛
			}
		}
		slots.clear();
	}
}
