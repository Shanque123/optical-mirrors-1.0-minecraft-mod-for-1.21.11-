package com.gzz.opticalmirrors.block;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 贴墙式镜面方块（类似物品展示框）。
 *
 * <p>{@code facing} 表示镜面朝向，共 6 种取值：贴在依附方块的哪一面。
 *
 * <h2>几何约定（关键，之前算反过）</h2>
 * 设镜面方块位于 {@code M}，朝向 {@code F}，则依附方块在 {@code M.relative(F.getOpposite())}。
 * 镜板必须<b>紧贴依附方块</b>，也就是放在 {@code M} 单元格靠依附方块那一侧的 1/16 处：
 * <ul>
 *     <li>{@code F} 为负向（NORTH / WEST / DOWN）：依附方块在正侧，镜板取高端的 1/16；</li>
 *     <li>{@code F} 为正向（SOUTH / EAST / UP）：依附方块在负侧，镜板取低端的 1/16。</li>
 * </ul>
 * 镜板的可见面（{@code #glass}）的法线等于 {@code F}，与依附方块接触的那一面设 {@code cullface}。
 *
 * <p>镜板铺满整格 16×16，所以相邻镜面在视觉上拼成一整块大镜子。
 */
public class MirrorBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

	/** 每个朝向对应的碰撞箱：贴墙的 1 像素厚整面板。 */
	private static final Map<Direction, VoxelShape> SHAPES = createShapes();

	private static Map<Direction, VoxelShape> createShapes() {
		Map<Direction, VoxelShape> map = new EnumMap<>(Direction.class);
		// F 为负向 → 依附方块在正侧 → 镜板在高端
		map.put(Direction.NORTH, Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D));
		map.put(Direction.WEST, Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D));
		map.put(Direction.DOWN, Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D));
		// F 为正向 → 依附方块在负侧 → 镜板在低端
		map.put(Direction.SOUTH, Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D));
		map.put(Direction.EAST, Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D));
		map.put(Direction.UP, Block.box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D));
		return map;
	}

	public MirrorBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MirrorBlockEntity(pos, state);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getClickedFace();
		BlockState state = this.defaultBlockState().setValue(FACING, facing);
		return this.canSurvive(state, context.getLevel(), context.getClickedPos()) ? state : null;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		Direction facing = state.getValue(FACING);
		BlockPos supportPos = pos.relative(facing.getOpposite());
		return level.getBlockState(supportPos).isFaceSturdy(level, supportPos, facing);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
			Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		return this.canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	/** 镜面法线（单位向量）。 */
	public static Direction mirrorFacing(BlockState state) {
		return state.getValue(FACING);
	}

	/**
	 * 镜面（玻璃面，也就是真正反光的那个面）所在平面的坐标，单位是 1/16 格。
	 *
	 * <p>镜板厚 1 像素（6 个模型都是 [15,16] 或 [0,1]），有两个平行面：
	 * <ul>
	 *     <li>玻璃面：朝 facing，玩家看到、真正反光的一面；</li>
	 *     <li>背面：贴着依附方块，无光。</li>
	 * </ul>
	 *
	 * <p>早期版本返回的是背面（base + 1 或 base），于是反射面被算在玻璃面后面
	 * 1/16 格，深度测试被方块自己的玻璃面挡住 —— 表现就是镜子里什么都没有，
	 * 把方块打碎才看得到画面。
	 *
	 * <p>正确取值（对应 [15,16] / [0,1]）：
	 * north / west / down（负向）玻璃面在 15/16；
	 * south / east / up（正向）玻璃面在 1/16。
	 *
	 * <p>返回 1/16 单位的整数，让共面判断精确 —— 浮点比较会把相邻平面误合并。
	 */
	public static int planeCoord(BlockPos pos, Direction facing) {
		int base = switch (facing.getAxis()) {
			case X -> pos.getX();
			case Y -> pos.getY();
			default -> pos.getZ();
		};
		boolean negative = facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
		return base * 16 + (negative ? 15 : 1);
	}
}
