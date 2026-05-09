import { useState, useEffect, useRef } from 'react';
import {
  LayoutDashboard, Send, Search, ClipboardList,
  Settings, X, ExternalLink, BarChart3, Activity, Zap,
} from 'lucide-react';
import StatsDashboard from './components/StatsDashboard';
import SendEvent from './components/SendEvent';
import EventLookup from './components/EventLookup';
import ActivityLog from './components/ActivityLog';
import type { ActivityEntry, ApiConfig } from './types';
import './App.css';

const STORAGE_KEY = 'ep-config';
const ACTIVITY_KEY = 'ep-activity';
const DEFAULT_CONFIG: ApiConfig = { baseUrl: 'http://localhost:8080', apiKey: 'prod-key-alpha-001' };

type Tab = 'dashboard' | 'send' | 'lookup' | 'log';

interface ConfigPanelProps {
  config: ApiConfig;
  onChange: (c: ApiConfig) => void;
  onClose: () => void;
}

function ConfigPanel({ config, onChange, onClose }: ConfigPanelProps) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [onClose]);

  return (
    <div className="config-panel-overlay">
      <div className="config-panel" ref={ref}>
        <div className="config-panel-header">
          <h3>Connection</h3>
          <button className="icon-btn" onClick={onClose}><X size={15} /></button>
        </div>
        <div className="config-panel-body">
          <div className="config-field">
            <label>API Base URL</label>
            <input
              value={config.baseUrl}
              onChange={e => onChange({ ...config, baseUrl: e.target.value })}
              placeholder="http://localhost:8080"
              spellCheck={false}
            />
          </div>
          <div className="config-field">
            <label>API Key</label>
            <input
              type="password"
              value={config.apiKey}
              onChange={e => onChange({ ...config, apiKey: e.target.value })}
              placeholder="prod-key-alpha-001"
              spellCheck={false}
            />
          </div>
          <div style={{ fontSize: 11, color: 'var(--tx3)', lineHeight: 1.6, marginTop: 4 }}>
            Stored locally. Never transmitted to third parties.
          </div>
          <div style={{ borderTop: '1px solid var(--line)', paddingTop: 16 }}>
            <div style={{ fontSize: 10.5, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--tx3)', marginBottom: 10 }}>
              Observability
            </div>
            {[
              { label: 'Grafana', href: 'http://localhost:3000', icon: BarChart3 },
              { label: 'Jaeger',  href: 'http://localhost:16686', icon: Activity },
              { label: 'Prometheus', href: 'http://localhost:9090', icon: Activity },
            ].map(({ label, href, icon: Icon }) => (
              <a
                key={label}
                href={href}
                target="_blank"
                rel="noreferrer"
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  padding: '8px 10px', borderRadius: 4,
                  color: 'var(--tx3)', textDecoration: 'none',
                  fontSize: 12, fontWeight: 500,
                  transition: 'background 0.12s, color 0.12s',
                }}
                onMouseEnter={e => { (e.currentTarget as HTMLElement).style.background = 'var(--bg2)'; (e.currentTarget as HTMLElement).style.color = 'var(--tx1)'; }}
                onMouseLeave={e => { (e.currentTarget as HTMLElement).style.background = ''; (e.currentTarget as HTMLElement).style.color = ''; }}
              >
                <Icon size={13} />
                {label}
                <ExternalLink size={10} style={{ marginLeft: 'auto', opacity: 0.5 }} />
              </a>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

const TABS: { id: Tab; label: string; icon: typeof LayoutDashboard }[] = [
  { id: 'dashboard', label: 'Overview',     icon: LayoutDashboard },
  { id: 'send',      label: 'Send Event',   icon: Send },
  { id: 'lookup',    label: 'Lookup',        icon: Search },
  { id: 'log',       label: 'Activity',      icon: ClipboardList },
];

const PAGE_META: Record<Tab, { title: string; sub: string }> = {
  dashboard: { title: 'Overview',         sub: 'Live platform metrics · auto-refreshes every 5s' },
  send:      { title: 'Publish Event',    sub: 'Dispatch a new event to the platform' },
  lookup:    { title: 'Event Lookup',     sub: 'Inspect delivery status by event ID' },
  log:       { title: 'Activity Log',     sub: 'Events sent during this session' },
};

export default function App() {
  const [config, setConfig] = useState<ApiConfig>(() => {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '') as ApiConfig; }
    catch { return DEFAULT_CONFIG; }
  });
  const [activity, setActivity] = useState<ActivityEntry[]>(() => {
    try { return JSON.parse(localStorage.getItem(ACTIVITY_KEY) ?? '[]') as ActivityEntry[]; }
    catch { return []; }
  });
  const [tab, setTab]             = useState<Tab>('dashboard');
  const [configOpen, setConfigOpen] = useState(false);

  useEffect(() => { localStorage.setItem(STORAGE_KEY, JSON.stringify(config)); }, [config]);
  useEffect(() => { localStorage.setItem(ACTIVITY_KEY, JSON.stringify(activity.slice(-100))); }, [activity]);

  const addActivity = (e: ActivityEntry) => setActivity(p => [...p, e]);
  const { title, sub } = PAGE_META[tab];

  return (
    <div className="app">
      {/* ── Sidebar ── */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <div className="sidebar-brand-row">
            <div className="brand-mark">
              <Zap size={14} color="#fff" strokeWidth={2.5} />
            </div>
            <span className="brand-name">Porter</span>
          </div>
          <div className="brand-sub">Event Platform</div>
        </div>

        <nav className="sidebar-nav">
          {TABS.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              className={`nav-btn ${tab === id ? 'nav-btn--active' : ''}`}
              onClick={() => setTab(id)}
            >
              <Icon size={15} />
              {label}
              {id === 'log' && activity.length > 0 && (
                <span className="nav-count">{activity.length}</span>
              )}
            </button>
          ))}
        </nav>

        <div className="sidebar-footer">
          <button
            className="status-pill"
            onClick={() => setConfigOpen(true)}
            title="Connection settings"
          >
            <span className="status-dot" />
            {config.baseUrl.replace('http://', '')}
            <Settings size={11} style={{ marginLeft: 'auto', opacity: 0.5 }} />
          </button>
        </div>
      </aside>

      {/* ── Main ── */}
      <div className="main-content">
        <div className="page-header">
          <div className="page-title">{title}</div>
          <div className="page-sub">{sub}</div>
        </div>

        <div className="page-body">
          {tab === 'dashboard' && <StatsDashboard config={config} />}
          {tab === 'send'      && <SendEvent config={config} onSent={addActivity} />}
          {tab === 'lookup'    && <EventLookup config={config} />}
          {tab === 'log'       && <ActivityLog entries={activity} onClear={() => setActivity([])} />}
        </div>
      </div>

      {configOpen && (
        <ConfigPanel config={config} onChange={setConfig} onClose={() => setConfigOpen(false)} />
      )}
    </div>
  );
}
