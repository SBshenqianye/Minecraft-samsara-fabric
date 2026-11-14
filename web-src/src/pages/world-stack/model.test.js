import { describe, expect, it } from 'vitest';
import { stackIconLabel, stackMapRegion, stackMapTileUrl } from './model.js';

describe('world-stack icon model', () => {
  it('出生点所在 region 已生成时直接使用', () => {
    const stack = {
      id: 'world',
      spawn: { x: 160, y: 65, z: -96 },
      dimensions: [{ dim: 'overworld', regions: [[0, -1], [0, 0]] }],
    };
    expect(stackMapRegion(stack)).toEqual({ rx: 0, rz: -1 });
  });

  it('出生点 region 缺失时选最近的一个', () => {
    const stack = {
      id: 'world',
      spawn: { x: 3000, y: 65, z: 3000 },
      dimensions: [{ dim: 'overworld', regions: [[0, 0], [10, 10]] }],
    };
    expect(stackMapRegion(stack)).toEqual({ rx: 10, rz: 10 });
  });

  it('没有出生点时退回第一个 region', () => {
    const stack = {
      id: 'world',
      dimensions: [{ dim: 'overworld', regions: [[3, 4]] }],
    };
    expect(stackMapRegion(stack)).toEqual({ rx: 3, rz: 4 });
  });

  it('没有 region 但有出生点时使用出生点 region', () => {
    const stack = { id: 'world', spawnX: 1024, spawnY: 64, spawnZ: -1024 };
    expect(stackMapRegion(stack)).toEqual({ rx: 2, rz: -2 });
  });

  it('没有 region 也没有出生点时返回 null', () => {
    expect(stackMapRegion({ id: 'world' })).toBeNull();
  });

  it('tile URL 使用公开只读端点并带版本号', () => {
    const url = stackMapTileUrl({
      id: 'stack 1',
      spawn: { x: 0, y: 64, z: 0 },
    });
    expect(url).toContain('/api/user/map/tile?stack=stack%201');
    expect(url).toContain('&rx=0&rz=0');
    expect(url).toContain('&scale=4&shade=1&v=6');
    expect(stackMapTileUrl({ id: 'empty' })).toBe('');
  });

  it('图标来源标签完整且未知来源有回退', () => {
    expect(stackIconLabel('upload')).toBe('自定义图标');
    expect(stackIconLabel('save')).toBe('存档图标');
    expect(stackIconLabel('map')).toBe('出生点地图');
    expect(stackIconLabel('placeholder')).toBe('暂无图标');
    expect(stackIconLabel('unknown')).toBe('暂无图标');
  });
});
