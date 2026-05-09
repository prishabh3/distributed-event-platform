import { useState } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { RefreshCw, Send, CheckCircle2, AlertCircle, Copy } from 'lucide-react';
import { sendEvent } from '../api';
import type { ActivityEntry, ApiConfig, EventResponse, EventType, SendEventRequest } from '../types';

const EVENT_TYPES: EventType[] = [
  'ORDER_UPDATE', 'DRIVER_ASSIGNED', 'PAYMENT_DONE', 'TRIP_STARTED', 'TRIP_ENDED',
];

const PAYLOAD_TEMPLATES: Record<EventType, object> = {
  ORDER_UPDATE:    { orderId: 'order-001', status: 'confirmed', amount: 250.00, currency: 'INR' },
  DRIVER_ASSIGNED: { orderId: 'order-001', driverId: 'driver-42', eta: 8 },
  PAYMENT_DONE:    { orderId: 'order-001', transactionId: 'txn-abc123', amount: 250.00 },
  TRIP_STARTED:    { orderId: 'order-001', driverId: 'driver-42', startTime: new Date().toISOString() },
  TRIP_ENDED:      { orderId: 'order-001', driverId: 'driver-42', duration: 1200, fareAmount: 320.00 },
};

interface Props {
  config: ApiConfig;
  onSent: (entry: ActivityEntry) => void;
}

export default function SendEvent({ config, onSent }: Props) {
  const [form, setForm] = useState({
    eventId:      uuidv4(),
    eventType:    'ORDER_UPDATE' as EventType,
    partitionKey: '',
    webhookUrl:   '',
    payloadRaw:   JSON.stringify(PAYLOAD_TEMPLATES['ORDER_UPDATE'], null, 2),
  });
  const [result,  setResult]  = useState<EventResponse | null>(null);
  const [error,   setError]   = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [copied,  setCopied]  = useState(false);

  const field = (key: keyof typeof form, value: string) =>
    setForm(f => ({ ...f, [key]: value }));

  const handleTypeChange = (t: EventType) => {
    setForm(f => ({
      ...f,
      eventType: t,
      payloadRaw: JSON.stringify(PAYLOAD_TEMPLATES[t], null, 2),
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setResult(null);

    let payload: Record<string, unknown>;
    try { payload = JSON.parse(form.payloadRaw); }
    catch { setError('Payload is not valid JSON — check the editor below.'); return; }

    const req: SendEventRequest = {
      eventId: form.eventId,
      eventType: form.eventType,
      partitionKey: form.partitionKey,
      webhookUrl: form.webhookUrl,
      payload,
      timestamp: new Date().toISOString(),
    };

    setLoading(true);
    try {
      const res = await sendEvent(config, req);
      setResult(res);
      onSent({
        eventId: form.eventId,
        eventType: form.eventType,
        partitionKey: form.partitionKey,
        webhookUrl: form.webhookUrl,
        status: res.status,
        sentAt: new Date().toISOString(),
      });
      setForm(f => ({ ...f, eventId: uuidv4() }));
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? (err as { message?: string })?.message ?? 'Request failed';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  const copyId = () => {
    navigator.clipboard.writeText(form.eventId);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="card">
      <div className="card-header">
        <div className="card-title">
          <div className="card-icon"><Send size={14} /></div>
          <h2>Publish Event</h2>
        </div>
      </div>

      <div className="card-body">
        <form className="form" onSubmit={handleSubmit}>

          {/* Event ID */}
          <div className="form-row">
            <label>Event ID <span className="label-required">required</span></label>
            <div className="input-group">
              <input
                value={form.eventId}
                onChange={e => field('eventId', e.target.value)}
                style={{ fontFamily: 'var(--mono)', fontSize: 12 }}
                required
              />
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => field('eventId', uuidv4())}>
                <RefreshCw size={12} /> New
              </button>
              <button type="button" className="btn btn-secondary btn-sm btn-icon" onClick={copyId} title="Copy">
                {copied ? <CheckCircle2 size={13} color="var(--green)" /> : <Copy size={13} />}
              </button>
            </div>
            <span className="input-hint">Must be a valid UUID v4 — used for idempotency.</span>
          </div>

          {/* Two-column row: type + partition key */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div className="form-row">
              <label>Event Type <span className="label-required">required</span></label>
              <select value={form.eventType} onChange={e => handleTypeChange(e.target.value as EventType)}>
                {EVENT_TYPES.map(t => <option key={t}>{t}</option>)}
              </select>
            </div>
            <div className="form-row">
              <label>Partition Key <span className="label-required">required</span></label>
              <input
                value={form.partitionKey}
                onChange={e => field('partitionKey', e.target.value)}
                placeholder="e.g. order-123"
                required
              />
            </div>
          </div>

          {/* Webhook URL */}
          <div className="form-row">
            <label>Webhook URL <span className="label-required">required</span></label>
            <input
              value={form.webhookUrl}
              onChange={e => field('webhookUrl', e.target.value)}
              placeholder="https://example.com/webhook"
              type="url"
              required
            />
            <span className="input-hint">The platform will POST the event here with an HMAC-SHA256 signature header.</span>
          </div>

          {/* Payload */}
          <div className="form-row">
            <label>Payload <span className="label-required">required</span></label>
            <textarea
              value={form.payloadRaw}
              onChange={e => field('payloadRaw', e.target.value)}
              rows={8}
              spellCheck={false}
            />
            <span className="input-hint">Valid JSON object. Pre-filled with a template for the selected event type.</span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <><div className="spinner" /> Sending…</> : <><Send size={13} /> Send Event</>}
            </button>
            {result && !loading && (
              <span style={{ fontSize: 12, color: 'var(--text-3)' }}>
                Last response: <strong style={{ color: result.status === 'DUPLICATE' ? 'var(--amber)' : 'var(--green)' }}>{result.status}</strong>
              </span>
            )}
          </div>
        </form>

        {result && (
          <div style={{ marginTop: 20 }}>
            <div className={`alert ${result.status === 'DUPLICATE' ? 'alert--warning' : 'alert--success'}`}>
              <div className="alert-icon">
                {result.status === 'DUPLICATE'
                  ? <AlertCircle size={16} />
                  : <CheckCircle2 size={16} />}
              </div>
              <div className="alert-body">
                <div className="alert-title">{result.status} — {result.message}</div>
                <div className="alert-desc mono">{result.eventId}</div>
              </div>
            </div>
          </div>
        )}

        {error && (
          <div style={{ marginTop: 20 }}>
            <div className="alert alert--error">
              <div className="alert-icon"><AlertCircle size={16} /></div>
              <div className="alert-body">
                <div className="alert-title">Request failed</div>
                <div className="alert-desc">{error}</div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
