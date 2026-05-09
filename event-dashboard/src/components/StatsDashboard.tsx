import { useEffect, useState, useCallback, useRef } from 'react';
import {
  Inbox, CheckCircle2, XCircle, Copy, Clock, Gauge,
  RefreshCw, TrendingUp,
} from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import { fetchStats, subscribeStats } from '../api';
import type { ApiConfig, StatsResponse, StatsSnapshot } from '../types';

interface Props { config: ApiConfig; }

interface CardDef {
  key: keyof StatsResponse;
  label: string;
  icon: typeof Inbox;
  variant: string;
  format?: (v: number) => string;
}

const CARDS: CardDef[] = [
  { key: 'totalEventsReceived',    label: 'Total Received',      icon: Inbox,        variant: '' },
  { key: 'totalEventsDelivered',   label: 'Delivered',           icon: CheckCircle2, variant: 'stat-card--green' },
  { key: 'totalEventsFailed',      label: 'Failed',              icon: XCircle,      variant: 'stat-card--red' },
  { key: 'totalDuplicatesRejected',label: 'Duplicates Rejected', icon: Copy,         variant: 'stat-card--amber' },
  { key: 'pendingOutboxCount',     label: 'Pending in Outbox',   icon: Clock,        variant: 'stat-card--blue' },
  { key: 'eventsPerSecond',        label: 'Throughput',          icon: Gauge,        variant: 'stat-card--indigo',
    format: v => `${v.toFixed(2)}/s` },
];

const MAX_HISTORY = 30;

function DeliveryRing({ delivered, received }: { delivered: number; received: number }) {
  const pct = received === 0 ? 0 : Math.min(100, (delivered / received) * 100);
  const r = 28, circ = 2 * Math.PI * r;
  const dash = (pct / 100) * circ;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '20px 24px', borderBottom: '1px solid var(--border)' }}>
      <svg width={72} height={72} viewBox="0 0 72 72">
        <circle cx={36} cy={36} r={r} fill="none" stroke="var(--surface-3)" strokeWidth={6} />
        <circle
          cx={36} cy={36} r={r} fill="none"
          stroke={pct >= 90 ? 'var(--green)' : pct >= 50 ? 'var(--amber)' : 'var(--red)'}
          strokeWidth={6}
          strokeDasharray={`${dash} ${circ - dash}`}
          strokeDashoffset={circ * 0.25}
          strokeLinecap="round"
          style={{ transition: 'stroke-dasharray 0.6s ease' }}
        />
        <text x={36} y={40} textAnchor="middle" fill="var(--text-head)" fontSize={14} fontWeight={700} fontFamily="Inter">
          {pct.toFixed(0)}%
        </text>
      </svg>
      <div>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-head)' }}>Delivery Rate</div>
        <div style={{ fontSize: 12, color: 'var(--text-3)', marginTop: 3 }}>
          {delivered.toLocaleString()} of {received.toLocaleString()} events delivered
        </div>
        <div style={{ marginTop: 8 }}>
          {pct >= 90
            ? <span className="badge badge--green">Healthy</span>
            : pct >= 50
            ? <span className="badge badge--amber">Degraded</span>
            : <span className="badge badge--red">Critical</span>}
        </div>
      </div>
    </div>
  );
}

const chartTooltipStyle = {
  backgroundColor: 'var(--bg2)',
  border: '1px solid var(--line)',
  borderRadius: 6,
  fontSize: 12,
  color: 'var(--tx2)',
};

export default function StatsDashboard({ config }: Props) {
  const [stats, setStats]           = useState<StatsResponse | null>(null);
  const [history, setHistory]       = useState<StatsSnapshot[]>([]);
  const [error, setError]           = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [spinning, setSpinning]     = useState(false);
  const [liveMode, setLiveMode]     = useState(true);
  const unsubRef                    = useRef<(() => void) | null>(null);

  const addSnapshot = useCallback((s: StatsResponse) => {
    const snap: StatsSnapshot = {
      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
      received: s.totalEventsReceived,
      delivered: s.totalEventsDelivered,
      failed: s.totalEventsFailed,
    };
    setHistory(prev => [...prev.slice(-(MAX_HISTORY - 1)), snap]);
  }, []);

  const handleStats = useCallback((data: StatsResponse) => {
    setStats(data);
    setLastUpdated(new Date());
    setError(null);
    addSnapshot(data);
  }, [addSnapshot]);

  // Try SSE first; fall back to polling on error
  useEffect(() => {
    let pollId: ReturnType<typeof setInterval> | null = null;

    const startPolling = () => {
      setLiveMode(false);
      const poll = async () => {
        try { handleStats(await fetchStats(config)); }
        catch { setError('Cannot reach the producer-service. Check the base URL and API key in Settings.'); }
      };
      poll();
      pollId = setInterval(poll, 5000);
    };

    const unsub = subscribeStats(config, handleStats, startPolling);
    unsubRef.current = unsub;

    return () => {
      unsub();
      if (pollId) clearInterval(pollId);
    };
  }, [config, handleStats]);

  const manualRefresh = useCallback(async () => {
    setSpinning(true);
    try { handleStats(await fetchStats(config)); }
    catch { setError('Cannot reach the producer-service.'); }
    finally { setTimeout(() => setSpinning(false), 400); }
  }, [config, handleStats]);

  return (
    <div className="stack">
      {/* Metrics card */}
      <div className="card">
        <div className="card-header">
          <div className="card-title">
            <div className="card-icon"><TrendingUp size={14} /></div>
            <h2>Platform Metrics</h2>
          </div>
          <div className="card-actions">
            {lastUpdated && (
              <span className="live-dot" style={{ color: liveMode ? 'var(--grn)' : 'var(--tx3)' }}>
                {liveMode ? 'Live' : 'Polling'}
              </span>
            )}
            <button className="btn btn-ghost btn-sm btn-icon" onClick={manualRefresh} title="Refresh now">
              <RefreshCw size={13} style={spinning ? { animation: 'spin 0.6s linear' } : {}} />
            </button>
          </div>
        </div>

        {error && (
          <div style={{ padding: '12px 20px' }}>
            <div className="alert alert--error">
              <div className="alert-body">
                <div className="alert-title">Connection error</div>
                <div className="alert-desc">{error}</div>
              </div>
            </div>
          </div>
        )}

        {stats && (
          <>
            <DeliveryRing delivered={stats.totalEventsDelivered} received={stats.totalEventsReceived} />
            <div className="stats-grid">
              {CARDS.map(({ key, label, icon: Icon, variant, format }) => (
                <div key={key} className={`stat-card ${variant}`}>
                  <div className="stat-card-icon"><Icon size={16} /></div>
                  <div className="stat-value">
                    {format ? format(stats[key] as number) : (stats[key] as number).toLocaleString()}
                  </div>
                  <div className="stat-label">{label}</div>
                </div>
              ))}
            </div>
          </>
        )}

        {!stats && !error && (
          <div style={{ padding: 24 }}>
            <div className="loading-row">
              <div className="loading-spinner" />
              Connecting to producer-service…
            </div>
          </div>
        )}
      </div>

      {/* Throughput chart */}
      {history.length > 1 && (
        <div className="card">
          <div className="card-header">
            <div className="card-title">
              <div className="card-icon"><TrendingUp size={14} /></div>
              <h2>Throughput History</h2>
            </div>
            <span className="timestamp">Last {history.length} snapshots</span>
          </div>
          <div style={{ padding: '16px 8px 8px' }}>
            <ResponsiveContainer width="100%" height={180}>
              <AreaChart data={history} margin={{ top: 4, right: 16, left: -10, bottom: 0 }}>
                <defs>
                  <linearGradient id="gradReceived" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="var(--blu)" stopOpacity={0.2} />
                    <stop offset="95%" stopColor="var(--blu)" stopOpacity={0} />
                  </linearGradient>
                  <linearGradient id="gradDelivered" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="var(--grn)" stopOpacity={0.2} />
                    <stop offset="95%" stopColor="var(--grn)" stopOpacity={0} />
                  </linearGradient>
                  <linearGradient id="gradFailed" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="var(--red)" stopOpacity={0.2} />
                    <stop offset="95%" stopColor="var(--red)" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                <XAxis dataKey="time" tick={{ fontSize: 10, fill: 'var(--tx3)' }} tickLine={false} axisLine={false} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 10, fill: 'var(--tx3)' }} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip contentStyle={chartTooltipStyle} />
                <Legend wrapperStyle={{ fontSize: 11, color: 'var(--tx3)', paddingTop: 8 }} />
                <Area type="monotone" dataKey="received"  name="Received"  stroke="var(--blu)" fill="url(#gradReceived)"  strokeWidth={1.5} dot={false} />
                <Area type="monotone" dataKey="delivered" name="Delivered" stroke="var(--grn)" fill="url(#gradDelivered)" strokeWidth={1.5} dot={false} />
                <Area type="monotone" dataKey="failed"    name="Failed"    stroke="var(--red)" fill="url(#gradFailed)"    strokeWidth={1.5} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {lastUpdated && (
        <div style={{ textAlign: 'right', fontSize: 11, color: 'var(--text-3)' }}>
          Last updated {lastUpdated.toLocaleTimeString()}
          {liveMode ? ' · live via SSE' : ' · polling every 5 s'}
        </div>
      )}
    </div>
  );
}
