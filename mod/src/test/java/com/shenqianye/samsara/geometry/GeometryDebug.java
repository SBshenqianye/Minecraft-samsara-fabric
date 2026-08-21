package com.shenqianye.samsara.geometry;

import java.util.*;

/** Debug helper — run from IDE to trace algorithm steps */
public class GeometryDebug {
    public static void main(String[] args) {
        // Test03: orthogonal square
        List<int[]> segs = List.of(
                new int[]{0,0, 3,0},
                new int[]{3,0, 3,3},
                new int[]{3,3, 0,3},
                new int[]{0,3, 0,0}
        );

        // Build points + adj manually
        Set<ChunkCoord> allPoints = new HashSet<>();
        Map<ChunkCoord, Set<ChunkCoord>> adj = new HashMap<>();
        for (int[] seg : segs) {
            ChunkCoord a = new ChunkCoord(seg[0], seg[1]);
            ChunkCoord b = new ChunkCoord(seg[2], seg[3]);
            List<ChunkCoord> path = Geometry.rasterize(a, b);
            for (int i = 0; i < path.size(); i++) {
                allPoints.add(path.get(i));
                ChunkCoord cur = path.get(i);
                if (i > 0) {
                    ChunkCoord prev = path.get(i - 1);
                    adj.computeIfAbsent(cur, k -> new HashSet<>()).add(prev);
                    adj.computeIfAbsent(prev, k -> new HashSet<>()).add(cur);
                }
            }
        }

        System.out.println("allPoints (" + allPoints.size() + "): " + sorted(allPoints));

        // Find faces
        List<Set<ChunkCoord>> faces = Geometry.findFaces(allPoints, adj);
        System.out.println("\nfaces found: " + faces.size());
        for (int i = 0; i < faces.size(); i++) {
            System.out.println("  face " + i + " (" + faces.get(i).size() + "): " + sorted(faces.get(i)));
        }

        // Scanline fill each face
        for (int i = 0; i < faces.size(); i++) {
            Set<ChunkCoord> interior = Geometry.scanlineFill(faces.get(i), adj);
            System.out.println("  interior " + i + " (" + interior.size() + "): " + sorted(interior));
        }

        // Full compute
        Set<ChunkCoord> result = Geometry.compute(null, segs);
        System.out.println("\nresult (" + result.size() + "): " + sorted(result));
    }

    static List<ChunkCoord> sorted(Set<ChunkCoord> s) {
        List<ChunkCoord> l = new ArrayList<>(s);
        l.sort(Comparator.comparingInt((ChunkCoord c) -> c.x).thenComparingInt(c -> c.z));
        return l;
    }
}
