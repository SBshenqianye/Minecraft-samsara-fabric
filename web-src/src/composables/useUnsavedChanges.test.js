import { beforeEach, describe, expect, it } from 'vitest';

const listeners = [];

beforeEach(() => {
  listeners.length = 0;
  globalThis.window = {
    addEventListener: (type, handler) => listeners.push({ type, handler }),
    removeEventListener: (type, handler) => {
      const index = listeners.findIndex((item) => item.type === type && item.handler === handler);
      if (index >= 0) listeners.splice(index, 1);
    },
  };
});

describe('useUnsavedChanges', () => {
  it('registers dirty state and clears on cleanup', async () => {
    const { useUnsavedChanges, hasUnsavedChanges } = await import('./useUnsavedChanges.js');
    expect(hasUnsavedChanges()).toBe(false);
    const cleanup = useUnsavedChanges(() => true);
    expect(hasUnsavedChanges()).toBe(true);
    cleanup();
    expect(hasUnsavedChanges()).toBe(false);
    expect(listeners).toHaveLength(0);
  });

  it('ignores throwing readers', async () => {
    const { useUnsavedChanges, hasUnsavedChanges } = await import('./useUnsavedChanges.js');
    const cleanup = useUnsavedChanges(() => {
      throw new Error('component gone');
    });
    expect(hasUnsavedChanges()).toBe(false);
    cleanup();
  });

  it('registers beforeunload listener', async () => {
    const { useUnsavedChanges } = await import('./useUnsavedChanges.js');
    const cleanup = useUnsavedChanges(() => false);
    expect(listeners.some((item) => item.type === 'beforeunload')).toBe(true);
    cleanup();
    expect(listeners.some((item) => item.type === 'beforeunload')).toBe(false);
  });
});
