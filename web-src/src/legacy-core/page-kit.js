'use strict';

// 页面生命周期基础设施：注册 / 挂载 / 卸载 / 作用域清理。
// 业务页面可以逐步迁移到 mount/unmount/buildNav，未迁移的页面继续走 app.js 的旧 activate。
(function () {
  const pages = new Map();

  function register(name, definition) {
    if (!name) throw new Error('page name required');
    pages.set(name, Object.assign({ name }, definition || {}));
    return pages.get(name);
  }

  function unregister(name) {
    return pages.delete(name);
  }

  function get(name) {
    return pages.get(name) || null;
  }

  function has(name) {
    return pages.has(name);
  }

  function list() {
    return [...pages.keys()];
  }

  function createScope(name) {
    const timers = new Set();
    const intervals = new Set();
    const removers = [];
    const cleanups = [];
    let disposed = false;

    const scope = {
      name: name || '',
      get disposed() {
        return disposed;
      },
      get pending() {
        return timers.size + intervals.size;
      },
      setTimeout(fn, ms) {
        if (disposed || typeof fn !== 'function') return 0;
        const id = window.setTimeout(() => {
          timers.delete(id);
          if (!disposed) fn();
        }, ms);
        timers.add(id);
        return id;
      },
      clearTimeout(id) {
        window.clearTimeout(id);
        timers.delete(id);
      },
      setInterval(fn, ms) {
        if (disposed || typeof fn !== 'function') return 0;
        const id = window.setInterval(() => {
          if (!disposed) fn();
        }, ms);
        intervals.add(id);
        return id;
      },
      clearInterval(id) {
        window.clearInterval(id);
        intervals.delete(id);
      },
      listen(target, type, handler, options) {
        if (!target || disposed || typeof handler !== 'function') {
          return () => {};
        }
        const wrapped = (event) => {
          if (!disposed) handler(event);
        };
        target.addEventListener(type, wrapped, options);
        let active = true;
        const remove = () => {
          if (!active) return;
          active = false;
          target.removeEventListener(type, wrapped, options);
        };
        removers.push(remove);
        return remove;
      },
      cleanup(fn) {
        if (typeof fn === 'function') cleanups.push(fn);
      },
      dispose() {
        if (disposed) return;
        disposed = true;
        for (const id of timers) window.clearTimeout(id);
        for (const id of intervals) window.clearInterval(id);
        timers.clear();
        intervals.clear();
        for (const remove of removers.splice(0)) {
          try {
            remove();
          } catch (error) {
            console.warn('[page-kit] listener cleanup failed', error);
          }
        }
        for (const fn of cleanups.splice(0)) {
          try {
            fn();
          } catch (error) {
            console.warn('[page-kit] cleanup failed', error);
          }
        }
      },
    };
    return scope;
  }

  /** 基础设施自测：不触碰真实页面。 */
  function selfTest() {
    const name = '__pagekit_self_test__';
    let mounted = 0;
    let unmounted = 0;
    let listenerRemoved = 0;
    const scope = createScope(name);
    const target = {
      addEventListener() {},
      removeEventListener() {
        listenerRemoved++;
      },
    };
    try {
      register(name, {
        mount(context) {
          mounted++;
          if (context.scope !== scope) throw new Error('scope mismatch');
          context.scope.setTimeout(() => {}, 10);
          context.scope.setInterval(() => {}, 10);
          context.scope.listen(target, 'test', () => {});
          context.scope.cleanup(() => {});
        },
        unmount() {
          unmounted++;
        },
      });
      const page = get(name);
      page.mount({ name, scope });
      page.unmount({ name, scope });
      scope.dispose();
      return {
        registered: has(name),
        mounted,
        unmounted,
        disposed: scope.disposed,
        pending: scope.pending,
        listenerRemoved,
      };
    } finally {
      unregister(name);
    }
  }

  window.SamsaraPageKit = {
    register,
    unregister,
    get,
    has,
    list,
    createScope,
    selfTest,
  };
})();
