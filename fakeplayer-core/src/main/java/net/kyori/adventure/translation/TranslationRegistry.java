package net.kyori.adventure.translation;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 兼容垫片 (compatibility shim)。
 *
 * <p>Adventure 5 (Paper 26.x 自带) 删除了旧的 {@code TranslationRegistry},
 * 改用 {@link TranslationStore} / {@link MessageFormatTranslationStore}。
 * 但 devtools 的 {@code PluginTranslator} 仍然按 Adventure 4 的 API 调用:</p>
 *
 * <ul>
 *     <li>{@code TranslationRegistry.create(Key)}</li>
 *     <li>{@code defaultLocale(Locale)}</li>
 *     <li>{@code registerAll(Locale, ResourceBundle, boolean)}</li>
 *     <li>{@code translate(String, Locale)}</li>
 * </ul>
 *
 * <p>注意: Adventure 4 里 {@code TranslationRegistry} 是<b>接口</b>,
 * devtools 的字节码用 {@code InterfaceMethodref} 调用它, 因此这里也必须保持接口形态,
 * 否则会抛 {@link IncompatibleClassChangeError}。</p>
 */
public interface TranslationRegistry extends Translator {

    static @NotNull TranslationRegistry create(@NotNull Key key) {
        return new SimpleTranslationRegistry(key);
    }

    void defaultLocale(@NotNull Locale locale);

    void registerAll(@NotNull Locale locale, @NotNull ResourceBundle bundle, boolean override);

    @Override
    @Nullable MessageFormat translate(@NotNull String key, @Nullable Locale locale);

}

/**
 * 最小实现: 先按精确 locale 查找, 找不到再回落到默认 locale。
 */
final class SimpleTranslationRegistry implements TranslationRegistry {

    private final Key key;

    private final Map<Locale, Map<String, MessageFormat>> translations = new ConcurrentHashMap<>();

    private volatile Locale defaultLocale = Locale.US;

    SimpleTranslationRegistry(@NotNull Key key) {
        this.key = key;
    }

    @Override
    public @NotNull Key name() {
        return this.key;
    }

    @Override
    public void defaultLocale(@NotNull Locale locale) {
        this.defaultLocale = locale;
    }

    @Override
    public void registerAll(@NotNull Locale locale, @NotNull ResourceBundle bundle, boolean override) {
        var messages = this.translations.computeIfAbsent(locale, ignored -> new ConcurrentHashMap<>());
        for (var messageKey : bundle.keySet()) {
            if (!override && messages.containsKey(messageKey)) {
                continue;
            }
            String value;
            try {
                value = bundle.getString(messageKey);
            } catch (MissingResourceException ignored) {
                continue;
            }
            messages.put(messageKey, new MessageFormat(value, locale));
        }
    }

    @Override
    public @Nullable MessageFormat translate(@NotNull String messageKey, @Nullable Locale locale) {
        if (locale == null) {
            locale = this.defaultLocale;
        }

        var messages = this.translations.get(locale);
        if (messages != null) {
            var format = messages.get(messageKey);
            if (format != null) {
                return format;
            }
        }

        var fallback = this.translations.get(this.defaultLocale);
        return fallback == null ? null : fallback.get(messageKey);
    }

}
