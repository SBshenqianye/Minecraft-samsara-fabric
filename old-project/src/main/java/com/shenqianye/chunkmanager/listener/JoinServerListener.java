package com.shenqianye.chunkmanager.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinServerListener implements Listener {
    @EventHandler
    public void onPlayerJoinTest(PlayerJoinEvent event){

        Bukkit.broadcastMessage("Welcome to the server!");

        event.setJoinMessage("Welcome, " + event.getPlayer().getName() + "!");
    }
}
