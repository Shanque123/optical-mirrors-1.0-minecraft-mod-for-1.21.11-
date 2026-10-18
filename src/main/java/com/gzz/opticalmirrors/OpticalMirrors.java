package com.gzz.opticalmirrors;

import java.util.function.Function;

import com.gzz.opticalmirrors.block.MirrorBlock;
import com.gzz.opticalmirrors.block.MirrorBlockEntity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 光学镜面（Optical Mirrors）模组入口。
 *
 * <p>一件家具：贴墙式光学镜面（mirror）。可贴在方块的任意一面，
 * 像物品展示框一样固定在墙、地板或天花板上，相邻镜面会拼成一整面大镜子。
 */
public class OpticalMirrors implements ModInitializer {
	public static final String MOD_ID = "opticalmirrors";

	/** 1.21.11 中 CreativeModeTabs 的字段已私有化，直接构造注册键。 */
	private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks"));

	public static final Logger LOGGER = LoggerFactory.getLogger("OpticalMirrors");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	// ---------------- 音效 ----------------
	// 音效文件直接引用原版资源（sounds.json 里的 type=event 代理），
	// 只把字幕换成"镜子：被破坏"。字幕文本在 lang 文件里，随客户端语言变化。

	public static final SoundEvent MIRROR_BREAK = registerSound("mirror.break");
	public static final SoundEvent MIRROR_PLACE = registerSound("mirror.place");

	/**
	 * 方块音效：破坏与放置用自己的事件（字幕为"镜子：…"），
	 * 踩踏/敲击/落地沿用玻璃，手感与原版一致。
	 */
	private static final SoundType MIRROR_SOUND = new SoundType(1.0F, 1.0F,
			MIRROR_BREAK,
			SoundType.GLASS.getStepSound(),
			MIRROR_PLACE,
			SoundType.GLASS.getHitSound(),
			SoundType.GLASS.getFallSound());

	// 强度、抗爆、掉落完全参照玻璃板：玻璃板是 strength(0.3F)，
	// 徒手可破、不需要工具，但不带精准采集就不掉东西（由 loot_table 控制）。
	public static final Block MIRROR = register("mirror", properties -> new MirrorBlock(
			properties.strength(0.3F)
					.sound(MIRROR_SOUND)
					.noOcclusion()
					.mapColor(MapColor.METAL)));

	public static final BlockEntityType<?> MIRROR_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			id("mirror"),
			FabricBlockEntityTypeBuilder.create(MirrorBlockEntity::new, MIRROR).build());

	private static SoundEvent registerSound(String name) {
		Identifier soundId = id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, soundId,
				SoundEvent.createVariableRangeEvent(soundId));
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id(name));
		Block block = factory.apply(BlockBehaviour.Properties.of().setId(blockKey));

		Registry.register(BuiltInRegistries.BLOCK, blockKey, block);

		Item item = new BlockItem(block, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(name))));
		Registry.register(BuiltInRegistries.ITEM, id(name), item);

		return block;
	}

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS).register(entries -> {
			// 显式转型到 MC 的接口：否则引用 owner 是 Fabric 的类，
			// 在 intermediary 环境下接口方法名不同，会触发 NoSuchMethodError。
			CreativeModeTab.Output output = entries;
			output.accept(MIRROR);
		});
	}
}
