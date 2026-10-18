package net.kyori.adventure.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * 兼容垫片 (compatibility shim)。
 *
 * <p>Adventure 5 (Paper 26.x 自带) 移除了 {@code net.kyori.adventure.util.UTF8ResourceBundleControl},
 * 而 devtools 的 {@code PluginTranslator} 仍然直接 {@code new} 这个类来读取 UTF-8 的
 * {@code .properties} 语言文件, 导致插件启用时抛 {@link NoClassDefFoundError}。</p>
 *
 * <p>这里按 Adventure 4.x 的语义补回同名类: 以 UTF-8 读取 {@code .properties}。
 * 因为父加载器 (服务端) 中不存在该类, 插件类加载器会回落到本类, 从而让 devtools 正常工作。</p>
 */
public class UTF8ResourceBundleControl extends ResourceBundle.Control {

    public UTF8ResourceBundleControl() {
        super();
    }

    @Override
    public @Nullable ResourceBundle newBundle(
            @NotNull String baseName,
            @NotNull Locale locale,
            @NotNull String format,
            @NotNull ClassLoader loader,
            boolean reload
    ) throws IllegalAccessException, InstantiationException, IOException {
        if (!"java.properties".equals(format)) {
            return super.newBundle(baseName, locale, format, loader, reload);
        }

        var resourceName = toResourceName(toBundleName(baseName, locale), "properties");
        try (InputStream stream = loader.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return null;
            }
            return new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

}
