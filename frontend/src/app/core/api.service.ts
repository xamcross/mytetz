import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { SessionView, TopicSummary } from './models';

export interface Health {
  status: string;
  mongo: boolean;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  health(): Promise<Health> {
    return firstValueFrom(this.http.get<Health>('/api/health'));
  }

  topics(q?: string): Promise<TopicSummary[]> {
    const params = q === undefined ? undefined : new HttpParams().set('q', q);
    return firstValueFrom(this.http.get<TopicSummary[]>('/api/catalog/topics', { params }));
  }

  createSession(topicSlug: string): Promise<SessionView> {
    return firstValueFrom(this.http.post<SessionView>('/api/sessions', { topicSlug }));
  }

  session(id: string): Promise<SessionView> {
    return firstValueFrom(this.http.get<SessionView>(`/api/sessions/${id}`));
  }
}
