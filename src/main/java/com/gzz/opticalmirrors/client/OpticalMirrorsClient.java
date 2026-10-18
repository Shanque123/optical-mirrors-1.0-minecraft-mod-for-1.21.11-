package com.gzz.opticalmirrors.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpticalMirrorsClient implements ClientModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("OpticalMirrors/Client");

	@Override
	public void onInitializeClient() {
		// 配置先读，后面所有参数都从它来
		MirrorConfig.load();
		IrisCompat.init();

		// 离屏渲染放在 tick 阶段：此时不在任何 framegraph pass 内部，
		// 可以安全地再跑一遍世界渲染（不会嵌套帧图）。
		ClientTickEvents.END_CLIENT_TICK.register(MirrorReflection::updateOffscreen);

		// 正式渲染时把反射贴图画到镜面上。
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(context ->
				MirrorReflection.draw(Minecraft.getInstance(), context.matrices(), context.consumers()));

		// /omirror 客户端指令：调参数、开关、打开面板
		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess) -> MirrorCommand.register(dispatcher));

		// 资源重载后贴图失效，需要重建
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
				new SimpleSynchronousResourceReloadListener() {
					@Override
					public Identifier getFabricId() {
						return com.gzz.opticalmirrors.OpticalMirrors.id("reflection_reload");
					}

					@Override
					public void onResourceManagerReload(ResourceManager manager) {
						MirrorReflection.onReload();
					}
				});

		LOGGER.info("[OpticalMirrors] Client initialized, reflection={}, {}",
				MirrorReflection.isEnabled(), IrisCompat.describe());
	}
}
