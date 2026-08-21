package com.shenqianye.chunkmanager;

import com.shenqianye.chunkmanager.command.CommandKit;
import com.shenqianye.chunkmanager.exceptions.CreateFileFailError;
import com.shenqianye.chunkmanager.listener.BannerPlacementListener;
import com.shenqianye.chunkmanager.listener.JoinServerListener;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class ChunkManager extends JavaPlugin {
    FileConfiguration config = getConfig();

    @Override
    public void onEnable() {
        try {
            loadDataFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.getCommand("kit").setExecutor(new CommandKit());
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new JoinServerListener(), this);
        pluginManager.registerEvents(new BannerPlacementListener(), this);

    }

    private void loadDataFile() throws IOException {
        File dataFolder = getDataFolder();
        // 检查配置文件是否存在
        if (!dataFolder.exists()) {
            // 创建数据文件夹
            if (!dataFolder.mkdir()){
                throw new CreateFileFailError();
            }
        }

        // 不存在数据文件则创建文件
        File whitelistFile = new File(dataFolder, "chunk_whitelist.dat"); // 指定文件路径
        File influenceGroupFile = new File(dataFolder, "influence_group.dat"); // 指定文件路径
        if(!whitelistFile.exists() || !influenceGroupFile.exists()){
            if(!whitelistFile.createNewFile()){
                throw new CreateFileFailError();
            }
            if(!influenceGroupFile.createNewFile()){
                throw new CreateFileFailError();
            }
        }

        // 读取数据文件
    }
}
