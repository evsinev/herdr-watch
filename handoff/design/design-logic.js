
class Component extends DCLogic {
  constructor(props) {
    super(props);
    this.state = {
      view: 'monitor',
      editing: null,          // null | 'new' | index
      confirmRemove: null,    // null | index
      errors: {},
      form: null,             // {id,host,herdrPath,poll,reconnect,enabled}
      hosts: [
        { id: 'm3-local', host: 'm3-local', herdrPath: '/opt/homebrew/bin/herdr', poll: 2, reconnect: 5, enabled: true, health: 'connected', updated: 2 },
        { id: 'dqa1', host: 'dqa1', herdrPath: 'herdr', poll: 2, reconnect: 5, enabled: true, health: 'connected', updated: 4 },
        { id: 'dqa2', host: 'dqa2', herdrPath: 'herdr', poll: 2, reconnect: 5, enabled: true, health: 'degraded', updated: 47 },
        { id: 'dqa3', host: 'dqa3', herdrPath: 'herdr', poll: 2, reconnect: 5, enabled: false, health: 'unreachable', updated: 312 },
      ],
    };
    this.monitorData = {
      'm3-local': [
        { project: 'dc-agent', wid: 'wF', agents: [
          { title: 'enable-ossindex-sonatype-auth', kind: 'claude', pane: 'wF:p1', status: 'idle' },
          { title: 'Claude Code', kind: 'claude', pane: 'wF:p2', status: 'idle' },
        ], worktrees: [
          { branch: 'main', open: 'wF' },
        ]},
        { project: 'uman', wid: 'wG', agents: [
          { title: 'refactor-session-store', kind: 'codex', pane: 'wG:p1', status: 'idle' },
        ], worktrees: [
          { branch: 'feature/gitlab-import', open: 'wG' },
          { branch: 'main', flag: 'prunable' },
        ]},
        { project: 'deploy', wid: 'wH', focused: true, agents: [
          { title: 'ci-tests-approach-a-b', kind: 'claude', pane: 'wH:p1', status: 'working', focused: true },
        ], worktrees: [
          { branch: 'diff-service-redmine-jdk21', open: 'wH' },
        ]},
      ],
      'dqa1': [
        { project: 'paynet-ui-mcp', wid: 'wA', agents: [
          { title: 'fix-approval-rate-calc', kind: 'claude', pane: 'wA:p1', status: 'blocked' },
        ], worktrees: [
          { branch: 'fix/approval-rate', open: 'wA' },
        ]},
      ],
      'dqa2': [
        { project: 'ydb-user-admin', wid: 'wH', agents: [
          { title: 'tenant-tls-setup', kind: 'pi', pane: 'wH:p1', status: 'idle' },
        ]},
      ],
      'dqa3': [],
    };
  }

  STATUS = {
    blocked: { color: '#E24B4A', p: 5 },
    working: { color: '#EF9F27', p: 4 },
    done:    { color: '#378ADD', p: 3 },
    idle:    { color: '#639922', p: 2 },
    unknown: { color: '#888780', p: 1 },
  };
  HEALTH = {
    connected:   { color: '#639922', label: 'connected' },
    degraded:    { color: '#EF9F27', label: 'degraded' },
    unreachable: { color: '#888780', label: 'unreachable' },
  };
  hex(h, a) {
    const n = parseInt(h.slice(1), 16);
    return `rgba(${(n>>16)&255},${(n>>8)&255},${n&255},${a})`;
  }

  openAdd = () => this.setState({ editing: 'new', errors: {}, form: { id: '', host: '', herdrPath: 'herdr', poll: '2', reconnect: '5', enabled: true } });
  openEdit = (i) => { const h = this.state.hosts[i]; this.setState({ editing: i, errors: {}, confirmRemove: null, form: { id: h.id, host: h.host, herdrPath: h.herdrPath, poll: String(h.poll), reconnect: String(h.reconnect), enabled: h.enabled } }); };
  closeForm = () => this.setState({ editing: null, errors: {}, form: null });
  setField = (k, v) => this.setState(s => ({ form: { ...s.form, [k]: v } }));

  saveForm = () => {
    const f = this.state.form;
    const e = {};
    if (!f.id.trim()) e.id = 'Enter a name for this host';
    if (!f.host.trim()) e.host = 'Enter an ssh target';
    if (!/^\d+$/.test(String(f.poll).trim()) || +f.poll < 1) e.poll = 'Use a positive whole number';
    if (!/^\d+$/.test(String(f.reconnect).trim()) || +f.reconnect < 1) e.reconnect = 'Use a positive whole number';
    if (Object.keys(e).length) { this.setState({ errors: e }); return; }
    this.setState(s => {
      const editing = s.editing;
      const prev = typeof editing === 'number' ? s.hosts[editing] : null;
      let health;
      if (f.enabled) health = (prev && prev.health && prev.health !== 'unreachable') ? prev.health : 'connected';
      else health = 'unreachable';
      const rec = {
        id: f.id.trim(), host: f.host.trim(), herdrPath: f.herdrPath.trim() || 'herdr',
        poll: +f.poll, reconnect: +f.reconnect, enabled: f.enabled, health, updated: 0,
      };
      const hosts = s.hosts.slice();
      if (typeof editing === 'number') hosts[editing] = rec; else hosts.push(rec);
      if (!this.monitorData[rec.id]) this.monitorData[rec.id] = [];
      return { hosts, editing: null, errors: {}, form: null };
    });
  };

  toggleEnabled = (i) => this.setState(s => {
    const hosts = s.hosts.slice();
    const h = { ...hosts[i] };
    h.enabled = !h.enabled;
    h.health = h.enabled ? (h.health !== 'unreachable' ? h.health : 'connected') : 'unreachable';
    h.updated = 0;
    hosts[i] = h;
    return { hosts };
  });

  askRemove = (i) => this.setState({ confirmRemove: i });
  cancelRemove = () => this.setState({ confirmRemove: null });
  confirmRemoveHost = () => this.setState(s => ({ hosts: s.hosts.filter((_, i) => i !== s.confirmRemove), confirmRemove: null }));

  renderVals() {
    const S = this.STATUS, H = this.HEALTH, hex = this.hex.bind(this);
    const st = this.state;
    const err = st.errors;
    const view = st.view;

    // ---- Monitor derived data ----
    let agBlocked = 0, agWorking = 0, down = 0;
    const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
    const hosts = st.hosts.map(h => {
      const unreachable = h.health === 'unreachable';
      if (unreachable) down++;
      const wsRaw = this.monitorData[h.id] || [];
      let hostMax = 0, agentCount = 0, wsIdx = 0;
      const workspaces = wsRaw.map(ws => {
        wsIdx++;
        let wsMax = 0;
        const agents = ws.agents.map(a => {
          agentCount++;
          const s = S[a.status] || S.unknown;
          if (a.status === 'blocked') agBlocked++;
          if (a.status === 'working') agWorking++;
          wsMax = Math.max(wsMax, s.p);
          return {
            title: a.title, kind: a.kind, pane: a.pane, status: a.status,
            statusColor: s.color, statusBg: hex(s.color, 0.1), statusBorder: hex(s.color, 0.28),
            dotHalo: hex(s.color, 0.12), rowBg: a.focused ? hex(s.color, 0.07) : 'transparent',
          };
        });
        hostMax = Math.max(hostMax, wsMax);
        const wsColor = (Object.values(S).find(v => v.p === wsMax) || S.unknown).color;
        const worktrees = (ws.worktrees || []).map(w => {
          const flag = w.flag === 'detached' ? { label: 'detached', color: S.blocked.color }
            : w.flag === 'prunable' ? { label: 'prunable', color: S.working.color } : null;
          return {
            branch: w.branch,
            hasOpen: !!w.open, openLabel: w.open ? `open · ${w.open}` : '',
            hasFlag: !!flag, flagLabel: flag ? flag.label : '', flagColor: flag ? flag.color : '',
            flagBg: flag ? hex(flag.color, 0.1) : 'transparent', flagBorder: flag ? hex(flag.color, 0.3) : 'transparent',
          };
        });
        return { ordinal: wsIdx, project: ws.project, wid: ws.wid, focused: !!ws.focused, dotColor: wsColor, headerBg: ws.focused ? 'rgba(255,255,255,0.025)' : 'transparent', agents, worktrees, hasWorktrees: worktrees.length > 0 };
      });
      const hm = H[h.health];
      return {
        id: h.id, host: h.host, healthColor: hm.color, healthLabel: hm.label,
        healthBg: hex(hm.color, 0.1), healthBorder: hex(hm.color, 0.28),
        meta: `${plural(agentCount, 'agent')} · ${plural(wsRaw.length, 'workspace')} · updated ${h.updated}s ago`,
        opacity: unreachable ? 0.6 : 1, empty: workspaces.length === 0, workspaces,
        _sortKey: unreachable ? -1 : hostMax,
      };
    });
    hosts.sort((a, b) => b._sortKey - a._sortKey);
    const chips = [
      { label: 'down', value: down, color: H.unreachable.color },
      { label: 'blocked', value: agBlocked, color: S.blocked.color },
      { label: 'working', value: agWorking, color: S.working.color },
    ].filter(c => c.value > 0);

    // ---- Settings rows ----
    const settingsRows = st.hosts.map((h, i) => {
      const hm = H[h.health];
      const confirming = st.confirmRemove === i;
      return {
        id: h.id, host: h.host, herdrPath: h.herdrPath, poll: h.poll, reconnect: h.reconnect,
        rowOpacity: h.enabled ? 1 : 0.55,
        toggleBg: h.enabled ? '#378ADD' : 'rgba(255,255,255,0.14)',
        knobLeft: h.enabled ? '17px' : '2px',
        healthColor: hm.color, healthLabel: hm.label,
        confirming, notConfirming: !confirming,
        onToggle: () => this.toggleEnabled(i),
        onEdit: () => this.openEdit(i),
        onAskRemove: () => this.askRemove(i),
        onConfirmRemove: this.confirmRemoveHost,
        onCancelRemove: this.cancelRemove,
      };
    });

    // ---- Form view ----
    const bd = (bad) => bad ? '#E24B4A' : 'rgba(255,255,255,0.12)';
    const f = st.form;
    const form = f ? {
      title: st.editing === 'new' ? 'Add host' : `Edit ${f.id || 'host'}`,
      saveLabel: st.editing === 'new' ? 'Add host' : 'Save changes',
      id: f.id, host: f.host, herdrPath: f.herdrPath, poll: f.poll, reconnect: f.reconnect,
      idError: err.id || '', hostError: err.host || '', pollError: err.poll || '', reconnectError: err.reconnect || '',
      idBorder: bd(err.id), hostBorder: bd(err.host), pollBorder: bd(err.poll), reconnectBorder: bd(err.reconnect),
      toggleBg: f.enabled ? '#378ADD' : 'rgba(255,255,255,0.14)',
      knobLeft: f.enabled ? '17px' : '2px',
      onId: e => this.setField('id', e.target.value),
      onHost: e => this.setField('host', e.target.value),
      onHerdr: e => this.setField('herdrPath', e.target.value),
      onPoll: e => this.setField('poll', e.target.value),
      onReconnect: e => this.setField('reconnect', e.target.value),
      onToggle: () => this.setField('enabled', !f.enabled),
      onSave: this.saveForm,
    } : {};

    const active = '#e6e8ec', dim = '#7a808a', accent = '#378ADD';
    return {
      isMonitor: view === 'monitor', isSettings: view === 'settings',
      goMonitor: () => this.setState({ view: 'monitor' }),
      goSettings: () => this.setState({ view: 'settings' }),
      nav: {
        monColor: view === 'monitor' ? active : dim, monBorder: view === 'monitor' ? accent : 'transparent',
        setColor: view === 'settings' ? active : dim, setBorder: view === 'settings' ? accent : 'transparent',
      },
      hosts, summary: { totalHosts: st.hosts.length, chips },
      settingsRows, isEmpty: st.hosts.length === 0, hasHosts: st.hosts.length > 0,
      openAdd: this.openAdd, closeForm: this.closeForm,
      formOpen: !!f, form,
    };
  }
}
