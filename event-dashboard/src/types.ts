export type EventType =
  | 'ORDER_UPDATE'
  | 'DRIVER_ASSIGNED'
  | 'PAYMENT_DONE'
  | 'TRIP_STARTED'
  | 'TRIP_ENDED';

export type EventStatus =
  | 'QUEUED'
  | 'DELIVERED'
  | 'FAILED'
  | 'DEAD_LETTERED'
  | 'DUPLICATE';

export interface StatsResponse {
  totalEventsReceived: number;
  totalEventsDelivered: number;
  totalEventsFailed: number;
  totalDuplicatesRejected: number;
  pendingOutboxCount: number;
  eventsPerSecond: number;
}

export interface EventResponse {
  eventId: string;
  status: EventStatus;
  message: string;
}

export interface EventDetail {
  id: string;
  eventType: EventType;
  partitionKey: string;
  payload: Record<string, unknown>;
  webhookUrl: string;
  status: EventStatus;
  retryCount: number;
  createdAt: string;
  deliveredAt: string | null;
  errorMessage: string | null;
}

export interface SendEventRequest {
  eventId: string;
  eventType: EventType;
  partitionKey: string;
  payload: Record<string, unknown>;
  webhookUrl: string;
  timestamp: string;
}

export interface ActivityEntry {
  eventId: string;
  eventType: EventType;
  partitionKey: string;
  webhookUrl: string;
  status: EventStatus;
  sentAt: string;
}

export interface ApiConfig {
  baseUrl: string;
  apiKey: string;
}

export interface StatsSnapshot {
  time: string;
  received: number;
  delivered: number;
  failed: number;
}
