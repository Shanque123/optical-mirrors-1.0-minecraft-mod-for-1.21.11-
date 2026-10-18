package com.gzz.opticalmirrors.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * 把一个 {@link RenderTarget} 的颜色贴图包装成 {@link AbstractTexture}，
 * 注册进 {@code TextureManager}，于是可以用标准的
 * {@code RenderTypes.xxx(Identifier)} 采样它，不需要自定义 RenderPipeline。
 *
 * <h2>关键：不写父类的 protected 字段</h2>
 * {@code AbstractTexture} 的 {@code texture} / {@code textureView} / {@code sampler}
 * 是 protected 字段。早期版本直接赋值，运行期报：
 * <pre>
 * NoSuchFieldError: Class ...ReflectionTexture does not have member field 'GpuTexture texture'
 * </pre>
 * 原因是 javac 对<b>继承字段</b>生成的引用 owner 是<b>当前类</b>
 * （常量池里是 {@code ReflectionTexture.texture}），而重映射需要沿继承链把
 * 名字换成 {@code field_56974}——这一步对字段没有生效。
 *
 * <p>因此改成：<b>自己保存一份，并重写三个 getter</b>，完全不碰父类字段。
 * 父类字段另外用 {@link UnsafeSwap#setByType} 按<b>类型</b>补写一份，
 * 以覆盖"万一某个原版代码直接读字段而不是调 getter"的情况
 * ——按类型扫描不依赖中介名，不会再出现名字解析问题。
 */
final class ReflectionTexture extends AbstractTexture {

	private final RenderTarget target;
	private GpuTexture ownTexture;
	private GpuTextureView ownView;
	private GpuSampler ownSampler;

	ReflectionTexture(RenderTarget target, GpuSampler sampler) {
		this.target = target;
		this.ownSampler = sampler;
		sync();
	}

	/** {@code target.resize()} 会重建底层纹理，之后必须重新绑定。 */
	void sync() {
		this.ownTexture = this.target.getColorTexture();
		this.ownView = this.target.getColorTextureView();

		// 按类型补写父类字段（不依赖字段名）
		UnsafeSwap.setByType(AbstractTexture.class, this, GpuTexture.class, this.ownTexture);
		UnsafeSwap.setByType(AbstractTexture.class, this, GpuTextureView.class, this.ownView);
		UnsafeSwap.setByType(AbstractTexture.class, this, GpuSampler.class, this.ownSampler);
	}

	@Override
	public GpuTexture getTexture() {
		return this.ownTexture;
	}

	@Override
	public GpuTextureView getTextureView() {
		return this.ownView;
	}

	@Override
	public GpuSampler getSampler() {
		return this.ownSampler;
	}

	@Override
	public void close() {
		// 纹理由 RenderTarget 拥有，这里绝不能关。
	}
}
