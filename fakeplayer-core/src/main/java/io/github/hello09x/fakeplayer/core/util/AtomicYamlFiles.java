package io.github.hello09x.fakeplayer.core.util;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;

public final class AtomicYamlFiles {
    private AtomicYamlFiles() {}

    public static void write(YamlConfiguration yaml, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        var temporary = target.resolveSibling(target.getFileName() + ".tmp");
        yaml.save(temporary.toFile());
        if (Files.exists(target)) {
            Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
