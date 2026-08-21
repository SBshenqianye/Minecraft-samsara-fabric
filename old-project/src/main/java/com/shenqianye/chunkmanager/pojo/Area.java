package com.shenqianye.chunkmanager.pojo;

import java.util.*;


/**
 * 处理节点，包括将节点纳入白名单范围、更新白名单等。
 * <p>
 * 检测节点位置（白名单内部/影响范围/白名单外部）、检测新节点周围是否存在受影响的已有节点（有/无）、检测某坐标是否在影响范围内、
 * 将传入节点作为线段末端延展范围、将传入节点作为线段起始点延展范围、新增一个区域
 *
 * @author shenqianye
 * @version 0.1
 * @since 2024.05.20
 */
public class Area {
    List<Set<LineSegment>> segmentGroups;
    Set<ChunkCoordinate> whitelist;
    List<HashMap<ChunkCoordinate, Node>> influenceNodeMaps;
    HashMap<ChunkCoordinate, Integer> nodeIndexMap;

    public enum Position{
        INNER,
        INFLUENCE,
        OUTER
    }
    public enum AffectedNodeStatus
    {
        PRESENT,  // 存在被影响的节点
        ABSENT    // 不存在被影响的节点
    }

    public Position detectPositionOfNode(Node node){
        if(whitelist.contains(node.getLocation())){
            return Position.INNER;
        }
        if(isInInfluenceGroup(node)){
            return Position.INFLUENCE;
        }
        return Position.OUTER;
    }

    public boolean isInInfluenceGroup(ChunkCoordinate coordinate){
        // 查询传入的坐标是否在影响组中
        for (HashMap<ChunkCoordinate, Node> influenceArea: influenceNodeMaps){
            if(influenceArea.get(coordinate)!=null){
                return true;
            }
        }
        return false;
    }

    public boolean isInInfluenceGroup(int X, int Z){
        return isInInfluenceGroup(new ChunkCoordinate(X, Z));
    }

    public boolean isInInfluenceGroup(Node node){
        return isInInfluenceGroup(node.getLocation());
    }

    public void extendEndOfAreaTo(Node node){
        for (HashMap<ChunkCoordinate, Node> influenceSourceNodeMap : influenceNodeMaps){
            Node sourceNode = influenceSourceNodeMap.get(node.getLocation());
            if(null != sourceNode){
                // 将节点添加至已经存在的线段组中
                int index = influenceNodeMaps.indexOf(influenceSourceNodeMap);
                LineSegment segment = new LineSegment(sourceNode.getLocation() , node.getLocation());
                segmentGroups.get(index).add(segment);
                // 移除 influenceAreaList 中 influenceSourceNodeMap 的某个影响
                influenceSourceNodeMap.put(node.getLocation(), null);
                // 添加新节点影响范围及坐标-索引map
                addInfluence(influenceSourceNodeMap, node);
                nodeIndexMap.put(node.getLocation(), index);
            }
        }
    }

    public void extendStartOfAreaAt(Node node){
        for(ChunkCoordinate influencedCoordination : node.getInfluencedCoordination()){
            Integer index = nodeIndexMap.get(influencedCoordination);
            if(index!=null){
                LineSegment segment = new LineSegment(node.getLocation() , influencedCoordination);
                segmentGroups.get(index).add(segment);
                // 添加新节点影响范围及坐标-索引map
                addInfluence(influenceNodeMaps.get(index), node);
                nodeIndexMap.put(node.getLocation(), index);
            }
        }
    }

    public void createNewArea(Node node){
        // 将节点添加至新的线段组中
        Set<LineSegment> group = new HashSet<>();
        LineSegment segment = new LineSegment(node.getLocation(), node.getLocation());
        group.add(segment);
        segmentGroups.add(group);
        // 添加新节点影响范围及坐标-索引map
        HashMap<ChunkCoordinate, Node> influenceSourceNodeMap = new HashMap<>();
        addInfluence(influenceSourceNodeMap, node);
        influenceNodeMaps.add(influenceSourceNodeMap);
        int index = influenceNodeMaps.size();
        nodeIndexMap.put(node.getLocation(), index);
    }

    public AffectedNodeStatus detectInfluencedNodesAround(Node node){
        for(ChunkCoordinate influencedCoordination : node.getInfluencedCoordination()){
            Integer index = nodeIndexMap.get(influencedCoordination);
            if(index != null){
                return AffectedNodeStatus.PRESENT;
            }
        }
        return AffectedNodeStatus.ABSENT;
    }

    public void mergeSegmentGroups(){
        // 合并拥有相同线段的组
        for(int i = 0; i < segmentGroups.size(); i++){
            Set<LineSegment> segmentSetI = segmentGroups.get(i);
            if(segmentSetI==null || segmentSetI.size()==0) continue;
            for(int j = i; j < segmentGroups.size(); j++){
                Set<LineSegment> segmentSetJ = segmentGroups.get(j);
                if(segmentSetJ==null || segmentSetJ.size()==0) continue;
                Set<LineSegment> tempSet = new HashSet<>(segmentSetI);
                tempSet.retainAll(segmentSetJ);
                if(tempSet.size()!=0){
                    segmentSetI.addAll(segmentSetJ);
                    segmentSetJ.clear();
                }
            }
        }
        segmentGroups.removeIf(segmentSet -> segmentSet.size() == 0);
    }

    public Shape convertLineSegmentsToShape(Set<LineSegment> lineSegments){
        // 化线为面(暂且搁置
        return null;
    }

    public void generateWhitelistCoordinates(){
        // 总结白名单坐标
        Set<ChunkCoordinate> whitelist = new HashSet<>();
        for(Set<LineSegment> lineSegments: segmentGroups){
            Set<ChunkCoordinate> whitelistPart = new HashSet<>();
            Shape s = convertLineSegmentsToShape(lineSegments);
            if(s != null){
                // 获取内部点集;
            }
            for (LineSegment lineSegment : lineSegments){
                whitelistPart.add((ChunkCoordinate) lineSegment.getEnd());
                whitelistPart.add((ChunkCoordinate) lineSegment.getStart());
            }
            whitelist.addAll(whitelistPart);
        }
        this.whitelist = whitelist;
    }
    public void addInfluence(HashMap<ChunkCoordinate, Node> influenceMap, Node node){
        // 添加新的影响
        List<ChunkCoordinate> influencedCoordination = node.getInfluencedCoordination();
        for(ChunkCoordinate coordinate: influencedCoordination){
            influenceMap.put(coordinate, node);
        }
    }
}
