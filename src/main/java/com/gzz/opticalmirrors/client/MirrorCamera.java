package com.gzz.opticalmirrors.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 镜子里的"虚拟摄像机"。
 *
 * <h2>视角约定（v3.4.0 按需求改为镜像法线视角）</h2>
 * 相机放在<b>镜面玻璃面外侧、法线方向上</b>，朝法线方向看出去 —— 也就是
 * "如果这里有一台摄像机，它看到的画面"。不是把玩家眼位关于镜面反射。
 *
 * <p>这样做的两个好处：
 * <ol>
 *     <li>镜面显示的是"从镜子位置看出去"的内容，符合显示器的语义；</li>
 *     <li>相机视线垂直于镜面，线性 UV 映射就是<b>精确</b>的，
 *         不需要投影纹理（projective texturing）。</li>
 * </ol>
 *
 * <h2>为什么还要重写 getter</h2>
 * 实测 1.21.11 的 {@code GameRenderer.renderLevel(DeltaTracker)} 内部只调用
 * {@code extractCamera}，而 {@code extractCamera} 仅<b>读取</b>
 * {@code position() / blockPosition() / entity() / rotation() / isInitialized()}，
 * 不会再调用 {@code setup(...)}，所以 {@link #place} 设好的值不会被覆盖。
 *
 * <p>但为了防御第三方模组在本帧内再调一次 {@code setup(...)}，这里：
 * <ul>
 *     <li>先 {@code super.setup(...)} 让内部状态（nearPlane/farPlane/detached）正常；</li>
 *     <li>再用 {@link #place} 覆盖位置朝向，并<b>快照</b>下来；</li>
 *     <li>重写全部取值方法返回快照 —— 即使有人再调 setup，读出来的仍是镜像视角。</li>
 * </ul>
 */
final class MirrorCamera extends Camera {

	private Vec3 mirrorPos = Vec3.ZERO;
	private BlockPos mirrorBlockPos = BlockPos.ZERO;
	private float mirrorPitch;
	private float mirrorYaw;
	private final Quaternionf mirrorRotation = new Quaternionf();
	private boolean placed;

	/**
	 * 摆放相机。
	 *
	 * @param eye   世界坐标（在玻璃面外侧）
	 * @param yaw   Minecraft 偏航角（0 = +Z，90 = -X）
	 * @param pitch Minecraft 俯仰角（正 = 向下看）
	 */
	void place(Vec3 eye, float yaw, float pitch) {
		super.setPosition(eye);
		super.setRotation(yaw, pitch);

		this.mirrorPos = super.position();
		this.mirrorBlockPos = BlockPos.containing(eye);
		this.mirrorYaw = yaw;
		this.mirrorPitch = pitch;
		this.mirrorRotation.set(super.rotation());
		this.placed = true;
	}

	/**
	 * 在镜面位置刷新环境属性采样。
	 *
	 * <h2>为什么这一步是天空盒消失的根因</h2>
	 * 1.21.11 把天空色、太阳角、雾色、云色都挪进了
	 * {@code EnvironmentAttributeProbe}，而 probe 只在
	 * {@link Camera#tick()} 里按相机位置采样。我们每次渲染都 new 一个
	 * {@code MirrorCamera}，它的 probe 从未 tick 过 ——
	 * {@code EnvironmentAttributeProbe.getValue} 在
	 * {@code level == null || position == null} 时直接返回
	 * {@code Attribute.defaultValue()}。
	 *
	 * <p>而 {@code visual/sky_color} 的默认值是 {@code 0}（ARGB 全透明黑），
	 * {@code visual/fog_color} 也是 {@code 0}，{@code visual/cloud_color} 是 0。
	 * 于是天空 pass 往镜面贴图里写了一整片 <b>alpha=0 的黑色</b>，
	 * 我们的 quad 又用带 alpha 测试的 RenderType 绘制，这些像素被直接丢弃 ——
	 * 表现就是"镜子里没有天空"。
	 *
	 * <p>在 {@link #place} 之后调用一次即可（probe 的数据会保留到本趟渲染结束）。
	 */
	void primeEnvironment(Level level) {
		if (this.placed) {
			this.attributeProbe().tick(level, this.mirrorPos);
		}
	}

	/** 由朝向向量推出 yaw / pitch。 */
	static float[] angles(Vector3f forward) {
		float f = forward.length();
		if (f < 1.0E-6F) {
			return new float[] { 0.0F, 0.0F };
		}
		float x = forward.x / f;
		float y = forward.y / f;
		float z = forward.z / f;

		float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
		float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0F, Math.min(1.0F, -y))));
		return new float[] { yaw, pitch };
	}

	@Override
	public void setup(Level level, Entity entity, boolean detached, boolean thirdPerson, float partialTick) {
		super.setup(level, entity, detached, thirdPerson, partialTick);
		if (this.placed) {
			// 第三方再调 setup 时，把镜像视角重新盖回去
			super.setPosition(this.mirrorPos);
			super.setRotation(this.mirrorYaw, this.mirrorPitch);
		}
	}

	@Override
	public Vec3 position() {
		return this.placed ? this.mirrorPos : super.position();
	}

	@Override
	public BlockPos blockPosition() {
		return this.placed ? this.mirrorBlockPos : super.blockPosition();
	}

	@Override
	public float xRot() {
		return this.placed ? this.mirrorPitch : super.xRot();
	}

	@Override
	public float yRot() {
		return this.placed ? this.mirrorYaw : super.yRot();
	}

	@Override
	public float yaw() {
		return this.placed ? this.mirrorYaw : super.yaw();
	}

	@Override
	public Quaternionf rotation() {
		return this.placed ? this.mirrorRotation : super.rotation();
	}

	/**
	 * 恒为 {@code true}：这是镜子能照出玩家自己的<b>必要条件</b>。
	 *
	 * <p>反编译 {@code LevelRenderer.extractVisibleEntities} 得到的实体剔除逻辑：
	 * <pre>
	 * if (entity == camera.entity()) {                                  // 197 if_acmpne 239
	 *     if (!camera.isDetached()) {                                   // 206 ifne 239
	 *         if (!(camera.entity() instanceof LivingEntity)) continue;  // 220
	 *         if (!livingEntity.isSleeping()) continue;                 // 233
	 *     }
	 * }
	 * if (entity instanceof LocalPlayer) {
	 *     if (camera.entity() != entity) continue;                       // 253 if_acmpeq 259
	 * }
	 * 提取进 entityRenderStates
	 * </pre>
	 *
	 * <p>两条规则合起来的意思是：<b>玩家只有在它就是相机所附着的实体时才会被提取</b>；
	 * 而一旦相机所附着的实体是活着的活体（玩家必然是），又会被第一条规则剔掉，
	 * 除非 {@link #isDetached()} 返回 true。
	 *
	 * <p>所以必须同时满足：{@code entity() == 玩家} 且 {@code isDetached() == true}。
	 * 这正是原版旁观者 / 自由视角相机的用法。
	 */
	@Override
	public boolean isDetached() {
		return this.placed || super.isDetached();
	}
}
