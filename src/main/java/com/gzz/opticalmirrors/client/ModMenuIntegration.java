package com.gzz.opticalmirrors.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：把模组列表里的「配置」按钮接到 {@link MirrorConfigScreen}。
 *
 * <h2>为什么不做成硬依赖</h2>
 * Mod Menu 只实例化 {@code fabric.mod.json} 里 {@code "modmenu"} 入口声明的类。
 * 没装 Mod Menu 时这个类根本不会被加载，所以不需要在编译期之外的任何保护，
 * 也不会因为缺少 ModMenu 类而抛 {@code NoClassDefFoundError}。
 *
 * <p>界面本身（{@link MirrorConfigScreen}）只有一个入口，
 * Mod Menu 和 {@code /omirror config} 打开的是同一个屏幕，
 * 两者改的是同一份 {@code config/opticalmirrors.json}。
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MirrorConfigScreen::new;
	}
}
