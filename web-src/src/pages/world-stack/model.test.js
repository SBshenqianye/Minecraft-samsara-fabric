import { describe, expect, it } from 'vitest';
import {
  filesView,
  fmtBytes,
  fmtTime,
  importPreviewView,
  importQueueView,
  jobsView,
  orderView,
  sortStacksByIndex,
  sourceName,
  stackIconLabel,
  stackMapRegion,
  stackMapTileUrl,
  stackStats,
} from './model.js';

describe('world-stack icon model', () => {  it('出生点所在 region 已生成时直接使用', () => {
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

  it('字节数按 KiB/MiB/GiB 展示', () => {
    expect(fmtBytes(0)).toBe('0 B');
    expect(fmtBytes(1023)).toBe('1023 B');
    expect(fmtBytes(1024)).toBe('1.0 KiB');
    expect(fmtBytes(1024 * 1024)).toBe('1.0 MiB');
    expect(fmtBytes(5.5 * 1024 * 1024 * 1024)).toBe('5.5 GiB');
    expect(fmtBytes(null)).toBe('0 B');
  });

  it('时间格式化对空值回退为破折号', () => {
    expect(fmtTime('')).toBe('—');
    expect(fmtTime(null)).toBe('—');
    expect(typeof fmtTime('2026-11-14T00:00:00Z')).toBe('string');
    expect(fmtTime('2026-11-14T00:00:00Z')).not.toBe('—');
  });

  it('世界来源与排序稳定', () => {
    expect(sourceName('current')).toBe('当前世界');
    expect(sourceName('legacy')).toBe('旧数据');
    expect(sourceName('custom')).toBe('custom');
    expect(sourceName(undefined)).toBe('—');
    const input = [{ id: 'b', index: 2 }, { id: 'a', index: 0 }];
    const sorted = sortStacksByIndex(input);
    expect(sorted.map((item) => item.id)).toEqual(['a', 'b']);
    expect(input.map((item) => item.id)).toEqual(['b', 'a']);
    expect(sortStacksByIndex(null)).toEqual([]);
  });
});

describe('world-stack page model', () => {
  const stacks = [
    { id: 'stack_0', index: 0, top: false, seed: 1, dataBytes: 1024 },
    { id: 'world', index: 1, top: true, seed: 2, dataBytes: 2048 },
  ];

  it('统计 chip 汇总栈层/栈顶/待执行', () => {
    const chips = stackStats({
      totalBytes: 3072, restartRequired: true, importRoots: ['D:/saves'],
      lastReport: { status: 'ok', message: '完成' },
    }, stacks);
    expect(chips).toHaveLength(6);
    expect(chips[1]).toEqual(['栈顶', 'world']);
    expect(chips[3]).toEqual(['待执行', '需要重启']);
    expect(chips[5]).toEqual(['上次任务', 'ok · 完成']);
  });

  it('排序视图反向显示并给出预览', () => {
    const view = orderView(['stack_0', 'world'], ['stack_0', 'world'], (id) => stacks.find((s) => s.id === id));
    expect(view.changed).toBe(false);
    expect(view.canSave).toBe(false);
    expect(view.rows[0].id).toBe('world');
    expect(view.rows[0].label).toContain('world（栈顶）');
    expect(view.rows[1].label).toContain('第 1 层');
    expect(view.rows[1].downDisabled).toBe(true);
    const changed = orderView(['world', 'stack_0'], ['stack_0', 'world'],
      (id) => stacks.find((s) => s.id === id));
    expect(changed.changed).toBe(true);
    expect(changed.topMoved).toBe(true);
    expect(changed.preview).toContain('保存后提升栈顶');
  });

  it('导入队列、预览与任务卡片', () => {
    const model = {
      pendingImports: [
        { jobId: 'j1', source: 'D:/a', sourceBytes: 1024, position: 'top' },
        { jobId: 'j2', source: 'D:/b', sourceBytes: 2048, position: 'between', afterId: 'stack_0', targetId: 'stack_1' },
      ],
      pendingArchive: { jobId: 'j3', id: 'stack_1' },
      jobs: [{ id: 'old', type: 'import', status: 'completed', message: 'done', log: ['a'] }],
    };
    const queue = importQueueView(model);
    expect(queue).toHaveLength(2);
    expect(queue[0].label).toContain('栈顶（替换 world）');
    expect(queue[1].label).toContain('插到 stack_0 之上');
    const jobs = jobsView(model);
    expect(jobs.map((job) => job.status)).toEqual(['queued', 'queued', 'queued', 'completed']);
    expect(jobs[3].log).toEqual(['a']);
    const preview = importPreviewView({
      ok: true, position: 'top', targetId: 'world', targetIndex: 1,
      neededBytes: 1024, freeBytes: 2048, playerConflicts: 2,
      source: {
        canonicalPath: 'D:/a', path: 'D:/a', layout: 'world', worldName: 'a',
        seed: 42, spawnX: 0, spawnY: 64, spawnZ: 0, sizeBytes: 1024,
        fileCount: 3, playerFiles: 1, sessionLocked: false,
      },
      warnings: ['w'], steps: ['s'], errors: [],
    });
    expect(preview.title).toBe('导入预览：可以执行');
    expect(preview.rows[0]).toEqual(['源目录', 'D:/a']);
    expect(preview.rows.some(([key]) => key === '位置')).toBe(true);
    expect(preview.confirmText).toContain('压入旧栈');
    expect(importPreviewView(null)).toBeNull();
  });

  it('文件位置行格式化', () => {
    const rows = filesView({
      files: [{ path: 'D:/x', label: 'x', note: 'n', exists: true, kind: 'dir', depth: 1, bytes: 1024 }],
    });
    expect(rows[0]).toMatchObject({
      path: 'D:/x', label: 'x', note: 'n', exists: true, kind: 'dir', depth: 1, bytes: 1024,
    });
    expect(rows[0].size).toBe(fmtBytes(1024));
  });
});
