package com.shenqianye.samsara.container;

import java.util.ArrayList;
import java.util.List;

/** 容器索引磁盘分片：一个维度的一个 region（32×32 区块）。 */
public final class ContainerShard {
    public int format = 1;
    public String dimension = "";
    public int regionX;
    public int regionZ;
    public List<ContainerEntry> entries = new ArrayList<>();
}
