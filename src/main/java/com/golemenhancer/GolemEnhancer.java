package com.golemenhancer;

import org.bukkit.plugin.java.JavaPlugin;

public class GolemEnhancer extends JavaPlugin {

    @Override
    public void onEnable() {
        GolemListener listener = new GolemListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getLogger().info("GolemEnhancer enabled! Copper Golems are now smarter.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GolemEnhancer disabled.");
    }
}
