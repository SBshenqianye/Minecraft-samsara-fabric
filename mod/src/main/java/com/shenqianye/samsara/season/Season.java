package com.shenqianye.samsara.season;

import java.util.ArrayList;
import java.util.List;

/**
 * 周目（Season）—— 一世。字段与设计文档 §3.3 的 season.json 对应。
 */
public class Season {
    public enum State { PENDING, ACTIVE, ENDED, ARCHIVED }

    public int id;
    public String name;
    public State state = State.PENDING;
    public String mode = "same-world";          // same-world | new-world
    public String worldName = "world";
    public Long seed;                            // new-world 模式种子（null = 随机）
    public Long startTime;                       // epoch ms
    public Long endTime;
    public SpawnCenter spawnCenter = new SpawnCenter();
    public List<GroupSpawn> groupSpawns = new ArrayList<>();
    public String note = "";

    public static class SpawnCenter {
        public int x;
        public int z;
        public int radius = 1500;
    }

    public static class GroupSpawn {
        public int x;
        public int z;
        public List<String> playerUuids = new ArrayList<>();
    }
}
