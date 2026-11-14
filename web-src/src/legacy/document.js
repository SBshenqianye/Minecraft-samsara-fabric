export function waitForElement(id, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const tick = () => {
      const found = document.getElementById(id);
      if (found) {
        resolve(found);
        return;
      }
      if (Date.now() - started >= timeoutMs) {
        reject(new Error('等待页面元素超时：' + id));
        return;
      }
      window.setTimeout(tick, 40);
    };
    tick();
  });
}
