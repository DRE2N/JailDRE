/*
 * Copyright (C) 2020 Daniel Saukel
 *
 * All rights reserved.
 */
package de.erethon.jaildre;

import static net.md_5.bungee.api.ChatColor.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ClickEvent.Action;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class JailDRE extends JavaPlugin implements Listener {

    static final String[] CMD_WHITELIST = {
        "/w(arn|xs|arnxs|)? list",
        "/(check|reply|set|)?ticket.*"
    };

    Player[] playerCache;
    int[] timeCache = new int[getServer().getMaxPlayers()];
    int[][] posCache = new int[3][getServer().getMaxPlayers()];
    Tick tick;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getDataFolder().mkdir();

        playerCache = new Player[timeCache.length];

        for (Player player : getServer().getOnlinePlayers()) {
            int time = getJailTime(player);
            if (time > 0) {
                jail(player, time);
            }
        }
    }

    @Override
    public void onDisable() {
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("jail") || args.length < 1 || !sender.isOp()) {
            return false;
        }

        if (args[0].equalsIgnoreCase("set") && sender instanceof Player) {
            Player player = (Player) sender;
            getConfig().set("location", player.getLocation());
            player.spigot().sendMessage(new ComponentBuilder("Die Jail-Position wurde aktualisiert.").color(DARK_AQUA).create());
            return true;
        }

        if (getConfig().get("location") == null) {
            sender.sendMessage("Es ist kein Jail festgelegt worden.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("/jail [Spieler] [Minuten]");
            return true;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[0]);
        if (!offlinePlayer.hasPlayedBefore()) {
            if (sender instanceof Player) {
                ((Player) sender).spigot().sendMessage(new ComponentBuilder("Der Spieler konnte nicht gefunden werden.").color(DARK_RED).create());
            } else {
                sender.sendMessage("Der Spieler konnte nicht gefunden werden.");
            }
            return true;
        }

        int duration = -1;
        try {
            duration = getJailTime(offlinePlayer) + Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage("/jail [Spieler] [Minuten]");
            return true;
        }
        if (duration < 0) {
            duration = 0;
        }

        setJailTime(offlinePlayer, duration);
        if (offlinePlayer.isOnline()) {
            jail(offlinePlayer.getPlayer(), duration);
        }

        if (sender instanceof Player) {
            ((Player) sender).spigot().sendMessage(new ComponentBuilder("Der Spieler ").color(DARK_AQUA)
                    .append(offlinePlayer.getName()).color(DARK_RED)
                    .append(" wurde f\u00fcr insgesamt\n ").color(DARK_AQUA)
                    .append(toHHMM(duration)).color(DARK_RED)
                    .append(" inhaftiert.").color(DARK_AQUA)
                    .create()
            );
        } else {
            sender.sendMessage(String.format("Der Spieler \"%s\" wurde f\u00fcr %s inhaftiert.", offlinePlayer.getName(), toHHMM(duration)));
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int time = getJailTime(player);
        if (time > 0) {
            getServer().getScheduler().runTaskLater(this, () -> jail(player, time), 1L);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        int time = getJailTime(player);
        if (time <= 0) {
            return;
        }

        String msg = event.getMessage().toLowerCase();
        for (String cmd : CMD_WHITELIST) {
            if (msg.matches(cmd)) {
                return;
            }
        }

        player.spigot().sendMessage(new ComponentBuilder("Du darfst w\u00e4hrend der Haft keine Befehle benutzen.").color(DARK_RED).create());
        event.setCancelled(true);
    }

    class Tick extends BukkitRunnable {

        int sec, min;

        @Override
        public void run() {
            boolean stopTick = true;
            sec++;

            for (int i = 0; i < playerCache.length; i++) {
                if (playerCache[i] == null) {
                    continue;
                }
                if (!playerCache[i].isOnline()) {
                    playerCache[i] = null;
                    continue;
                }

                if (sec == 59) {
                    if (!posChanged(i) && min == 4) {
                        playerCache[i].kickPlayer("Du darfst im Gef\u00e4ngnis nicht AFK sein.");
                        continue;
                    }
                    sendTime(playerCache[i], --timeCache[i]);
                    setJailTime(playerCache[i], timeCache[i]);
                    sec = 0;
                    if (min == 4) {
                        min = 0;
                    } else {
                        min++;
                    }
                    stopTick = timeCache[i] <= 0;

                } else {
                    sendTime(playerCache[i], timeCache[i]);
                    stopTick = false;
                }
            }

            if (stopTick) {
                cancel();
                tick = null;
            }
        }

    }

    void startTick() {
        if (tick == null) {
            (tick = new Tick()).runTaskTimer(JailDRE.this, 20L, 20L);
        }
    }

    int getJailTime(OfflinePlayer offlinePlayer) {
        return getConfig().getInt("prisoners." + offlinePlayer.getUniqueId().toString(), 0);
    }

    void setJailTime(OfflinePlayer offlinePlayer, int time) {
        getConfig().set("prisoners." + offlinePlayer.getUniqueId().toString(), time > 0 ? time : null);
        if (time != 0 || !offlinePlayer.isOnline()) {
            return;
        }
        for (int i = 0; i < playerCache.length; i++) {
            if (offlinePlayer.getPlayer().equals(playerCache[i])) {
                unjail(i);
            }
        }
    }

    void jail(Player player, int time) {
        player.performCommand("dxl leave");
        player.performCommand("f c p");
        Location location = (Location) getConfig().get("location");
        player.teleport(location);
        player.spigot().sendMessage(new ComponentBuilder("Du wurdest f\u00fcr insgesamt ").color(DARK_AQUA)
                .append(toHHMM(time)).color(DARK_RED)
                .append(" inhaftiert.\n ").color(DARK_AQUA)
                .append("Klicke hier").color(DARK_RED).underlined(true)
                .event(new ClickEvent(Action.RUN_COMMAND, "/w list"))
                .append(" f\u00fcr mehr Informationen.").color(DARK_AQUA).underlined(false)
                .create()
        );

        for (int i = 0; i < playerCache.length; i++) {
            if (playerCache[i] != null && !player.equals(playerCache[i])) {
                continue;
            }
            playerCache[i] = player;
            timeCache[i] = time;
            posChanged(i);
            break;
        }
        startTick();
    }

    void unjail(int i) {
        playerCache[i].spigot().sendMessage(new ComponentBuilder("Du wurdest aus dem Gef\u00e4ngnis freigelassen.").color(DARK_AQUA).create());
        playerCache[i].teleport(getServer().getWorlds().get(0).getSpawnLocation());
        playerCache[i] = null;
    }

    void sendTime(Player player, int min) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new ComponentBuilder("Du bist noch f\u00fcr ").color(DARK_RED)
                .append(toHHMM(min)).color(DARK_AQUA)
                .append(" inhaftiert.").color(DARK_RED)
                .create()
        );
    }

    String toHHMM(int min) {
        String hhmm;
        int h = min / 60;
        if (h == 1) {
            hhmm = h + " Stunde und ";
        } else if (h > 1) {
            hhmm = h + " Stunden und ";
        } else {
            hhmm = "";
        }
        min = min % 60;
        if (min == 1) {
            hhmm += min + " Minute";
        } else {
            hhmm += min + " Minuten";
        }
        return hhmm;
    }

    boolean posChanged(int i) {
        boolean changed = posCache[0][i] != playerCache[i].getLocation().getBlockX()
                || posCache[1][i] != playerCache[i].getLocation().getBlockY()
                || posCache[2][i] != playerCache[i].getLocation().getBlockZ();
        posCache[0][i] = playerCache[i].getLocation().getBlockX();
        posCache[1][i] = playerCache[i].getLocation().getBlockY();
        posCache[2][i] = playerCache[i].getLocation().getBlockZ();
        return changed;
    }

}
