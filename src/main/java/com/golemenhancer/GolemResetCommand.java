package com.golemenhancer;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;

public class GolemResetCommand implements CommandExecutor {

    private final GolemListener listener;

    public GolemResetCommand(GolemListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int count = 0;

        for (World world : org.bukkit.Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof CopperGolem golem) {
                    listener.clearMemory(golem);
                    count++;
                }
            }
        }

        sender.sendMessage("§aCleared memory for " + count + " copper golem(s) across all worlds.");
        return true;
    }
}
