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

  /**
   * One published topic, by slug.
   *
   * The route answers 404 for a topic that does not exist and for one that is not published. It
   * uses one answer for both on purpose, so that a guessed slug tells a caller nothing.
   * A session on a topic that a curator later unpublished therefore gets a 404 here, while the
   * session itself still loads. A caller must handle that.
   */
  topic(slug: string): Promise<TopicSummary> {
    return firstValueFrom(this.http.get<TopicSummary>(`/api/catalog/topics/${slug}`));
  }

  createSession(topicSlug: string): Promise<SessionView> {
    return firstValueFrom(this.http.post<SessionView>('/api/sessions', { topicSlug }));
  }

  session(id: string): Promise<SessionView> {
    return firstValueFrom(this.http.get<SessionView>(`/api/sessions/${id}`));
  }
}
