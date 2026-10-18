package com.gzz.opticalmirrors.client;

import java.util.Locale;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 反射参数调整面板。
 *
 * <h2>为什么自带界面而不是依赖 Cloth Config</h2>
 * 依赖第三方配置库意味着玩家必须同时装那个库，且要跟着它的每次 API 变动改代码。
 * 这里只用原版 {@link Button} 与 {@link Screen}，零额外依赖。
 *
 * <h2>交互</h2>
 * 每个数值项是一个<b>循环按钮</b>：点一次跳到下一个预设档位（到顶回到最小）。
 * 需要精确值就用指令 {@code /omirror set <键> <值>}，两者读写同一份配置。
 *
 * <p>所有文案都是翻译键，随客户端语言变化；简体中文显示中文，其它语言回落英文。
 */
public final class MirrorConfigScreen extends Screen {

	private static final int ROW_H = 24;
	private static final int COL_W = 150;
	private static final int GAP = 4;

	private final Screen parent;

	public MirrorConfigScreen(Screen parent) {
		super(Component.translatable("opticalmirrors.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MirrorConfig cfg = MirrorConfig.get();

		int colLeft = this.width / 2 - COL_W - GAP / 2;
		int colRight = this.width / 2 + GAP / 2 + 1;
		int top = 42;

		// 左列：开销相关
		add(colLeft, top, () -> label("opticalmirrors.config.enabled", onOff(cfg.enabled)),
				b -> {
					cfg.enabled = !cfg.enabled;
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		add(colLeft, top + ROW_H, () -> label("opticalmirrors.config.pixelsPerBlock",
				Integer.toString(cfg.pixelsPerBlock)), b -> {
					cfg.pixelsPerBlock = MirrorConfig.nextStep(MirrorConfig.PIXELS_PER_BLOCK_STEPS, cfg.pixelsPerBlock);
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		add(colLeft, top + ROW_H * 2, () -> label("opticalmirrors.config.maxResolution",
				Integer.toString(cfg.maxResolution)), b -> {
					cfg.maxResolution = MirrorConfig.nextStep(MirrorConfig.MAX_RESOLUTION_STEPS, cfg.maxResolution);
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		add(colLeft, top + ROW_H * 3, () -> label("opticalmirrors.config.planesPerTick",
				Integer.toString(cfg.planesPerTick)), b -> {
					cfg.planesPerTick = MirrorConfig.nextStep(MirrorConfig.PLANES_PER_TICK_STEPS, cfg.planesPerTick);
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		add(colLeft, top + ROW_H * 4, () -> label("opticalmirrors.config.intervalTicks",
				Integer.toString(cfg.intervalTicks)), b -> {
					cfg.intervalTicks = MirrorConfig.nextStep(MirrorConfig.INTERVAL_TICKS_STEPS, cfg.intervalTicks);
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		add(colLeft, top + ROW_H * 5, () -> label("opticalmirrors.config.budgetMs",
				String.format(Locale.ROOT, "%.1f", cfg.budgetMs)), b -> {
					int next = MirrorConfig.nextStep(MirrorConfig.BUDGET_MS_STEPS,
							(int) Math.ceil(cfg.budgetMs));
					cfg.budgetMs = next;
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		// 右列：其余项 + 操作
		add(colRight, top, () -> label("opticalmirrors.config.maxDistance",
				Integer.toString(cfg.maxDistance)), b -> {
					cfg.maxDistance = MirrorConfig.nextStep(MirrorConfig.MAX_DISTANCE_STEPS, cfg.maxDistance);
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		add(colRight, top + ROW_H, () -> label("opticalmirrors.config.irisMode",
				Component.translatable("opticalmirrors.config.iris." + cfg.irisMode.name().toLowerCase(Locale.ROOT))),
				b -> {
					cfg.irisMode = nextIrisMode(cfg.irisMode);
					MirrorConfig.save();
					MirrorReflection.onConfigChanged();
					this.rebuildWidgets();
				});

		add(colRight, top + ROW_H * 2, () -> label("opticalmirrors.config.irisIntervalTicks",
				Integer.toString(cfg.irisIntervalTicks)), b -> {
					cfg.irisIntervalTicks = MirrorConfig.nextStep(MirrorConfig.IRIS_INTERVAL_STEPS,
							cfg.irisIntervalTicks);
					MirrorConfig.save();
					this.rebuildWidgets();
				});

		this.addRenderableWidget(Button.builder(Component.translatable("opticalmirrors.config.reset"), b -> {
			MirrorConfig.get().reset();
			MirrorConfig.save();
			MirrorReflection.onConfigChanged();
			this.rebuildWidgets();
		}).bounds(colRight, top + ROW_H * 4, COL_W, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("opticalmirrors.config.done"), b -> this.onClose())
				.bounds(colRight, top + ROW_H * 5, COL_W, 20).build());
	}

	/** 统一的按钮工厂：标签用 Supplier 现取，改完 rebuild 就能刷新。 */
	private void add(int x, int y, Supplier<Component> text, Button.OnPress onPress) {
		this.addRenderableWidget(Button.builder(text.get(), onPress).bounds(x, y, COL_W, 20).build());
	}

	private static Component onOff(boolean enabled) {
		return Component.translatable(enabled ? "opticalmirrors.config.on" : "opticalmirrors.config.off");
	}

	/** 键值：{@code 名称: 值}。 */
	private static Component label(String key, Component value) {
		return Component.translatable(key).append(Component.literal(": ")).append(value);
	}

	private static Component label(String key, String value) {
		return label(key, Component.literal(value));
	}

	private static MirrorConfig.IrisMode nextIrisMode(MirrorConfig.IrisMode mode) {
		MirrorConfig.IrisMode[] all = MirrorConfig.IrisMode.values();
		return all[(mode.ordinal() + 1) % all.length];
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
		// 4 个参数必须与 lang 里的 4 个 %s 对齐：少传一个 String.format 会抛异常，
		// 面板一打开就崩。
		graphics.drawCenteredString(this.font,
				Component.translatable("opticalmirrors.config.status",
						Component.translatable(IrisCompat.describeKey()),
						MirrorReflection.planeCount(), MirrorReflection.groupCount(),
						MirrorReflection.lastRenderMillis()),
				this.width / 2, 26, 0xA0A0A0);
		graphics.drawCenteredString(this.font,
				Component.translatable("opticalmirrors.config.hint"),
				this.width / 2, this.height - 20, 0x808080);
	}

	@Override
	public void onClose() {
		MirrorConfig.save();
		MirrorReflection.onConfigChanged();
		this.minecraft.setScreen(this.parent);
	}
}
