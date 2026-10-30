(function () {
  const tokenInput = document.getElementById('token');
  const enabled = document.getElementById('enabled');
  const syncPlayerData = document.getElementById('syncPlayerData');
  const maxOwners = document.getElementById('maxOwners');
  const groupsRoot = document.getElementById('groups');
  const message = document.getElementById('message');
  const pathLine = document.getElementById('path');
  const rootLine = document.getElementById('root');
  const auditOutput = document.getElementById('audit-output');
  const candidates = document.getElementById('candidates');
  const template = document.getElementById('group-template');
  let model = { config: { groups: [] } };

  tokenInput.value = localStorage.getItem('samsara-token') || '';

  function token() {
    const value = tokenInput.value.trim();
    localStorage.setItem('samsara-token', value);
    return value;
  }

  function setMessage(text, bad) {
    message.textContent = text || '';
    message.className = 'message' + (bad ? ' bad' : '');
  }

  async function api(method, path, body) {
    const headers = { 'Authorization': 'Bearer ' + token() };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { error: text }; }
    if (!response.ok) {
      throw new Error(data.error || ('HTTP ' + response.status));
    }
    return data;
  }

  function render(next) {
    model = next;
    const config = model.config || {};
    enabled.checked = !!config.enabled;
    syncPlayerData.checked = config.syncPlayerData !== false;
    maxOwners.value = config.maxOwners || 32;
    pathLine.textContent = '配置文件：' + (model.path || '');
    rootLine.textContent = '运行时数据：' + (model.identityRoot || '');
    candidates.replaceChildren();
    (model.candidates || []).forEach(function (item) {
      const option = document.createElement('option');
      option.value = item.uuid;
      option.label = item.name + (item.online ? ' · 在线' : '');
      candidates.appendChild(option);
    });
    groupsRoot.replaceChildren();
    (config.groups || []).forEach(function (group, index) {
      groupsRoot.appendChild(groupCard(group, index));
    });
    const errors = model.errors || {};
    const keys = Object.keys(errors);
    if (keys.length) {
      setMessage(keys.map(function (key) { return key + '：' + errors[key]; }).join('；'), true);
    }
  }

  function groupCard(group, index) {
    const node = template.content.firstElementChild.cloneNode(true);
    node.dataset.index = String(index);
    const title = node.querySelector('.file-title');
    title.textContent = (group.name || '未命名组') + (group.data ? ' · ' + group.data : '');
    node.querySelectorAll('[data-field]').forEach(function (input) {
      const field = input.dataset.field;
      input.value = group[field] == null ? '' : group[field];
    });
    return node;
  }

  function collect() {
    const groups = [];
    groupsRoot.querySelectorAll('.identity-group').forEach(function (card) {
      const group = {};
      card.querySelectorAll('[data-field]').forEach(function (input) {
        const value = input.value.trim();
        group[input.dataset.field] = value === '' ? (input.dataset.field === 'premium' || input.dataset.field === 'offline' ? null : '') : value;
      });
      groups.push(group);
    });
    return {
      enabled: enabled.checked,
      syncPlayerData: syncPlayerData.checked,
      maxOwners: Number(maxOwners.value || 32),
      groups: groups
    };
  }

  async function connect() {
    try {
      setMessage('正在读取…');
      render(await api('GET', '/api/identity'));
      setMessage('已连接');
    } catch (e) {
      setMessage(e.message, true);
    }
  }

  async function save() {
    try {
      setMessage('正在保存…');
      render(await api('PUT', '/api/identity', collect()));
      setMessage('身份组配置已保存并热重载');
    } catch (e) {
      setMessage(e.message, true);
    }
  }

  async function preview(card) {
    const group = cardData(card);
    try {
      card.querySelector('.group-preview').textContent = '正在预览…';
      const result = await api('POST', '/api/identity/preview', {
        group: group.name,
        data: group.data,
        uuids: [group.premium, group.offline].filter(Boolean)
      });
      card.querySelector('.group-preview').textContent = result.text || JSON.stringify(result, null, 2);
    } catch (e) {
      card.querySelector('.group-preview').textContent = e.message;
    }
  }

  async function rebind(card) {
    const group = cardData(card);
    let newData = window.prompt('输入新的 data UUID（留空则生成随机 UUID）', '');
    if (newData === null) return;
    newData = newData.trim();
    if (!newData) {
      try { newData = (await api('POST', '/api/identity/generate')).data; } catch (e) { return; }
    }
    let mode = window.prompt('新 data 已有数据时如何处理？输入 reject / overwrite / merge', 'reject');
    if (mode === null) return;
    mode = mode.trim() || 'reject';
    if (!window.confirm('确认把「' + (group.name || '') + '」的 data 从 '
        + (group.data || '空') + ' 重绑到 ' + newData + '？\n会先备份旧/新数据，旧数据保留。')) {
      return;
    }
    try {
      card.querySelector('.group-preview').textContent = '正在重绑…';
      const result = await api('POST', '/api/identity/rebind', {
        group: group.name,
        newData: newData,
        mode: mode,
        confirm: true
      });
      render(result);
      setMessage(result.message || 'data UUID 已重绑');
    } catch (e) {
      card.querySelector('.group-preview').textContent = e.message;
      setMessage(e.message, true);
    }
  }

  function cardData(card) {
    const group = {};
    card.querySelectorAll('[data-field]').forEach(function (input) {
      const value = input.value.trim();
      group[input.dataset.field] = value === '' ? null : value;
    });
    return group;
  }

  groupsRoot.addEventListener('click', function (event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const card = button.closest('.identity-group');
    if (!card) return;
    const action = button.dataset.action;
    if (action === 'preview') preview(card);
    if (action === 'rebind') rebind(card);
    if (action === 'remove') {
      if (window.confirm('确认删除这个身份组？（不会删除玩家数据）')) {
        card.remove();
      }
    }
  });

  document.getElementById('connect').addEventListener('click', connect);
  document.getElementById('save').addEventListener('click', save);
  document.getElementById('reload').addEventListener('click', async function () {
    try {
      render(await api('POST', '/api/identity/reload'));
      setMessage('已重新读取 identity.yml');
    } catch (e) { setMessage(e.message, true); }
  });
  document.getElementById('audit').addEventListener('click', async function () {
    try {
      const result = await api('GET', '/api/identity/audit');
      auditOutput.textContent = result.text || '';
      setMessage('审计完成');
    } catch (e) { setMessage(e.message, true); }
  });
  document.getElementById('add').addEventListener('click', function () {
    groupsRoot.appendChild(groupCard({ name: '', data: '', premium: '', offline: '', note: '' }, Date.now()));
  });
  document.getElementById('generate').addEventListener('click', async function () {
    try {
      const data = (await api('POST', '/api/identity/generate')).data;
      if (navigator.clipboard) {
        navigator.clipboard.writeText(data).catch(function () {});
      }
      setMessage('已生成 data UUID：' + data + '（已尝试复制）');
    } catch (e) { setMessage(e.message, true); }
  });

  if (tokenInput.value) {
    connect();
  }
})();
