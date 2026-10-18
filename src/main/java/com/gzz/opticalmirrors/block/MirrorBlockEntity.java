package com.gzz.opticalmirrors.block;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import com.gzz.opticalmirrors.OpticalMirrors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 镜面方块实体。
 *
 * <p>不参与任何方块逻辑，唯一作用是<b>让客户端能枚举已加载的镜面</b>：
 * 渲染反射时需要知道附近有哪些镜面、它们属于哪个平面。
 * 用方块实体而不是扫描世界，避免每帧做体积扫描。
 */
public class MirrorBlockEntity extends BlockEntity {

	/** 客户端已加载的镜面（弱引用，卸载自动消失）。 */
	private static final Set<MirrorBlockEntity> CLIENT = Collections.newSetFromMap(new WeakHashMap<>());

	public MirrorBlockEntity(BlockPos pos, BlockState state) {
		super(OpticalMirrors.MIRROR_ENTITY, pos, state);
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		if (level != null && level.isClientSide()) {
			synchronized (CLIENT) {
				CLIENT.add(this);
			}
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		synchronized (CLIENT) {
			CLIENT.remove(this);
		}
	}

	/** 返回当前维度内已加载的镜面副本。 */
	public static java.util.List<MirrorBlockEntity> loaded() {
		synchronized (CLIENT) {
			return new java.util.ArrayList<>(CLIENT);
		}
	}
}
