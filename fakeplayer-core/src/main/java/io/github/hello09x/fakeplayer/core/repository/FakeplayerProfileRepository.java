package io.github.hello09x.fakeplayer.core.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.devtools.database.jdbc.JdbcTemplate;
import io.github.hello09x.devtools.database.jdbc.rowmapper.BooleanRowMapper;
import io.github.hello09x.fakeplayer.core.repository.model.FakePlayerProfile;
import io.github.hello09x.fakeplayer.core.manager.naming.exception.IllegalCustomNameException;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * @author tanyaofei
 * @since 2024/8/3
 **/
@Singleton
public class FakeplayerProfileRepository {

    private final JdbcTemplate jdbc;

    @Inject
    public FakeplayerProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.initTables();
    }

    public void insert(@NotNull String name, @NotNull UUID uuid) {
        var sql = "INSERT INTO fake_player_profile (name, uuid) VALUES (?, ?)";
        jdbc.update(sql, name, uuid.toString());
    }

    public boolean existsByUUID(@NotNull UUID uuid) {
        var sql = "SELECT EXISTS(SELECT 1 FROM fake_player_profile WHERE uuid = ?)";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, new BooleanRowMapper(), uuid.toString()));
    }

    public @Nullable UUID selectUUIDByName(@NotNull String name) {
        return Optional.ofNullable(this.selectByName(name)).map(FakePlayerProfile::uuid).map(UUID::fromString).orElse(null);
    }

    public @Nullable FakePlayerProfile selectByName(@NotNull String name) {
        var sql = "SELECT * FROM fake_player_profile WHERE name = ? COLLATE NOCASE ORDER BY id";
        var matches = jdbc.query(sql, FakePlayerProfile.FakePlayerProfileRowMapper.instance, name);
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.getFirst();
        // Old versions could create independent saves differing only by case. Never merge them.
        return matches.stream().filter(profile -> profile.name().equals(name)).findFirst().orElseThrow(() ->
                new IllegalCustomNameException(Component.text("存在历史同名存档，请使用原始大小写：" +
                        matches.stream().map(FakePlayerProfile::name).collect(java.util.stream.Collectors.joining(", ")))));
    }

    private void initTables() {
        jdbc.execute("""
                             create table if not exists fake_player_profile
                             (
                                 id   integer  not null primary key autoincrement,
                                 name text(32) not null,
                                 uuid text(36) not null
                             );
                             """);

        jdbc.execute("""
                             create unique index if not exists fake_player_profile_name
                                        on fake_player_profile (name);
                             """);

        jdbc.execute("""
                             create unique index if not exists fake_player_profile_uuid
                                        on fake_player_profile (uuid);
                             """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS fake_player_profile_name_nocase ON fake_player_profile(name COLLATE NOCASE)");
        // Triggers protect new writes without rejecting or rewriting existing case conflicts.
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS fake_player_profile_case_insert BEFORE INSERT ON fake_player_profile
                WHEN EXISTS(SELECT 1 FROM fake_player_profile WHERE name = NEW.name COLLATE NOCASE)
                BEGIN SELECT RAISE(ABORT, 'Fakeplayer name already exists ignoring case'); END
                """);
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS fake_player_profile_case_update BEFORE UPDATE OF name ON fake_player_profile
                WHEN NEW.name <> OLD.name AND EXISTS(SELECT 1 FROM fake_player_profile
                    WHERE id <> OLD.id AND name = NEW.name COLLATE NOCASE)
                BEGIN SELECT RAISE(ABORT, 'Fakeplayer name already exists ignoring case'); END
                """);
    }


}
