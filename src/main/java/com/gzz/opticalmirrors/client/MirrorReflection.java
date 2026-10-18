package com.gzz.opticalmirrors.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.gzz.opticalmirrors.OpticalMirrors;
import com.gzz.opticalmirrors.block.MirrorBlock;
import com.gzz.opticalmirrors.block.MirrorBlockEntity;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 实时镜面反射：镜面是一块显示器，显示<b>镜面位置沿法线看出去</b>的画面。
 *
 * <h2>视角</h2>
 * 虚拟相机放在玻璃面外侧、沿法线外移一点点，朝法线方向看。
 * 视线垂直于镜面时，线性 UV 映射是<b>精确</b>的，不需要投影纹理。
 *
 * <h2>光影下的两个核心问题</h2>
 * <ol>
 *     <li><b>画面错误 / 卡顿</b>：Iris 会在 {@code renderLevel} 的 HEAD/TAIL 里
 *         跑自己的管线（gbuffer、shadow、composite），把我们的重入当成完整一帧。
 *         默认策略 {@link MirrorConfig.IrisMode#VANILLA} 会在这一趟临时换回
 *         {@code VanillaRenderingPipeline}，见 {@link IrisPipelineOverride}。</li>
 *     <li><b>闪烁</b>：即使走原版管线，Iris 的 mixin 仍然会写
 *         {@code CapturedRenderingState} 的帧级矩阵/雾色，污染主画面下一帧的
 *         composite。所以每趟都要快照还原，见 {@link IrisStateGuard}。</li>
 * </ol>
 *
 * <h2>自适应节流</h2>
 * 离屏渲染的成本与光影包、镜面数量强相关，写死间隔必然有人卡。这里按实测耗时
 * 动态决定"两次渲染之间至少隔多久"，让镜面占用墙钟时间的比例有上界；
 * 单次异常昂贵时退避更久，避免连续掉帧。
 */
public final class MirrorReflection {

	/** 相机沿法线外移量（格）。要大于 0，让相机落在镜板（1 像素厚）外侧。 */
	private static final double CAMERA_OFFSET = 0.02D;

	/** 绘制面沿法线再外移一点，避免与镜板侧边共面。 */
	private static final double SURFACE_EPS = 0.005D;

	/** 扫描时在 {@code maxDistance} 之外多留一点余量，避免边缘镜面反复进出。 */
	private static final double SCAN_MARGIN = 32.0D;

	private static final int MAX_CONSECUTIVE_FAILURES = 4;

	/** 单次离屏渲染超过这个耗时，就认为"这一下会掉帧"，立刻长时间退避。 */
	private static final double PANIC_MS = 50.0D;

	/** 退避最长 3 秒：宁可画面旧一点，也不要连续卡顿。 */
	private static final long PANIC_COOLDOWN_NANOS = 3_000_000_000L;

	private static final Identifier GLASS_TEXTURE = OpticalMirrors.id("textures/block/mirror_glass.png");

	private MirrorReflection() {
	}

	/** 一个共面、连通的镜面组。 */
	static final class Plane {
		final Direction facing;
		/** 玻璃面坐标，单位 1/16 格。 */
		final int coord;
		final List<BlockPos> cells = new ArrayList<>();
		int minA, minB, maxA, maxB;
		TextureTarget target;
		ReflectionTexture texture;
		Identifier textureId;
		boolean ready;
		/** 上次成功渲染的 tick，用于"最久未更新优先"。 */
		long lastUpdateTick = -1L;

		Plane(Direction facing, int coord) {
			this.facing = facing;
			this.coord = coord;
		}

		/** 接手另一份平面的 GPU 资源与就绪状态。 */
		void adopt(Plane other) {
			this.target = other.target;
			this.texture = other.texture;
			this.textureId = other.textureId;
			this.ready = other.ready;
			this.lastUpdateTick = other.lastUpdateTick;
		}

		int sizeA() {
			return this.maxA - this.minA + 1;
		}

		int sizeB() {
			return this.maxB - this.minB + 1;
		}

		/** 玻璃面沿法线轴的世界坐标（格）。 */
		double surface() {
			return this.coord / 16.0D;
		}
	}

	private static final Map<String, Plane> PLANES = new LinkedHashMap<>();
	private static Map<BlockPos, Direction> known = new LinkedHashMap<>();
	private static int textureSeq = 0;

	private static GpuSampler sampler;

	/** 回收的 GPU 资源（渲染目标 + 反射贴图 + 注册 id），供新平面复用。 */
	private record Resources(TextureTarget target, ReflectionTexture texture, Identifier id) {
	}

	private static final ArrayDeque<Resources> POOL = new ArrayDeque<>();
	private static final int POOL_MAX = 6;

	private static final boolean enabled = UnsafeSwap.AVAILABLE
			&& !"false".equalsIgnoreCase(System.getProperty("opticalmirrors.reflection", "true"));

	private static boolean disabled = false;
	private static boolean inMirrorPass = false;
	private static int ticks = 0;
	private static int consecutiveFailures = 0;

	// ---- 自适应节流状态 ----
	/** 每次离屏渲染耗时的指数滑动平均（ms）。 */
	private static double costEmaMs = 0.0D;
	/** 两次渲染之间至少间隔多少个"单次耗时"：越大越省，越大也越不流畅。 */
	private static double dutyFactor = 8.0D;
	private static long lastPassNanos = 0L;
	private static long cooldownUntilNanos = 0L;

	public static boolean isEnabled() {
		return enabled && !disabled && MirrorConfig.get().enabled;
	}

	// ------------------------------------------------------------ 对外状态（面板/指令用）

	public static int planeCount() {
		return known.size();
	}

	public static int groupCount() {
		return PLANES.size();
	}

	public static long lastRenderMillis() {
		return Math.round(costEmaMs);
	}

	/** 配置改了：清掉失败计数与节流状态，让下一次渲染重新尝试。 */
	public static void onConfigChanged() {
		disabled = false;
		consecutiveFailures = 0;
		costEmaMs = 0.0D;
		dutyFactor = 8.0D;
		lastPassNanos = 0L;
		cooldownUntilNanos = 0L;
		for (Plane plane : PLANES.values()) {
			plane.ready = false;
			plane.lastUpdateTick = -1L;
		}
	}

	// ------------------------------------------------------------ 平面内坐标轴

	/** 平面内第一条轴（对应 UV 的 u）。 */
	private static int axisA(Direction.Axis axis) {
		return switch (axis) {
			case X -> 2; // z
			case Y -> 0; // x
			default -> 0; // x
		};
	}

	/** 平面内第二条轴（对应 UV 的 v）。 */
	private static int axisB(Direction.Axis axis) {
		return switch (axis) {
			case X -> 1; // y
			case Y -> 2; // z
			default -> 1; // y
		};
	}

	private static int comp(BlockPos pos, int axis) {
		return switch (axis) {
			case 0 -> pos.getX();
			case 1 -> pos.getY();
			default -> pos.getZ();
		};
	}

	private static String planeKey(Direction facing, int coord) {
		return facing.getSerializedName() + "@" + coord;
	}

	/** 由平面内坐标 (a, b) 和法线轴坐标 surface 组成世界坐标。 */
	private static Vec3 point(Direction facing, double surface, double a, double b) {
		return switch (facing.getAxis()) {
			case X -> new Vec3(surface, b, a);
			case Y -> new Vec3(a, surface, b);
			default -> new Vec3(a, b, surface);
		};
	}

	private static Vec3 normal(Direction facing) {
		return new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
	}

	// ------------------------------------------------------------ 发现镜面

	/**
	 * 收集玩家周围已加载的镜面方块实体。
	 *
	 * <p>{@link MirrorBlockEntity} 自己在 {@code setLevel}/{@code setRemoved} 里维护了
	 * 一份客户端注册表，所以这里 O(镜面数)，且放置/破坏<b>同一 tick</b> 就生效，
	 * 不需要扫区块。
	 */
	private static void scan(Minecraft mc) {
		double maxSq = square(MirrorConfig.get().maxDistance + SCAN_MARGIN);
		Vec3 center = mc.player.position();
		Map<BlockPos, Direction> found = new LinkedHashMap<>();

		for (MirrorBlockEntity be : MirrorBlockEntity.loaded()) {
			if (be.getLevel() != mc.level || be.isRemoved()) {
				continue;
			}
			BlockPos pos = be.getBlockPos();
			if (pos.distToCenterSqr(center.x, center.y, center.z) > maxSq) {
				continue;
			}
			BlockState state = be.getBlockState();
			if (state.getBlock() instanceof MirrorBlock) {
				found.put(pos.immutable(), MirrorBlock.mirrorFacing(state));
			}
		}

		if (!found.equals(known)) {
			known = found;
			rebuildPlanes();
		}
	}

	/**
	 * 把镜面按"共面 + 连通"分组。
	 *
	 * <p>同一面墙上相隔很远的镜子（两个房间、上下两层）不能并成一个包围盒，
	 * 否则每格都会采样同一张图的错误切片 —— 表现为"某些镜子反射异常"。
	 * 所以对每个共面组做 4 邻接 flood fill，只有真正相邻的镜面才共享贴图。
	 */
	private static void rebuildPlanes() {
		Map<String, List<BlockPos>> groups = new LinkedHashMap<>();
		for (Map.Entry<BlockPos, Direction> e : known.entrySet()) {
			BlockPos pos = e.getKey();
			Direction facing = e.getValue();
			int coord = MirrorBlock.planeCoord(pos, facing);
			groups.computeIfAbsent(planeKey(facing, coord), k -> new ArrayList<>()).add(pos);
		}

		Map<String, Plane> found = new LinkedHashMap<>();
		for (List<BlockPos> cells : groups.values()) {
			splitComponents(cells, found);
		}

		// 复用已有平面的渲染目标与贴图，避免每 tick 重建
		for (Map.Entry<String, Plane> e : found.entrySet()) {
			Plane old = PLANES.get(e.getKey());
			if (old != null) {
				e.getValue().adopt(old);
			}
		}

		// 消失的平面把 GPU 资源交回池子。
		for (Map.Entry<String, Plane> e : PLANES.entrySet()) {
			if (!found.containsKey(e.getKey())) {
				release(e.getValue());
			}
		}

		PLANES.clear();
		PLANES.putAll(found);
	}

	/** 一个共面组内按 4 邻接拆成若干连通块。 */
	private static void splitComponents(List<BlockPos> cells, Map<String, Plane> out) {
		Set<BlockPos> remaining = new HashSet<>(cells);
		while (!remaining.isEmpty()) {
			BlockPos seed = remaining.iterator().next();
			remaining.remove(seed);

			List<BlockPos> component = new ArrayList<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(seed);
			while (!queue.isEmpty()) {
				BlockPos pos = queue.poll();
				component.add(pos);
				for (Direction dir : Direction.values()) {
					BlockPos next = pos.relative(dir);
					if (remaining.remove(next)) {
						queue.add(next);
					}
				}
			}
			addPlane(component, out);
		}
	}

	private static void addPlane(List<BlockPos> cells, Map<String, Plane> out) {
		BlockPos first = cells.get(0);
		Direction facing = known.get(first);
		if (facing == null) {
			return;
		}
		int coord = MirrorBlock.planeCoord(first, facing);
		Plane plane = new Plane(facing, coord);

		int aAxis = axisA(facing.getAxis());
		int bAxis = axisB(facing.getAxis());
		for (BlockPos pos : cells) {
			plane.cells.add(pos);
			int va = comp(pos, aAxis);
			int vb = comp(pos, bAxis);
			if (plane.cells.size() == 1) {
				plane.minA = plane.maxA = va;
				plane.minB = plane.maxB = vb;
			} else {
				plane.minA = Math.min(plane.minA, va);
				plane.maxA = Math.max(plane.maxA, va);
				plane.minB = Math.min(plane.minB, vb);
				plane.maxB = Math.max(plane.maxB, vb);
			}
		}

		String key = planeKey(facing, coord) + "@" + plane.minA + "," + plane.minB;
		while (out.containsKey(key)) {
			key += "+";
		}
		out.put(key, plane);
	}

	/** 把一个平面的资源交回池子（超出上限则销毁）。 */
	private static void release(Plane plane) {
		plane.ready = false;
		if (plane.target == null || plane.texture == null || plane.textureId == null) {
			return;
		}
		if (POOL.size() >= POOL_MAX) {
			destroy(plane.target);
			return;
		}
		POOL.push(new Resources(plane.target, plane.texture, plane.textureId));
	}

	private static void destroy(TextureTarget target) {
		if (target == null) {
			return;
		}
		try {
			target.destroyBuffers();
		} catch (Throwable ignored) {
			// 释放失败不影响后续重建
		}
	}

	private static Vec3 planeCenter(Plane plane) {
		double a = (plane.minA + plane.maxA + 1) / 2.0D;
		double b = (plane.minB + plane.maxB + 1) / 2.0D;
		return point(plane.facing, plane.surface(), a, b);
	}

	private static double distanceSq(Plane plane, Vec3 eye) {
		return planeCenter(plane).distanceToSqr(eye);
	}

	/**
	 * 视锥锥体测试：把镜面当作包围球，判断它是否可能出现在画面里。
	 *
	 * <p>用 {@code max(水平半FOV, 垂直半FOV) + 包围球角半径}，
	 * 屏幕外的镜子完全不渲染。
	 */
	private static boolean inView(Plane plane, Vec3 eye, Vector3f look, float vFovRad, double aspect) {
		Vec3 to = planeCenter(plane).subtract(eye);
		double dist = to.length();
		if (dist < 1.0E-4D) {
			return true;
		}
		double dot = (to.x * look.x + to.y * look.y + to.z * look.z) / dist;
		double angle = Math.acos(Math.max(-1.0D, Math.min(1.0D, dot)));

		double radius = 0.5D * Math.hypot(plane.sizeA(), plane.sizeB()) + 0.5D;
		double angularRadius = Math.atan2(radius, Math.max(0.05D, dist - radius));
		double halfV = vFovRad / 2.0D;
		double halfH = Math.atan(Math.tan(halfV) * aspect);
		return angle <= Math.max(halfV, halfH) + angularRadius;
	}

	/** 镜面在屏幕上大约有多少像素（取较长边），用于决定刷新频率。 */
	private static double projectedPixels(Plane plane, double dist, float vFovRad, int screenHeight) {
		double pxPerBlock = screenHeight / (2.0D * Math.tan(vFovRad / 2.0D) * Math.max(1.0D, dist));
		return pxPerBlock * Math.max(plane.sizeA(), plane.sizeB());
	}

	/**
	 * 该平面这一帧建议的刷新间隔（tick）。
	 *
	 * <p>远处或很小的镜面即使晚 0.5 秒更新也看不出来，把预算留给眼前的大镜子。
	 */
	private static int refreshInterval(MirrorConfig cfg, double dist, double screenPx) {
		int interval = Math.max(1, cfg.intervalTicks);
		if (dist > 32.0D) {
			interval *= 2;
		}
		if (dist > 64.0D) {
			interval *= 2;
		}
		if (screenPx < 40.0D) {
			interval = Math.max(interval, 8);
		}
		if (screenPx < 12.0D) {
			interval = Math.max(interval, 24);
		}
		return Math.min(interval, 200);
	}

	// ------------------------------------------------------------ 离屏渲染

	public static void updateOffscreen(Minecraft mc) {
		MirrorConfig cfg = MirrorConfig.get();
		if (!isEnabled() || mc.level == null || mc.player == null) {
			return;
		}

		try {
			ticks++;

			// 光影状态每 tick 刷新一次（纯缓存，见 IrisCompat）。
			IrisCompat.tick(ticks);

			// 扫描与平面重建不受任何节流影响：它们决定放置/破坏的反应速度。
			scan(mc);

			if (PLANES.isEmpty()) {
				return;
			}

			// 打开界面 / 暂停时镜子保留最后一帧，没必要跟着重渲染。
			if (mc.screen != null || mc.isPaused()) {
				return;
			}

			if (IrisCompat.shouldPause()) {
				return;
			}
			// 光影生效但原版管线替换不可用时，宁可冻结上一帧，也不重入光影管线：
			// 一是每次 ~20ms，二是它会写主画面的 gbuffer/雾/云时间等全局状态，
			// 表现就是主视角跟着镜面更新而闪烁。
			if (IrisCompat.useVanillaPipeline() && !IrisPipelineOverride.available()) {
				warnNoIrisSwap();
				return;
			}
			// 旧版 THROTTLE 才需要额外降频；VANILLA 走原版管线，成本已经很低
			int divisor = IrisCompat.irisThrottleDivisor();
			if (divisor > 1 && (ticks % divisor) != 0) {
				return;
			}

			// 自适应节流：按实测耗时控制"两次渲染之间的最小间隔"。
			// 这是卡顿的主要闸门 —— 单次 8ms 的渲染如果每 tick 都做，必然掉帧。
			long now = System.nanoTime();
			if (now < cooldownUntilNanos) {
				return;
			}
			if (lastPassNanos != 0L) {
				long required = (long) (Math.max(0.5D, costEmaMs) * 1_000_000L * dutyFactor);
				if (now - lastPassNanos < required) {
					return;
				}
			}

			Camera camera = mc.gameRenderer.getMainCamera();
			Vec3 eye = camera.position();
			Vector3f look = new Vector3f(camera.forwardVector());

			float vFov = (float) Math.toRadians(mc.options.fov().get());
			double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
			int screenHeight = mc.getWindow().getHeight();

			double maxSq = square(cfg.maxDistance);
			List<Plane> due = new ArrayList<>();
			for (Plane plane : PLANES.values()) {
				double distSq = distanceSq(plane, eye);
				if (distSq > maxSq) {
					continue;
				}
				double dist = Math.sqrt(distSq);
				if (!inView(plane, eye, look, vFov, aspect)) {
					continue;
				}
				double px = projectedPixels(plane, dist, vFov, screenHeight);
				int interval = refreshInterval(cfg, dist, px);
				if (plane.lastUpdateTick >= 0L && (ticks - plane.lastUpdateTick) < interval) {
					continue;
				}
				due.add(plane);
			}

			// 视野里没有镜子就完全不渲染。转身时它们会变成"最久未更新"，
			// 下一 tick 立刻被补上。
			if (due.isEmpty()) {
				return;
			}

			// 最久未更新的优先：公平轮转，刚进入视野的镜子第一时间补齐。
			due.sort((x, y) -> {
				int byAge = Long.compare(x.lastUpdateTick, y.lastUpdateTick);
				if (byAge != 0) {
					return byAge;
				}
				return Double.compare(distanceSq(x, eye), distanceSq(y, eye));
			});

			int cap = Math.max(1, cfg.planesPerTick);
			long budgetNanos = (long) (Math.max(0.5D, cfg.budgetMs) * 1_000_000L);
			long nano = 0L;
			int rendered = 0;
			int attempted = 0;
			for (Plane plane : due) {
				if (rendered >= cap) {
					break;
				}
				// 至少渲染一个，避免预算设得过小导致永远不更新
				if (rendered > 0 && nano >= budgetNanos) {
					break;
				}
				long t0 = System.nanoTime();
				boolean ok = renderPlane(mc, plane);
				nano += System.nanoTime() - t0;
				attempted++;
				if (ok) {
					rendered++;
					plane.lastUpdateTick = ticks;
				}
			}

			// 只要尝试过就刷新光照贴图。
			// 离屏渲染内部会按镜子相机重建这张<b>全局</b>贴图并吃掉更新标志，
			// 不刷新的话主画面下一帧会用镜子的光照 —— 夜晚闪烁就是这么来的。
			// 即使这趟失败了也要刷新，否则标志位留在 false 上，同样会闪一帧。
			if (attempted > 0) {
				refreshLightmap(mc);
				updateThrottle(nano / attempted, rendered > 0);
			}
		} catch (Throwable t) {
			fail("mirror pass failed", t);
		}
	}

	/**
	 * 根据这次实测耗时调整节流。
	 *
	 * <p>{@code dutyFactor} 是"间隔 = 单次耗时 × dutyFactor"，也就是镜面最多占用
	 * 约 {@code 1/dutyFactor} 的墙钟时间。单次超过预算就快速退避，回落后缓慢恢复。
	 */
	private static void updateThrottle(long passNanos, boolean success) {
		double passMs = passNanos / 1_000_000.0D;
		costEmaMs = costEmaMs <= 0.0D ? passMs : costEmaMs * 0.7D + passMs * 0.3D;

		double budget = Math.max(1.0D, MirrorConfig.get().budgetMs);
		if (!success || passMs > budget * 2.0D) {
			dutyFactor = Math.min(60.0D, dutyFactor * 1.7D);
		} else if (passMs < budget) {
			dutyFactor = Math.max(6.0D, dutyFactor * 0.96D);
		}

		// 单次异常昂贵：接下来 3 秒彻底停手，避免连续掉帧。
		if (passMs > PANIC_MS) {
			cooldownUntilNanos = System.nanoTime() + PANIC_COOLDOWN_NANOS;
			OpticalMirrors.LOGGER.warn(
					"[OpticalMirrors] Mirror pass took {} ms, pausing reflections for 3s", Math.round(passMs));
		}
		lastPassNanos = System.nanoTime();
	}

	/**
	 * 目标贴图尺寸。
	 *
	 * <p>投影矩阵用的是窗口宽高比，所以目标也必须是窗口宽高比，绘制时再按
	 * 平面宽高比居中裁剪。让"裁剪后"的像素密度等于 {@code pixelsPerBlock}：
	 * 平面比屏幕更宽就以宽度为限制，否则以高度为限制。
	 */
	private static void ensureTarget(Minecraft mc, Plane plane) {
		MirrorConfig cfg = MirrorConfig.get();
		int cap = Math.max(64, cfg.maxResolution);

		double screenAspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		double quadAspect = (double) plane.sizeA() / (double) plane.sizeB();

		double w;
		double h;
		if (quadAspect >= screenAspect) {
			w = (double) plane.sizeA() * cfg.pixelsPerBlock;
			h = w / screenAspect;
		} else {
			h = (double) plane.sizeB() * cfg.pixelsPerBlock;
			w = h * screenAspect;
		}
		double longest = Math.max(w, h);
		if (longest > cap) {
			double scale = cap / longest;
			w *= scale;
			h *= scale;
		}
		int tw = Math.max(32, (int) Math.round(w));
		int th = Math.max(32, (int) Math.round(h));

		if (plane.target == null && !POOL.isEmpty()) {
			Resources r = POOL.pop();
			plane.target = r.target();
			plane.texture = r.texture();
			plane.textureId = r.id();
			plane.ready = false;
		}
		if (plane.target == null) {
			plane.target = new TextureTarget("opticalmirrors_reflection", tw, th, true);
			plane.ready = false;
		} else if (plane.target.width != tw || plane.target.height != th) {
			plane.target.resize(tw, th);
			plane.ready = false;
		}
	}

	private static GpuSampler sampler() {
		if (sampler == null) {
			try {
				sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			} catch (Throwable t) {
				sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			}
		}
		return sampler;
	}

	private static void ensureTexture(Minecraft mc, Plane plane) {
		if (plane.texture != null && plane.textureId != null) {
			plane.texture.sync();
			return;
		}
		if (plane.texture == null) {
			TextureManager tm = mc.getTextureManager();
			Identifier id = OpticalMirrors.id("reflection/p" + (textureSeq++));
			plane.texture = new ReflectionTexture(plane.target, sampler());
			// register 只是 map.put，不触发任何资源加载
			tm.register(id, plane.texture);
			plane.textureId = id;
		} else {
			plane.texture.sync();
		}
	}

	private static boolean renderPlane(Minecraft mc, Plane plane) {
		try {
			ensureTarget(mc, plane);
			ensureTexture(mc, plane);

			Direction facing = plane.facing;
			Vec3 normal = normal(facing);

			// 相机：镜面中心，沿法线外移，朝法线方向看
			Vec3 camPos = planeCenter(plane).add(normal.scale(CAMERA_OFFSET));
			Vector3f forward = new Vector3f((float) normal.x, (float) normal.y, (float) normal.z);
			float[] angles = MirrorCamera.angles(forward);

			MirrorCamera cam = new MirrorCamera();
			float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			// detached 必须为 true：否则实体提取会把「相机所附着的活体实体」剔除，
			// 玩家永远不出现在镜子里（详见 MirrorCamera#isDetached）。
			cam.setup(mc.level, mc.player, true, false, partialTick);
			cam.place(camPos, angles[0], angles[1]);
			// 在镜面位置采样环境属性。不调用的话 probe 里全是默认值，
			// 天空色 0 / 雾色 0 / 太阳角 0 —— 镜子里永远没有天空。
			cam.primeEnvironment(mc.level);

			Camera real = mc.gameRenderer.getMainCamera();
			RenderTarget mainTarget = mc.getMainRenderTarget();

			// 除了 Minecraft / LevelRenderer / GameRenderer，还要换掉
			// EntityRenderDispatcher 自己的 camera 字段，否则实体剔除仍按真实相机判断。
			List<Object> holders = List.of(mc, mc.levelRenderer, mc.gameRenderer,
					mc.getEntityRenderDispatcher());

			var targetSlots = UnsafeSwap.replaceAll(holders, mainTarget, plane.target);
			var cameraSlots = UnsafeSwap.replaceAll(holders, real, cam);

			// ---- 全局渲染状态快照 ----
			// 这趟是「在别人的帧里再跑一遍完整世界渲染」，它会写全局状态：
			//   * RenderSystem 的投影矩阵与雾缓冲
			//   * LightTexture 的全局光照贴图
			//   * outputColor/DepthTextureOverride
			//   * Iris 的 CapturedRenderingState（gbuffer 矩阵 / 雾色 / 云时间）
			// 任何一个不还原，主画面下一帧就会用镜子视角的值 —— 表现为闪烁、色调异常。
			RenderSystem.backupProjectionMatrix();
			GpuBufferSlice savedFog = RenderSystem.getShaderFog();
			GpuBufferSlice savedLights = RenderSystem.getShaderLights();
			GpuTextureView savedColorOverride = RenderSystem.outputColorTextureOverride;
			GpuTextureView savedDepthOverride = RenderSystem.outputDepthTextureOverride;
			// GameRenderer.renderLevel 内部会 pick(...)，把 mc.hitResult 改成镜子的准星目标。
			HitResult savedHitResult = mc.hitResult;
			IrisStateGuard.Handle irisState = IrisStateGuard.capture();

			// renderLevel 内部会调用 renderItemInHand；不切成第三人称，镜子里会多出一只
			// 悬空的手。ScreenEffectRenderer 的水下/岩浆滤镜同理。
			var cameraTypeSlots = UnsafeSwap.replaceAll(
					List.of(mc.options), CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK);

			// 光影生效时，把这趟渲染临时切回原版管线。
			IrisPipelineOverride.Handle irisGuard = IrisPipelineOverride.begin();

			inMirrorPass = true;
			try {
				mc.gameRenderer.renderLevel(mc.getDeltaTracker());
			} finally {
				inMirrorPass = false;
				UnsafeSwap.restore(cameraTypeSlots);
				// 先还原 Iris 的帧级状态，再还原相机/目标
				irisState.restore();
				UnsafeSwap.restore(cameraSlots);
				UnsafeSwap.restore(targetSlots);

				mc.hitResult = savedHitResult;

				RenderSystem.outputColorTextureOverride = savedColorOverride;
				RenderSystem.outputDepthTextureOverride = savedDepthOverride;
				// 光照方向缓冲也是全局的：镜面相机下 LevelRenderer 会按本渲位置重算，
				// 不还原的话主画面的实体/方块明暗会跟着镜面更新而跳变。
				if (savedLights != null) {
					RenderSystem.setShaderLights(savedLights);
				}
				RenderSystem.setShaderFog(savedFog);
				RenderSystem.restoreProjectionMatrix();

				// 最后把 Iris 管线换回去，确保异常也不会污染主画面
				irisGuard.close();
			}

			plane.ready = true;
			consecutiveFailures = 0;
			return true;
		} catch (Throwable t) {
			consecutiveFailures++;
			fail("offscreen render failed (consecutive " + consecutiveFailures + ")", t);
			return false;
		}
	}

	/**
	 * 用真实相机重建全局光照贴图。
	 *
	 * <p>{@code LightTexture.updateLightTexture} 开头是
	 * {@code if (!this.updateLightTexture) return; this.updateLightTexture = false;}，
	 * 然后按 {@code gameRenderer.getMainCamera()} 的天空光属性写全局贴图。
	 * 我们那趟离屏渲染用的是镜子相机，所以必须恢复相机后重新置位并重建一次。
	 */
	private static void refreshLightmap(Minecraft mc) {
		try {
			LightTexture lightTexture = mc.gameRenderer.lightTexture();
			UnsafeSwap.setBooleanByType(LightTexture.class, lightTexture, true);
			lightTexture.updateLightTexture(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		} catch (Throwable ignored) {
			// 失败也不影响渲染，下一帧玩家光照变化时仍会自行恢复
		}
	}

	private static double square(double v) {
		return v * v;
	}

	private static boolean warnedNoIrisSwap = false;

	/** 光影在跑但原版管线替换不可用：只报一次，避免刷屏。 */
	private static void warnNoIrisSwap() {
		if (warnedNoIrisSwap) {
			return;
		}
		warnedNoIrisSwap = true;
		OpticalMirrors.LOGGER.warn("[OpticalMirrors] Iris pipeline swap unavailable; "
				+ "reflections are frozen while a shaderpack is active "
				+ "(set irisMode=auto to silence this, or disable shaders)");
	}

	private static void fail(String message, Throwable t) {
		if (disabled) {
			return;
		}
		if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
			disabled = true;
			OpticalMirrors.LOGGER.warn("[OpticalMirrors] {}, reflections disabled (static glass only)", message, t);
		} else if (consecutiveFailures == 1) {
			OpticalMirrors.LOGGER.warn("[OpticalMirrors] {}: {}", message, t.toString(), t);
		}
	}

	// ------------------------------------------------------------ 绘制

	public static void draw(Minecraft mc, PoseStack poseStack, MultiBufferSource consumers) {
		if (inMirrorPass || PLANES.isEmpty()) {
			return;
		}

		MirrorConfig cfg = MirrorConfig.get();

		try {
			Vec3 eye = mc.gameRenderer.getMainCamera().position();

			Matrix4f m = poseStack.last().pose();
			double dx = Math.abs(m.m30() + (float) eye.x);
			double dy = Math.abs(m.m31() + (float) eye.y);
			double dz = Math.abs(m.m32() + (float) eye.z);
			boolean cameraRelative = (dx + dy + dz) < 1.0D;

			poseStack.pushPose();
			if (!cameraRelative) {
				poseStack.translate(-eye.x, -eye.y, -eye.z);
			}

			double maxSq = square(cfg.maxDistance);
			for (Plane plane : PLANES.values()) {
				if (distanceSq(plane, eye) > maxSq) {
					continue;
				}
				// 反射未就绪 / 已关闭时画静态玻璃，避免出现空洞
				boolean live = isEnabled() && plane.ready && plane.textureId != null;
				drawPlane(poseStack, consumers, plane, live ? plane.textureId : GLASS_TEXTURE, live);
			}

			poseStack.popPose();
		} catch (Throwable t) {
			fail("draw failed", t);
		}
	}

	/**
	 * 画一个平面。
	 *
	 * <p>UV 用相机基向量算：每个角点的 u/v 等于它在平面内相对中心的投影。
	 * 这样六个朝向都自动正确，不需要维护翻转表。
	 *
	 * @param live 是否使用实时反射贴图。实时贴图来自 RenderTarget（第 0 行在下），
	 *             静态玻璃贴图来自 PNG（第 0 行在上），后者需要翻转 v。
	 */
	private static void drawPlane(PoseStack poseStack, MultiBufferSource consumers, Plane plane,
			Identifier texture, boolean live) {
		// 实时画面不能做 alpha 裁剪：天空/雾里可能出现 alpha<0.1 的像素，
		// entityCutoutNoCull 会把它们整块丢掉（"镜子里没有天空"的原因之一）。
		RenderType type = live ? RenderTypes.entitySolid(texture) : RenderTypes.entityCutoutNoCull(texture);
		VertexConsumer vc = consumers.getBuffer(type);

		Direction facing = plane.facing;
		Vec3 n = normal(facing);
		boolean negative = facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
		double surface = plane.surface() + (negative ? -SURFACE_EPS : SURFACE_EPS);

		int aAxis = axisA(facing.getAxis());
		int bAxis = axisB(facing.getAxis());

		int sa = plane.sizeA();
		int sb = plane.sizeB();
		Vec3 center = planeCenter(plane);

		// 直接用相机四元数求屏幕右/上方向，和 Camera.setRotation 完全一致。
		// 不能假设"水平镜面 up 就是 +Z"：朝下时 MC 的相机 up 实际是 -Z。
		Vector3f normalVec = new Vector3f((float) n.x, (float) n.y, (float) n.z);
		float[] camAngles = MirrorCamera.angles(normalVec);
		Quaternionf camRotation = new Quaternionf().rotationYXZ(
				(float) Math.PI - (float) Math.toRadians(camAngles[0]),
				-(float) Math.toRadians(camAngles[1]), 0.0F);
		Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F).rotate(camRotation);
		Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(camRotation);

		// 采样区域：按比例居中裁剪，避免拉伸
		double imgAspect = (plane.target != null && plane.target.height > 0)
				? (double) plane.target.width / plane.target.height
				: mcAspect();
		double quadAspect = (double) sa / (double) sb;
		double spanU = 1.0D;
		double spanV = 1.0D;
		if (live) {
			if (quadAspect >= imgAspect) {
				spanV = imgAspect / quadAspect;
			} else {
				spanU = quadAspect / imgAspect;
			}
		}
		float offU = (float) ((1.0D - spanU) / 2.0D);
		float offV = (float) ((1.0D - spanV) / 2.0D);
		float scaleU = (float) spanU;
		float scaleV = (float) spanV;

		for (BlockPos cell : plane.cells) {
			int va = comp(cell, aAxis);
			int vb = comp(cell, bAxis);

			Vec3 p00 = point(facing, surface, va, vb);
			Vec3 p10 = point(facing, surface, va + 1, vb);
			Vec3 p11 = point(facing, surface, va + 1, vb + 1);
			Vec3 p01 = point(facing, surface, va, vb + 1);

			float u00 = projectU(p00, center, right, sa);
			float u10 = projectU(p10, center, right, sa);
			float u01 = projectU(p01, center, right, sa);
			float u11 = projectU(p11, center, right, sa);

			float v00 = projectV(p00, center, up, sb);
			float v10 = projectV(p10, center, up, sb);
			float v01 = projectV(p01, center, up, sb);
			float v11 = projectV(p11, center, up, sb);

			if (live) {
				// entitySolid 有背面剔除，必须保证绕序与法线一致，
				// 否则一半朝向的镜子会整面消失。用叉积判断，不依赖朝向表。
				Vec3 e1 = p10.subtract(p00);
				Vec3 e2 = p01.subtract(p00);
				double crossDot = (e1.y * e2.z - e1.z * e2.y) * n.x
						+ (e1.z * e2.x - e1.x * e2.z) * n.y
						+ (e1.x * e2.y - e1.y * e2.x) * n.z;

				// RenderTarget 第 0 行在下：v 随世界"上"方向增大，直接使用
				if (crossDot >= 0.0D) {
					vertex(vc, poseStack, p00, offU + u00 * scaleU, offV + v00 * scaleV, n);
					vertex(vc, poseStack, p10, offU + u10 * scaleU, offV + v10 * scaleV, n);
					vertex(vc, poseStack, p11, offU + u11 * scaleU, offV + v11 * scaleV, n);
					vertex(vc, poseStack, p01, offU + u01 * scaleU, offV + v01 * scaleV, n);
				} else {
					vertex(vc, poseStack, p00, offU + u00 * scaleU, offV + v00 * scaleV, n);
					vertex(vc, poseStack, p01, offU + u01 * scaleU, offV + v01 * scaleV, n);
					vertex(vc, poseStack, p11, offU + u11 * scaleU, offV + v11 * scaleV, n);
					vertex(vc, poseStack, p10, offU + u10 * scaleU, offV + v10 * scaleV, n);
				}
			} else {
				// PNG 第 0 行在上：v 翻转；静态玻璃用 NoCull，不需要管绕序
				vertex(vc, poseStack, p00, u00, 1.0F - v00, n);
				vertex(vc, poseStack, p10, u10, 1.0F - v10, n);
				vertex(vc, poseStack, p11, u11, 1.0F - v11, n);
				vertex(vc, poseStack, p01, u01, 1.0F - v01, n);
			}
		}
	}

	private static double mcAspect() {
		Minecraft mc = Minecraft.getInstance();
		return (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
	}

	private static float projectU(Vec3 p, Vec3 center, Vector3f right, int sizeA) {
		double d = (p.x - center.x) * right.x + (p.y - center.y) * right.y + (p.z - center.z) * right.z;
		return (float) (0.5D + d / sizeA);
	}

	private static float projectV(Vec3 p, Vec3 center, Vector3f up, int sizeB) {
		double d = (p.x - center.x) * up.x + (p.y - center.y) * up.y + (p.z - center.z) * up.z;
		return (float) (0.5D + d / sizeB);
	}

	private static void vertex(VertexConsumer vc, PoseStack poseStack, Vec3 p, float u, float v, Vec3 n) {
		vc.addVertex(poseStack.last().pose(), (float) p.x, (float) p.y, (float) p.z)
				.setColor(255, 255, 255, 255)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(0xF000F0)
				.setNormal(poseStack.last(), (float) n.x, (float) n.y, (float) n.z);
	}

	/** 资源重载后 TextureManager 会清空，我们的贴图需要重建。 */
	public static void onReload() {
		for (Plane plane : PLANES.values()) {
			plane.ready = false;
			destroy(plane.target);
			plane.target = null;
			plane.texture = null;
			plane.textureId = null;
		}
		for (Resources r : POOL) {
			destroy(r.target());
		}
		PLANES.clear();
		POOL.clear();
		known = new LinkedHashMap<>();
	}
}
