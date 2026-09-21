package dev.bastion.hook;

import dev.bastion.dungeon.Dungeon;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** %dungeon_state%, %dungeon_goal_current%, ... The list is in the plan and the README. */
public final class DungeonExpansion extends PlaceholderExpansion {

    private final Dungeon dungeon;

    public DungeonExpansion(Dungeon dungeon) {
        this.dungeon = dungeon;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "dungeon";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Bastion";
    }

    @Override
    public @NotNull String getVersion() {
        return dungeon.plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return dungeon.placeholder(player, params.toLowerCase(java.util.Locale.ROOT));
    }
}
