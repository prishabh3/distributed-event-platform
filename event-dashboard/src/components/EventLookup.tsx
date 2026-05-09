import { useState } from 'react';
import { Search, AlertCircle, CheckCircle2, XCircle, Clock, RotateCcw } from 'lucide-react';
import { lookupEvent } from '../api';
import type { ApiConfig, EventDetail, EventStatus } from '../types';

interface Props { config: ApiConfig; }

const STATUS_CONFIG: Record<EventStatus, { label: string; cls: string; icon: typeof CheckCircle2 }> = {
  DELIVERED:    { label: 'Delivered',    cls: 'badge--green',  icon: CheckCircle2 },
  QUEUED:       { label: 'Queued',       cls: 'badge--blue',   icon: Clock },
  FAILED:       { label: 'Failed',       cls: 'badge--red',    icon: XCircle },
  DEAD_LETTERED:{ label: 'Dead Letter',  cls: 'badge--red',    icon: XCircle },
  DUPLICATE:    { label: 'Duplicate',    cls: 'badge--gray',   icon: RotateCcw },
};

function StatusBadge({ status }: { status: EventStatus }) {
  const cfg = STATUS_CONFIG[status] ?? { label: status, cls: 'badge--gray', icon: Clock };
  const Icon = cfg.icon;
  return (
    <span className={`badge ${cfg.cls}`}>
      <Icon size={10} />
      {cfg.label}
    </span>
  );
}

export default function EventLookup({ config }: Props) {
  const [id,      setId]      = useState('');
  const [event,   setEvent]   = useState<EventDetail | null>(null);
  const [error,   setError]   = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleLookup = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setEvent(null);
    setLoading(true);
    try {
      setEvent(await lookupEvent(config, id.trim()));
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      setError(status === 404 ? 'No event found with that ID.' : 'Lookup failed — check the ID and connection settings.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="stack">
      <div className="card">
        <div className="card-header">
          <div className="card-title">
            <div className="card-icon"><Search size={14} /></div>
            <h2>Event Lookup</h2>
          </div>
        </div>
        <div className="card-body">
          <form onSubmit={handleLookup} style={{ maxWidth: 580 }}>
            <div className="form-row">
              <label>Event ID (UUID v4)</label>
              <div className="input-group">
                <input
                  value={id}
                  onChange={e => setId(e.target.value)}
                  placeholder="a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                  style={{ fontFamily: 'var(--mono)', fontSize: 12 }}
                  required
                  spellCheck={false}
                />
                <button type="submit" className="btn btn-primary" disabled={loading}>
                  {loading
                    ? <><div className="spinner" /> Looking up…</>
                    : <><Search size={13} /> Lookup</>}
                </button>
              </div>
            </div>
          </form>

          {error && (
            <div className="alert alert--error" style={{ marginTop: 16, maxWidth: 580 }}>
              <div className="alert-icon"><AlertCircle size={16} /></div>
              <div className="alert-body"><div className="alert-title">{error}</div></div>
            </div>
          )}
        </div>
      </div>

      {event && (
        <div className="card">
          <div className="card-header">
            <div className="card-title">
              <div className="card-icon"><Search size={14} /></div>
              <h2>Event Details</h2>
            </div>
            <StatusBadge status={event.status} />
          </div>

          <div className="event-detail-header" style={{ padding: '12px 20px' }}>
            <span className="tag">{event.id}</span>
            <span className="timestamp">{new Date(event.createdAt).toLocaleString()}</span>
          </div>

          {[
            { key: 'Event Type',    val: <span className="badge badge--indigo">{event.eventType}</span> },
            { key: 'Partition Key', val: <span className="mono text-sm">{event.partitionKey}</span> },
            { key: 'Webhook URL',   val: <span className="text-sm">{event.webhookUrl}</span> },
            { key: 'Retry Count',   val: event.retryCount },
            { key: 'Created At',    val: <span className="timestamp">{new Date(event.createdAt).toLocaleString()}</span> },
            ...(event.deliveredAt
              ? [{ key: 'Delivered At', val: <span className="timestamp">{new Date(event.deliveredAt).toLocaleString()}</span> }]
              : []),
            ...(event.errorMessage
              ? [{ key: 'Error', val: <span style={{ color: 'var(--red)', fontFamily: 'var(--mono)', fontSize: 12 }}>{event.errorMessage}</span> }]
              : []),
          ].map(({ key, val }) => (
            <div className="detail-row" key={key}>
              <span className="detail-key">{key}</span>
              <span className="detail-value">{val}</span>
            </div>
          ))}

          <div className="detail-row">
            <span className="detail-key">Payload</span>
            <pre className="payload-block">{JSON.stringify(event.payload, null, 2)}</pre>
          </div>
        </div>
      )}
    </div>
  );
}
