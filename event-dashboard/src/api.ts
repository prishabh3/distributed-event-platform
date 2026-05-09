import axios from 'axios';
import type { ApiConfig, EventDetail, EventResponse, SendEventRequest, StatsResponse } from './types';

export function subscribeStats(
  config: ApiConfig,
  onData: (stats: StatsResponse) => void,
  onError: () => void,
): () => void {
  const url = `${config.baseUrl}/api/v1/stats/stream`;
  const source = new EventSource(url);
  source.addEventListener('stats', (e: MessageEvent) => {
    try { onData(JSON.parse(e.data) as StatsResponse); } catch { /* ignore */ }
  });
  source.onerror = () => { source.close(); onError(); };
  return () => source.close();
}

function client(config: ApiConfig) {
  return axios.create({
    baseURL: config.baseUrl,
    headers: { 'X-API-Key': config.apiKey, 'Content-Type': 'application/json' },
  });
}

export async function fetchStats(config: ApiConfig): Promise<StatsResponse> {
  const { data } = await client(config).get<StatsResponse>('/api/v1/stats');
  return data;
}

export async function sendEvent(config: ApiConfig, req: SendEventRequest): Promise<EventResponse> {
  const { data } = await client(config).post<EventResponse>('/api/v1/events', req);
  return data;
}

export async function lookupEvent(config: ApiConfig, id: string): Promise<EventDetail> {
  const { data } = await client(config).get<EventDetail>(`/api/v1/events/${id}`);
  return data;
}
