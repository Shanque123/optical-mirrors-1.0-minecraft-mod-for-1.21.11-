package com.gzz.opticalmirrors.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;

/**
 * 镜子反射参数，可持久化到 {@code config/opticalmirrors.json}。
 *
 * <h2>为什么参数要交给玩家</h2>
 * 反射的本质是每 tick 把整个世界<b>再渲染一遍</b>，开销与画面复杂度、光影包、
 * 镜面数量直接相关。同一套参数在不同机器上表现差几十倍，写死不可能通用。
 * 所以这里把关键开销旋钮全部暴露出来：
 *
 * <table border="1">
 *     <tr><th>键</th><th>含义</th><th>开销关系</th></tr>
 *     <tr><td>enabled</td><td>总开关</td><td>关掉即零开销</td></tr>
 *     <tr><td>pixelsPerBlock</td><td>每格多少像素</td><td>面积成正比</td></tr>
 *     <tr><td>maxResolution</td><td>单边上限</td><td>面积成正比</td></tr>
 *     <tr><td>planesPerTick</td><td>每 tick 渲染几个平面</td><td>线性</td></tr>
 *     <tr><td>intervalTicks</td><td>多少 tick 更新一次</td><td>线性倒数</td></tr>
 *     <tr><td>maxDistance</td><td>多远之外不绘制</td><td>影响可见镜面数</td></tr>
 *     <tr><td>irisMode</td><td>光影兼容策略</td><td>见 {@link IrisCompat}</td></tr>
 * </table>
 *
 * <p>字段全部 {@code public} 是刻意的：Gson 直接反射字段，省掉一套序列化代码，
 * 配置文件的键名也就等于字段名 —— 和指令 {@code /omirror set <key> <value>}
 * 的键完全一致，玩家改文件或改指令都是同一套名字。
 */
public final class MirrorConfig {

	/** 与光影包的兼容策略。 */
	public enum IrisMode {
		/**
		 * 光影生效时，这一趟镜面渲染<b>临时切回原版管线</b>（默认）。
		 *
		 * <p>Iris 的管线会缓存自己的 gbuffer / 合成目标，重入 {@code renderLevel}
		 * 并不会把最终画面写进我们的贴图，反而会按镜面贴图的尺寸重建整条 gbuffer，
		 * 既错又慢（实测 ~20ms）。切回 {@code VanillaRenderingPipeline} 后，
		 * 这一趟就是纯原版渲染：天空盒、实体、雾都正确，耗时回到 2–3ms，
		 * 而且不需要降频。代价是镜面里没有光影包的后处理色调。
		 */
		VANILLA,
		/**
		 * 光影生效时<b>降频</b>继续走 Iris 管线：每 {@link #irisIntervalTicks} tick 才做一次。
		 *
		 * <p>保留旧行为，仅用于对比/调试。一次离屏渲染在光影下是整帧固定成本，
		 * 而且画面通常不正确，不建议日常使用。
		 */
		THROTTLE,
		/** 检测到光影正在生效就暂停反射，保留最后一帧（零额外开销）。 */
		AUTO,
		/** 不管光影，照常全速渲染（光影下每 tick 20ms，会明显卡顿）。 */
		IGNORE,
		/** 检测到 Iris 模组就永不渲染。 */
		DISABLE
	}

	// 预设档位：GUI 里点按钮循环切换，指令可填任意值
	public static final int[] PIXELS_PER_BLOCK_STEPS = { 32, 48, 64, 96, 128, 192, 256, 384 };
	public static final int[] MAX_RESOLUTION_STEPS = { 128, 192, 256, 384, 512, 768, 1024, 1536 };
	public static final int[] PLANES_PER_TICK_STEPS = { 1, 2, 3, 4, 6, 8 };
	public static final int[] INTERVAL_TICKS_STEPS = { 1, 2, 3, 4, 5, 10, 20 };
	public static final int[] MAX_DISTANCE_STEPS = { 16, 32, 48, 64, 96, 128, 192 };
	public static final int[] IRIS_INTERVAL_STEPS = { 5, 10, 20, 40, 60, 100 };
	public static final int[] BUDGET_MS_STEPS = { 1, 2, 3, 4, 6, 8, 12 };

	private static final String[] KEYS = {
			"enabled", "pixelsPerBlock", "maxResolution", "planesPerTick",
			"intervalTicks", "budgetMs", "maxDistance", "irisMode", "irisIntervalTicks"
	};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 本类自己的 logger。
	 *
	 * <p>不用 {@code OpticalMirrors.LOGGER}：那会触发 {@code OpticalMirrors} 的
	 * 静态初始化，进而注册方块/物品。配置在客户端极早期就会被读取，
	 * 一旦顺序不对就是 {@code ExceptionInInitializerError}（“Not bootstrapped”）。
	 */
	private static final org.slf4j.Logger LOGGER =
			org.slf4j.LoggerFactory.getLogger("OpticalMirrors/Config");
	private static MirrorConfig instance;

	/**
	 * 配置文件架构版本。
	 *
	 * <p>存在的原因：Gson 读旧文件时会把缺失字段保留为当前默认值，
	 * 于是<b>改过默认值也不会生效</b>。3.7.0 的默认 {@code irisMode} 是
	 * {@code THROTTLE}，而它正是光影下闪烁/卡顿的根源；如果不能在载入时识别
	 * 旧文件并迁移，用户就算升级了也会继续跑在有问题的路径上。
	 */
	public static final int CONFIG_VERSION = 2;

	// ---------------- 持久化字段 ----------------

	/** 架构版本；旧文件没有这个键，读出来就是 0。 */
	public int configVersion = CONFIG_VERSION;

	public boolean enabled = true;
	public int pixelsPerBlock = 64;
	public int maxResolution = 768;
	public int planesPerTick = 2;
	public int intervalTicks = 2;
	/**
	 * 每 tick 最多把多少毫秒花在离屏渲染上。
	 *
	 * <p>只靠 {@link #planesPerTick} 限制不住开销：一帧离屏渲染在低配机上可能是 8ms，
	 * 两个平面就是 16ms。改成时间预算后，帧时间超了本 tick 就停手，剩下的平面下一 tick 再来，
	 * 掉帧从"一次 20ms"变成"每次最多 budgetMs"。
	 */
	public double budgetMs = 3.0D;
	public int maxDistance = 64;
	/**
	 * 默认 {@link IrisMode#VANILLA}：光影生效时镜面这一趟临时切回原版管线。
	 *
	 * <p>这是唯一能同时做到"画面正确（含天空盒）"和"开销不爆炸"的方案，详见枚举注释。
	 * 想要光影色调的人可以选 {@code ignore}（会卡），想要零开销可选 {@code auto}。
	 */
	public IrisMode irisMode = IrisMode.VANILLA;
	/** 仅 {@link IrisMode#THROTTLE} 使用：光影生效时多少个 tick 更新一次。 */
	public int irisIntervalTicks = 20;

	private MirrorConfig() {
	}

	public static MirrorConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	private static Path path() {
		Minecraft mc = Minecraft.getInstance();
		Path dir = Path.of(mc.gameDirectory.getPath(), "config");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			// 建不出目录就退化为不持久化
		}
		return dir.resolve("opticalmirrors.json");
	}

	/** 读配置；文件不存在或损坏都用默认值启动，绝不抛。 */
	public static void load() {
		MirrorConfig loaded = null;
		try {
			Path p = path();
			if (Files.exists(p)) {
				String json = Files.readString(p, StandardCharsets.UTF_8);
				loaded = GSON.fromJson(json, MirrorConfig.class);
			}
		} catch (Throwable ignored) {
			loaded = null;
		}
		instance = loaded != null ? loaded : new MirrorConfig();
		boolean migrated = instance.migrate();
		instance.sanitize();
		if (migrated) {
			save();
		}
	}

	/**
	 * 把旧版本配置文件升级到 {@link #CONFIG_VERSION}。
	 *
	 * @return 是否发生了迁移（调用方据此决定要不要立刻回写）
	 */
	private boolean migrate() {
		if (this.configVersion >= CONFIG_VERSION) {
			return false;
		}
		if (this.configVersion < 2) {
			// v1 及以前默认 irisMode = THROTTLE。光影下它会让整条 Iris 管线
			// 按镜面尺寸重建并合成，既是 ~20ms/次，也会污染主画面状态（闪烁）。
			// 升级时统一改成 VANILLA，除非用户明确选了别的（THROTTLE 以外）。
			if (this.irisMode == null || this.irisMode == IrisMode.THROTTLE) {
				this.irisMode = IrisMode.VANILLA;
			}
		}
		this.configVersion = CONFIG_VERSION;
		LOGGER.info("Migrated config to version {} (irisMode={})", CONFIG_VERSION, this.irisMode);
		return true;
	}

	/** 写配置；失败只记日志。 */
	public static void save() {
		try {
			Files.writeString(path(), GSON.toJson(get()), StandardCharsets.UTF_8);
			return;
		} catch (Throwable t) {
			LOGGER.warn("Failed to save configuration", t);
		}
	}

	/** 把越界值夹回合法范围（手改配置文件时容易写坏）。 */
	private void sanitize() {
		this.pixelsPerBlock = clamp(this.pixelsPerBlock, 8, 512);
		this.maxResolution = clamp(this.maxResolution, 64, 2048);
		this.planesPerTick = clamp(this.planesPerTick, 1, 16);
		this.intervalTicks = clamp(this.intervalTicks, 1, 100);
		this.budgetMs = Math.max(0.5D, Math.min(20.0D, this.budgetMs));
		this.maxDistance = clamp(this.maxDistance, 8, 256);
		this.irisIntervalTicks = clamp(this.irisIntervalTicks, 1, 200);
		if (this.irisMode == null) {
			this.irisMode = IrisMode.VANILLA;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** 在预设档位里循环到下一个值。 */
	public static int nextStep(int[] steps, int current) {
		for (int i = 0; i < steps.length; i++) {
			if (steps[i] > current) {
				return steps[i];
			}
		}
		return steps[0];
	}

	public void reset() {
		MirrorConfig d = new MirrorConfig();
		this.enabled = d.enabled;
		this.pixelsPerBlock = d.pixelsPerBlock;
		this.maxResolution = d.maxResolution;
		this.planesPerTick = d.planesPerTick;
		this.intervalTicks = d.intervalTicks;
		this.budgetMs = d.budgetMs;
		this.maxDistance = d.maxDistance;
		this.irisMode = d.irisMode;
		this.irisIntervalTicks = d.irisIntervalTicks;
	}

	// ---------------- 指令读写 ----------------

	public static String[] keys() {
		return KEYS.clone();
	}

	/** 读某个键的当前值（字符串形式）。未知键返回 null。 */
	public String getKey(String key) {
		return switch (key) {
			case "enabled" -> Boolean.toString(this.enabled);
			case "pixelsPerBlock" -> Integer.toString(this.pixelsPerBlock);
			case "maxResolution" -> Integer.toString(this.maxResolution);
			case "planesPerTick" -> Integer.toString(this.planesPerTick);
			case "intervalTicks" -> Integer.toString(this.intervalTicks);
			case "budgetMs" -> Double.toString(this.budgetMs);
			case "maxDistance" -> Integer.toString(this.maxDistance);
			case "irisMode" -> this.irisMode.name().toLowerCase(java.util.Locale.ROOT);
			case "irisIntervalTicks" -> Integer.toString(this.irisIntervalTicks);
			default -> null;
		};
	}

	/** {@link #setKey} 的结果。 */
	public enum SetResult {
		OK,
		UNKNOWN_KEY,
		BAD_VALUE
	}

	/**
	 * 写某个键。
	 *
	 * <p>返回机器可读的结果而不是文案：文案要随客户端语言变化，
	 * 由 {@code /omirror} 指令侧用 {@code Component.translatable} 拼。
	 */
	public SetResult setKey(String key, String value) {
		try {
			switch (key) {
				case "enabled" -> this.enabled = parseBool(value);
				case "pixelsPerBlock" -> this.pixelsPerBlock = Integer.parseInt(value.trim());
				case "maxResolution" -> this.maxResolution = Integer.parseInt(value.trim());
				case "planesPerTick" -> this.planesPerTick = Integer.parseInt(value.trim());
				case "intervalTicks" -> this.intervalTicks = Integer.parseInt(value.trim());
				case "budgetMs" -> this.budgetMs = Double.parseDouble(value.trim());
				case "maxDistance" -> this.maxDistance = Integer.parseInt(value.trim());
				case "irisMode" -> this.irisMode = IrisMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
				case "irisIntervalTicks" -> this.irisIntervalTicks = Integer.parseInt(value.trim());
				default -> {
					return SetResult.UNKNOWN_KEY;
				}
			}
		} catch (IllegalArgumentException e) {
			return SetResult.BAD_VALUE;
		}
		sanitize();
		return SetResult.OK;
	}

	private static boolean parseBool(String v) {
		String s = v.trim();
		if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on") || s.equals("1")) {
			return true;
		}
		if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off") || s.equals("0")) {
			return false;
		}
		throw new IllegalArgumentException("不是布尔值: " + v);
	}

	/**
	 * 三档预设。手调六个数字太麻烦，给玩家一个"先能跑"的入口。
	 *
	 * <table border="1">
	 *     <tr><th>档</th><th>pixelsPerBlock</th><th>maxResolution</th><th>planesPerTick</th><th>intervalTicks</th><th>maxDistance</th></tr>
	 *     <tr><td>low</td><td>32</td><td>256</td><td>1</td><td>4</td><td>32</td></tr>
	 *     <tr><td>medium</td><td>64</td><td>768</td><td>2</td><td>2</td><td>64</td></tr>
	 *     <tr><td>high</td><td>128</td><td>1024</td><td>2</td><td>1</td><td>128</td></tr>
	 * </table>
	 *
	 * @return 未知档名返回 false
	 */
	public boolean applyPreset(String name) {
		switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "low" -> {
				this.pixelsPerBlock = 32;
				this.maxResolution = 256;
				this.planesPerTick = 1;
				this.intervalTicks = 4;
				this.budgetMs = 1.5D;
				this.maxDistance = 32;
			}
			case "medium", "mid" -> {
				this.pixelsPerBlock = 64;
				this.maxResolution = 768;
				this.planesPerTick = 2;
				this.intervalTicks = 2;
				this.budgetMs = 3.0D;
				this.maxDistance = 64;
			}
			case "high" -> {
				this.pixelsPerBlock = 128;
				this.maxResolution = 1024;
				this.planesPerTick = 2;
				this.intervalTicks = 1;
				this.budgetMs = 6.0D;
				this.maxDistance = 128;
			}
			default -> {
				return false;
			}
		}
		sanitize();
		return true;
	}
}
