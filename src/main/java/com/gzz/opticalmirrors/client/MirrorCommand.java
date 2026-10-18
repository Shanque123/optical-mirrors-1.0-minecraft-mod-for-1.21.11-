package com.gzz.opticalmirrors.client;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /omirror} 客户端指令。
 *
 * <h2>为什么是客户端指令</h2>
 * 这些参数只影响<b>本机</b>的反射贴图与开销，与服务器无关，也不该需要 OP 权限。
 *
 * <h2>所有文案都走翻译键</h2>
 * 反馈文本一律用 {@code Component.translatable}，由客户端语言决定显示内容：
 * 简体中文显示中文，其它语言回落到 {@code en_us}。代码里不留任何中文字面量。
 */
public final class MirrorCommand {

	private MirrorCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("omirror")
				.executes(ctx -> status(ctx.getSource()))

				.then(ClientCommandManager.literal("status")
						.executes(ctx -> status(ctx.getSource())))

				.then(ClientCommandManager.literal("on")
						.executes(ctx -> set(ctx.getSource(), "enabled", "true")))

				.then(ClientCommandManager.literal("off")
						.executes(ctx -> set(ctx.getSource(), "enabled", "false")))

				.then(ClientCommandManager.literal("toggle")
						.executes(ctx -> {
							MirrorConfig cfg = MirrorConfig.get();
							return set(ctx.getSource(), "enabled", Boolean.toString(!cfg.enabled));
						}))

				.then(ClientCommandManager.literal("config")
						.executes(ctx -> {
							var client = ctx.getSource().getClient();
							client.setScreen(new MirrorConfigScreen(client.screen));
							return 1;
						}))

				.then(ClientCommandManager.literal("list")
						.executes(ctx -> {
							MirrorConfig cfg = MirrorConfig.get();
							for (String key : MirrorConfig.keys()) {
								ctx.getSource().sendFeedback(Component.literal(key + " = " + cfg.getKey(key)));
							}
							return 1;
						}))

				.then(ClientCommandManager.literal("get")
						.then(ClientCommandManager.argument("key", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (String key : MirrorConfig.keys()) {
										builder.suggest(key);
									}
									return builder.buildFuture();
								})
								.executes(ctx -> {
									String key = StringArgumentType.getString(ctx, "key");
									String value = MirrorConfig.get().getKey(key);
									if (value == null) {
										ctx.getSource().sendError(Component.translatable(
												"opticalmirrors.command.set.unknownKey", key));
										return 0;
									}
									ctx.getSource().sendFeedback(Component.literal(key + " = " + value));
									return 1;
								})))

				.then(ClientCommandManager.literal("set")
						.then(ClientCommandManager.argument("key", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (String key : MirrorConfig.keys()) {
										builder.suggest(key);
									}
									return builder.buildFuture();
								})
								.then(ClientCommandManager.argument("value", StringArgumentType.word())
										.executes(ctx -> set(ctx.getSource(),
												StringArgumentType.getString(ctx, "key"),
												StringArgumentType.getString(ctx, "value"))))))

				.then(ClientCommandManager.literal("preset")
						.then(ClientCommandManager.argument("level", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									builder.suggest("low");
									builder.suggest("medium");
									builder.suggest("high");
									return builder.buildFuture();
								})
								.executes(ctx -> {
									String level = StringArgumentType.getString(ctx, "level");
									if (!MirrorConfig.get().applyPreset(level)) {
										ctx.getSource().sendError(Component.translatable(
												"opticalmirrors.command.preset.unknown", level));
										return 0;
									}
									MirrorConfig.save();
									MirrorReflection.onConfigChanged();
									ctx.getSource().sendFeedback(Component.translatable(
											"opticalmirrors.command.preset.ok", level));
									return 1;
								})))

				.then(ClientCommandManager.literal("reset")
						.executes(ctx -> {
							MirrorConfig.get().reset();
							MirrorConfig.save();
							MirrorReflection.onConfigChanged();
							ctx.getSource().sendFeedback(Component.translatable("opticalmirrors.command.reset"));
							return 1;
						}))

				.then(ClientCommandManager.literal("save")
						.executes(ctx -> {
							MirrorConfig.save();
							ctx.getSource().sendFeedback(Component.translatable("opticalmirrors.command.saved"));
							return 1;
						}))

				.then(ClientCommandManager.literal("reload")
						.executes(ctx -> {
							MirrorConfig.load();
							MirrorReflection.onConfigChanged();
							ctx.getSource().sendFeedback(Component.translatable("opticalmirrors.command.reloaded"));
							return 1;
						})));
	}

	private static int set(FabricClientCommandSource source, String key, String value) {
		MirrorConfig cfg = MirrorConfig.get();
		MirrorConfig.SetResult result = cfg.setKey(key, value);
		if (result == MirrorConfig.SetResult.UNKNOWN_KEY) {
			source.sendError(Component.translatable("opticalmirrors.command.set.unknownKey", key));
			return 0;
		}
		if (result == MirrorConfig.SetResult.BAD_VALUE) {
			source.sendError(Component.translatable("opticalmirrors.command.set.badValue", key, value));
			return 0;
		}
		MirrorConfig.save();
		MirrorReflection.onConfigChanged();
		source.sendFeedback(Component.translatable("opticalmirrors.command.set.ok", key, cfg.getKey(key)));
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		MirrorConfig cfg = MirrorConfig.get();
		Component enabled = Component.translatable(
				cfg.enabled ? "opticalmirrors.command.on" : "opticalmirrors.command.off");

		source.sendFeedback(Component.translatable("opticalmirrors.command.status",
				enabled,
				MirrorReflection.planeCount(),
				MirrorReflection.groupCount(),
				MirrorReflection.lastRenderMillis(),
				Component.translatable(IrisCompat.describeKey())));

		source.sendFeedback(Component.translatable("opticalmirrors.command.status.detail",
				cfg.pixelsPerBlock,
				cfg.maxResolution,
				cfg.planesPerTick,
				cfg.intervalTicks,
				formatMs(cfg.budgetMs),
				cfg.maxDistance,
				Component.translatable("opticalmirrors.mode." + cfg.irisMode.name().toLowerCase(Locale.ROOT))));
		return 1;
	}

	private static String formatMs(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
