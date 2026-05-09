import { useState } from 'react';
import { ClipboardList, Trash2, CheckCircle2, XCircle, Clock, RotateCcw, Copy, Filter } from 'lucide-react';
import type { ActivityEntry, EventStatus, EventType } from '../types';

interface Props {
  entries: ActivityEntry[];
  onClear: () => void;
}

const STATUS_CONFIG: Record<EventStatus, { cls: string; icon: typeof CheckCircle2 }> = {
  DELIVERED:    { cls: 'badge--green', icon: CheckCircle2 },
  QUEUED:       { cls: 'badge--blue',  icon: Clock },
  FAILED:       { cls: 'badge--red',   icon: XCircle },
  DEAD_LETTERED:{ cls: 'badge--red',   icon: XCircle },
  DUPLICATE:    { cls: 'badge--gray',  icon: RotateCcw },
};

const TYPE_COLORS: Record<string, string> = {
  ORDER_UPDATE:    'badge--blue',
  DRIVER_ASSIGNED: 'badge--indigo',
  PAYMENT_DONE:    'badge--green',
  TRIP_STARTED:    'badge--amber',
  TRIP_ENDED:      'badge--gray',
};

const ALL_STATUSES: EventStatus[] = ['DELIVERED', 'QUEUED', 'FAILED', 'DEAD_LETTERED', 'DUPLICATE'];
const ALL_TYPES: EventType[] = ['ORDER_UPDATE', 'DRIVER_ASSIGNED', 'PAYMENT_DONE', 'TRIP_STARTED', 'TRIP_ENDED'];

function StatusBadge({ status }: { status: EventStatus }) {
  const cfg = STATUS_CONFIG[status] ?? { cls: 'badge--gray', icon: Clock };
  const Icon = cfg.icon;
  return <span className={`badge ${cfg.cls}`}><Icon size={10} />{status}</span>;
}

export default function ActivityLog({ entries, onClear }: Props) {
  const [statusFilter, setStatusFilter] = useState<EventStatus | 'ALL'>('ALL');
  const [typeFilter,   setTypeFilter]   = useState<EventType | 'ALL'>('ALL');

  const filtered = entries.filter(e =>
    (statusFilter === 'ALL' || e.status === statusFilter) &&
    (typeFilter   === 'ALL' || e.eventType === typeFilter)
  );

  const total     = entries.length;
  const delivered = entries.filter(e => e.status === 'DELIVERED').length;
  const failed    = entries.filter(e => e.status === 'FAILED' || e.status === 'DEAD_LETTERED').length;

  const selectStyle: React.CSSProperties = { padding: '5px 28px 5px 10px', fontSize: 12, width: 'auto' };

  return (
    <div className="stack">
      {total > 0 && (
        <div style={{ display: 'flex', gap: 12 }}>
          {[
            { label: 'Total Sent', val: total,     cls: '' },
            { label: 'Delivered',  val: delivered, cls: 'stat-card--green' },
            { label: 'Failed',     val: failed,    cls: failed > 0 ? 'stat-card--red' : '' },
          ].map(({ label, val, cls }) => (
            <div key={label} className={`stat-card ${cls}`} style={{ flex: 1 }}>
              <div className="stat-value" style={{ fontSize: 20 }}>{val}</div>
              <div className="stat-label">{label}</div>
            </div>
          ))}
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <div className="card-title">
            <div className="card-icon"><ClipboardList size={14} /></div>
            <h2>Activity Log</h2>
            {total > 0 && (
              <span className="badge badge--indigo">
                {filtered.length !== total ? `${filtered.length} / ${total}` : total}
              </span>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {total > 0 && (
              <>
                <Filter size={12} style={{ color: 'var(--tx3)' }} />
                <select style={selectStyle} value={statusFilter} onChange={e => setStatusFilter(e.target.value as EventStatus | 'ALL')}>
                  <option value="ALL">All statuses</option>
                  {ALL_STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
                </select>
                <select style={selectStyle} value={typeFilter} onChange={e => setTypeFilter(e.target.value as EventType | 'ALL')}>
                  <option value="ALL">All types</option>
                  {ALL_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
                <button className="btn btn-ghost btn-sm" onClick={onClear}>
                  <Trash2 size={12} /> Clear
                </button>
              </>
            )}
          </div>
        </div>

        {total === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon"><Copy size={22} /></div>
            <div>
              <strong>No activity yet</strong>
              <p>Events you send will appear here. Switch to "Send Event" to publish your first event.</p>
            </div>
          </div>
        ) : filtered.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon"><Filter size={22} /></div>
            <div>
              <strong>No matching events</strong>
              <p>No events match the current filters. Try a different status or type.</p>
            </div>
          </div>
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Time</th><th>Event ID</th><th>Type</th>
                  <th>Partition Key</th><th>Webhook</th><th>Status</th>
                </tr>
              </thead>
              <tbody>
                {[...filtered].reverse().map((e, i) => (
                  <tr key={`${e.eventId}-${i}`}>
                    <td><span className="timestamp">{new Date(e.sentAt).toLocaleTimeString()}</span></td>
                    <td>
                      <span className="mono text-sm" style={{ color: 'var(--text-2)' }} title={e.eventId}>
                        {e.eventId.slice(0, 8)}…
                      </span>
                    </td>
                    <td><span className={`badge ${TYPE_COLORS[e.eventType] ?? 'badge--gray'}`}>{e.eventType}</span></td>
                    <td><span className="mono text-sm">{e.partitionKey}</span></td>
                    <td><span className="truncate text-sm" title={e.webhookUrl}>{e.webhookUrl}</span></td>
                    <td><StatusBadge status={e.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
