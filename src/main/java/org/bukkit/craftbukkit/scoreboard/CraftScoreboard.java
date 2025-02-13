package org.bukkit.craftbukkit.scoreboard;

import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class CraftScoreboard implements org.bukkit.scoreboard.Scoreboard {
    final net.minecraft.scoreboard.Scoreboard board;
    final Map<String, CraftObjective> objectives = new HashMap<String, CraftObjective>();
    final Map<String, CraftTeam> teams = new HashMap<String, CraftTeam>();

    CraftScoreboard(net.minecraft.scoreboard.Scoreboard board) {
        this.board = board;

        for (net.minecraft.scoreboard.ScoreObjective objective : (Iterable<net.minecraft.scoreboard.ScoreObjective>) board.getScoreObjectives()) {
            new CraftObjective(this, objective); // It adds itself to map
        }
        for (net.minecraft.scoreboard.ScorePlayerTeam team : (Iterable<net.minecraft.scoreboard.ScorePlayerTeam>) board.getTeams()) {
            new CraftTeam(this, team); // It adds itself to map
        }
    }

    public CraftObjective registerNewObjective(String name, String criteria) throws IllegalArgumentException {
        Validate.notNull(name, "Objective name cannot be null");
        Validate.notNull(criteria, "Criteria cannot be null");
        Validate.isTrue(name.length() <= 16, "The name '" + name + "' is longer than the limit of 16 characters");
        Validate.isTrue(board.getObjective(name) == null, "An objective of name '" + name + "' already exists");

        CraftCriteria craftCriteria = CraftCriteria.getFromBukkit(criteria);
        net.minecraft.scoreboard.ScoreObjective objective = board.addScoreObjective(name, craftCriteria.criteria);
        return new CraftObjective(this, objective);
    }

    public Objective getObjective(String name) throws IllegalArgumentException {
        Validate.notNull(name, "Name cannot be null");
        return objectives.get(name);
    }

    public ImmutableSet<Objective> getObjectivesByCriteria(String criteria) throws IllegalArgumentException {
        Validate.notNull(criteria, "Criteria cannot be null");

        ImmutableSet.Builder<Objective> objectives = ImmutableSet.builder();
        for (CraftObjective objective : this.objectives.values()) {
            if (objective.getCriteria().equals(criteria)) {
                objectives.add(objective);
            }
        }
        return objectives.build();
    }

    public ImmutableSet<Objective> getObjectives() {
        return ImmutableSet.copyOf((Collection<? extends Objective>) objectives.values());
    }

    public Objective getObjective(DisplaySlot slot) throws IllegalArgumentException {
        Validate.notNull(slot, "Display slot cannot be null");
        net.minecraft.scoreboard.ScoreObjective objective = board.func_96539_a(CraftScoreboardTranslations.fromBukkitSlot(slot));
        if (objective == null) {
            return null;
        }
        return this.objectives.get(objective.getName());
    }

    public ImmutableSet<Score> getScores(OfflinePlayer player) throws IllegalArgumentException {
        Validate.notNull(player, "OfflinePlayer cannot be null");

        ImmutableSet.Builder<Score> scores = ImmutableSet.builder();
        for (CraftObjective objective : objectives.values()) {
            scores.add(objective.getScore(player));
        }
        return scores.build();
    }

    public ImmutableSet<Score> getScores(String entry) throws IllegalArgumentException {
        Validate.notNull(entry, "Entry cannot be null");

        ImmutableSet.Builder<Score> scores = ImmutableSet.builder();
        for (CraftObjective objective : objectives.values()) {
            scores.add(objective.getScore(entry));
        }
        return scores.build();
    }

    public void resetScores(OfflinePlayer player) throws IllegalArgumentException {
        Validate.notNull(player, "OfflinePlayer cannot be null");

        board.func_96515_c(player.getName());
    }

    public void resetScores(String entry) throws IllegalArgumentException {
        Validate.notNull(entry, "Entry cannot be null");

        board.func_96515_c(entry);
    }

    public Team getPlayerTeam(OfflinePlayer player) throws IllegalArgumentException {
        Validate.notNull(player, "OfflinePlayer cannot be null");

        net.minecraft.scoreboard.ScorePlayerTeam team = board.getPlayersTeam(player.getName());
        return team == null ? null : teams.get(team.getRegisteredName());
    }

    public Team getTeam(String teamName) throws IllegalArgumentException {
        Validate.notNull(teamName, "Team name cannot be null");

        return teams.get(teamName);
    }

    public ImmutableSet<Team> getTeams() {
        return ImmutableSet.copyOf((Collection<? extends Team>) teams.values());
    }

    public Team registerNewTeam(String name) throws IllegalArgumentException {
        Validate.notNull(name, "Team name cannot be null");
        Validate.isTrue(name.length() <= 16, "Team name '" + name + "' is longer than the limit of 16 characters");
        Validate.isTrue(board.getTeam(name) == null, "Team name '" + name + "' is already in use");

        return new CraftTeam(this, board.createTeam(name));
    }

    public ImmutableSet<OfflinePlayer> getPlayers() {
        ImmutableSet.Builder<OfflinePlayer> players = ImmutableSet.builder();
        for (Object playerName : board.getObjectiveNames()) {
            players.add(Bukkit.getOfflinePlayer(playerName.toString()));
        }
        return players.build();
    }

    public ImmutableSet<String> getEntries() {
        ImmutableSet.Builder<String> entries = ImmutableSet.builder();
        for (Object entry : board.getObjectiveNames()) {
            entries.add(entry.toString());
        }
        return entries.build();
    }

    public void clearSlot(DisplaySlot slot) throws IllegalArgumentException {
        Validate.notNull(slot, "Slot cannot be null");
        board.func_96530_a(CraftScoreboardTranslations.fromBukkitSlot(slot), null);
    }

    // CraftBukkit method
    public net.minecraft.scoreboard.Scoreboard getHandle() {
        return board;
    }
}
